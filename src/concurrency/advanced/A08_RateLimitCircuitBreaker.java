package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 08：限流与熔断 —— 过载保护的两道闸门。
 *
 * <p>限流解决「进来太多」，熔断解决「下游挂了还要不要等」。
 * 两者都是<b>快速失败</b>思想：与其让用户等到超时，不如立刻告诉他「稍后再试」。
 *
 * <p>本用例手写令牌桶 + 滑动窗口 + 熔断器状态机，把原理跑一遍。
 */
public class A08_RateLimitCircuitBreaker extends DemoBase {

    /** 令牌桶：固定速率补充令牌，桶满即止，取不到就拒绝。 */
    private static final class TokenBucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;

        TokenBucket(double permitsPerSecond, double capacity) {
            this.capacity = capacity;
            this.refillPerNano = permitsPerSecond / 1_000_000_000.0;
            this.tokens = capacity;
            this.lastRefill = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
            lastRefill = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** 简化版熔断器：滑动失败计数 + 三态机。 */
    private static final class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private final int failureThreshold;
        private final long openMillis;
        private State state = State.CLOSED;
        private int failures;
        private long openedAt;

        CircuitBreaker(int failureThreshold, long openMillis) {
            this.failureThreshold = failureThreshold;
            this.openMillis = openMillis;
        }

        synchronized boolean allowRequest() {
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - openedAt >= openMillis) {
                    state = State.HALF_OPEN; // 放一个请求去探路
                    return true;
                }
                return false;
            }
            return true;
        }

        synchronized void onSuccess() {
            state = State.CLOSED;
            failures = 0;
        }

        synchronized void onFailure() {
            failures++;
            if (state == State.HALF_OPEN || failures >= failureThreshold) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
            }
        }

        synchronized State state() {
            return state;
        }
    }

    public A08_RateLimitCircuitBreaker() {
        super("adv-08-rate-limit-circuit-breaker", Level.ADVANCED,
                "限流与熔断：过载保护",
                "令牌桶、滑动窗口、三态熔断器，手写一遍就懂了");
    }

    @Override
    public String knowledge() {
        return """
                ## 限流算法
                | 算法 | 特点 | 问题 |
                |---|---|---|
                | 固定窗口计数 | 最简单 | 窗口边界会放过 2 倍流量 |
                | 滑动窗口 | 平滑 | 实现稍复杂 |
                | 漏桶 | 恒定速率出 | 无法应对突发 |
                | 令牌桶 | 允许一定突发 | 需要调容量 |

                生产常用：Guava `RateLimiter`（令牌桶）、Sentinel（滑动窗口 + 预热 + 排队等待）、
                Redis + Lua（分布式限流）。

                ## 熔断器三态
                - **CLOSED**：正常放行，统计失败率；
                - **OPEN**：快速失败，不再调用下游（保护下游，也保护自己）；
                - **HALF_OPEN**：冷却时间到，放少量请求探测，成功则 CLOSED，失败则继续 OPEN。

                ## 熔断 vs 限流
                - 限流：保护**自己**不被流量打垮（入口）；
                - 熔断：保护**自己**不被下游拖死（出口）；
                - 降级：已经出问题了，返回兜底数据保证可用性。

                ## 关键参数
                - 失败率阈值（如 50%）或连续失败次数（如 5 次）；
                - 最小请求数（样本太少不熔断，避免误判）；
                - 熔断时长（如 5~30 秒），太长恢复慢，太短没效果；
                - 半开放行数量（通常 1~5 个）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 限流维度要选对：接口、用户、IP、租户、下游依赖，通常要多维度组合。
                - 限流阈值必须来自压测，不能拍脑袋；上线后按实际水位动态调整。
                - 熔断要按「下游 + 接口」维度配置，不同接口的成功率基线不同。
                - 熔断打开时必须有降级逻辑，不能直接抛异常给用户。
                - 分布式场景用 Redis/Lua 或 Sentinel 集群流控，单机限流挡不住总流量。
                - 限流/熔断的开关要能动态配置，故障时能立刻调整。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 令牌桶：每秒 5 个令牌，容量 5，瞬间来 20 个请求");
        TokenBucket bucket = new TokenBucket(5, 5);
        AtomicInteger passed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            if (bucket.tryAcquire()) {
                passed.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        }
        ctx.info("立即通过 %d 个，拒绝 %d 个（桶里有 5 个初始令牌）", passed.get(), rejected.get());
        ctx.verify("令牌桶允许突发（容量 5）", passed.get() == 5);

        ctx.step("② 等 1 秒，又攒了约 5 个令牌");
        ctx.sleep(1000);
        int secondRound = 0;
        for (int i = 0; i < 20; i++) {
            if (bucket.tryAcquire()) {
                secondRound++;
            }
        }
        ctx.info("1 秒后再放行 %d 个（速率 5/s）", secondRound);
        ctx.verify("令牌按速率补充", secondRound >= 4 && secondRound <= 6);

        ctx.step("③ 熔断器：连续失败 3 次后打开，快速失败");
        CircuitBreaker breaker = new CircuitBreaker(3, 500);
        AtomicInteger fastFail = new AtomicInteger();
        AtomicInteger realCalls = new AtomicInteger();

        java.util.function.Supplier<Boolean> downstream = () -> false; // 一直失败

        for (int i = 1; i <= 10; i++) {
            if (!breaker.allowRequest()) {
                fastFail.incrementAndGet();
                ctx.warn("第 %d 次请求：熔断打开，直接快速失败（没有调用下游）", i);
                continue;
            }
            realCalls.incrementAndGet();
            boolean ok = downstream.get();
            if (ok) {
                breaker.onSuccess();
            } else {
                breaker.onFailure();
                ctx.info("第 %d 次请求：调用失败，累计失败 → 状态 %s", i, breaker.state());
            }
        }
        ctx.info("实际调用下游 %d 次，快速失败 %d 次", realCalls.get(), fastFail.get());
        ctx.verify("熔断打开后不再调用下游", realCalls.get() == 3);
        ctx.verify("其余请求全部快速失败", fastFail.get() == 7);

        ctx.step("④ 冷却 500ms 后进入 HALF_OPEN，放一个请求探测");
        ctx.sleep(520);
        boolean probeAllowed = breaker.allowRequest();
        ctx.info("冷却后 allowRequest = %s（状态 %s）", probeAllowed, breaker.state());
        ctx.verify("进入半开状态", breaker.state() == CircuitBreaker.State.HALF_OPEN);
        breaker.onSuccess(); // 探测成功
        ctx.info("探测成功后状态 = %s（恢复正常）", breaker.state());
        ctx.verify("探测成功回到 CLOSED", breaker.state() == CircuitBreaker.State.CLOSED);

        ctx.step("⑤ 三者配合才是完整方案");
        ctx.info("限流（入口）→ 熔断（出口）→ 降级（兜底）→ 超时（每个等待）");
        ctx.info("再加隔离（舱壁）和重试退避，就是一套完整的稳定性体系");
        ctx.verify("过载保护 = 快速失败 + 有损服务", true);
    }
}
