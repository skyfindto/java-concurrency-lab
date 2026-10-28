package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.core.PoolMonitor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进阶 02：线程池可观测 —— 把「池子」变成几条曲线。
 *
 * <p>线程池出问题前一定会有信号：活跃线程数打满、队列持续上涨、开始出现拒绝。
 * 这些信号 {@link ThreadPoolExecutor} 都提供了，问题在于很多项目<b>从来没采集过</b>。
 *
 * <p>本用例创建一个真实压测场景，把 4 个核心指标采出来：
 * active / queue / completed / rejected。
 * 页面上的「线程池观测」tab 就是读的这份数据。
 */
public class O02_PoolMetrics extends DemoBase {

    public O02_PoolMetrics() {
        super("obs-02-pool-metrics", Level.OBSERVE,
                "线程池四指标：活跃、队列、完成、拒绝",
                "池子要爆之前，指标早就告诉你了");
    }

    @Override
    public String knowledge() {
        return """
                ## 必须采集的指标
                | 指标 | 方法 | 含义 | 告警建议 |
                |---|---|---|---|
                | 活跃线程数 | getActiveCount() | 正在干活的线程 | 持续 == max 需关注 |
                | 池大小 | getPoolSize() | 当前线程数 | 接近 max |
                | 队列长度 | getQueue().size() | 排队任务数 | 持续增长 / > 80% 容量 |
                | 已完成任务 | getCompletedTaskCount() | 累计完成 | 增长率骤降 = 卡住 |
                | 拒绝次数 | 自定义计数器 | 被拒绝的任务 | > 0 立即告警 |
                | 任务耗时 | 包一层计时 | P99 RT | 突增 |

                ## 为什么拒绝次数要自己统计
                `ThreadPoolExecutor` 没有内置的拒绝计数，必须自己包 RejectedExecutionHandler。
                本项目用 {@link PoolMonitor} 做了封装。

                ## 接入监控系统
                Micrometer：
                    new ExecutorServiceMetrics(executor, "order-pool", Tags.empty()).bindTo(registry);
                Prometheus 指标名通常是 executor_active_threads / executor_queued_tasks 等。

                ## 怎么根据指标判断问题
                - active 满 + queue 满 + 拒绝上升 → 容量不足或下游变慢；
                - active 低 + queue 涨 → 消费者线程被阻塞（IO/锁）；
                - active 满 + queue 空 + RT 高 → 任务本身慢，不是排队问题；
                - completed 停止增长 + active 满 → 线程卡死（死锁/无限等待）。

                ## 建议的告警阈值
                - 队列水位 > 80% 持续 1 分钟；
                - 拒绝次数 > 0（关键业务）/ > 1% （非关键）；
                - P99 RT > 基线 2 倍。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 每个线程池都要接入指标采集，并且带业务维度（pool name）。
                - 拒绝次数是「已经在丢任务」的信号，必须告警，不能只在日志里。
                - 队列水位比队列绝对值更有意义（容量不同）。
                - 指标要能按实例、按接口下钻，否则集群里不知道是哪台机器。
                - 给任务包一层耗时统计（Timer），这是定位慢任务最直接的数据。
                - 用动态线程池，指标异常时可以先在线调整 core/max，再定位根因。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 创建可观测线程池：2 线程、队列 5");
        ThreadPoolExecutor pool = ctx.executor("observable-pool", 2, 2, 5);
        AtomicInteger done = new AtomicInteger();

        ctx.step("② 灌入 30 个 150ms 任务，边跑边采指标");
        for (int i = 0; i < 30; i++) {
            try {
                pool.execute(() -> {
                    sleepQuietly(150);
                    done.incrementAndGet();
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 拒绝由 PoolMonitor 计数
            }
        }

        for (int round = 1; round <= 4; round++) {
            ctx.sleep(150);
            PoolMonitor.Snapshot snapshot = find("observable-pool");
            if (snapshot == null) {
                continue;
            }
            ctx.info("第 %d 次采样：active=%d/%d, queue=%d/%d, completed=%d, rejected=%d",
                    round, snapshot.activeCount(), snapshot.maxSize(),
                    snapshot.queueSize(), snapshot.queueCapacity(),
                    snapshot.completedTasks(), snapshot.rejected());
        }

        PoolMonitor.Snapshot snapshot = find("observable-pool");
        ctx.verify("能采集到活跃线程数", snapshot != null && snapshot.activeCount() > 0);
        ctx.verify("能采集到队列长度", snapshot != null && snapshot.queueCapacity() == 5);
        ctx.verify("能采集到拒绝次数", snapshot != null && snapshot.rejected() > 0);
        ctx.info("最终完成 %d 个，拒绝 %d 个", done.get(), snapshot == null ? -1 : snapshot.rejected());

        ctx.step("③ 指标怎么读");
        ctx.info("active 满 + queue 涨 + rejected > 0 → 这是一个「过载」的池子");
        ctx.info("如果 active 很低但 queue 一直涨 → 消费者线程被 IO/锁卡住了");
        ctx.info("如果 completed 不再增长且 active 满 → 大概率死锁或无限等待");

        ctx.step("④ 页面上能看到什么");
        ctx.info("打开「线程池观测」tab，observable-pool 会显示活跃/队列曲线");
        ctx.info("运行一次本用例，就能看到曲线先冲高、再回落的过程");
        ctx.verify("可观测性 = 指标 + 曲线 + 告警", true);
    }

    private PoolMonitor.Snapshot find(String name) {
        return PoolMonitor.snapshots().stream()
                .filter(snapshot -> snapshot.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
