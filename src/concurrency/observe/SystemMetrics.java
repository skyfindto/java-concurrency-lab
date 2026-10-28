package concurrency.observe;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM 运行时指标快照（页面右上角那几个数字的来源）。
 *
 * <p>用 jdk.management 的 com.sun.management.OperatingSystemMXBean 拿 CPU：
 * 标准 OperatingSystemMXBean.getSystemLoadAverage() 在 Windows 上返回 -1，
 * 而 getCpuLoad()/getProcessCpuLoad() 是可用的。
 *
 * <p>这些指标正是线上「多线程是否健康」的第一层观测：
 * 堆使用率、线程数、GC 时间占比、CPU 占用。
 */
public final class SystemMetrics {

    private static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final RuntimeMXBean RUNTIME = ManagementFactory.getRuntimeMXBean();

    private SystemMetrics() {
    }

    public static Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        MemoryUsage heap = MEMORY.getHeapMemoryUsage();
        MemoryUsage nonHeap = MEMORY.getNonHeapMemoryUsage();

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) {
                gcCount += count;
            }
            if (time > 0) {
                gcTime += time;
            }
        }

        map.put("ts", System.currentTimeMillis());
        map.put("heapUsed", mb(heap.getUsed()));
        map.put("heapMax", mb(heap.getMax()));
        map.put("heapCommitted", mb(heap.getCommitted()));
        map.put("nonHeapUsed", mb(nonHeap.getUsed()));
        map.put("cpuLoad", ratio(OS.getCpuLoad()));
        map.put("processCpuLoad", ratio(OS.getProcessCpuLoad()));
        map.put("threadCount", THREADS.getThreadCount());
        map.put("peakThreadCount", THREADS.getPeakThreadCount());
        map.put("daemonThreadCount", THREADS.getDaemonThreadCount());
        map.put("gcCount", gcCount);
        map.put("gcTimeMs", gcTime);
        map.put("uptimeMs", RUNTIME.getUptime());
        map.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        return map;
    }

    /** 字节 → MB，保留一位小数。 */
    static double mb(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return Math.round(bytes / 1024.0 / 1024.0 * 10) / 10.0;
    }

    /** 0~1 的比例，不可用时返回 0。 */
    static double ratio(double value) {
        if (value < 0 || Double.isNaN(value)) {
            return 0;
        }
        return Math.round(value * 1000) / 1000.0;
    }
}
