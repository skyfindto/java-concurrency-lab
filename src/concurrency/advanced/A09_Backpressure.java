package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 09：背压（Backpressure）—— 让生产者慢下来，而不是让内存涨上去。
 *
 * <p>「生产快、消费慢」是异步系统的常态。如果缓冲区无界，
 * 压力会变成内存占用，最终 OOM；如果有界，压力会传回生产者，让它慢下来。
 * 后者就是背压。
 *
 * <p>本用例对比：无界队列（危险）vs 有界队列（背压）vs 有界队列 + 拒绝（快速失败）。
 */
public class A09_Backpressure extends DemoBase {

    public A09_Backpressure() {
        super("adv-09-backpressure", Level.ADVANCED,
                "背压：有界队列才是安全阀",
                "生产速度是消费的 5 倍，队列会告诉你真相");
    }

    @Override
    public String knowledge() {
        return """
                ## 没有背压会发生什么
                生产者每秒 1000 个任务，消费者每秒 200 个。
                用无界队列 → 队列每秒涨 800 个，每个任务占 1KB，
                一小时就是 2.8GB → Full GC → OOM → 服务挂掉。
                而且**故障发现得很晚**：内存涨到 90% 才告警，那时已经来不及了。

                ## 有界队列的三种行为
                1. `put()` 阻塞：生产者自然降速（真正的背压）；
                2. `offer(timeout)`：等一会儿，还不行就放弃；
                3. 线程池拒绝策略：队列满 → 拒绝 → 降级（见 adv-03）。

                ## 怎么选容量
                容量 = 可接受的最大排队延迟 ÷ 平均处理耗时
                例：允许排队 2 秒、每单处理 50ms → 容量 40。
                队列再大，用户等的时间只会更长，最终超时 —— 还不如早点拒绝。

                ## 监控信号
                - 队列水位持续上升 → 消费能力不足（加消费者 / 优化处理逻辑）；
                - 队列满 + 拒绝数上升 → 已过载，必须限流或降级；
                - 队列始终为空 → 消费者太多，可以缩容。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 所有异步队列都必须有界，包括线程池队列、内存队列、本地缓存。
                - 容量按「最大可接受延迟」反推，而不是「内存能放多少」。
                - 队列满时的行为要明确：阻塞（背压）、拒绝（降级）还是丢弃（仅限可丢数据）。
                - 把队列水位作为核心监控指标，设置 80% 告警。
                - 消费端要能水平扩展，否则背压最终会把上游也拖死。
                - 不要用「扩大队列」来解决消费慢的问题，那只是把故障延后。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 有界队列（容量 10）：生产者被强制降速");
        int total = 200;
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger maxQueueSize = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                try {
                    queue.take();
                    consumed.incrementAndGet();
                    Thread.sleep(10); // 消费慢：10ms/个
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            done.countDown();
        }, "slow-consumer");
        consumer.start();

        long begin = System.nanoTime();
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                try {
                    // 有界 + 带超时的 offer：满了就先等，等不到才放弃
                    if (!queue.offer(i, 100, TimeUnit.MILLISECONDS)) {
                        dropped.incrementAndGet();
                    }
                    maxQueueSize.accumulateAndGet(queue.size(), Math::max);
                    Thread.sleep(2); // 生产快：2ms/个
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "fast-producer");
        producer.start();

        producer.join(30_000);
        long produceCost = (System.nanoTime() - begin) / 1_000_000;
        done.await(30, TimeUnit.SECONDS);
        long totalCost = (System.nanoTime() - begin) / 1_000_000;

        ctx.info("生产者理论上 200×2ms = 400ms 就能提交完，实际用了 %d ms", produceCost);
        ctx.info("队列水位最高 %d/10，被丢弃 %d 个", maxQueueSize.get(), dropped.get());
        ctx.info("消费者处理完 %d 个，总耗时 %d ms", consumed.get(), totalCost);
        ctx.verify("队列水位从未超过容量", maxQueueSize.get() <= 10);
        ctx.verify("生产者因为队列满而变慢", produceCost > 600);
        ctx.verify("消费端没有被压垮", consumed.get() == total);

        ctx.step("② 如果没有背压（无界队列会怎样）");
        ctx.warn("无界队列：生产者 400ms 就提交完 200 个，队列里瞬间堆 160 个待处理");
        ctx.warn("真实系统里这个差值会持续累积：1 小时 = 百万级任务 = 几个 GB 内存");
        ctx.info("无界队列把「过载」变成了「延迟 + OOM」，故障发现得最晚");

        ctx.step("③ 线程池场景：队列满了触发拒绝，才是可观测的过载信号");
        ThreadPoolExecutor pool = ctx.executor("backpressure-pool", 2, 2, 5);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 50; i++) {
            try {
                pool.execute(() -> sleepQuietly(50));
                accepted.incrementAndGet();
            } catch (java.util.concurrent.RejectedExecutionException e) {
                rejected.incrementAndGet();
            }
        }
        ctx.info("提交 50 个：入池 %d，拒绝 %d", accepted.get(), rejected.get());
        ctx.verify("拒绝是明确、可观测的信号", rejected.get() > 0);

        ctx.step("④ 生产建议");
        ctx.info("队列容量 = 最大可接受排队延迟 ÷ 平均处理耗时");
        ctx.info("队列水位 > 80% 告警；持续满 + 拒绝上升 → 限流/降级/扩容");
        ctx.verify("有界队列 = 把「内存爆炸」转换成「可观测的拒绝」", true);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
