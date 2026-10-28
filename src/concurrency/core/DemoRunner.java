package concurrency.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用例执行器：异步跑用例、超时强制中断、保留最近 N 次运行结果供页面轮询。
 *
 * <p>这里顺便示范了三个生产级细节：
 * <ol>
 *   <li><b>看门狗</b>：每个用例都有超时预算，到点 interrupt，绝不允许一个用例挂死整个进程；</li>
 *   <li><b>状态机</b>：RUNNING → OK / FAILED / TIMEOUT / CANCELLED，页面只看状态；</li>
 *   <li><b>有界历史</b>：最多保留 {@value #MAX_RUNS} 次运行，防止内存无限增长。</li>
 * </ol>
 */
public final class DemoRunner implements AutoCloseable {

    public enum Status { RUNNING, OK, FAILED, TIMEOUT, CANCELLED }

    private static final int MAX_RUNS = 40;

    /** 一次运行实例。字段 volatile，保证页面线程能看到状态变化。 */
    public static final class Run {
        private final String runId;
        private final Demo demo;
        private final Log log;
        private final long startedAt = System.currentTimeMillis();
        private final AtomicInteger ok = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private volatile long finishedAt;
        private volatile Status status = Status.RUNNING;
        private volatile String error = "";
        private volatile DemoContext ctx;
        private volatile Thread worker;
        private volatile boolean timedOut;

        Run(String runId, Demo demo) {
            this.runId = runId;
            this.demo = demo;
            this.log = new Log(4000);
        }

        public String runId() {
            return runId;
        }

        public Demo demo() {
            return demo;
        }

        public Log log() {
            return log;
        }

        public Status status() {
            return status;
        }

        public long startedAt() {
            return startedAt;
        }

        public long finishedAt() {
            return finishedAt;
        }

        public String error() {
            return error;
        }

        public int okCount() {
            return ok.get();
        }

        public int failCount() {
            return failed.get();
        }

        /** 页面轮询用：返回状态 + 增量日志（seq &gt; fromSeq）。 */
        public Map<String, Object> snapshot(long fromSeq) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("runId", runId);
            map.put("demoId", demo.id());
            map.put("title", demo.title());
            map.put("status", status.name());
            map.put("startedAt", startedAt);
            map.put("finishedAt", finishedAt);
            map.put("ok", ok.get());
            map.put("failed", failed.get());
            map.put("error", error);
            map.put("elapsed", (finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startedAt);

            List<Map<String, Object>> lines = new ArrayList<>();
            long next = fromSeq;
            for (Log.Line line : log.since(fromSeq)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("seq", line.seq());
                item.put("time", line.time());
                item.put("level", line.level().name());
                item.put("thread", line.thread());
                item.put("msg", line.msg());
                lines.add(item);
                next = line.seq();
            }
            map.put("lines", lines);
            map.put("nextSeq", next);
            return map;
        }
    }

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "demo-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Run> runs = new LinkedHashMap<>();

    public Run start(Demo demo) {
        Run run = new Run(UUID.randomUUID().toString().replace("-", "").substring(0, 10), demo);
        synchronized (runs) {
            runs.put(run.runId(), run);
            while (runs.size() > MAX_RUNS) {
                String eldest = runs.keySet().iterator().next();
                Run candidate = runs.get(eldest);
                if (candidate != null && candidate.status == Status.RUNNING) {
                    break; // 不淘汰正在跑的
                }
                runs.remove(eldest);
            }
        }
        // 用虚拟线程执行：用例线程名带上用例 id，日志里一眼能看出是谁在跑
        Thread worker = Thread.ofVirtual()
                .name("run-" + demo.id())
                .unstarted(() -> execute(run));
        run.worker = worker;
        worker.start();
        return run;
    }

    private void execute(Run run) {
        DemoContext ctx = new DemoContext(run.demo, run.log);
        run.ctx = ctx;
        run.worker = Thread.currentThread();
        run.log.echo(false);

        ScheduledFuture<?> timeout = watchdog.schedule(() -> {
            if (run.status != Status.RUNNING) {
                return;
            }
            run.timedOut = true;
            run.log.warn("执行超过 %d ms 预算，触发看门狗强制中断", run.demo.timeoutMillis());
            ctx.cancel();
            Thread worker = run.worker;
            if (worker != null) {
                worker.interrupt();
            }
        }, run.demo.timeoutMillis(), TimeUnit.MILLISECONDS);

        long begin = System.nanoTime();
        Status result;
        try {
            run.demo.run(ctx);
            if (run.timedOut) {
                result = Status.TIMEOUT;
            } else if (ctx.cancelled()) {
                result = Status.CANCELLED;
            } else {
                result = Status.OK;
            }
        } catch (DemoContext.CancelledException e) {
            result = run.timedOut ? Status.TIMEOUT : Status.CANCELLED;
        } catch (Throwable t) {
            // 被取消/超时的用例，任务里抛出 InterruptedException 属正常现象，不要算失败
            if (run.timedOut || ctx.cancelled()) {
                result = run.timedOut ? Status.TIMEOUT : Status.CANCELLED;
                run.log.warn("用例被中断：%s", t);
            } else {
                result = Status.FAILED;
                run.error = String.valueOf(t);
                run.log.error("用例抛出异常：%s\n%s", t, stackTrace(t));
            }
        }

        // 收尾工作必须在「发布最终状态」之前完成：
        // 否则页面/CLI 看到非 RUNNING 状态时，断言计数和收尾日志还没写好。
        timeout.cancel(false);
        try {
            ctx.close();
        } catch (RuntimeException ignored) {
            // 关闭线程池失败不影响结果
        }
        run.ok.set(ctx.okCount());
        run.failed.set(ctx.failCount());
        run.finishedAt = System.currentTimeMillis();
        run.log.info("执行结束：status=%s，耗时=%d ms，断言通过=%d，失败=%d",
                result, (System.nanoTime() - begin) / 1_000_000, run.okCount(), run.failCount());
        run.status = result;
    }

    /** 人工取消（页面上的「停止」按钮）。 */
    public boolean cancel(String runId) {
        Run run = get(runId);
        if (run == null || run.status != Status.RUNNING) {
            return false;
        }
        if (run.ctx != null) {
            run.ctx.cancel();
        }
        Thread worker = run.worker;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }

    public Run get(String runId) {
        synchronized (runs) {
            return runs.get(runId);
        }
    }

    public List<Run> recent() {
        synchronized (runs) {
            return new ArrayList<>(runs.values());
        }
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    @Override
    public void close() {
        for (Run run : recent()) {
            if (run.status == Status.RUNNING && run.worker != null) {
                run.worker.interrupt();
            }
        }
        watchdog.shutdownNow();
    }
}
