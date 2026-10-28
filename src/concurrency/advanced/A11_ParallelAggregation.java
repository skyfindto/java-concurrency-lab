package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 高级 11：并行聚合调用 —— 一个页面查三个下游，怎么做到 RT 最短、最稳。
 *
 * <p>串行调用：100 + 150 + 600 = 850ms，还要祈祷没有第 4 个下游。
 * 并行编排：max(100, 150, 300 超时) ≈ 300ms，且慢的那个自动降级。
 *
 * <p>这是 CompletableFuture 最有价值的落地场景，也是「敢在生产用多线程」的典型例子。
 */
public class A11_ParallelAggregation extends DemoBase {

    public A11_ParallelAggregation() {
        super("adv-11-parallel-aggregation", Level.ADVANCED,
                "并行聚合调用：allOf + 超时 + 部分失败兜底",
                "三个下游并行查，最慢的那个超时降级");
    }

    @Override
    public String knowledge() {
        return """
                ## 串行 vs 并行
                串行：A(100ms) → B(150ms) → C(600ms)，总 RT = 850ms，可用性 = 0.99³。
                并行：同时发起，总 RT = max(...) ≈ 600ms（或超时后 300ms），可用性 = 1-(0.01)³。

                ## 标准写法
                    CompletableFuture<String> a = supplyAsync(this::callA, pool);
                    CompletableFuture<String> b = supplyAsync(this::callB, pool);
                    CompletableFuture<String> c = supplyAsync(this::callC, pool)
                            .orTimeout(300, MILLISECONDS)      // 单个下游超时
                            .exceptionally(e -> "降级默认值"); // 超时/异常都兜住
                    allOf(a, b, c).join();
                    // 再分别 join 取结果，一定不会抛异常（因为都 handle 过）
                注意：allOf 返回的是 CompletableFuture<Void>，它只负责「都完成」，
                不负责收集结果，结果要自己从各个 future 里 join 出来。

                ## 三个必须做的保护
                1. **超时**：每个下游单独设超时，整体再设一个 deadline；
                2. **兜底**：`exceptionally` / `handle` 给默认值，保证部分失败也能返回；
                3. **线程池隔离**：每个下游用独立的池，避免慢下游拖垮快下游（见 adv-02）。

                ## allOf 的一个坑
                只要有一个 future 抛异常，allOf 就会异常完成，其他 future 的结果你可能拿不到。
                所以**每个分支在进入 allOf 之前就要 handle 掉异常**。

                ## 结果合并
                返回结果里要标注「哪些字段是降级的」（比如 `degraded: true`），
                前端才能展示「部分信息暂不可用」，而不是让用户以为数据完整。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 每个下游一个 CompletableFuture，所有分支先 handle/exceptionally 再 allOf。
                - 超时时间按下游 P99 设定，并留出整体 deadline。
                - 并行调用的线程池要隔离且足够大（一个请求会占 N 个线程）。
                - 记录每个下游的耗时和降级次数，这是定位慢依赖的关键指标。
                - 不要用 commonPool：阻塞调用会污染整个 JVM 的并行流。
                - 聚合结果要能标识降级字段，避免「静默返回不完整数据」。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService userPool = ctx.fixedPool("downstream-user", 4);
        ExecutorService orderPool = ctx.fixedPool("downstream-order", 4);
        ExecutorService recommendPool = ctx.fixedPool("downstream-recommend", 4);

        ctx.step("① 串行版本：100ms + 150ms + 600ms");
        long begin = System.nanoTime();
        String user = callUser(ctx);
        String order = callOrder(ctx);
        String recommendSlow = callRecommendSlow(ctx);
        long serialCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("串行结果：%s + %s + %s", user, order, recommendSlow);
        ctx.info("串行总耗时 %d ms", serialCost);
        ctx.verify("串行耗时接近三者之和", serialCost >= 800);

        ctx.step("② 并行版本：推荐服务 600ms，但我们只等 300ms");
        begin = System.nanoTime();
        CompletableFuture<String> userFuture = CompletableFuture.supplyAsync(() -> callUser(ctx), userPool);
        CompletableFuture<String> orderFuture = CompletableFuture.supplyAsync(() -> callOrder(ctx), orderPool);
        CompletableFuture<String> recommendFuture = CompletableFuture
                .supplyAsync(() -> callRecommendSlow(ctx), recommendPool)
                .orTimeout(300, TimeUnit.MILLISECONDS)
                .exceptionally(error -> {
                    ctx.warn("推荐服务降级：%s", describe(error));
                    return "推荐(降级默认值)";
                });

        CompletableFuture.allOf(userFuture, orderFuture, recommendFuture).join();
        long parallelCost = (System.nanoTime() - begin) / 1_000_000;
        String result = userFuture.join() + " + " + orderFuture.join() + " + " + recommendFuture.join();
        ctx.info("并行结果：%s", result);
        ctx.info("并行总耗时 %d ms（串行需要 %d ms）", parallelCost, serialCost);
        ctx.verify("并行明显更快", parallelCost < serialCost / 2);
        ctx.verify("慢下游被降级而不是拖垮整体", recommendFuture.join().contains("降级"));

        ctx.step("③ 部分失败也要返回：一个下游直接抛异常");
        CompletableFuture<String> broken = CompletableFuture
                .<String>supplyAsync(() -> {
                    throw new IllegalStateException("商品服务不可用");
                }, orderPool)
                .handle((value, error) -> error == null ? value : "商品(降级)");
        ctx.info("异常分支被 handle 兜住：%s", broken.join());
        ctx.verify("单个下游挂掉不影响整体", broken.join().contains("降级"));

        ctx.step("④ 关键细节");
        ctx.info("1. allOf 不收集结果，结果要自己 join（此时已 handle 过，不会抛）");
        ctx.info("2. orTimeout 只让等待方超时，底层任务还会跑完 —— 线程仍被占用");
        ctx.info("3. 所以下游线程池要隔离 + 够大，或者用虚拟线程");
        ctx.warn("推荐服务的慢任务在超时后仍占用了 downstream-recommend 的线程 600ms");

        ctx.step("⑤ 汇总");
        ctx.info("串行 %d ms → 并行 %d ms，提升 %.1f 倍",
                serialCost, parallelCost, serialCost * 1.0 / Math.max(1, parallelCost));
        ctx.info("返回给前端的结果：%s", result);
    }

    private String callUser(DemoContext ctx) {
        sleepQuietly(100);
        ctx.info("用户服务返回（100ms）");
        return "用户信息";
    }

    private String callOrder(DemoContext ctx) {
        sleepQuietly(150);
        ctx.info("订单服务返回（150ms）");
        return "订单列表";
    }

    private String callRecommendSlow(DemoContext ctx) {
        sleepQuietly(600);
        ctx.info("推荐服务返回（600ms，通常已经超时了）");
        return "推荐列表";
    }

    /** exceptionally 收到的可能是 TimeoutException，也可能被 CompletionException 包一层。 */
    private static String describe(Throwable error) {
        Throwable cause = error.getCause();
        return cause == null ? error.getClass().getSimpleName() : cause.getClass().getSimpleName();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
