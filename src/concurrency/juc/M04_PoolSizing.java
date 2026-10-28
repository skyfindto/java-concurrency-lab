package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 中级 04：线程池参数到底怎么算。
 *
 * <p>不要背「N+1 / 2N」这种口诀，它只在特定条件下成立。
 * 正确做法是用 <b>Little's Law（利特尔法则）</b>：
 *
 * <pre>
 *   并发数 = 到达率 × 平均处理时间
 *   线程数 = 并发数 × 安全系数
 * </pre>
 *
 * <p>本用例用一个可控实验验证：任务越偏「等待」，加线程带来的吞吐提升越明显；
 * 纯 CPU 密集时，线程超过核数只会增加上下文切换。
 */
public class M04_PoolSizing extends DemoBase {

    public M04_PoolSizing() {
        super("juc-04-pool-sizing", Level.INTERMEDIATE,
                "线程池参数怎么算：Little's Law 实验",
                "同样是 200 个任务，1/4/16 个线程分别要多久？");
    }

    @Override
    public String knowledge() {
        return """
                ## 两种任务类型，两套公式
                - **CPU 密集**（计算、序列化、加解密）：线程数 ≈ CPU 核数 + 1。
                  多一个线程是为了在偶发缺页时顶上，再多只会增加上下文切换。
                - **IO 密集**（DB、Redis、HTTP）：线程数 ≈ 核数 × (1 + 平均等待时间 / 平均计算时间)。
                  极端 IO 场景（等待占 99%），一台 8 核机器开 800 个线程是合理的。

                ## Little's Law（更实用）
                系统稳定时：**并发请求数 = QPS × 平均响应时间**
                例：QPS = 1000，RT = 200ms → 并发 = 1000 × 0.2 = 200 → 至少需要 200 个能同时干活的线程/协程。
                如果只有 50 个线程，理论最大 QPS = 50 / 0.2 = 250，剩下的请求只能在队列里排队。

                ## 队列容量怎么定
                队列容量 × 平均任务耗时 ≈ 最大排队延迟。
                想让用户最多等 1 秒、任务平均 50ms → 容量约 20 就该拒绝，而不是排 10000 个。

                ## 为什么虚拟线程改变了这个公式
                虚拟线程的等待不占 OS 线程，IO 密集场景可以「一个请求一个虚拟线程」，
                不再需要精心计算池大小 —— 但 CPU 密集场景仍然要靠平台线程 + 有界并发。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 参数不是拍脑袋定的：上线前压测，用真实 RT 和 QPS 反推。
                - 核心线程数决定「稳态吞吐」，最大线程数决定「抗突发能力」，队列决定「忍耐时长」。
                - 队列不要太大：大队列会把「过载」变成「超时」，故障表现从 500 变成大面积超时。
                - 给线程池设置合理的 keepAliveTime，突发结束后能缩容。
                - 用动态线程池（Nacos/Apollo 配置 + setCorePoolSize）应对流量变化，改参数不用重启。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int tasks = 200;
        int taskMillis = 10;

        ctx.step("实验：%d 个任务，每个「等待」%dms（模拟 IO 密集）", tasks, taskMillis);
        ctx.info("线程数 | 总耗时(ms) | 吞吐(任务/秒) | 相对提升");
        ctx.info("-------|-----------|--------------|--------");

        double baseline = 0;
        for (int poolSize : new int[]{1, 4, 16}) {
            long cost = runLoad(poolSize, tasks, taskMillis);
            double throughput = tasks * 1000.0 / Math.max(1, cost);
            if (baseline == 0) {
                baseline = throughput;
            }
            ctx.info("%6d | %9d | %12.0f | ×%.1f",
                    poolSize, cost, throughput, throughput / baseline);
        }

        ctx.step("结论 1：IO 密集时，线程数 ≈ 并发数，加线程几乎线性提升吞吐");
        ctx.info("因为线程大部分时间在「等」，不占 CPU，多开几个就能多等几路 IO");

        ctx.step("结论 2：CPU 密集时，超过核数就没有收益了");
        ctx.info("本机核数 = %d，CPU 密集任务把线程开到 %d 以上只会增加上下文切换",
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors() * 2);

        ctx.step("用 Little's Law 反推");
        double qps = 1000;
        double rtMillis = 200;
        double concurrency = qps * rtMillis / 1000.0;
        ctx.info("QPS=%.0f，RT=%.0fms → 需要的并发数 = %.0f", qps, rtMillis, concurrency);
        ctx.info("若线程池只有 50 个线程，理论最大 QPS = 50 / 0.2 = 250，瓶颈就在池子");
        ctx.verify("并发数 = QPS × RT", Math.abs(concurrency - 200) < 0.001);

        ctx.step("参数建议（以 8 核、IO 等待占 90% 为例）");
        ctx.info("corePoolSize      = 16~32（根据压测 RT 调整）");
        ctx.info("maximumPoolSize   = 64（抗突发）");
        ctx.info("队列容量          = 200~1000（对应最大排队延迟）");
        ctx.info("拒绝策略          = AbortPolicy + 上层降级");
    }

    /** 固定任务量，测不同并发度的总耗时。 */
    private long runLoad(int poolSize, int tasks, int taskMillis) throws InterruptedException {
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "load-" + poolSize);
            t.setDaemon(true);
            return t;
        });
        CountDownLatch done = new CountDownLatch(tasks);
        long begin = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            pool.execute(() -> {
                try {
                    Thread.sleep(taskMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(60, TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdownNow();
        return cost;
    }
}
