package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高级 10：锁优化实战 —— 临界区越小，系统越快。
 *
 * <p>大部分「加锁导致性能差」的问题，不是锁本身慢，而是<b>锁里干了不该干的事</b>：
 * 查数据库、调 RPC、写日志、序列化大对象。锁一持有就是几十毫秒，
 * 其他线程全在门口排队，吞吐自然上不去。
 *
 * <p>本用例用三组对比量化：锁内 IO、锁外 IO、分段锁。
 */
public class A10_LockOptimization extends DemoBase {

    public A10_LockOptimization() {
        super("adv-10-lock-optimization", Level.ADVANCED,
                "锁优化：缩小临界区与分段锁",
                "同样的业务逻辑，锁的位置不同，吞吐差 4 倍");
    }

    @Override
    public String knowledge() {
        return """
                ## 优化的四个方向
                1. **缩小临界区**：锁里只放共享状态的读写，IO/计算/日志放到锁外；
                2. **降低锁粒度**：把一把大锁拆成多把（分段锁、桶级锁），ConcurrentHashMap 就是这么做的；
                3. **读写分离**：读多写少用 ReadWriteLock / StampedLock / COW；
                4. **消除共享**：线程封闭、不可变对象、ThreadLocal —— 不需要锁才是最快的。

                ## 常见反模式
                - `synchronized` 方法里调用 RPC / 查库；
                - 在锁里做 JSON 序列化、打日志（日志 IO 可能阻塞）；
                - 用 `synchronized(this)` 锁住整个 Service，所有方法串行；
                - 锁的粒度和业务无关（比如锁一个全局的 Object）。

                ## 分段锁（LongAdder 的思想）
                把一个计数器拆成 N 份，每个线程操作自己那份，最后汇总。
                竞争被分散到 N 个独立的锁/CAS 上，吞吐近似线性提升。

                ## 锁消除与锁粗化
                - 锁消除：JIT 发现锁对象不会逃逸（比如方法内 new 的 StringBuffer），直接去掉锁；
                - 锁粗化：连续多次加解锁同一对象，JIT 合并成一次。
                这两个是 JVM 自动做的，你只需要别写出「锁逃逸」的代码。

                ## 先测量再优化
                用 JMH 或压测工具量化，别凭感觉。很多时候瓶颈根本不在锁上，而在 IO。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 锁内只做内存操作，任何 IO/RPC/日志都挪到锁外。
                - 能用一个原子类解决的，不要用锁；能用不可变的，连原子类都不要。
                - 大锁拆小锁前先确认竞争点，别把一致性搞丢。
                - 锁的顺序要全局一致，分段锁尤其要注意（否则死锁）。
                - 用 tryLock(timeout) 替代死等，给系统留退路。
                - 优化后用压测验证，别用「感觉快了」作为结论。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int threads = 4;
        int tasksPerThread = 50;
        AtomicLong counter = new AtomicLong();
        Object lock = new Object();

        ctx.step("① 反例：锁内做 5ms 的「IO」");
        long badCost = measure(ctx, threads, tasksPerThread, () -> {
            synchronized (lock) {
                sleepQuietly(5);
                counter.incrementAndGet();
            }
        });
        ctx.info("锁内 IO：%d 个任务耗时 %d ms（完全串行）", threads * tasksPerThread, badCost);

        ctx.step("② 正例：IO 放到锁外，锁内只更新状态");
        long goodCost = measure(ctx, threads, tasksPerThread, () -> {
            sleepQuietly(5);           // 耗时操作在锁外
            synchronized (lock) {
                counter.incrementAndGet(); // 临界区只有一行
            }
        });
        ctx.info("锁外 IO：同一批任务耗时 %d ms", goodCost);
        ctx.info("提升 %.1f 倍", badCost * 1.0 / Math.max(1, goodCost));
        ctx.verify("缩小临界区显著提升吞吐", goodCost < badCost);

        ctx.step("③ 分段锁：4 个分段，把竞争分散开");
        SegmentCounter segmented = new SegmentCounter(4);
        long segmentCost = measure(ctx, threads, tasksPerThread, segmented::increment);
        ctx.info("4 段计数：耗时 %d ms，最终值 = %d", segmentCost, segmented.sum());
        ctx.verifyEquals("分段计数结果正确", (long) threads * tasksPerThread, segmented.sum());

        ctx.step("④ 量级对比");
        ctx.info("锁内 IO   : %4d ms", badCost);
        ctx.info("锁外 IO   : %4d ms", goodCost);
        ctx.info("分段锁    : %4d ms（临界区本来就短，收益取决于竞争程度）", segmentCost);

        ctx.step("⑤ 优化清单");
        ctx.info("1. 锁内不碰 IO / RPC / 日志");
        ctx.info("2. 大锁拆小锁（按 key 分段）");
        ctx.info("3. 读多写少用读写锁 / COW");
        ctx.info("4. 能不用锁就不用（不可变、线程封闭、原子类）");
        ctx.info("5. 优化前后都要压测，别拍脑袋");
        ctx.verify("临界区长度 = 吞吐的反比", true);
    }

    private long measure(DemoContext ctx, int threads, int tasksPerThread, Runnable task) throws Exception {
        String poolName = "lock-bench-" + (++BENCH_SEQ);
        ExecutorService pool = ctx.fixedPool(poolName, threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads * tasksPerThread);
        long begin = System.nanoTime();
        for (int i = 0; i < threads * tasksPerThread; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    task.run();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(60, java.util.concurrent.TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdownNow();
        concurrency.core.PoolMonitor.unregister(poolName);
        return cost;
    }

    private static int BENCH_SEQ;

    /** 分段计数：每段一把锁，最后汇总。 */
    private static final class SegmentCounter {
        private final long[] values;
        private final Object[] locks;

        SegmentCounter(int segments) {
            values = new long[segments];
            locks = new Object[segments];
            for (int i = 0; i < segments; i++) {
                locks[i] = new Object();
            }
        }

        void increment() {
            int index = (int) (Thread.currentThread().threadId() % values.length);
            synchronized (locks[index]) {
                values[index]++;
            }
        }

        long sum() {
            long total = 0;
            for (int i = 0; i < values.length; i++) {
                synchronized (locks[i]) {
                    total += values[i];
                }
            }
            return total;
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
