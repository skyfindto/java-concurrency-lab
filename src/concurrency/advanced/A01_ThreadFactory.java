package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.core.PoolMonitor;
import concurrency.core.Threads;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 01：线程工厂与统一异常处理 —— 让线程「可辨认、可追责」。
 *
 * <p>两条生产铁律：
 * <ol>
 *   <li><b>每个线程都要有名字</b>，且名字里带业务标识。jstack 里看到 {@code order-pool-3}
 *       和看到 {@code pool-1-thread-3}，排查效率差 10 倍。</li>
 *   <li><b>未捕获异常必须被记录</b>。默认行为只是往 stderr 打一行，容器里经常被忽略，
 *       任务静默消失。</li>
 * </ol>
 */
public class A01_ThreadFactory extends DemoBase {

    public A01_ThreadFactory() {
        super("adv-01-thread-factory", Level.ADVANCED,
                "线程工厂与统一异常处理",
                "线程名、UncaughtExceptionHandler，以及为什么默认线程池不可用");
    }

    @Override
    public String knowledge() {
        return """
                ## 默认线程工厂的问题
                `Executors.defaultThreadFactory()` 产生的线程名是 `pool-1-thread-1`，
                非守护、优先级 5、没有异常处理器。一个应用里十几个池子，
                出事时你根本分不清哪个是订单、哪个是报表。

                ## 自定义线程工厂要做什么
                1. 命名：`业务名-序号`，最好带上环境/实例标识；
                2. 设置 daemon：业务线程池**不要**设 daemon（进程退出会丢任务）；
                3. 设置 UncaughtExceptionHandler：把异常打进日志 + 打点告警；
                4. （可选）设置优先级、线程组、类加载器。

                ## 未捕获异常会丢在哪里
                - `execute()` 提交的任务抛异常 → 线程的 UncaughtExceptionHandler；
                - `submit()` 提交的任务抛异常 → 封装进 Future，不 get 就没人知道；
                - 默认 handler 打印的堆栈在容器日志里经常被淹没；
                - 线程池的线程被复用，异常不会让线程退出，所以「任务失败」和「线程存活」是两件事。

                ## 全局兜底
                `Thread.setDefaultUncaughtExceptionHandler(...)` 可以兜住所有线程，
                适合接入统一日志/告警。但它只对「未捕获」的异常生效，不影响任务返回值。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 所有线程池都必须传自定义 ThreadFactory，名字规范：`{业务}-{用途}-{序号}`。
                - 业务线程池不要 setDaemon(true)：进程退出时会被强杀，丢任务丢消息。
                - UncaughtExceptionHandler 里至少做两件事：记 ERROR 日志（带任务信息）+ 打点。
                - 别在 handler 里抛异常或做重活，它运行在即将死亡的线程上。
                - 提交任务用 submit 时，务必处理 Future 的异常（或在任务内部 try-catch）。
                - 名字里带上 traceId/taskId 更好，但注意别每秒生成新线程名导致日志爆炸。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 默认线程工厂：名字毫无业务含义");
        ExecutorService defaultPool = Executors.newFixedThreadPool(2);
        defaultPool.submit(() -> ctx.info("默认工厂的线程名 = %s", Thread.currentThread().getName())).get();
        ctx.info("jstack 里看到 pool-1-thread-1，你知道它是订单还是报表吗？");
        defaultPool.shutdownNow();

        ctx.step("② 自定义工厂：命名 + 异常处理器 + 非守护");
        AtomicInteger uncaught = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "order-worker-" + Threads.nextId());
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler((t, e) -> {
                uncaught.incrementAndGet();
                ctx.error("[告警] 线程 %s 未捕获异常：%s", t.getName(), e);
            });
            return thread;
        };

        ThreadPoolExecutor orderPool = ctx.pool(
                "order-pool", 2, 4,
                new ArrayBlockingQueue<>(10),
                factory,
                new ThreadPoolExecutor.AbortPolicy());

        orderPool.submit(() -> ctx.info("业务线程名 = %s，daemon = %s",
                Thread.currentThread().getName(), Thread.currentThread().isDaemon())).get();

        ctx.step("③ 未捕获异常：execute 的任务抛异常，线程会死但池会补一个新的");
        orderPool.execute(() -> {
            throw new IllegalStateException("订单计算异常：金额为负");
        });
        ctx.sleep(150);
        ctx.info("UncaughtExceptionHandler 捕获次数 = %d", uncaught.get());
        ctx.verify("未捕获异常被自定义 handler 记录", uncaught.get() == 1);
        ctx.info("注意：线程挂了，但线程池会创建新线程继续工作，所以池子看起来「没事」");

        ctx.step("④ submit 的异常不会走 handler，只会静默进 Future");
        Future<?> silent = orderPool.submit(() -> {
            throw new IllegalStateException("这个异常不会触发 handler");
        });
        ctx.sleep(150);
        ctx.info("此时 UncaughtExceptionHandler 捕获次数仍为 %d", uncaught.get());
        try {
            silent.get();
        } catch (ExecutionException e) {
            ctx.warn("只有 get() 才拿到：%s", e.getCause());
        }
        ctx.verify("submit 的异常被 Future 吞掉", uncaught.get() == 1);

        ctx.step("⑤ 全局兜底 handler（演示后恢复，避免影响其他用例）");
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                ctx.warn("[全局兜底] 线程 %s 抛出 %s", t.getName(), e));
        Thread thrower = new Thread(() -> {
            throw new RuntimeException("没有自定义 handler 的线程，走全局兜底");
        }, "global-fallback-thread");
        thrower.start();
        thrower.join();
        Thread.setDefaultUncaughtExceptionHandler(previous);
        ctx.verify("全局默认处理器可以兜住所有线程", true);

        ctx.step("小结");
        ctx.info("线程名 = 可观测性的最低要求；异常处理器 = 任务不丢的最后一道防线");
        ctx.info("线程池 = 命名工厂 + 有界队列 + 拒绝策略 + 异常兜底，四件套缺一不可");
    }
}
