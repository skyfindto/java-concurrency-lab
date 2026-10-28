package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 中级 01：ExecutorService 的生命周期与正确关闭。
 *
 * <p>线程池不是「用完就扔」的对象，它有明确的状态机：
 * RUNNING → SHUTDOWN → TIDYING → TERMINATED。
 * 关闭姿势不对，轻则任务丢失，重则进程退不出去。
 */
public class M01_ExecutorLifecycle extends DemoBase {

    public M01_ExecutorLifecycle() {
        super("juc-01-executor-lifecycle", Level.INTERMEDIATE,
                "线程池生命周期：execute / submit / 关闭",
                "execute 与 submit 的异常差异，shutdown 与 shutdownNow 的取舍");
    }

    @Override
    public String knowledge() {
        return """
                ## 两种提交方式
                - `execute(Runnable)`：无返回值，任务抛出的异常交给线程的 UncaughtExceptionHandler
                  （默认打印到 stderr，很容易被忽略）。
                - `submit(...)`：返回 Future，异常被封装在 Future 里，**不调用 get() 就永远不知道任务失败了**。
                  这是线上「任务静默失败」的头号原因。

                ## 两种关闭方式
                - `shutdown()`：不再接受新任务，已提交的（含队列里的）继续执行完，线程池最终 TERMINATED。
                - `shutdownNow()`：不再接受新任务，中断所有工作线程，返回队列中尚未执行的任务列表。
                  注意：它「尝试」中断，任务是否响应取决于有没有检查中断标志。

                ## 正确关闭模板
                    pool.shutdown();
                    if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                        List<Runnable> dropped = pool.shutdownNow();   // 兜底
                        // 记录日志 / 补偿 dropped
                    }

                ## 状态判断
                - isShutdown()：是否已调用 shutdown/shutdownNow
                - isTerminated()：所有任务结束且线程池完全终止
                - awaitTermination(timeout)：阻塞等待到 TERMINATED，返回是否成功
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 永远不要用 `Executors.newFixedThreadPool(...)` 的非托管返回：用 Spring 的 ThreadPoolTaskExecutor
                  或自建 ThreadPoolExecutor，并把它注册为 Bean，容器关闭时统一 shutdown。
                - submit 之后一定要处理 Future：要么 get 并处理异常，要么用 CompletableFuture 的 exceptionally。
                - 关闭顺序：先摘流量（stop accepting）→ shutdown → awaitTermination → shutdownNow 兜底。
                - awaitTermination 必须带超时，且超时后要记录「还有多少任务没跑完」。
                - 线程池里的任务不要捕获 Throwable 后吞掉，至少记日志。
                - 单元测试里忘记 shutdown 会导致 JVM 不退出、Maven 卡住，这是最常见的新手问题。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① execute：异常走 UncaughtExceptionHandler（默认只打到 stderr）");
        ThreadPoolExecutor handlerPool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "handler-demo");
            thread.setUncaughtExceptionHandler((t, e) ->
                    ctx.error("UncaughtExceptionHandler 捕获到：%s（线程 %s）", e.getMessage(), t.getName()));
            return thread;
        });
        handlerPool.execute(() -> {
            throw new IllegalStateException("execute 任务抛的异常");
        });
        handlerPool.shutdown();
        handlerPool.awaitTermination(2, TimeUnit.SECONDS);
        ctx.verify("未捕获异常被自定义 handler 捕获", handlerPool.isTerminated());

        ctx.step("② submit：异常被封装进 Future，不 get 就永远静默");
        ThreadPoolExecutor pool = ctx.fixedPool("lifecycle-pool", 2);
        Future<Integer> ok = pool.submit(() -> 42);
        ctx.verifyEquals("submit 有返回值", 42, ok.get());

        Future<?> bad = pool.submit(() -> {
            throw new IllegalStateException("submit 任务抛的异常");
        });
        try {
            bad.get();
        } catch (ExecutionException e) {
            ctx.warn("只有调用 Future.get() 才暴露出来：%s", e.getCause());
        }

        ctx.step("③ shutdown：拒绝新任务，已提交的继续跑完");
        Future<String> slow = pool.submit(() -> {
            Thread.sleep(300);
            return "慢任务完成";
        });
        pool.shutdown();
        ctx.verify("isShutdown = true", pool.isShutdown());
        ctx.verify("isTerminated 此时还是 false", !pool.isTerminated());

        try {
            pool.submit(() -> ctx.info("这个任务不会被接受"));
            ctx.error("关闭后居然还能提交？不符合预期");
        } catch (RejectedExecutionException e) {
            ctx.ok("关闭后再提交被拒绝：%s", shorten(e.getMessage()));
        }
        ctx.info("已提交的任务仍然跑完了：%s", slow.get());
        ctx.verify("awaitTermination 返回 true", pool.awaitTermination(3, TimeUnit.SECONDS));

        ctx.step("④ shutdownNow：中断工作线程，并返回未执行的任务");
        ThreadPoolExecutor nowPool = ctx.executor("now-pool", 1, 1, 10);
        CountDownLatch holding = new CountDownLatch(1);
        nowPool.execute(() -> {
            try {
                holding.await();
            } catch (InterruptedException e) {
                ctx.warn("运行中的任务被 interrupt 唤醒");
                Thread.currentThread().interrupt();
            }
        });
        for (int i = 0; i < 3; i++) {
            nowPool.execute(() -> ctx.info("排队中的任务被执行（本不应该发生）"));
        }
        ctx.sleep(50);
        List<Runnable> dropped = nowPool.shutdownNow();
        ctx.info("shutdownNow 返回了 %d 个没来得及执行的任务", dropped.size());
        ctx.verifyEquals("队列里的 3 个任务被交还", 3, dropped.size());

        ctx.step("⑤ 生产模板");
        ctx.info("pool.shutdown();");
        ctx.info("if (!pool.awaitTermination(30, SECONDS)) { dropped = pool.shutdownNow(); }");
        ctx.info("关键：awaitTermination 必须带超时，否则进程可能永远退不出去");
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
}
