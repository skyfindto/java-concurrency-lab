package concurrency.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程池注册表：让「页面上看到的池子」和「用例里真实运行的池子」是同一个对象。
 *
 * <p>这是可观测性的第一步——<b>把池子暴露出来</b>。
 * 线上没有这个注册表，你就只能靠 jstack 猜；有了它，活跃线程数、队列长度、
 * 拒绝次数这些核心指标可以直接画成曲线。
 *
 * <p>注意：这里只保存强引用，仅用于演示。真实项目里建议用 Micrometer 的
 * {@code ExecutorServiceMetrics} 或自己定时采集，不要长期持有池子引用。
 */
public final class PoolMonitor {

    /** 一个时间点的池子快照（不可变，可直接序列化给前端）。 */
    public record Snapshot(
            String name,
            String kind,
            int coreSize,
            int maxSize,
            int poolSize,
            int activeCount,
            int queueSize,
            int queueCapacity,
            long completedTasks,
            long totalTasks,
            long rejected,
            long largestPoolSize,
            boolean shutdown,
            long threadCount
    ) {
    }

    private static final class Entry {
        final ThreadPoolExecutor pool;
        final AtomicLong rejected = new AtomicLong();

        Entry(ThreadPoolExecutor pool) {
            this.pool = pool;
        }
    }

    private static final Map<String, Entry> POOLS = new ConcurrentHashMap<>();
    private static final Map<String, LooseEntry> LOOSE = new ConcurrentHashMap<>();

    private record LooseEntry(ExecutorService executor, AtomicLong rejected) {
    }

    private PoolMonitor() {
    }

    /** 注册一个可观测的线程池，返回入参便于链式调用。 */
    public static ThreadPoolExecutor register(String name, ThreadPoolExecutor pool) {
        POOLS.put(name, new Entry(pool));
        return pool;
    }

    /** 虚拟线程执行器也登记一下，页面上能看到「无池化」的对比。 */
    public static void registerLoose(String name, ExecutorService executor) {
        LOOSE.put(name, new LooseEntry(executor, new AtomicLong()));
    }

    public static void unregister(String name) {
        POOLS.remove(name);
        LOOSE.remove(name);
    }

    public static void recordRejected(String name) {
        Entry entry = POOLS.get(name);
        if (entry != null) {
            entry.rejected.incrementAndGet();
        }
    }

    /**
     * 默认拒绝策略：先计数，再抛异常。
     * 生产上最常见的错误就是「用默认 AbortPolicy 但没人 catch」，
     * 任务悄悄丢了还没有任何指标，直到用户投诉。
     */
    public static RejectedExecutionHandler rejection(String name) {
        return (r, executor) -> {
            recordRejected(name);
            throw new java.util.concurrent.RejectedExecutionException(
                    "线程池[" + name + "]已满：活跃=" + executor.getActiveCount()
                            + ", 队列=" + executor.getQueue().size()
                            + ", 已完成=" + executor.getCompletedTaskCount());
        };
    }

    /** 包一层计数，保留原有拒绝策略的语义。 */
    public static RejectedExecutionHandler counting(String name, RejectedExecutionHandler delegate) {
        return (r, executor) -> {
            recordRejected(name);
            delegate.rejectedExecution(r, executor);
        };
    }

    /** 当前所有已注册池子的快照，按名称排序。 */
    public static List<Snapshot> snapshots() {
        List<Snapshot> list = new ArrayList<>();
        POOLS.forEach((name, entry) -> {
            ThreadPoolExecutor p = entry.pool;
            int queueSize = p.getQueue().size();
            // 无界队列的 remainingCapacity 是 Integer.MAX_VALUE，这里做饱和处理
            int remaining = p.getQueue().remainingCapacity();
            int capacity = (int) Math.min(Integer.MAX_VALUE, (long) queueSize + remaining);
            list.add(new Snapshot(
                    name,
                    "ThreadPoolExecutor",
                    p.getCorePoolSize(),
                    p.getMaximumPoolSize(),
                    p.getPoolSize(),
                    p.getActiveCount(),
                    queueSize,
                    capacity,
                    p.getCompletedTaskCount(),
                    p.getTaskCount(),
                    entry.rejected.get(),
                    p.getLargestPoolSize(),
                    p.isShutdown(),
                    p.getPoolSize()
            ));
        });
        LOOSE.forEach((name, entry) -> list.add(new Snapshot(
                name, "VirtualThreadPerTask", 0, 0, 0, 0, 0, 0, 0, 0,
                entry.rejected().get(), 0, entry.executor().isShutdown(), 0)));
        list.sort(Comparator.comparing(Snapshot::name));
        return list;
    }

    /** 清空注册表（测试/重置用）。 */
    public static void clear() {
        POOLS.clear();
        LOOSE.clear();
    }
}
