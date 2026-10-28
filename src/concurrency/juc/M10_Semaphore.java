package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中级 10：Semaphore —— 控制「同时有多少个」。
 *
 * <p>Semaphore 维护一组许可证（permit）：
 * acquire() 拿一个（没有就阻塞），release() 还一个。
 * 它不保护临界区，只限制<b>并发数量</b> —— 这是它和锁最本质的区别。
 */
public class M10_Semaphore extends DemoBase {

    public M10_Semaphore() {
        super("juc-10-semaphore", Level.INTERMEDIATE,
                "Semaphore：限流与资源池",
                "20 个任务、3 个许可证，实测最大并发到底是多少");
    }

    @Override
    public String knowledge() {
        return """
                ## 语义
                - `new Semaphore(3)` → 3 个许可，最多 3 个线程同时通过；
                - `acquire()` 拿不到会阻塞；`tryAcquire()` 立刻返回；`tryAcquire(timeout)` 等一会儿；
                - `release()` 归还，**必须放在 finally**，否则许可泄漏，最终没人能再通过。

                ## 和锁的区别
                - 锁：同一时刻只有一个线程进入临界区，且**谁加的锁谁释放**；
                - 信号量：允许 N 个并发，**释放者不必是获取者**（可以跨线程归还）。

                ## 典型用途
                1. 限流：限制同时访问某个下游的请求数（保护对方）；
                2. 资源池：数据库连接、HTTP 连接、线程池内部就是类似思路；
                3. 背压：把「无限制提交」变成「最多 N 个在飞」。

                ## 公平性
                `new Semaphore(n, true)` 是公平模式，按等待顺序分配，能避免饿死，但吞吐略低。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - acquire 和 release 必须成对，release 写在 finally 里。
                - 不要用 Semaphore 做「精确限流」：它是固定速率，不是令牌桶；限流用 Guava RateLimiter / Sentinel。
                - 限流值要小于等于下游的承载能力，通常还要留出重试余量。
                - 配合超时要使用 tryAcquire(timeout)，否则线程可能无限等待。
                - Semaphore 的许可数不会自动恢复，服务重启/异常后要注意泄漏。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 3 个许可证，20 个任务，观察实际并发峰值");
        Semaphore semaphore = new Semaphore(3);
        ExecutorService pool = ctx.executor("semaphore-pool", 10, 10, 100);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(20);

        long begin = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            pool.execute(() -> {
                try {
                    semaphore.acquire();
                    try {
                        int now = inFlight.incrementAndGet();
                        peak.accumulateAndGet(now, Math::max);
                        sleepQuietly(80);
                    } finally {
                        inFlight.decrementAndGet();
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(30, TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;

        ctx.info("20 个任务 × 80ms，许可证 3 → 总耗时 %d ms（理论 ≈ 20/3×80 ≈ 530ms）", cost);
        ctx.info("实测最大并发 = %d", peak.get());
        ctx.verify("并发数没有超过许可证数量", peak.get() <= 3);
        ctx.verify("确实达到了 3 个并发", peak.get() == 3);

        ctx.step("② tryAcquire(timeout)：拿不到就放弃，别死等");
        Semaphore one = new Semaphore(1);
        one.acquire();
        long t0 = System.nanoTime();
        boolean got = one.tryAcquire(200, TimeUnit.MILLISECONDS);
        long waited = (System.nanoTime() - t0) / 1_000_000;
        ctx.info("tryAcquire(200ms) = %s，等了 %d ms", got, waited);
        ctx.verify("没有许可时超时返回 false", !got);
        one.release();

        ctx.step("③ 公平模式");
        Semaphore fair = new Semaphore(2, true);
        ctx.verify("fair.isFair() == true", fair.isFair());
        ctx.info("公平模式按等待顺序分配，避免某个线程永远抢不到");

        ctx.step("④ 生产用法：保护下游，而不是保护自己");
        ctx.info("比如调用第三方接口最多允许 5 个并发：");
        ctx.info("  Semaphore downstream = new Semaphore(5);");
        ctx.info("  downstream.acquire(); try { http.call(); } finally { downstream.release(); }");
        ctx.info("这样即使上游流量翻倍，也不会把下游打挂");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
