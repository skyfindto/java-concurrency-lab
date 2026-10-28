package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;

/**
 * 中级 17：ForkJoinPool 与工作窃取。
 *
 * <p>ForkJoin 的核心不是「线程池」，而是<b>分治 + 工作窃取</b>：
 * 把大任务拆成小任务（fork），子任务完成后合并结果（join）；
 * 空闲线程会从其他线程队列的<b>尾部</b>偷任务，最大化 CPU 利用率。
 */
public class M17_ForkJoin extends DemoBase {

    private static final int THRESHOLD = 100_000;
    private static long sequentialSum;

    /** 分治求和：数据量大时切成子任务并递归。 */
    private static final class SumTask extends RecursiveTask<Long> {
        private final int from;
        private final int to;

        SumTask(int from, int to) {
            this.from = from;
            this.to = to;
        }

        @Override
        protected Long compute() {
            if (to - from <= THRESHOLD) {
                long sum = 0;
                for (int i = from; i <= to; i++) {
                    sum += i;
                }
                return sum;
            }
            int mid = (from + to) >>> 1;
            SumTask left = new SumTask(from, mid);
            SumTask right = new SumTask(mid + 1, to);
            left.fork();          // 把左半部分丢给池子（可能被别的线程偷走）
            long rightResult = right.compute(); // 右半部分自己算，避免无谓的线程切换
            long leftResult = left.join();      // 等左半部分结果
            return leftResult + rightResult;
        }
    }

    public M17_ForkJoin() {
        super("juc-17-fork-join", Level.INTERMEDIATE,
                "ForkJoinPool 与工作窃取",
                "分治求和：fork/join 为什么比普通线程池更适合 CPU 密集任务");
    }

    @Override
    public String knowledge() {
        return """
                ## 工作窃取（Work Stealing）
                每个工作线程有自己的一条双端队列（deque）：
                - 自己产生的子任务 push 到<b>队头</b>，也从头取（LIFO，缓存友好）；
                - 空闲线程从别人队列的<b>队尾</b>偷（FIFO，减少冲突）。
                这样既减少了竞争，又让 CPU 尽量不闲着。

                ## ForkJoinTask 的三种提交
                - `invoke(task)`：同步执行并等结果；
                - `fork()`：异步提交（放入当前线程队列）；
                - `join()`：等待结果，必要时帮助执行其他任务。
                常用模板：`left.fork(); right.compute(); left.join();`

                ## 适用场景
                - 可递归拆分的 CPU 密集计算：归并排序、大数组求和、树遍历；
                - parallelStream 底层就是 ForkJoinPool.commonPool。
                **不适合**：阻塞 IO（会占住工作线程，工作窃取也救不了）。

                ## commonPool 的坑
                公共池默认并行度 = CPU 核数 - 1。如果在里面跑阻塞任务，
                整个 JVM 的 parallelStream 都会被拖慢。阻塞任务请用
                `ManagedBlocker` 或独立的 ForkJoinPool / 普通线程池。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 任务拆分粒度要适中：太小（每次几纳秒）会被调度开销吃掉，太大则并行度不够。
                - 拆到「叶子任务耗时约 1~10 微秒」通常比较合适，用压测确定阈值。
                - CPU 密集用 ForkJoin，IO 密集用普通线程池（或虚拟线程）。
                - 不要在生产代码里随意用 parallelStream：它共用 commonPool，容易互相干扰。
                - ForkJoinTask 里不要做阻塞调用；必须阻塞时用 ForkJoinPool.managedBlock。
                - 递归深度可控（能用迭代就用迭代），避免 StackOverflowError。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int n = 20_000_000;
        long expected = (long) n * (n + 1) / 2;

        ctx.step("① 顺序求和：单线程跑 2000 万个数");
        long begin = System.nanoTime();
        long sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += i;
        }
        long sequentialCost = (System.nanoTime() - begin) / 1_000_000;
        sequentialSum = sum;
        ctx.info("顺序结果 = %,d，耗时 %d ms", sum, sequentialCost);
        ctx.verifyEquals("顺序计算结果正确", expected, sum);

        ctx.step("② ForkJoin 分治求和");
        ForkJoinPool pool = new ForkJoinPool();
        begin = System.nanoTime();
        long parallel = pool.invoke(new SumTask(1, n));
        long parallelCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("并行结果 = %,d，耗时 %d ms", parallel, parallelCost);
        ctx.info("并行度 = %d（默认等于 CPU 核数）", pool.getParallelism());
        ctx.verifyEquals("并行计算结果与顺序一致", expected, parallel);

        if (parallelCost < sequentialCost) {
            ctx.ok("并行比顺序快 %.2f 倍", sequentialCost * 1.0 / Math.max(1, parallelCost));
        } else {
            ctx.warn("这次并行没有更快（数据量小/机器忙），ForkJoin 有调度开销，量小反而慢");
        }

        ctx.step("③ commonPool 与 parallelStream");
        long commonBegin = System.nanoTime();
        long streamSum = java.util.stream.LongStream.rangeClosed(1, n).parallel().sum();
        long streamCost = (System.nanoTime() - commonBegin) / 1_000_000;
        ctx.info("parallelStream 结果 = %,d，耗时 %d ms，用的是 commonPool（并行度 %d）",
                streamSum, streamCost, ForkJoinPool.commonPool().getParallelism());
        ctx.verifyEquals("parallelStream 结果正确", expected, streamSum);

        ctx.step("④ 反例：在 ForkJoin 里做阻塞 IO");
        ctx.warn("如果 compute() 里调用 HTTP/DB，工作线程会被阻塞，工作窃取也救不了");
        ctx.warn("并行度只有 %d，阻塞几个任务就占满了", pool.getParallelism());
        ctx.info("阻塞任务请用普通线程池或虚拟线程；必须用 ForkJoin 时用 ManagedBlocker");

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
        ctx.step("选择：可递归拆分的 CPU 计算 → ForkJoin；IO 等待 → 线程池/虚拟线程");
    }
}
