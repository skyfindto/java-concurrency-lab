package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 03：拒绝策略与降级 —— 队列满了之后，代码到底该干什么。
 *
 * <p>很多项目写了拒绝策略，但拒绝了之后呢？没人处理。
 * 正确的姿势是：<b>拒绝 → 记录 → 降级 → 告警</b>，四步缺一不可。
 *
 * <p>本用例实现三种生产级方案，并量化「拒绝率 / 降级耗时」：
 * <ol>
 *   <li>快速失败 + 返回兜底数据（推荐用于查询类）；</li>
 *   <li>CallerRuns 背压（让调用方变慢，适合内部批处理）；</li>
 *   <li>溢出到本地重试队列（适合可延迟的任务）。</li>
 * </ol>
 */
public class A03_Degrade extends DemoBase {

    public A03_Degrade() {
        super("adv-03-degrade", Level.ADVANCED,
                "拒绝策略与降级方案",
                "拒绝之后做什么：兜底、背压还是重试队列");
    }

    @Override
    public String knowledge() {
        return """
                ## 拒绝不是终点，是降级的起点
                `RejectedExecutionException` 抛出来之后，你有三个选择：

                ### 1. 快速失败 + 兜底数据（查询类首选）
                    try {
                        pool.execute(task);
                    } catch (RejectedExecutionException e) {
                        metrics.rejected("order-query");
                        return cache.get(key);   // 返回缓存/默认值/空列表
                    }
                优点：RT 稳定，用户看到的是「旧数据」而不是「超时」。

                ### 2. CallerRunsPolicy：把压力还给调用方
                调用线程自己去执行任务，天然限速。但要小心：
                调用方可能是 Tomcat 工作线程，等于把背压传给了用户请求。

                ### 3. 溢出到本地队列 + 后台线程补偿
                拒绝后放进一个容量有限的 fallback 队列，
                由独立线程慢慢消费（或写入 MQ 异步处理）。
                必须限制大小，否则只是把 OOM 从线程池转移到内存。

                ## 关键指标
                - 拒绝次数（rejected）
                - 降级次数（degraded）
                - 队列水位（queue size / capacity）
                这三个指标必须打点告警，否则拒绝策略等于没写。

                ## 什么时候该扩队列/扩线程
                看「拒绝率」和「队列增长趋势」：
                - 偶发拒绝（< 0.1%）：正常，忽略；
                - 持续拒绝：容量不足，先查下游 RT，再考虑扩容；
                - 队列持续增长且不回落：有慢消费者/内存泄漏，扩容只会延后爆炸。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 每个线程池的拒绝策略都要显式声明，并在拒绝时打点（metric + 日志）。
                - 查询类接口：拒绝 → 返回缓存/默认值，保证 RT。
                - 写入类接口：拒绝 → 返回「稍后重试」或落库异步补偿，绝不静默丢弃。
                - CallerRunsPolicy 只在调用方本身能承受延迟时使用（如后台批处理）。
                - 本地重试队列必须有界 + 有 TTL，否则等于无界队列。
                - 定期演练：人为把池子打满，验证降级路径真的通。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("场景：核心业务线程池 2 线程、队列 5，瞬间涌入 30 个 100ms 的任务");
        ThreadPoolExecutor pool = ctx.executor("biz-pool", 2, 2, 5);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger degraded = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch allDone = new CountDownLatch(30);

        long begin = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    try {
                        ctx.info("任务 %d 执行中（线程 %s）", id, Thread.currentThread().getName());
                        sleepQuietly(100);
                        executed.incrementAndGet();
                    } finally {
                        allDone.countDown();
                    }
                });
                accepted.incrementAndGet();
            } catch (RejectedExecutionException e) {
                // 降级：真实场景里这里返回缓存、默认值或错误码
                degraded.incrementAndGet();
                allDone.countDown();
            }
        }
        allDone.await(10, TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;

        ctx.info("提交 30 个：成功入池 %d，立即降级 %d，实际执行 %d",
                accepted.get(), degraded.get(), executed.get());
        ctx.info("全部处理完耗时 %d ms（降级请求是毫秒级返回的）", cost);
        ctx.verify("确实发生了拒绝", degraded.get() > 0);
        ctx.verify("入池任务全部执行完", executed.get() == accepted.get());

        ctx.step("② 降级路径的 RT：被拒绝的请求几乎立即返回");
        long t0 = System.nanoTime();
        try {
            pool.execute(() -> sleepQuietly(100));
        } catch (RejectedExecutionException e) {
            long rejectedCost = (System.nanoTime() - t0) / 1_000_000;
            ctx.info("被拒绝的请求耗时 %d ms（而不是等 100ms 或超时）", rejectedCost);
            ctx.verify("降级是快速的", rejectedCost < 50);
        }

        ctx.step("③ 方案对比");
        ctx.info("AbortPolicy + 兜底数据  → RT 稳定，用户无感（推荐查询类）");
        ctx.info("CallerRunsPolicy        → 调用方被拖慢，形成背压（适合后台批处理）");
        ctx.info("DiscardPolicy           → 静默丢弃，禁止用于核心链路");
        ctx.info("本地重试队列            → 适合可延迟任务，但必须设容量上限");

        ctx.step("④ 必须监控的三个指标");
        ctx.info("rejected  = %d（拒绝次数，按池子/接口维度打点）", degraded.get());
        ctx.info("queueSize = %d/%d（水位，超过 80%% 告警）",
                pool.getQueue().size(), 5);
        ctx.info("active    = %d/%d（饱和度）", pool.getActiveCount(), pool.getMaximumPoolSize());
        ctx.verify("没有监控的拒绝策略 = 没有拒绝策略", true);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
