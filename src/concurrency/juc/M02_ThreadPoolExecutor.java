package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.core.Threads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 中级 02：ThreadPoolExecutor 七大参数与执行流程。
 *
 * <p>面试必问、线上必踩：线程池到底什么时候创建线程？什么时候进队列？什么时候拒绝？
 *
 * <p>记住这个顺序（很多人搞反）：
 * <b>核心线程 → 任务队列 → 扩容到最大线程 → 拒绝策略</b>。
 * 也就是说，只有当队列<b>满了</b>，才会去创建超过 corePoolSize 的线程。
 * 这也是「无界队列的线程池永远只有 core 个线程」的原因。
 */
public class M02_ThreadPoolExecutor extends DemoBase {

    public M02_ThreadPoolExecutor() {
        super("juc-02-thread-pool-executor", Level.INTERMEDIATE,
                "ThreadPoolExecutor 七大参数与执行流程",
                "core=2, max=4, queue=2：为什么第 7 个任务才被拒绝");
    }

    @Override
    public String knowledge() {
        return """
                ## 七个参数
                1. corePoolSize   核心线程数（常驻，默认不会被回收）
                2. maximumPoolSize 最大线程数
                3. keepAliveTime  非核心线程空闲存活时间
                4. unit           时间单位
                5. workQueue      任务队列（有界/无界决定会不会 OOM）
                6. threadFactory  线程工厂（命名、优先级、异常处理器）
                7. handler        拒绝策略

                ## execute() 的执行流程
                1. 当前线程数 < corePoolSize → 直接创建核心线程执行；
                2. 否则尝试入队 → 队列没满，入队等待；
                3. 队列满了且线程数 < maximumPoolSize → 创建非核心线程执行；
                4. 线程数已达 maximumPoolSize → 触发拒绝策略。

                ## 容易被忽略的点
                - 队列满了才会扩容，所以「队列容量」比 maximumPoolSize 更早决定行为；
                - 核心线程默认不回收，除非 allowCoreThreadTimeOut(true)；
                - 空闲线程超过 keepAliveTime 会被回收，但不低于 corePoolSize；
                - 线程池预热：prestartAllCoreThreads() 可避免流量突增时的创建抖动；
                - 队列容量设置为 0（SynchronousQueue）时，任务必须立刻被线程接手，否则直接扩容/拒绝。

                ## 为什么不用 Executors
                - newFixedThreadPool / newSingleThreadExecutor：LinkedBlockingQueue 无界 → 任务堆积 OOM；
                - newCachedThreadPool：max = Integer.MAX_VALUE → 线程数暴涨；
                - newScheduledThreadPool：DelayedWorkQueue 无界 → 同上。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 一律手写 new ThreadPoolExecutor，显式指定有界队列 + 命名线程工厂 + 拒绝策略。
                - 队列容量按「可容忍的排队时长」算：容量 × 平均任务耗时 ≈ 最大排队延迟。
                - 核心线程数按业务类型设：CPU 密集 N+1，IO 密集 N×(1+等待/计算)。
                - 让线程池「能被监控」：暴露 active/queue/rejected，接入 Prometheus。
                - 线程名一定带上业务标识，jstack 里才看得懂。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("场景：corePoolSize=2，maximumPoolSize=4，队列容量=2");
        CountDownLatch hold = new CountDownLatch(1);
        ThreadPoolExecutor pool = ctx.executor("tpe-flow", 2, 4, 2);

        for (int i = 1; i <= 7; i++) {
            int index = i;
            try {
                pool.execute(() -> {
                    try {
                        hold.await(); // 模拟长任务，让线程一直占着
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                ctx.sleep(30);
                ctx.info("提交第 %d 个任务 → poolSize=%d, active=%d, queue=%d/2",
                        index, pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size());
            } catch (RejectedExecutionException e) {
                ctx.warn("提交第 %d 个任务 → 被拒绝：%s", index, e.getMessage());
            }
        }

        ctx.verifyEquals("最终线程数达到 maximumPoolSize", 4, pool.getPoolSize());
        ctx.verifyEquals("队列已满", 2, pool.getQueue().size());
        ctx.verify("最大池大小记录正确", pool.getLargestPoolSize() == 4);

        ctx.step("逐条对应执行流程");
        ctx.info("第 1、2 个：直接创建核心线程（corePoolSize=2）");
        ctx.info("第 3、4 个：核心线程已满 → 进入队列（容量 2）");
        ctx.info("第 5、6 个：队列已满 → 扩容到 maximumPoolSize=4");
        ctx.info("第 7 个  ：线程到顶且队列满 → 拒绝策略");

        hold.countDown();
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        ctx.step("对比：SynchronousQueue（容量 0）时，任务必须立刻被接手");
        ThreadPoolExecutor elastic = new ThreadPoolExecutor(
                0, 4, 200, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), Threads.named("elastic-pool"));
        for (int i = 0; i < 4; i++) {
            elastic.execute(() -> sleepQuietly(100));
        }
        ctx.info("提交 4 个任务后 poolSize = %d（每个任务都直接创建线程）", elastic.getPoolSize());
        ctx.info("等待 500ms，非核心线程按 keepAliveTime=200ms 被回收……");
        ctx.sleep(500);
        ctx.info("回收后 poolSize = %d（这就是 newCachedThreadPool 的行为）", elastic.getPoolSize());
        ctx.verify("空闲线程被回收", elastic.getPoolSize() <= 1);
        elastic.shutdown();

        ctx.step("结论");
        ctx.info("「队列满」是扩容的触发条件，不是「线程满」");
        ctx.info("无界队列 = 永远不会扩容到 max，也永远不会拒绝 → 只会堆到 OOM");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
