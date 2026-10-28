package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设计模式 01：Worker Thread（工作线程）—— 线程池的本质。
 *
 * <p>模式结构：一个任务队列 + N 个常驻工作线程，线程不断从队列取任务执行。
 * 这就是 {@code ThreadPoolExecutor} 的雏形：线程被复用，任务与线程解耦。
 *
 * <p>本用例手写一个 20 行的「迷你线程池」，再对比 JDK 的实现和虚拟线程的差异。
 */
public class P01_WorkerThread extends DemoBase {

    /** 迷你工作线程池：N 个线程 + 一条队列 + 毒丸关闭。 */
    private static final class TinyPool implements AutoCloseable {
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        private final List<Thread> workers = new ArrayList<>();
        private final AtomicInteger executed = new AtomicInteger();

        TinyPool(int size, String name) {
            for (int i = 0; i < size; i++) {
                Thread thread = new Thread(() -> {
                    while (true) {
                        try {
                            Runnable task = queue.take();
                            if (task == POISON) {
                                return; // 毒丸：优雅退出
                            }
                            task.run();
                            executed.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, name + "-" + i);
                thread.start();
                workers.add(thread);
            }
        }

        private static final Runnable POISON = () -> { };

        void execute(Runnable task) {
            queue.add(task);
        }

        int executed() {
            return executed.get();
        }

        @Override
        public void close() {
            for (int i = 0; i < workers.size(); i++) {
                queue.add(POISON);
            }
            for (Thread worker : workers) {
                try {
                    worker.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public P01_WorkerThread() {
        super("pattern-01-worker-thread", Level.PATTERN,
                "Worker Thread：线程池的本质",
                "20 行手写迷你线程池，再看 JDK 是怎么做的");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式结构
                - **任务队列**：存放待处理任务（生产者放入，工作线程取出）；
                - **工作线程**：常驻循环 `take() -> run() -> take()`；
                - **关闭机制**：毒丸（poison pill）、标志位 + 中断、或 shutdown 方法。

                ## 为什么需要它
                每个请求 new 一个线程的代价：创建 ~几十微秒、栈 1MB、上下文切换、无法限流。
                工作线程模式把「线程创建」变成「任务入队」，线程数可控、可复用、可监控。

                ## 和线程池的关系
                `ThreadPoolExecutor` = Worker Thread + 队列策略 + 拒绝策略 + 生命周期管理 + 监控。
                也就是说，线程池不是新东西，它是这个模式的产品化实现。

                ## 毒丸（Poison Pill）
                往队列里放一个特殊任务，工作线程取到它就退出。
                适合「所有任务处理完再关闭」的场景，但要注意：毒丸要放在队列末尾，
                并且有几个 worker 就放几个。

                ## 虚拟线程时代的重新思考
                线程便宜了，还需要 Worker Thread 吗？
                - CPU 密集 / 需要限流：仍然需要（线程池 = 并发控制）；
                - IO 密集：可以一个请求一个虚拟线程，不再需要「复用」。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 生产代码用 ThreadPoolExecutor，不要手写 Worker Thread（除非有特殊调度需求）。
                - 队列必须有界，否则和线程池无界队列一样会 OOM。
                - 关闭必须优雅：先停止接收任务，再放毒丸/等队列排空，最后 join。
                - 工作线程要命名，且要 catch 任务异常，否则一个异常可能让 worker 退出。
                - 需要「任务优先级/延迟」时，用 PriorityBlockingQueue / DelayQueue 替换普通队列。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 3 个 worker + 10 个任务：线程被复用");
        TinyPool pool = new TinyPool(3, "tiny-worker");
        java.util.Set<String> usedThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (int i = 1; i <= 10; i++) {
            int id = i;
            pool.execute(() -> {
                usedThreads.add(Thread.currentThread().getName());
                ctx.info("任务 %d 由 %s 执行", id, Thread.currentThread().getName());
                sleepQuietly(30);
            });
        }
        ctx.await("10 个任务全部完成", 5000, () -> pool.executed() == 10);
        ctx.verifyEquals("10 个任务全部执行", 10, pool.executed());
        ctx.info("实际使用了 %d 个线程：%s", usedThreads.size(), usedThreads);
        ctx.verify("线程被复用，没有为每个任务创建新线程", usedThreads.size() <= 3);

        ctx.step("② 毒丸关闭：放 3 个毒丸，3 个 worker 全部退出");
        pool.close();
        ctx.verify("所有工作线程已退出", pool.workers.stream().noneMatch(Thread::isAlive));

        ctx.step("③ 对比 JDK 线程池");
        var jdkPool = ctx.fixedPool("jdk-worker", 3);
        for (int i = 1; i <= 10; i++) {
            int id = i;
            jdkPool.execute(() -> ctx.info("JDK 线程池执行任务 %d（%s）", id, Thread.currentThread().getName()));
        }
        jdkPool.shutdown();
        jdkPool.awaitTermination(5, TimeUnit.SECONDS);
        ctx.info("ThreadPoolExecutor = Worker Thread + 队列策略 + 拒绝策略 + 监控");
        ctx.verify("线程池实现了同样的模式，但多了治理能力", jdkPool.isTerminated());

        ctx.step("④ 虚拟线程：不再需要复用了");
        try (var virtual = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= 3; i++) {
                int id = i;
                virtual.execute(() -> ctx.info("虚拟线程 %s 处理消息 %d", Thread.currentThread().getName(), id));
            }
        }
        ctx.info("IO 密集场景：一个消息一个虚拟线程，简单且没有池化复杂度");
        ctx.info("CPU 密集场景：仍然需要 Worker Thread 来控制并发度");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
