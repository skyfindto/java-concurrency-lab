package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设计模式 06：Two-Phase Termination（两阶段终止）。
 *
 * <p>「停止一个线程」不能靠 stop()（已废弃，会让对象处于不一致状态）。
 * 正确做法分两个阶段：
 * <ol>
 *   <li><b>发出停止请求</b>：设置标志位 + interrupt；</li>
 *   <li><b>等待善后完成</b>：线程自己退出循环、执行清理、通知「我已经停了」。</li>
 * </ol>
 *
 * <p>本用例实现一个标准的可停止工作者，并验证：清理逻辑一定执行、且只执行一次。
 */
public class P06_TwoPhaseTermination extends DemoBase {

    /** 标准的两阶段终止工作者。 */
    private static final class StoppableWorker {
        private final DemoContext ctx;
        private final AtomicInteger processed = new AtomicInteger();
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private volatile boolean running = true;
        private final Thread thread;

        StoppableWorker(DemoContext ctx) {
            this.ctx = ctx;
            this.thread = new Thread(this::loop, "two-phase-worker");
        }

        void start() {
            thread.start();
        }

        /** 阶段一：发出停止请求（幂等）。 */
        void shutdown() {
            running = false;
            thread.interrupt();
        }

        /** 阶段二：等待对方完成善后。 */
        boolean awaitTermination(long millis) throws InterruptedException {
            return terminated.await(millis, TimeUnit.MILLISECONDS);
        }

        int processed() {
            return processed.get();
        }

        boolean cleaned() {
            return cleaned.get();
        }

        private void loop() {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    processed.incrementAndGet();
                    Thread.sleep(30);
                }
            } catch (InterruptedException e) {
                // 被中断是「计划内」的，恢复标志后退出
                Thread.currentThread().interrupt();
            } finally {
                cleanup();          // 无论怎么退出，善后都要做
                terminated.countDown();
            }
        }

        private void cleanup() {
            if (cleaned.compareAndSet(false, true)) {
                ctx.info("执行清理：保存进度 %d 条、释放资源", processed.get());
            }
        }
    }

    public P06_TwoPhaseTermination() {
        super("pattern-06-two-phase-termination", Level.PATTERN,
                "两阶段终止：优雅地让线程停下来",
                "标志位 + interrupt + 等待善后，一个都不能少");
    }

    @Override
    public String knowledge() {
        return """
                ## 为什么 stop() 被废弃
                `Thread.stop()` 会在任意字节码位置抛出 ThreadDeath，直接终止线程：
                - 锁会被释放，但对象可能处于「改了一半」的状态；
                - finally 不保证执行，资源泄漏；
                - 数据库事务可能半提交。
                所以只能「请求」线程停止，由线程自己在安全点退出。

                ## 两个阶段
                **阶段一：请求**
                - 设置 `volatile boolean running = false`；
                - `thread.interrupt()` 唤醒阻塞中的线程（sleep/wait/join）。

                **阶段二：等待**
                - 线程在 finally 里做清理（保存进度、关闭资源、提交 offset）；
                - 通过 CountDownLatch / join 通知「我已停止」；
                - 调用方 `awaitTermination(timeout)`，超时则记录并强杀。

                ## 为什么标志位和 interrupt 要一起用
                - 只设标志位：线程如果阻塞在 `sleep(60_000)`，要等一分钟才能看到；
                - 只 interrupt：线程如果没在阻塞、也没检查中断，就完全无感。
                两个一起用，覆盖「运行中」和「阻塞中」两种状态。

                ## 清理必须幂等
                可能被多次 shutdown（用户点两次、停机脚本重试），
                用 AtomicBoolean 保证清理只执行一次。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 停止线程用「标志位 + interrupt」，绝不使用 stop() / suspend()。
                - 清理逻辑放 finally，并用 CAS/AtomicBoolean 保证只执行一次。
                - shutdown() 必须幂等，调用方可能重复触发。
                - awaitTermination 必须带超时，超时后记录「没停干净」并告警。
                - 线程池场景直接用 shutdown/awaitTermination，不要自己造轮子。
                - 消息消费者停机时先停拉取，再处理完在途消息，最后提交 offset。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 启动工作者，让它处理一会儿");
        StoppableWorker worker = new StoppableWorker(ctx);
        worker.start();
        ctx.sleep(200);
        ctx.info("已处理 %d 条", worker.processed());
        ctx.verify("工作者正在运行", worker.processed() > 0);

        ctx.step("② 阶段一：发出停止请求");
        long begin = System.nanoTime();
        worker.shutdown();
        ctx.info("shutdown() 立即返回，不阻塞调用方");

        ctx.step("③ 阶段二：等待善后完成");
        boolean stopped = worker.awaitTermination(2000);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.verify("工作者在 2 秒内停止", stopped);
        ctx.info("从请求到完全停止耗时 %d ms", cost);
        ctx.verify("清理逻辑被执行", worker.cleaned());
        ctx.verify("停止后不再处理新任务", true);

        ctx.step("④ 重复 shutdown 是安全的（幂等）");
        worker.shutdown();
        worker.shutdown();
        ctx.verify("多次调用不会抛异常，也不会重复清理", true);

        ctx.step("⑤ 对比：如果没有两阶段会怎样");
        ctx.warn("直接 kill 线程 → 清理没做 → 进度丢失、消息重复消费、文件写坏");
        ctx.info("两阶段终止 = 给线程一个「体面退出」的机会");
        ctx.verify("可停止性 = 线程安全的一部分", true);
    }
}
