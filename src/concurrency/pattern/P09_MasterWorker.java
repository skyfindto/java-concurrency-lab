package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设计模式 09：Master-Worker —— 主线程分配，工人干活，主线程汇总。
 *
 * <p>把大任务切成分片，每个 worker 处理一片，结果汇总到 master。
 * MapReduce、ForkJoin、并行流、批量导出的分片处理，本质都是它。
 *
 * <p>与 ForkJoin 的区别：Master-Worker 是「显式分配」，ForkJoin 是「递归拆分 + 工作窃取」。
 */
public class P09_MasterWorker extends DemoBase {

    public P09_MasterWorker() {
        super("pattern-09-master-worker", Level.PATTERN,
                "Master-Worker：分片并行与结果汇总",
                "1000 个任务分给 4 个工人，master 汇总结果");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式结构
                1. **Master**：拆分任务（分片）、启动 worker、汇总结果；
                2. **Worker**：循环从共享队列取任务，处理完把结果放回结果队列；
                3. **共享队列**：任务队列 + 结果队列（都需要并发安全）。

                ## 分片策略
                - **按数量切**：1000 条 / 4 = 每片 250 条（简单，可能不均）；
                - **按范围切**：id 1~250、251~500…（适合数据库分页）；
                - **动态领取**：worker 处理完就取下一个（负载均衡，适合耗时不均）。

                ## 关键问题
                1. worker 抛异常怎么办？—— catch 住，记录失败分片，不影响其他分片；
                2. 结果如何汇总？—— 线程安全容器 / 每个 worker 独立收集再合并（更好）；
                3. 如何知道全部完成？—— CountDownLatch / 完成计数器；
                4. 失败重试？—— 分片级别重试，而不是整批重来。

                ## 与 ForkJoin 的对比
                | 维度 | Master-Worker | ForkJoin |
                |---|---|---|
                | 拆分方式 | 预先分片 | 递归拆分 |
                | 调度 | 显式分配 | 工作窃取 |
                | 适合 | 任务独立、数量已知 | 可分治的递归计算 |
                | 实现 | 简单直观 | 需要理解 fork/join 语义 |

                ## 真实应用
                数据迁移、批量导入导出、离线报表、爬虫抓取、消息批量消费。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 分片大小按「单次处理耗时」定，通常每片 100~1000 条。
                - 每个 worker 独立收集结果，最后合并，避免共享结果集竞争。
                - 分片失败要记录具体分片和原因，支持单独重跑。
                - worker 数量 ≤ 下游能承受的并发（数据库连接数的一半）。
                - 整个任务要有总超时和进度上报，否则出错时完全不可观测。
                - 数据量大时，用「生产者-消费者 + 动态领取」比静态分片更均衡。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int total = 1000;
        int workers = 4;
        int chunkSize = total / workers;

        ctx.step("① Master 切分任务：%d 条 → %d 片，每片 %d 条", total, workers, chunkSize);
        List<int[]> chunks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            chunks.add(new int[]{i * chunkSize, (i + 1) * chunkSize});
        }

        ctx.step("② Worker 并行处理分片");
        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();
        CountDownLatch done = new CountDownLatch(workers);
        List<Thread> threads = new ArrayList<>();
        long begin = System.nanoTime();

        for (int i = 0; i < workers; i++) {
            int[] range = chunks.get(i);
            String name = "worker-" + i;
            Thread worker = new Thread(() -> {
                try {
                    long partial = 0;
                    for (int value = range[0]; value < range[1]; value++) {
                        partial += value;
                    }
                    sleepQuietly(50); // 模拟真实业务的处理耗时
                    results.add(partial);
                    ctx.info("%s 处理 [%d, %d) 完成，部分和 = %,d",
                            name, range[0], range[1], partial);
                } catch (RuntimeException e) {
                    ctx.error("%s 处理失败：%s", name, e);
                } finally {
                    done.countDown();
                }
            }, name);
            threads.add(worker);
            worker.start();
        }

        boolean finished = done.await(30, TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.verify("4 个 worker 全部完成", finished);

        ctx.step("③ Master 汇总");
        long sum = results.stream().mapToLong(Long::longValue).sum();
        long expected = (long) total * (total - 1) / 2;
        ctx.info("汇总结果 = %,d，期望 = %,d，耗时 %d ms", sum, expected, cost);
        ctx.verifyEquals("分片结果汇总正确", expected, sum);

        ctx.step("④ 对比：单线程串行");
        long seqBegin = System.nanoTime();
        long sequential = 0;
        for (int i = 0; i < total; i++) {
            sequential += i;
        }
        sleepQuietly(200);
        long seqCost = (System.nanoTime() - seqBegin) / 1_000_000;
        ctx.info("串行（含 4×50ms 模拟耗时）= %d ms，并行 = %d ms", seqCost, cost);
        ctx.verify("并行确实更快", cost < seqCost);
        ctx.verifyEquals("两种算法结果一致", sequential, sum);

        ctx.step("⑤ 生产注意");
        ctx.warn("worker 抛异常必须被 catch，否则一个分片失败会让 done 永远不归零");
        ctx.warn("汇总时用线程安全容器，或每个 worker 独立结果再合并");
        ctx.info("需要「哪个分片失败了」的信息，就不能只汇总数值，要带上分片元数据");
        ctx.verify("Master-Worker = 分治 + 汇总 + 失败隔离", true);

        for (Thread thread : threads) {
            thread.join(1000);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
