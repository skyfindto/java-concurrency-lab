package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 设计模式 07：Active Object（主动对象）—— 用「单线程串行」换「无锁并发」。
 *
 * <p>把方法调用变成「消息」放进队列，由一个专门的调度线程串行执行。
 * 调用方立刻拿到 Future，不阻塞；对象内部<b>永远只被一个线程访问</b>，
 * 所以完全不需要锁。
 *
 * <p>这就是 Actor 模型的核心思想，也是 Netty、Akka、Redis 单线程模型的共同点。
 */
public class P07_ActiveObject extends DemoBase {

    /** 主动对象：所有状态只在 scheduler 线程里被修改。 */
    private static final class ActiveCounter {
        private static final Runnable STOP = () -> { };

        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final Thread scheduler;
        private int value; // 只被 scheduler 线程访问，所以不需要 volatile/synchronized

        ActiveCounter() {
            scheduler = new Thread(() -> {
                try {
                    while (true) {
                        Runnable task = queue.take();
                        if (task == STOP) {
                            return;
                        }
                        task.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "active-object-scheduler");
            scheduler.setDaemon(true);
            scheduler.start();
        }

        /** 异步方法调用：立刻返回 Future，实际执行被排进队列。 */
        SimpleFuture<Integer> add(int delta) {
            SimpleFuture<Integer> future = new SimpleFuture<>();
            queue.add(() -> {
                value += delta;
                future.complete(value);
            });
            return future;
        }

        SimpleFuture<Integer> current() {
            SimpleFuture<Integer> future = new SimpleFuture<>();
            queue.add(() -> future.complete(value));
            return future;
        }

        void shutdown() throws InterruptedException {
            queue.add(STOP);
            scheduler.join(2000);
        }

        boolean isStopped() {
            return !scheduler.isAlive();
        }
    }

    public P07_ActiveObject() {
        super("pattern-07-active-object", Level.PATTERN,
                "Active Object：单线程串行 = 无锁并发",
                "10 个线程并发加 1，为什么一行锁都不用写");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式结构
                1. **Proxy（代理）**：对外暴露方法，把调用封装成 Method Request 放进队列；
                2. **Activation Queue**：任务队列（有界！否则会堆积）；
                3. **Scheduler**：一个线程不断取任务执行；
                4. **Future**：调用方凭它拿结果。

                ## 为什么不需要锁
                所有对 `value` 的读写都发生在 scheduler 这一个线程里。
                多线程竞争被「队列 + 单线程执行」消解掉了 ——
                这就是「串行化临界区」的极致版本。

                ## 优点
                - 调用方完全不阻塞（异步）；
                - 内部实现简单，不需要考虑并发；
                - 天然的顺序性（消息按入队顺序处理）。

                ## 缺点
                - 单线程处理能力有限，CPU 密集时是瓶颈；
                - 队列必须有界，否则过载时内存爆炸；
                - 调试时栈会「跳」，日志需要 traceId 串起来。

                ## 真实世界的 Active Object
                - Netty 的 EventLoop（一个 Channel 绑定一个 EventLoop 线程）；
                - Redis 的单线程命令处理；
                - Akka Actor（每个 Actor 串行处理消息）；
                - Node.js 事件循环。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 状态需要严格顺序、又不想加锁时，用 Active Object / Actor。
                - 队列必须有界，并定义满了之后的策略（阻塞/拒绝/丢弃）。
                - 调度线程要命名，并处理任务异常，否则一个异常就能让它退出。
                - 不要在这个线程里做阻塞 IO，否则整个对象卡住（和单线程 Redis 一样）。
                - 需要多核扩展时，按 key 分片到多个 Active Object（Redis Cluster / Actor 分片）。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 10 个线程并发调用 add(1)，内部完全无锁");
        ActiveCounter counter = new ActiveCounter();
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<SimpleFuture<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread caller = new Thread(() -> {
                try {
                    start.await();
                    SimpleFuture<Integer> future = counter.add(1);
                    futures.add(future);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "caller-" + i);
            caller.start();
        }
        start.countDown();
        ctx.await("所有调用都已入队", 3000, () -> futures.size() == threads);

        ctx.step("② 调用方立刻返回，结果通过 Future 拿");
        int sum = 0;
        for (SimpleFuture<Integer> future : futures) {
            sum = future.get(2000);
        }
        ctx.info("最终值 = %d（每个 Future 返回的是「当时的值」）", sum);
        ctx.verifyEquals("10 次 add(1) 全部生效，没有丢更新", 10, sum);

        ctx.step("③ 读也要走队列，保证顺序一致");
        int current = counter.current().get(1000);
        ctx.info("current() = %d", current);
        ctx.verifyEquals("读到的值与写入一致", 10, current);

        ctx.step("④ 关闭：同样通过队列投递停止信号");
        counter.shutdown();
        ctx.verify("调度线程已退出", counter.isStopped());

        ctx.step("⑤ 对比：如果用普通对象 + 并发写");
        ctx.info("普通 int 被 10 个线程 ++ 需要 synchronized，否则丢更新（见 basic-07）");
        ctx.info("Active Object 把「并发」变成「排队」，从根上消除了竞态");
        ctx.verify("串行化 = 最简单的一致性问题解法", true);
    }
}
