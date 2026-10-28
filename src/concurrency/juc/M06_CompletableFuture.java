package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 中级 06：CompletableFuture —— 异步编排的主力。
 *
 * <p>真实业务里，「查用户 + 查订单 + 查优惠券」必须并行，最后合并成一个响应。
 * 用裸 Future 要写一堆 get 和 try-catch；CompletableFuture 可以用一行链式表达。
 *
 * <p>本用例把常用 API 全部跑一遍：thenApply / thenCompose / thenCombine /
 * allOf / anyOf / exceptionally / handle / orTimeout / completeOnTimeout，
 * 并重点演示两个最容易踩的点：
 * <ul>
 *   <li><b>整体超时</b>要加在「聚合后的 future」上，而不是只给单个下游加；</li>
 *   <li><b>orTimeout / cancel 都不会停止底层任务</b>，线程仍在被占用。</li>
 * </ul>
 */
public class M06_CompletableFuture extends DemoBase {

    /** 领域对象：金额用「分」存储，避免浮点误差。 */
    private record Order(long id, long amountFen, String user) {
    }

    /** 对外 VO：金额转成「元」的字符串。 */
    private record OrderVO(String orderNo, String amountYuan, String user) {
    }

    public M06_CompletableFuture() {
        super("juc-06-completable-future", Level.INTERMEDIATE,
                "CompletableFuture 编排全家桶",
                "并行聚合、thenApply 转换、单路超时与整体超时");
    }

