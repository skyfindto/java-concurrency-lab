package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 07：重试与退避 —— 重试是把双刃剑。
 *
 * <p>「失败了就重试 3 次」看起来是提高成功率，实际上是把流量放大 3 倍。
 * 当下游已经扛不住时，重试会把它彻底压死 —— 这叫<b>重试风暴</b>。
 *
 * <p>正确的重试必须同时具备：<b>指数退避 + 随机抖动 + 次数上限 + 幂等 + 熔断兜底</b>。
 */
public class A07_RetryBackoff extends DemoBase {

    public A07_RetryBackoff() {
        super("adv-07-retry-backoff", Level.ADVANCED,
                "重试与退避：别把下游打死",
                "重试 3 次 = 流量 ×3，退避和抖动才是解药");
    }

    @Override
    public String knowledge() {
        return """
                ## 重试放大效应
                QPS 100、失败率 50%、重试 2 次：
                实际打到下游的请求 ≈ 100 + 50 + 25 = 175，瞬时可能更高。
                如果下游已经过载，重试就是压垮它的最后一根稻草。

                ## 什么该重试
                ✅ 网络抖动、连接超时、5xx、限流（429）、死锁（DB）
                ❌ 参数错误（400）、鉴权失败（401/403）、资源不存在（404）、
                   业务规则拒绝（余额不足）、非幂等操作（已扣款）

                ## 重试必须满足
                1. **幂等**：同一个请求重试多次，结果一致（用唯一请求号/去重表）；
                2. **退避**：等待时间递增（指数退避 100ms → 200ms → 400ms）；
                3. **抖动（jitter）**：加随机量，避免所有客户端同时重试造成「共振」；
                4. **上限**：次数 + 总耗时都不能无限；
                5. **熔断**：连续失败就停止重试，直接快速失败。

                ## 退避公式
                    delay = min(cap, base * 2^attempt) * random(0.5, 1.0)
                第一次 50~100ms，第二次 100~200ms，第三次 200~400ms……

                ## 生产建议
                - 只在「调用方」重试，不要在每一层都重试（会指数放大）；
                - 重试日志要带 attempt 和下一次延迟，方便排查；
                - 有状态的操作（下单、扣款）不要自动重试，用「查询 + 补偿」代替。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 重试次数 2~3 次足够，超过说明下游真的不可用，重试也没用。
                - 必须加指数退避 + 抖动，禁止固定间隔密集重试。
                - 重试前先判断异常类型，业务异常绝不重试。
                - 非幂等接口不要重试：用「业务唯一号 + 去重表」或「先查后补」。
                - 重试要计入整体超时预算，别让重试把接口 RT 拖到 10 秒。
                - 配合熔断器：熔断打开时直接失败，不要浪费重试次数。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 模拟一个「每 3 次才成功 1 次」的下游");
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Supplier<Boolean> flaky = () -> calls.incrementAndGet() % 3 != 0;

        ctx.step("② 立即重试（无退避）：3 次内成功，但调用量翻倍");
        calls.set(0);
        long begin = System.nanoTime();
        boolean success = false;
        int attempt = 0;
        while (attempt < 3 && !success) {
            attempt++;
            success = flaky.get();
            ctx.info("第 %d 次调用 → %s", attempt, success ? "成功" : "失败");
        }
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("耗时 %d ms，共调用 %d 次", cost, calls.get());
        ctx.verify("最终成功", success);

        ctx.step("③ 指数退避 + 抖动：给下游喘息时间");
        calls.set(0);
        begin = System.nanoTime();
        success = false;
        attempt = 0;
        while (attempt < 3 && !success) {
            attempt++;
            success = flaky.get();
            if (!success && attempt < 3) {
                long base = 100L << (attempt - 1);          // 100, 200, 400
                long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
                long delay = base + jitter;
                ctx.info("第 %d 次失败，%d ms 后重试（base=%d + jitter=%d）",
                        attempt, delay, base, jitter);
                Thread.sleep(delay);
            }
        }
        long backoffCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("退避重试耗时 %d ms（比立即重试慢，但保护了下游）", backoffCost);
        ctx.verify("退避重试同样成功", success);

        ctx.step("④ 重试放大：算一笔账");
        int qps = 100;
        double failRate = 0.5;
        int retries = 2;
        double expected = qps;
        double remaining = qps;
        for (int i = 0; i < retries; i++) {
            remaining = remaining * failRate;
            expected += remaining;
        }
        ctx.info("原始 QPS=%d，失败率=%.0f%%，重试 %d 次 → 实际打到下游 ≈ %.0f QPS",
                qps, failRate * 100, retries, expected);
        ctx.verify("重试会放大流量", expected > qps);
        ctx.info("如果是 3 层都重试，放大倍数 = 3^3 = 27 倍，下游必死");

        ctx.step("⑤ 不该重试的场景");
        ctx.warn("参数错误、鉴权失败、余额不足、非幂等写操作 —— 重试只会制造脏数据");
        ctx.info("非幂等写操作要重试时：用业务唯一号 + 去重表保证幂等");

        ctx.step("⑥ 生产模板");
        ctx.info("for (int i = 0; i < maxAttempts; i++) {");
        ctx.info("    try { return call(); }");
        ctx.info("    catch (RetryableException e) {");
        ctx.info("        sleep(backoff(i) + jitter());");
        ctx.info("    }");
        ctx.info("}");
        ctx.info("工具：Resilience4j Retry / Spring Retry / Guava Retrying");
    }
}
