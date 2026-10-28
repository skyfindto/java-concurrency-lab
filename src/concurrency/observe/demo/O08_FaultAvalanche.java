package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进阶 08：故障演练 —— 一个慢下游如何拖垮整个线程池（雪崩）。
 *
 * <p>雪崩的典型剧本：
 * 下游 B 变慢 → 线程都卡在等 B → 线程池耗尽 → 连不依赖 B 的接口也拿不到线程 →
 * 全站不可用。整个过程可能只发生在 30 秒内。
 *
 * <p>本用例先复现，再用「隔离 + 超时 + 降级」修复，用同一组指标对比效果。
 */
public class O08_FaultAvalanche extends DemoBase {

    public O08_FaultAvalanche() {
        super("obs-08-fault-avalanche", Level.OBSERVE,
                "故障演练：慢下游引发的雪崩",
                "共享线程池被慢请求占满，核心接口一起挂");
    }

    @Override
    public String knowledge() {
        return """
                ## 雪崩的三步
                1. 下游 B 的 RT 从 50ms 涨到 5s；
                2. 调用 B 的线程全部阻塞，线程池被占满；
                3. 其他接口也拿不到线程 → 全站 503/超时。

                ## 为什么会「全站」
                因为很多系统只有一个共享的线程池（或者 Tomcat 的 200 个工作线程），
                慢请求把它占满，快请求也只能排队。

                ## 防线（从内到外）
                1. **超时**：每个下游必须有超时，慢请求快速失败；
                2. **隔离**：不同下游用不同线程池 / 信号量，防止互相占满；
                3. **熔断**：下游持续失败时直接快速失败，不再占用线程；
                4. **限流**：入口限制总并发，防止突发流量放大问题；
                5. **降级**：慢/失败时返回缓存或默认值。

                ## 本用例的对比
                - 场景 A：4 线程共享池 + 无超时 → 4 个慢请求占满，快请求等 3 秒；
                - 场景 B：隔离池 + 300ms 超时 → 快请求 < 100ms，慢请求降级返回。

                ## 真实案例
                某电商大促，推荐服务 RT 从 100ms 涨到 8s，共享线程池被占满，
                导致下单接口全部超时。修复：推荐服务独立线程池 + 500ms 超时 + 降级为空列表。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 任何跨进程调用都必须有超时，且超时要小于上游剩余预算。
                - 慢/非核心依赖必须隔离线程池，禁止和核心链路共用一个池。
                - 超时后要降级（返回默认值），而不是把异常抛给用户。
                - 用熔断器在下游持续失败时快速失败，避免线程继续被占用。
                - 压测时专门测「一个下游变慢」的场景，验证不会横向扩散。
                - 监控要能看到「每个下游的线程池」饱和度和超时率。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 复现：4 线程共享池，4 个慢请求（3 秒）打满");
        ExecutorService shared = ctx.fixedPool("shared-avalanche-pool", 4);

        for (int i = 0; i < 4; i++) {
            shared.execute(() -> sleepQuietly(3000)); // 模拟下游 B 变慢
        }
        ctx.sleep(100);

        long badBegin = System.nanoTime();
        try {
            shared.submit(() -> {
                ctx.info("核心接口终于拿到线程了");
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            ctx.warn("核心接口等待失败：%s", e);
        }
        long badLatency = (System.nanoTime() - badBegin) / 1_000_000;
        ctx.error("核心接口在共享池里的延迟 = %d ms（用户侧早已超时）", badLatency);
        ctx.verify("共享池导致核心接口被拖慢", badLatency > 2000);

        ctx.step("② 修复：隔离池 + 超时 + 降级");
        ExecutorService recommendPool = ctx.fixedPool("recommend-pool", 4);
        ExecutorService corePool = ctx.fixedPool("core-pool", 8);

        // 慢下游：独立池，且只等 300ms
        for (int i = 0; i < 4; i++) {
            CompletableFuture
                    .supplyAsync(() -> {
                        sleepQuietly(3000);
                        return "推荐结果";
                    }, recommendPool)
                    .orTimeout(300, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> "推荐(超时降级)");
        }

        long goodBegin = System.nanoTime();
        String coreResult = CompletableFuture.supplyAsync(() -> {
            sleepQuietly(20);
            return "核心业务结果";
        }, corePool).get(2, TimeUnit.SECONDS);
        long goodLatency = (System.nanoTime() - goodBegin) / 1_000_000;

        ctx.info("核心接口返回：%s，延迟 %d ms", coreResult, goodLatency);
        ctx.verify("隔离后核心接口不受影响", goodLatency < 300);
        ctx.info("推荐服务在 300ms 后降级，用户看到「暂无推荐」而不是整页 500");

        ctx.step("③ 效果对比");
        ctx.info("共享池 + 无超时：核心接口延迟 %d ms（雪崩）", badLatency);
        ctx.info("隔离池 + 超时降级：核心接口延迟 %d ms（正常）", goodLatency);

        ctx.step("④ 三道防线缺一不可");
        ctx.info("超时：让慢请求「早点失败」，释放线程");
        ctx.info("隔离：让慢请求「只影响自己那个池」");
        ctx.info("熔断：让持续失败的请求「不再占用线程」");
        ctx.verify("雪崩防护 = 超时 + 隔离 + 熔断", true);

        ctx.step("⑤ 注意：超时后底层任务仍在跑");
        ctx.warn("orTimeout 只是让等待方放弃，推荐服务的任务还会跑满 3 秒");
        ctx.warn("如果下游长期慢，隔离池也会被占满 —— 所以还需要熔断和限流");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