    @Override
    public String knowledge() {
        return """
                ## 核心 API 速查
                - 创建：`supplyAsync(有返回值)` / `runAsync(无返回值)`
                - 转换：`thenApply(fn)` 同步转换；`thenCompose(fn)` 嵌套另一个异步任务（扁平化）
                - 合并：`thenCombine(other, fn)` 等两个都完成；`thenAccept` 只消费不返回
                - 等待全部：`allOf(cfs...)`，返回 `CompletableFuture<Void>`，需要再 join 各结果
                - 任一完成：`anyOf(cfs...)`，返回 `CompletableFuture<Object>`
                - 异常：`exceptionally(fn)` 兜底；`handle((v,e)->...)` 同时处理成功与失败；`whenComplete` 只做收尾
                - 超时：`orTimeout(ms)` 抛 TimeoutException；`completeOnTimeout(v, ms)` 给默认值

                ## thenApply 的执行线程（很多人误解）
                它**不会新开线程**，而是在「完成上游的那个线程」上执行；
                如果上游已经完成（比如 `completedFuture`），它就在**调用 thenApply 的当前线程**上同步执行。
                所以：thenApply 里只放纯内存转换，别做阻塞调用；要换线程用 `thenApplyAsync(fn, pool)`。

                ## get() 与 join() 的区别
                | 维度 | get() | join() |
                |---|---|---|
                | 异常 | 受检 `InterruptedException` / `ExecutionException` | 非受检 `CompletionException` |
                | 超时 | 支持 `get(timeout, unit)` | 不支持，只能配合 `orTimeout` |
                | 可中断 | 是（抛 InterruptedException） | **否**，会忽略中断继续等 |
                | 来源 | Future 接口 | CompletableFuture 独有 |
                两者异常链都是「包一层」：get → ExecutionException(cause=原始异常)，
                join → CompletionException(cause=原始异常)。取消时都抛 CancellationException。

                ## 整体超时加在哪
                1. **聚合 future 上**：`allOf(...).orTimeout(deadline)`，
                   或 `completeOnTimeout(默认值, deadline)`；
                2. **注意**：它只解决「等待方别等了」，**不会停止上游任务**，
                   上游线程仍被占用到自然结束（本用例第 ⑦ 步会实测）；
                3. **要真正取消**：提交时用 `ExecutorService.submit()` 拿真 Future
                   （`cancel(true)` 才会 interrupt），或用 `invokeAll(tasks, timeout, unit)`，
                   或让任务自己检查 deadline；
                4. **deadline 传递**：每个下游按「剩余时间」设置自己的超时，自上而下递减。

                ## 默认线程池的坑
                不传 Executor 时用 `ForkJoinPool.commonPool()`，并行度默认是 CPU 核数 - 1。
                如果在里面跑阻塞任务（DB/HTTP/sleep），会把整个 JVM 的公共池拖死，
                连 parallelStream 都会跟着卡住。**生产必须显式传自己的线程池。**

                ## thenApply vs thenCompose
                - thenApply：`T -> R`，同步转换，结果是 `CompletableFuture<R>`
                - thenCompose：`T -> CompletableFuture<R>`，用于串联另一个异步调用，
                  否则你会得到 `CompletableFuture<CompletableFuture<R>>`。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 所有 supplyAsync 都显式指定业务线程池；禁止裸用 commonPool 跑阻塞任务。
                - 编排链里用 join() 更顺手（没有受检异常）；但需要超时或可中断时，
                  在边界用 get(timeout) 或 orTimeout —— join() 是不响应中断的。
                - 聚合请求一定要在 allOf 之后加整体超时，否则一个慢下游会把整个请求挂住。
                - 记住超时 / cancel 都不会停止底层任务：线程池要隔离、够大，或让任务自己检查 deadline。
                - 不要用 `allOf(...).get()`，用 join() 或 handle，避免把受检异常带到业务代码里。
                - 部分失败要能降级：用 exceptionally 给默认值，而不是让整个请求 500。
                - 注意线程上下文（TraceId/用户信息）不会自动传递，需要包装（见 adv-06）。
                - thenApply 只做纯内存转换，阻塞逻辑放 thenApplyAsync 或另一个线程池。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService pool = ctx.fixedPool("cf-pool", 8);
        ctx.info("业务线程池并行度 = 8；commonPool 并行度 = %d（别用它跑阻塞任务）",
                ForkJoinPool.commonPool().getParallelism());

        parallelAggregation(ctx, pool);
        thenApplyDemo(ctx, pool);
        composeAndCombine(ctx, pool);
        errorHandling(ctx, pool);
        singleTimeout(ctx, pool);
        overallTimeout(ctx, pool);
        anyOfDemo(ctx, pool);

        ctx.step("小结");
        ctx.info("并行聚合省时间，超时兜底保可用，exceptionally 保体验");
        ctx.info("记住：orTimeout / cancel 只影响「等待方」，底层任务还在跑");
    }

    // ------------------------------------------------------------ ① 并行聚合

    private void parallelAggregation(DemoContext ctx, ExecutorService pool) {
        ctx.step("① 并行聚合：三个 300ms 的下游调用同时发起");
        long begin = System.nanoTime();
        CompletableFuture<String> userFuture = CompletableFuture.supplyAsync(() -> {
            sleep(300);
            return "用户信息";
        }, pool);
        CompletableFuture<String> orderFuture = CompletableFuture.supplyAsync(() -> {
            sleep(300);
            return "订单列表";
        }, pool);
        CompletableFuture<String> couponFuture = CompletableFuture.supplyAsync(() -> {
            sleep(300);
            return "优惠券";
        }, pool);

        CompletableFuture.allOf(userFuture, orderFuture, couponFuture).join();
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("结果：%s + %s + %s", userFuture.join(), orderFuture.join(), couponFuture.join());
        ctx.info("总耗时 %d ms（串行需要 ~900ms）", cost);
        ctx.verify("并行总耗时接近单个调用而非相加", cost < 600);
    }

    // ------------------------------------------------------------ ② thenApply

    private void thenApplyDemo(DemoContext ctx, ExecutorService pool) {
        ctx.step("② thenApply：同步转换（DO → VO，纯内存操作）");
        AtomicReference<String> callbackThread = new AtomicReference<>();

        CompletableFuture<Order> orderFuture = CompletableFuture.supplyAsync(() -> {
            sleep(100);
            return new Order(1001L, 12_800L, "alice");
        }, pool);

        CompletableFuture<OrderVO> voFuture = orderFuture.thenApply(order -> {
            // 这里跑在「完成 orderFuture 的那个线程」上，而不是新线程
            callbackThread.set(Thread.currentThread().getName());
            String yuan = String.format(Locale.ROOT, "%.2f", order.amountFen() / 100.0);
            return new OrderVO("ORDER-" + order.id(), yuan, order.user());
        });

        OrderVO vo = voFuture.join();
        ctx.info("DO  = %s", orderFuture.join());
        ctx.info("VO  = %s", vo);
        ctx.info("thenApply 回调线程 = %s（复用完成上游的线程，不新开线程）", callbackThread.get());
        ctx.verify("金额从「分」转成「元」", "128.00".equals(vo.amountYuan()));
        ctx.verify("thenApply 复用上游线程（cf-pool）", callbackThread.get().startsWith("cf-pool"));

        ctx.step("为什么用 thenApply 而不是再 submit 一个任务？");
        ctx.info("纯内存转换再 submit 一次 = 多一次线程切换、多一个任务对象，没有收益");
        ctx.info("需要换线程/做阻塞操作才用 thenApplyAsync(fn, 另一个池)");
        ctx.info("如果上游已经完成，thenApply 会在【当前线程】直接同步执行");
        OrderVO syncVo = CompletableFuture.completedFuture(new Order(2L, 9_900L, "bob"))
                .thenApply(o -> new OrderVO("ORDER-" + o.id(), "99.00", o.user()))
                .join();
        ctx.info("completedFuture + thenApply = 同步调用，结果 %s", syncVo);
        ctx.verify("thenApply 也可以做纯同步链路", "99.00".equals(syncVo.amountYuan()));
    }

    // ------------------------------------------------------------ ③④ thenCompose / thenCombine

    private void composeAndCombine(DemoContext ctx, ExecutorService pool) {
        ctx.step("③ thenCompose：串联另一个异步任务（避免嵌套 Future）");
        String composed = CompletableFuture.supplyAsync(() -> "user-1", pool)
                .thenCompose(userId -> CompletableFuture.supplyAsync(
                        () -> "根据 " + userId + " 查到的订单", pool))
                .join();
        ctx.info("thenCompose → %s", composed);
        ctx.verify("thenCompose 把结果压平成一个 CompletableFuture", composed.contains("user-1"));

        ctx.step("④ thenCombine：两个独立结果合并");
        String combined = CompletableFuture.supplyAsync(() -> "商品A", pool)
                .thenCombine(CompletableFuture.supplyAsync(() -> "¥99", pool),
                        (product, price) -> product + " 卖 " + price)
                .join();
        ctx.info("thenCombine → %s", combined);
        ctx.verify("thenCombine 合并两个独立结果", combined.contains("¥99"));
    }

    // ------------------------------------------------------------ ⑤ 异常处理

    private void errorHandling(DemoContext ctx, ExecutorService pool) {
        ctx.step("⑤ 异常处理：exceptionally 兜底，handle 统一处理");
        String fallback = CompletableFuture.<String>supplyAsync(() -> {
            throw new IllegalStateException("下游服务超时");
        }, pool).exceptionally(e -> "兜底默认值").join();
        ctx.info("exceptionally → %s", fallback);
        ctx.verify("异常被兜底", "兜底默认值".equals(fallback));

        String handled = CompletableFuture.<String>supplyAsync(() -> {
            throw new IllegalStateException("下游服务超时");
        }, pool).handle((value, error) -> error == null ? value : "handle 兜底："
                + error.getCause().getMessage()).join();
        ctx.info("handle → %s", handled);
        ctx.verify("handle 同时处理成功与失败", handled.contains("handle 兜底"));
    }

    // ------------------------------------------------------------ ⑥ 单路超时

    private void singleTimeout(DemoContext ctx, ExecutorService pool) {
        ctx.step("⑥ 单路超时：orTimeout 只让「等待方」超时");
        CompletableFuture<String> recommend = CompletableFuture
                .supplyAsync(() -> {
                    sleep(800);
                    return "推荐列表";
                }, pool)
                .orTimeout(200, TimeUnit.MILLISECONDS)
                .exceptionally(e -> "推荐(降级)");
        String result = recommend.join();
        ctx.info("推荐服务结果：%s", result);
        ctx.verify("单路超时后降级", result.contains("降级"));
        ctx.warn("注意：被超时的那个任务还在池子里跑，线程没有释放");
    }

    // ------------------------------------------------------------ ⑦ 整体超时

    private void overallTimeout(DemoContext ctx, ExecutorService pool) throws InterruptedException {
        ctx.step("⑦ 整体超时：给「聚合后的 future」加 deadline");
        AtomicBoolean slowFinished = new AtomicBoolean();
        CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> {
            sleep(900);
            slowFinished.set(true);
            return "A";
        }, pool);
        CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> {
            sleep(800);
            return "B";
        }, pool);

        long begin = System.nanoTime();
        String combined;
        try {
            combined = CompletableFuture.allOf(a, b)
                    .thenApply(ignored -> a.join() + "+" + b.join())   // 聚合
                    .orTimeout(300, TimeUnit.MILLISECONDS)             // ← 整体超时加在这里
                    .join();
        } catch (CompletionException e) {
            combined = "聚合超时 → 降级返回";
            ctx.warn("整体超时：%s", e.getCause());
        }
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("整体结果：%s，耗时 %d ms", combined, cost);
        ctx.verify("整体在 deadline 附近返回", cost >= 280 && cost < 600);

        ctx.info("此刻上游任务 a 完成了吗？%s（超时不会取消它）", a.isDone());
        ctx.verify("orTimeout 不会取消上游任务", !a.isDone());
        Thread.sleep(700); // 等它自己跑完
        ctx.info("700ms 后 a 才完成：%s（线程白白被占用）", slowFinished.get());
        ctx.verify("上游任务最终仍然执行完", slowFinished.get());

        ctx.step("另一种写法：超时给默认值而不是抛异常");
        long t0 = System.nanoTime();
        String value = CompletableFuture
                .supplyAsync(() -> {
                    sleep(500);
                    return "慢结果";
                }, pool)
                .completeOnTimeout("默认值", 200, TimeUnit.MILLISECONDS)
                .join();
        long fallbackCost = (System.nanoTime() - t0) / 1_000_000;
        ctx.info("completeOnTimeout 在 %d ms 返回：%s", fallbackCost, value);
        ctx.verify("completeOnTimeout 返回默认值", "默认值".equals(value));

        ctx.step("要想「超时就真正停下」，必须用真 Future");
        ctx.info("CompletableFuture.cancel(true) 不会中断底层任务（mayInterruptIfRunning 被忽略）");
        ctx.info("正确做法 1：ExecutorService.submit() 拿 Future → cancel(true) 才会 interrupt");
        ctx.info("正确做法 2：invokeAll(tasks, timeout, unit)，超时会取消未完成的任务");
        ctx.info("正确做法 3：把 deadline 传进任务，自己检查剩余时间");
        ctx.warn("否则：下游慢 10 秒，你的线程就被占 10 秒；超时只是保护了调用方，没保护线程池");
    }

    // ------------------------------------------------------------ ⑧ anyOf

    private void anyOfDemo(DemoContext ctx, ExecutorService pool) {
        ctx.step("⑧ anyOf：谁先返回用谁（比如多机房读取）");
        CompletableFuture<String> fast = CompletableFuture.supplyAsync(() -> {
            sleep(100);
            return "机房A";
        }, pool);
        CompletableFuture<String> slowest = CompletableFuture.supplyAsync(() -> {
            sleep(800);
            return "机房B";
        }, pool);
        Object first = CompletableFuture.anyOf(fast, slowest).join();
        ctx.info("anyOf 先拿到：%s", first);
        ctx.verify("anyOf 返回最快的那个", "机房A".equals(first));
        ctx.info("注意：慢的那个不会被取消，仍然会跑完");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
