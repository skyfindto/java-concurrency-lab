package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 高级 05：超时控制 —— 让线程池里的任务「必然会结束」。
 *
 * <p>没有超时的等待，就是一颗定时炸弹。上游 QPS 一高，
 * 所有线程都卡在等下游上，池子瞬间耗尽，整个服务雪崩。
 *
 * <p>本用例演示三个层级的超时，以及一个极其反直觉的坑：
 * <b>CompletableFuture.orTimeout 只让「等待」超时，不会停止底层任务</b>。
 */
public class A05_Timeout extends DemoBase {

    public A05_Timeout() {
        super("adv-05-timeout", Level.ADVANCED,
                "超时控制：从 Future 到 CompletableFuture",
                "orTimeout 之后，那个慢任务真的停了吗？");
    }

    @Override
    public String knowledge() {
        return """
                ## 三个层级的超时
                1. **连接超时**（connectTimeout）：TCP 握手最多等多久，通常 200~500ms；
                2. **读超时**（readTimeout）：拿到连接后等响应，通常 500~3000ms；
                3. **业务超时**（deadline）：整个调用链的预算，包含重试、排队。

                ## 为什么必须有超时
                - 线程池的线程数是有限的（比如 200），如果每个请求都无限等待，
                  200 个慢请求就能让服务完全失去响应；
                - 超时是「快速失败」的前提，也是熔断、重试能工作的前提；
                - **没有超时的下游调用 = 把可用性交给了别人**。

                ## 坑 1：Future.get(timeout) 超时后任务还在跑
                超时只是「调用方不等了」，任务继续占用线程。
                必须 `cancel(true)`，并且任务内部要响应中断。

                ## 坑 2：CompletableFuture.orTimeout 不会中断底层任务
                `orTimeout` 只是把 Future 标记为异常完成，
                supplyAsync 里的任务仍会跑完。要真正取消，需要自己持有任务句柄并 cancel。

                ## 超时预算（务必自上而下递减）
                    网关 3000ms
                      └─ 订单服务 2000ms
                           ├─ 库存 800ms（可重试 1 次）
                           └─ 优惠券 500ms（不重试）
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 所有 RPC/HTTP/DB 调用都必须显式设置连接超时和读超时，不要依赖默认值（可能是无限）。
                - 超时时间要按 P99 设定，并预留重试预算；不要拍脑袋写 60 秒。
                - 超时后要 cancel 任务，并记录「超时」指标（按下游维度）。
                - 超时预算自上而下递减，下游超时必须小于上游剩余时间。
                - 重试会增加总耗时，重试预算也要算进 deadline。
                - 批量任务要给整个批次设 deadline，而不是每个元素独立超时。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService pool = ctx.fixedPool("timeout-pool", 4);

        ctx.step("① Future.get(timeout) + cancel(true)：调用方和任务都结束");
        Future<String> slow = pool.submit(() -> {
            Thread.sleep(2000);
            return "慢任务结果";
        });
        long begin = System.nanoTime();
        try {
            slow.get(200, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            long waited = (System.nanoTime() - begin) / 1_000_000;
            ctx.warn("200ms 超时（实际等了 %d ms），现在 cancel", waited);
            boolean cancelled = slow.cancel(true);
            ctx.info("cancel(true) = %s，isCancelled = %s", cancelled, slow.isCancelled());
            ctx.verify("任务被真正取消", slow.isCancelled());
        }

        ctx.step("② 关键坑：CompletableFuture.orTimeout 不会停止底层任务");
        AtomicBoolean taskFinished = new AtomicBoolean();
        CompletableFuture<Void> cf = CompletableFuture.runAsync(() -> {
            sleepQuietly(600);
            taskFinished.set(true);
            ctx.warn("底层任务仍然跑完了（orTimeout 只是让等待方超时）");
        }, pool);
        try {
            cf.orTimeout(200, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            ctx.warn("等待方在 200ms 超时返回：%s", e.getCause().getClass().getSimpleName());
        }
        ctx.sleep(700);
        ctx.info("底层任务是否执行完成？%s", taskFinished.get());
        ctx.verify("orTimeout 不会中断底层任务", taskFinished.get());
        ctx.info("要真正取消：自己保存 Future 并 cancel(true)，或让任务主动检查超时");

        ctx.step("③ 用 Semaphore 限并发 + 超时，避免线程被无限占用");
        Semaphore permits = new Semaphore(2);
        int acquired = 0;
        for (int i = 0; i < 5; i++) {
            boolean ok = permits.tryAcquire(50, TimeUnit.MILLISECONDS);
            if (ok) {
                acquired++;
            }
        }
        ctx.info("5 次尝试、2 个许可：成功获得 %d 个，其余快速失败", acquired);
        ctx.verifyEquals("只有 2 个许可可用", 2, acquired);

        ctx.step("④ 超时预算：自上而下递减");
        ctx.info("网关 3000ms → 订单 2000ms → 库存 800ms → 缓存 200ms");
        ctx.info("如果库存重试 2 次，单次超时必须 ≤ 2000/3 ≈ 600ms");
        ctx.warn("最常见的错误：每一层都设 3 秒，结果最坏情况 9 秒才返回");

        ctx.step("⑤ 代码模板");
        ctx.info("try {");
        ctx.info("    return future.get(800, MILLISECONDS);");
        ctx.info("} catch (TimeoutException e) {");
        ctx.info("    future.cancel(true);");
        ctx.info("    metrics.timeout(\"inventory\");");
        ctx.info("    return fallback();");
        ctx.info("}");
        ctx.verify("任何等待都必须有上限", true);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
