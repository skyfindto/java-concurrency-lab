package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * 设计模式 02：Future / Promise —— 异步调用的「取货凭证」。
 *
 * <p>调用方发起一个耗时操作，立刻拿到一张「凭证」（Future），
 * 之后可以去做别的事，需要结果时再凭票取货。
 *
 * <p>本用例手写一个 Future，把 {@code get()} 背后的 wait/notify 机制摊开给你看。
 */
public class P02_FuturePattern extends DemoBase {

    public P02_FuturePattern() {
        super("pattern-02-future", Level.PATTERN,
                "Future / Promise：手写一个异步结果容器",
                "get() 阻塞的本质就是 wait/notify");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式角色
                - **Future（凭证）**：对外暴露 get / isDone / cancel；
                - **Promise（承诺）**：由执行方持有，负责 complete / completeExceptionally；
                - **执行者**：把耗时操作放到另一个线程，完成后兑现承诺。

                ## 核心实现
                    synchronized T get() {
                        while (!done) wait();     // 没完成就等
                        if (error != null) throw ...;
                        return value;
                    }
                    synchronized void complete(T v) {
                        this.value = v; this.done = true;
                        notifyAll();              // 叫醒所有等待者
                    }
                就这么简单。JDK 的 FutureTask 额外做了状态机、取消、中断和内存语义。

                ## 为什么需要它
                - 避免调用方被阻塞（可以先做别的事）；
                - 把「并发」封装成「一个对象」，调用方不需要懂线程；
                - 支持超时、取消、组合（CompletableFuture 就是它的增强版）。

                ## 演进
                Future（JDK5）→ CompletableFuture（JDK8，可编排）→
                Reactor/RxJava（响应式，支持背压）→ 虚拟线程（让「同步写法」重新可用）。

                ## 注意
                Future 只是「凭证」，不负责「任务生命周期」。
                get 超时不会取消任务，必须显式 cancel(true)（见 juc-05）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 生产代码用 CompletableFuture，不要手写 Future。
                - 永远使用带超时的 get，并对超时做降级。
                - 提供「取消」能力，且任务内部要响应中断。
                - Future 里抛的异常会被包装，记得取 getCause()。
                - 把 Future 当成「可能失败的结果」，而不是「一定会成功的结果」。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService pool = ctx.fixedPool("future-pattern", 2);

        ctx.step("① 发起异步任务，立刻拿到凭证");
        long begin = System.nanoTime();
        SimpleFuture<String> future = new SimpleFuture<>();
        pool.execute(() -> {
            sleepQuietly(300);
            ctx.info("后台任务完成，调用 complete() 兑现承诺");
            future.complete("计算结果-42");
        });
        long submitCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("提交耗时 %d ms，isDone=%s（任务还没跑完）", submitCost, future.isDone());
        ctx.verify("提交不会阻塞", submitCost < 50);
        ctx.verify("此刻还没有结果", !future.isDone());

        ctx.step("② 做点别的事情，然后再凭票取货");
        ctx.info("主线程继续做其他工作……");
        String value = future.get(2000);
        long total = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("get() 拿到：%s，总耗时 %d ms", value, total);
        ctx.verify("结果正确", "计算结果-42".equals(value));

        ctx.step("③ 超时：get(timeout) 不会无限等待");
        SimpleFuture<String> slow = new SimpleFuture<>();
        pool.execute(() -> {
            sleepQuietly(1000);
            slow.complete("迟到的结果");
        });
        try {
            slow.get(150);
            ctx.error("居然没超时？");
        } catch (TimeoutException e) {
            ctx.warn("150ms 超时返回：%s", e.getMessage());
            ctx.verify("超时机制生效", !slow.isDone());
        }
        ctx.info("超时只是「不等了」，任务仍在后台跑（这就是为什么需要 cancel）");

        ctx.step("④ 异常结果也要能传递");
        SimpleFuture<String> failed = new SimpleFuture<>();
        pool.execute(() -> failed.completeExceptionally(new IllegalStateException("下游服务错误")));
        try {
            failed.get(1000);
        } catch (Exception e) {
            ctx.info("get() 抛出：%s，原因：%s", e.getMessage(), e.getCause());
            ctx.verify("异常被正确传递", e.getCause() instanceof IllegalStateException);
        }

        ctx.step("⑤ 重复 complete 只生效一次（幂等）");
        SimpleFuture<String> once = new SimpleFuture<>();
        ctx.verify("第一次 complete 成功", once.complete("first"));
        ctx.verify("第二次 complete 被拒绝", !once.complete("second"));
        ctx.verifyEquals("值保持第一次的结果", "first", once.get(100));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
