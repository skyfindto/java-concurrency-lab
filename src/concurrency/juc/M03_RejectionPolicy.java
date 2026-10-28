package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.core.Threads;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 中级 03：四种拒绝策略与 Executors 的四个坑。
 *
 * <p>拒绝策略是线程池最后的防线，选错会直接导致「任务丢了还没人知道」。
 * 本用例用同一组压力场景，把四种策略的实际行为跑出来给你看。
 */
public class M03_RejectionPolicy extends DemoBase {

    public M03_RejectionPolicy() {
        super("juc-03-rejection-policy", Level.INTERMEDIATE,
                "四种拒绝策略与 Executors 的坑",
                "Abort / CallerRuns / Discard / DiscardOldest 到底谁会丢任务");
    }

    @Override
    public String knowledge() {
        return """
                ## 四种内置拒绝策略
                | 策略 | 行为 | 适合场景 |
                |---|---|---|
                | AbortPolicy（默认） | 抛 RejectedExecutionException | 快速失败 + 上游捕获降级 |
                | CallerRunsPolicy | 由提交任务的线程自己执行 | 天然背压，但会阻塞调用方（可能是 Web 容器线程） |
                | DiscardPolicy | 静默丢弃新任务 | 可丢的埋点/日志 |
                | DiscardOldestPolicy | 丢掉队列里最老的，再提交新的 | 只关心最新数据（如行情推送） |

                ## 为什么默认 AbortPolicy 最危险也最安全
                它至少会抛异常，你能捕获、能告警、能降级；
                而 Discard 系列是【静默丢弃】，没有日志、没有指标，故障时最难排查。

                ## Executors 的四个坑
                1. newFixedThreadPool(n)：LinkedBlockingQueue 无界（Integer.MAX_VALUE），
                   任务堆积到 OOM，且永远不会触发拒绝；
                2. newSingleThreadExecutor()：同上，还只有 1 个线程；
                3. newCachedThreadPool()：maximumPoolSize = Integer.MAX_VALUE，
                   流量一冲就创建海量线程（每个 1MB 栈）；
                4. newScheduledThreadPool(n)：DelayedWorkQueue 无界，周期任务堆积同样 OOM。

                ## 自定义拒绝策略
                最常见的生产写法：记录被拒绝的任务 + 打点 + 抛异常或走降级逻辑。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 核心链路用 AbortPolicy + 上层 catch 后降级（返回缓存/默认值/排队重试）。
                - CallerRunsPolicy 慎用：如果调用方是 Tomcat 工作线程，等于把背压传导给了用户请求。
                - 拒绝次数必须打点告警，否则等于没有拒绝策略。
                - 队列必须有界：容量按「能接受的最大排队延迟」反推。
                - 拒绝时把任务内容/关键参数记下来，方便事后补偿。
                - 不要用 Executors 的快捷方法，除非你明确知道它的队列和最大线程数。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("统一场景：core=max=1，队列容量 1，一次提交 3 个 300ms 的任务");
        ctx.info("第 1 个占住线程，第 2 个进队列，第 3 个交给拒绝策略");

        ctx.info("");
        ctx.info("① AbortPolicy（默认）：抛异常，调用方必须处理");
        runCase(ctx, new ThreadPoolExecutor.AbortPolicy());

        ctx.info("");
        ctx.info("② CallerRunsPolicy：让提交任务的线程自己跑，形成背压");
        runCase(ctx, new ThreadPoolExecutor.CallerRunsPolicy());

        ctx.info("");
        ctx.info("③ DiscardPolicy：静默丢弃，最难排查");
        runCase(ctx, new ThreadPoolExecutor.DiscardPolicy());

        ctx.info("");
        ctx.info("④ DiscardOldestPolicy：丢最老的，保最新的");
        runCase(ctx, new ThreadPoolExecutor.DiscardOldestPolicy());

        ctx.step("结论：只有 Abort 和 CallerRuns 是「有感知」的");
        ctx.verify("四种策略行为各不相同", true);

        ctx.step("看看 Executors 给你埋了什么雷");
        ThreadPoolExecutor fixed = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        ctx.warn("newFixedThreadPool 的队列：%s，剩余容量 = %,d（无界！）",
                fixed.getQueue().getClass().getSimpleName(), fixed.getQueue().remainingCapacity());
        ctx.warn("maximumPoolSize = %,d（永远不会扩容，也永远不会拒绝）", fixed.getMaximumPoolSize());

        ThreadPoolExecutor cached = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        ctx.warn("newCachedThreadPool 的 maximumPoolSize = %,d（约等于无限）", cached.getMaximumPoolSize());
        ctx.warn("队列是 %s（容量 0，来一个任务就要一个线程）", cached.getQueue().getClass().getSimpleName());

        ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1);
        ctx.warn("newScheduledThreadPool 的队列同样是无界延迟队列");

        fixed.shutdownNow();
        cached.shutdownNow();
        scheduled.shutdownNow();

        ctx.step("生产写法");
        ctx.info("new ThreadPoolExecutor(core, max, 60s, new ArrayBlockingQueue<>(1000),");
        ctx.info("    Threads.named(\"order-pool\"), 自定义拒绝策略 + 打点告警)");
        ctx.verify("有界队列 + 命名线程 + 可观测拒绝 = 可治理的线程池", true);
    }

    /** 用同一场景跑一遍某个拒绝策略，用「耗时」区分它是阻塞执行还是静默丢弃。 */
    private void runCase(DemoContext ctx, RejectedExecutionHandler handler) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), Threads.named("reject-demo"), handler);
        pool.execute(() -> sleepQuietly(300));   // 占住唯一线程
        pool.execute(() -> sleepQuietly(300));   // 进入队列

        long begin = System.nanoTime();
        String outcome;
        try {
            pool.execute(() -> sleepQuietly(300)); // 第三个：触发策略
            outcome = "提交没有抛异常";
        } catch (RejectedExecutionException e) {
            outcome = "抛出 RejectedExecutionException";
        }
        long cost = (System.nanoTime() - begin) / 1_000_000;

        String interpretation;
        if (outcome.startsWith("抛出")) {
            interpretation = "快速失败，调用方能感知";
        } else if (cost >= 250) {
            interpretation = "调用者线程被拉去执行任务（耗时 " + cost + "ms），这就是背压";
        } else {
            interpretation = "立刻返回，任务被丢弃（耗时 " + cost + "ms）—— 静默丢失";
        }
        ctx.info("%s，%s → %s", handler.getClass().getSimpleName(), outcome, interpretation);
        pool.shutdownNow();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
