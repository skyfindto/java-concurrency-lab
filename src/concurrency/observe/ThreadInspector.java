package concurrency.observe;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线程巡检：直接把 jstack 能看到的信息，用 Java API 结构化出来。
 *
 * <p>对应命令行里的 {@code jstack -l <pid>}：
 * <ul>
 *   <li>{@link ThreadMXBean#dumpAllThreads(boolean, boolean)} 等价于 jstack 的线程 + 锁信息；</li>
 *   <li>{@link ThreadMXBean#findDeadlockedThreads()} 等价于 jstack 里的 "Found one Java-level deadlock"；</li>
 *   <li>{@link ThreadMXBean#getThreadCpuTime(long)} 能精确算出「哪个线程在烧 CPU」。</li>
 * </ul>
 *
 * <p><b>注意</b>：ThreadMXBean 只包含平台线程，虚拟线程不在这里（JDK21 的 jcmd Thread.dump_to_file 才能看到）。
 */
public final class ThreadInspector {

    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
    private static final String[] ALL_STATES = {
            "NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED"
    };

    private ThreadInspector() {
    }

    /** 所有平台线程的详细快照（含锁信息和堆栈）。 */
    public static Map<String, Object> snapshot() {
        boolean cpuSupported = MX.isThreadCpuTimeSupported();
        if (cpuSupported && !MX.isThreadCpuTimeEnabled()) {
            MX.setThreadCpuTimeEnabled(true);
        }
        ThreadInfo[] infos = MX.dumpAllThreads(true, true);

        Map<String, Integer> stateCount = new LinkedHashMap<>();
        for (String state : ALL_STATES) {
            stateCount.put(state, 0);
        }

        List<Map<String, Object>> threads = new ArrayList<>();
        for (ThreadInfo info : infos) {
            String state = info.getThreadState().name();
            stateCount.merge(state, 1, Integer::sum);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tid", info.getThreadId());
            item.put("name", info.getThreadName());
            item.put("state", state);
            item.put("daemon", info.isDaemon());
            item.put("priority", info.getPriority());
            item.put("cpuMs", cpuSupported ? MX.getThreadCpuTime(info.getThreadId()) / 1_000_000 : -1);
            item.put("blockedCount", info.getBlockedCount());
            item.put("waitedCount", info.getWaitedCount());
            item.put("lockName", info.getLockName() == null ? "" : info.getLockName());
            item.put("lockOwnerName", info.getLockOwnerName() == null ? "" : info.getLockOwnerName());

            List<String> stack = new ArrayList<>();
            for (StackTraceElement element : info.getStackTrace()) {
                stack.add(element.toString());
            }
            item.put("stack", stack);
            threads.add(item);
        }

        threads.sort(Comparator.comparingLong(t -> -((Number) t.get("cpuMs")).longValue()));

        List<Map<String, Object>> states = new ArrayList<>();
        stateCount.forEach((state, count) -> {
            if (count > 0) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("state", state);
                entry.put("count", count);
                states.add(entry);
            }
        });
        states.sort(Comparator.comparingInt(s -> -((Number) s.get("count")).intValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ts", System.currentTimeMillis());
        result.put("total", infos.length);
        result.put("states", states);
        result.put("threads", threads);
        return result;
    }

    /** 死锁检测（monitor 锁 + ReentrantLock 等 ownable synchronizer）。 */
    public static Map<String, Object> deadlocks() {
        long[] ids = MX.findDeadlockedThreads();
        List<Map<String, Object>> groups = new ArrayList<>();

        if (ids != null && ids.length > 0) {
            ThreadInfo[] infos = MX.getThreadInfo(ids, true, true);
            Map<String, Object> group = new LinkedHashMap<>();
            List<Map<String, Object>> members = new ArrayList<>();
            for (ThreadInfo info : infos) {
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("tid", info.getThreadId());
                member.put("name", info.getThreadName());
                member.put("state", info.getThreadState().name());
                member.put("lockName", info.getLockName() == null ? "" : info.getLockName());
                member.put("lockOwnerName", info.getLockOwnerName() == null ? "" : info.getLockOwnerName());
                List<String> stack = new ArrayList<>();
                for (StackTraceElement element : info.getStackTrace()) {
                    stack.add(element.toString());
                }
                member.put("stack", stack);
                members.add(member);
            }
            group.put("threads", members);
            groups.add(group);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ts", System.currentTimeMillis());
        result.put("deadlocks", groups);
        return result;
    }

    /** 给定一组线程 id，返回名字（诊断报告用）。 */
    public static List<String> namesOf(long[] ids) {
        List<String> names = new ArrayList<>();
        if (ids == null) {
            return names;
        }
        for (long id : ids) {
            ThreadInfo info = MX.getThreadInfo(id);
            if (info != null) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }

    /** 线程 CPU 采样：返回 id → 名字，按占用排序，用于定位热点线程。 */
    public static List<Map<String, Object>> topCpuThreads(int topN, long sampleMillis) {
        if (!MX.isThreadCpuTimeSupported()) {
            return List.of();
        }
        if (!MX.isThreadCpuTimeEnabled()) {
            MX.setThreadCpuTimeEnabled(true);
        }
        long[] ids = MX.getAllThreadIds();
        Map<Long, Long> before = new LinkedHashMap<>();
        for (long id : ids) {
            before.put(id, MX.getThreadCpuTime(id));
        }
        try {
            Thread.sleep(sampleMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<Long, Long> delta = new LinkedHashMap<>();
        for (long id : ids) {
            long now = MX.getThreadCpuTime(id);
            long prev = before.getOrDefault(id, -1L);
            if (now > 0 && prev >= 0) {
                delta.put(id, now - prev);
            }
        }
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(delta.entrySet());
        entries.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, entries.size()); i++) {
            Map.Entry<Long, Long> entry = entries.get(i);
            if (entry.getValue() <= 0) {
                continue;
            }
            ThreadInfo info = MX.getThreadInfo(entry.getKey());
            if (info == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tid", entry.getKey());
            item.put("name", info.getThreadName());
            item.put("state", info.getThreadState().name());
            item.put("cpuMs", entry.getValue() / 1_000_000);
            item.put("percentOfWindow", Math.min(100.0,
                    Math.round(entry.getValue() * 1000.0 / (sampleMillis * 1_000_000.0)) / 10.0));
            result.add(item);
        }
        return result;
    }
}
