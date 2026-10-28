package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 中级 05：Future / FutureTask —— 异步任务的「取货凭证」。
 *
 * <p>Future 有三个坑，每一个都在生产上造成过事故：
 * <ol>
 *   <li>{@code get()} 会无限阻塞：调用方线程被拖死；</li>
 *   <li>{@code get(timeout)} 超时后<b>任务并不会停</b>，它还在后台跑，继续占线程；</li>
 *   <li>{@code cancel(false)} 只是让 get() 抛异常，任务照跑不误。</li>
 * </ol>
 */
public class M05_Future extends DemoBase {

    public M05_Future() {
        super("juc-05-future", Level.INTERMEDIATE,
                "Future：超时、取消与三个坑",
                "get(timeout) 超时了，任务真的停了吗？");
    }

    @Override
    public String knowledge() {
        return """
                ## Future 的五个方法
                - `get()`：阻塞直到结果就绪；任务抛异常时抛 ExecutionException（原因在 getCause()）。
                - `get(timeout, unit)`：最多等这么久，超时抛 TimeoutException。
                - `cancel(true)`：尝试中断正在执行的任务；`cancel(false)`：只在任务未开始时阻止它启动。
                - `isCancelled()` / `isDone()`：状态查询。注意 isDone 对「正常完成、异常、取消」都为 true。

                ## 坑 1：超时 ≠ 停止
                Future.get 超时只是「调用方不等了」，线程池里的任务还在跑。
                如果任务本身很慢，线程会被一直占用，池子照样会被耗尽。
                正确做法：超时后主动 cancel(true)，并且任务内部要响应中断。

                ## 坑 2：cancel(true) 也需要任务配合
                cancel(true) 只是 interrupt，任务里如果是死循环且不检查中断标志，照样停不下来。
                另外，任务若是阻塞在不可中断的 IO（如 Socket 读），interrupt 也无效。

                ## 坑 3：submit 的异常被吞
                submit 返回 Future，异常被封在 Future 里。不 get 就永远不知道失败，
                日志里连一行错误都没有 —— 这是「任务静默失败」的经典来源。

                ## 生产更推荐 CompletableFuture
                可编排、可组合、支持超时（orTimeout）和异常兜底（exceptionally），
                比裸 Future 好用得多（见 juc-06）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 永远用 `get(timeout, unit)`，不要用无参 get()。
                - 超时后必须 `cancel(true)`，并记录「任务被放弃」的日志和指标。
                - 任务内部要定期 `Thread.interrupted()` / 检查中断标志，否则取消是无效的。
                - 异步调用下游时，超时时间要小于上游的超时预算（留出余量）。
                - 需要「失败重试 / 多路并发 / 结果聚合」时直接用 CompletableFuture。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService pool = ctx.fixedPool("future-pool", 2);

        ctx.step("① get()：调用线程会被阻塞，直到结果出来");
        Future<String> future = pool.submit(() -> {
            Thread.sleep(300);
            return "任务结果";
        });
        long begin = System.nanoTime();
        String value = future.get();
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("get() 返回「%s」，主线程被阻塞了 %d ms", value, cost);
        ctx.verify("get 确实阻塞等待了", cost >= 250);

        ctx.step("② get(timeout) 超时：任务并不会停，仍然占着线程");
        Future<String> slow = pool.submit(() -> {
            Thread.sleep(1500);
            return "迟到的结果";
        });
        try {
            slow.get(200, TimeUnit.MILLISECONDS);
            ctx.error("居然没超时？");
        } catch (TimeoutException e) {
            ctx.warn("200ms 超时返回，但任务还在跑：isDone=%s, isCancelled=%s",
                    slow.isDone(), slow.isCancelled());
        }
        ctx.verify("超时后任务仍在运行", !slow.isDone());

        ctx.step("③ 超时后必须主动 cancel(true)，否则线程一直被占着");
        boolean cancelled = slow.cancel(true);
        ctx.info("cancel(true) = %s", cancelled);
        ctx.verify("isCancelled = true", slow.isCancelled());
        try {
            slow.get();
        } catch (CancellationException e) {
            ctx.ok("对已取消的任务 get() 抛 CancellationException");
        }
        ctx.verify("isDone 对已取消任务也是 true", slow.isDone());

        ctx.step("④ cancel(false) 不中断任务：任务照跑，只是结果拿不到");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Future<?> stubborn = pool.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
        });
        started.await();
        ctx.info("调用 cancel(false) = %s", stubborn.cancel(false));
        ctx.sleep(600);
        ctx.info("任务实际跑完了吗？%s（cancel(false) 拦不住已经开跑的任务）", finished.get());
        ctx.verify("cancel(false) 不会中断运行中的任务", finished.get());

        ctx.step("⑤ 任务里的异常：ExecutionException 包着原始异常");
        Future<?> failed = pool.submit(() -> {
            throw new IllegalArgumentException("参数不合法");
        });
        try {
            failed.get();
        } catch (ExecutionException e) {
            ctx.info("getCause() = %s", e.getCause());
            ctx.verify("原始异常被保留", e.getCause() instanceof IllegalArgumentException);
        }
    }
}
