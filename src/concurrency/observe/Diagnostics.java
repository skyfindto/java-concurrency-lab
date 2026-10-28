package concurrency.observe;

import concurrency.core.PoolMonitor;
import concurrency.core.Threads;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键诊断：把「人肉 jstack + 看监控面板」的判断逻辑固化成规则。
 *
 * <p>这就是生产上所谓的「多线程巡检脚本」，通常挂在定时任务里，
 * 发现异常就发告警。规则不复杂，但能把 80% 的常见问题直接定位到线程名和栈顶。
 *
 * <p>覆盖的典型故障：
 * <ul>
 *   <li>死锁（ThreadMXBean 直接给答案）；</li>
 *   <li>CPU 被某个线程打满（CPU 时间采样）；</li>
 *   <li>线程泄漏（线程总数异常增长）；</li>
 *   <li>锁竞争（BLOCKED 比例过高）；</li>
 *   <li>线程池打满 / 队列积压 / 大量拒绝；</li>
 *   <li>堆内存接近上限、GC 占用过高。</li>
 * </ul>
 */
public final class Diagnostics {

    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();

    private Diagnostics() {
    }

    public static Map<String, Object> diagnose() {
        List<Map<String, Object>> findings = new ArrayList<>();

        checkDeadlock(findings);
        checkCpuHotspot(findings);
        checkThreadCount(findings);
        checkBlockedRatio(findings);
        checkPools(findings);
        checkMemory(findings);

        if (findings.isEmpty()) {
            findings.add(finding("INFO", "未发现明显异常",
                    "线程、线程池、内存指标均在正常范围。可以继续观察趋势，或到「排障演练场」制造一次故障看看诊断是否生效。",
                    List.of(Threads.summary())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ts", System.currentTimeMillis());
        result.put("findings", findings);
        return result;
    }

    private static void checkDeadlock(List<Map<String, Object>> findings) {
        long[] ids = MX.findDeadlockedThreads();
        if (ids != null && ids.length > 0) {
            List<String> names = new ArrayList<>();
            for (ThreadInfo info : MX.getThreadInfo(ids, true, true)) {
                names.add(info.getThreadName() + " (等 " + info.getLockName() + "，被 "
                        + info.getLockOwnerName() + " 持有)");
            }
            findings.add(finding("ERROR", "检测到 " + ids.length + " 个线程死锁",
                    "ThreadMXBean.findDeadlockedThreads() 返回了环路。死锁不会自愈，必须重启或中断相关线程。"
                            + "同步块死锁只能重启；ReentrantLock 可以用 tryLock/超时来预防。",
                    names));
        }
    }

    private static void checkCpuHotspot(List<Map<String, Object>> findings) {
        List<Map<String, Object>> top = ThreadInspector.topCpuThreads(5, 300);
        if (top.isEmpty()) {
            return;
        }
        Map<String, Object> hottest = top.get(0);
        double percent = ((Number) hottest.get("percentOfWindow")).doubleValue();
        if (percent >= 60) {
            List<String> evidence = new ArrayList<>();
            for (Map<String, Object> item : top) {
                evidence.add(item.get("name") + " → " + item.get("percentOfWindow") + "% CPU，状态 "
                        + item.get("state"));
            }
            findings.add(finding("WARN", "线程 " + hottest.get("name") + " 占用了 " + percent + "% 的 CPU",
                    "在 300ms 采样窗口内该线程几乎一直在跑。常见原因：死循环、正则回溯、序列化大对象、加解密、频繁 GC。"
                            + "下一步：jstack 抓栈看它在哪一行循环，或用 async-profiler 生成火焰图。",
                    evidence));
        }
    }

    private static void checkThreadCount(List<Map<String, Object>> findings) {
        int count = MX.getThreadCount();
        int peak = MX.getPeakThreadCount();
        int cores = Runtime.getRuntime().availableProcessors();
        if (count > Math.max(200, cores * 32)) {
            Map<String, Integer> prefixes = new LinkedHashMap<>();
            for (ThreadInfo info : MX.dumpAllThreads(false, false)) {
                String name = info.getThreadName();
                String prefix = name.contains("-") ? name.substring(0, name.indexOf('-')) : name;
                prefixes.merge(prefix, 1, Integer::sum);
            }
            List<String> evidence = new ArrayList<>();
            prefixes.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(6)
                    .forEach(e -> evidence.add(e.getKey() + " → " + e.getValue() + " 个线程"));
            findings.add(finding("WARN", "线程总数偏高：" + count + " 个（历史峰值 " + peak + "）",
                    "平台线程默认 1MB 栈，线程数过多会吃内存并加重上下文切换。"
                            + "先看下面的线程名前缀，确认是哪个池子/哪段代码在创建线程，再检查是否忘记 shutdown 或池子 max 设置过大。",
                    evidence));
        }
    }

    private static void checkBlockedRatio(List<Map<String, Object>> findings) {
        ThreadInfo[] infos = MX.dumpAllThreads(false, false);
        int blocked = 0;
        for (ThreadInfo info : infos) {
            if (info.getThreadState() == Thread.State.BLOCKED) {
                blocked++;
            }
        }
        double ratio = infos.length == 0 ? 0 : blocked * 1.0 / infos.length;
        if (blocked >= 8 && ratio > 0.2) {
            findings.add(finding("WARN", "锁竞争严重：" + blocked + " 个线程处于 BLOCKED",
                    "BLOCKED 只发生在等 synchronized monitor 锁时。说明临界区太长或锁粒度太大，"
                            + "通常是锁里做了 IO/RPC。下一步：看这些线程的 lockOwnerName，找出持锁线程在干什么。",
                    List.of("BLOCKED 线程占比 " + Math.round(ratio * 100) + "%",
                            "建议用 jstack -l 查看 \"waiting to lock\" 的持有者")));
        }
    }

    private static void checkPools(List<Map<String, Object>> findings) {
        for (PoolMonitor.Snapshot pool : PoolMonitor.snapshots()) {
            if (pool.shutdown()) {
                continue;
            }
            long rejected = pool.rejected();
            if (rejected > 0) {
                findings.add(finding("ERROR", "线程池[" + pool.name() + "]已拒绝 " + rejected + " 个任务",
                        "拒绝意味着任务被丢弃或降级。检查队列容量与 maximumPoolSize 是否匹配流量，"
                                + "确认拒绝策略是「快速失败 + 告警」而不是静默丢弃。",
                        List.of("活跃 " + pool.activeCount() + "/" + pool.maxSize(),
                                "队列 " + pool.queueSize() + "/" + pool.queueCapacity(),
                                "已完成 " + pool.completedTasks())));
            } else if (pool.queueCapacity() > 0 && pool.queueSize() * 1.0 / pool.queueCapacity() > 0.8) {
                findings.add(finding("WARN", "线程池[" + pool.name() + "]队列积压 "
                                + Math.round(pool.queueSize() * 100.0 / pool.queueCapacity()) + "%",
                        "队列快满了，说明消费速度跟不上生产速度。再涨就会触发拒绝。"
                                + "排查下游 RT、任务里的阻塞点，必要时扩容或限流。",
                        List.of("队列 " + pool.queueSize() + "/" + pool.queueCapacity(),
                                "活跃 " + pool.activeCount() + "/" + pool.maxSize())));
            } else if (pool.activeCount() >= pool.maxSize() && pool.queueSize() > 0) {
                findings.add(finding("WARN", "线程池[" + pool.name() + "]已满负荷运行",
                        "所有工作线程都在忙，且已经排队。持续下去会拖高接口 RT。",
                        List.of("活跃 " + pool.activeCount() + "/" + pool.maxSize(),
                                "队列 " + pool.queueSize())));
            }
        }
    }

    private static void checkMemory(List<Map<String, Object>> findings) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        if (heap.getMax() <= 0) {
            return;
        }
        double usedRatio = heap.getUsed() * 1.0 / heap.getMax();
        if (usedRatio > 0.85) {
            findings.add(finding("WARN", "堆内存使用率 " + Math.round(usedRatio * 100) + "%",
                    "接近上限，频繁 Full GC 会让所有业务线程停顿。先看是不是无界队列塞满了任务、"
                            + "或者 ThreadLocal 没清理导致对象无法回收。",
                    List.of("heap " + SystemMetrics.mb(heap.getUsed()) + "MB / "
                            + SystemMetrics.mb(heap.getMax()) + "MB")));
        }

        long gcTime = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gc.getCollectionTime();
            if (time > 0) {
                gcTime += time;
            }
        }
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptime > 10_000 && gcTime * 1.0 / uptime > 0.1) {
            findings.add(finding("WARN", "GC 时间占比 " + Math.round(gcTime * 100.0 / uptime) + "%",
                    "GC 占用超过 10% 的墙钟时间，说明内存压力大或对象分配过快。"
                            + "用 jcmd GC.heap_info 和 jstat -gcutil 确认，再用 jmap 分析大对象。",
                    List.of("GC 总耗时 " + gcTime + "ms / 运行 " + uptime + "ms")));
        }
    }

    private static Map<String, Object> finding(String level, String title, String detail, List<String> evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", level);
        map.put("title", title);
        map.put("detail", detail);
        map.put("evidence", evidence);
        return map;
    }
}
