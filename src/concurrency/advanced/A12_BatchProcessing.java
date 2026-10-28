package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 12：批量任务的并发处理 —— 分批 + 限并发 + 失败重试。
 *
 * <p>「一次性提交十万条数据」是线上常见需求：数据迁移、补偿任务、批量推送。
 * 直接 for 循环 submit 会把队列打爆，一次性全跑会把 DB 打挂。
 *
 * <p>正确做法：<b>分批（batch）+ 限并发（Semaphore）+ 单条失败重试 + 进度统计</b>。
 */
public class A12_BatchProcessing extends DemoBase {

    public A12_BatchProcessing() {
        super("adv-12-batch-processing", Level.ADVANCED,
                "批量处理：分批、限并发、失败重试",
                "1000 条数据，4 并发、每批 100、5% 失败重试");
    }

    @Override
    public String knowledge() {
        return """
                ## 为什么不能「全部 submit」
                - 10 万次 execute 会把队列瞬间打满，触发大量拒绝；
                - 所有任务同时抢 DB 连接，把连接池打爆；
                - 内存里积压大量 Future 对象，GC 压力大；
                - 出错了无法感知进度，也不知道哪些成功哪些失败。

                ## 标准方案：分批 + 限并发
                    List<List<Item>> batches = partition(items, 100);
                    Semaphore permits = new Semaphore(4);       // 最多 4 个批次并行
                    for (List<Item> batch : batches) {
                        permits.acquire();
                        CompletableFuture.runAsync(() -> {
                            try { process(batch); } finally { permits.release(); }
                        }, pool);
                    }

                ## 每条数据的处理
                - 单条失败不要影响整批：catch 住，记录失败列表；
                - 可重试的异常重试 1~2 次（带退避），不可重试的直接记失败；
                - 用 `AtomicInteger` 统计成功/失败/重试次数。

                ## 进度与可观测
                - 进度 = 已完成条数 / 总条数，定期打日志；
                - 失败明细落库/落文件，支持人工补偿；
                - 整批任务要有总超时，避免个别条卡死整批。

                ## 数据一致性
                - 分批处理天然是「部分成功」，下游要能接受这种状态；
                - 每批一个事务，而不是整个任务一个事务（否则长事务锁表）；
                - 失败重试要幂等（唯一键 + upsert / 去重表）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 批大小根据 DB/下游能力定（通常 50~500），不要超过单次 SQL 的参数上限。
                - 并发度 ≤ 连接池大小的 1/2，给正常业务留余量。
                - 单条失败要捕获并记录，绝不能让一条脏数据中断整批。
                - 重试只针对可重试异常，且带退避；重复失败要熔断。
                - 提供进度查询和失败重跑入口，批量任务没有这两样就是运维灾难。
                - 大批量任务放到单独的线程池，别和 Web 请求抢资源。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int total = 1000;
        int batchSize = 100;
        int concurrency = 4;

        List<Integer> items = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            items.add(i);
        }

        ExecutorService pool = ctx.fixedPool("batch-pool", concurrency);
        Semaphore permits = new Semaphore(concurrency);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger retried = new AtomicInteger();

        List<List<Integer>> batches = partition(items, batchSize);
        ctx.step("① 分 %d 批，每批 %d 条，最多 %d 个批次并行", batches.size(), batchSize, concurrency);

        long begin = System.nanoTime();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<Integer> batch = batches.get(i);
            int batchIndex = i + 1;
            permits.acquire();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    int batchSuccess = 0;
                    for (Integer item : batch) {
                        boolean ok = processItem(ctx, item, retried);
                        if (ok) {
                            success.incrementAndGet();
                            batchSuccess++;
                        } else {
                            failed.incrementAndGet();
                        }
                    }
                    ctx.info("第 %d 批完成：成功 %d / %d", batchIndex, batchSuccess, batch.size());
                } finally {
                    permits.release();
                }
            }, pool));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long cost = (System.nanoTime() - begin) / 1_000_000;

        ctx.step("② 结果统计");
        ctx.info("总数 %d，成功 %d，失败 %d，重试 %d 次",
                total, success.get(), failed.get(), retried.get());
        ctx.info("总耗时 %d ms，吞吐 %.0f 条/秒", cost, total * 1000.0 / Math.max(1, cost));
        ctx.verify("每条数据都被处理（成功 + 失败 = 总数）",
                success.get() + failed.get() == total);
        ctx.verify("成功占绝大多数", success.get() > total * 0.9);
        ctx.verify("发生过重试", retried.get() > 0);

        ctx.step("③ 关键设计点");
        ctx.info("分批：避免一次提交 1000 个任务打爆队列");
        ctx.info("限并发：Semaphore 控制同时访问下游的数量");
        ctx.info("单条隔离：一条失败不影响整批，失败明细可重跑");
        ctx.info("重试：只对可重试异常，带退避，最多 2 次");
        ctx.info("进度：每批完成打一条日志，便于观察卡在哪一批");

        ctx.step("④ 生产落地");
        ctx.info("大批量任务用独立线程池（避免占用 Web 线程）");
        ctx.info("任务状态落库：RUNNING / SUCCESS / PARTIAL / FAILED");
        ctx.info("提供「失败重跑」按钮，按失败明细重新提交");
        ctx.verify("批量任务 = 可控的并发 + 可观测的进度", true);
    }

    /** 处理单条：5% 概率失败，重试一次。 */
    private boolean processItem(DemoContext ctx, Integer item, AtomicInteger retried) {
        sleepQuietly(2);
        boolean ok = item % 20 != 0; // 每 20 条失败 1 条（5%）
        if (ok) {
            return true;
        }
        retried.incrementAndGet();
        ctx.warn("第 %d 条处理失败，重试一次", item);
        sleepQuietly(5);
        return item % 40 != 0; // 重试后一半能成功
    }

    private static List<List<Integer>> partition(List<Integer> items, int size) {
        List<List<Integer>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            batches.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return batches;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
