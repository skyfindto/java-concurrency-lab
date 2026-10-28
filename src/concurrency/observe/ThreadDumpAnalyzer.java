package concurrency.observe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jstack 线程转储分析器：把一堆文本变成「谁在阻塞、谁持有锁、有没有死锁」。
 *
 * <p>线上排障的真实流程往往是：用户说接口卡了 → 运维给你一份 jstack 文本 →
 * 你要在几百个线程里 3 分钟内找到问题。手工看太慢，所以把判断逻辑写成了代码。
 *
 * <p>分析能力：
 * <ul>
 *   <li>解析线程名、tid/nid、状态、堆栈；</li>
 *   <li>从 {@code - locked} / {@code - waiting to lock} / {@code - parking to wait for}
 *       构建锁等待图，用环检测找死锁；</li>
 *   <li>按「栈顶」聚合，找出大量线程卡在同一个地方（连接池耗尽、下游超时的典型特征）；</li>
 *   <li>输出可直接给页面渲染的 findings。</li>
 * </ul>
 */
public final class ThreadDumpAnalyzer {

    private static final Pattern HEADER = Pattern.compile("^\"(.+?)\"\\s+#(\\d+)(.*)$");
    private static final Pattern TID = Pattern.compile("tid=(0x[0-9a-fA-F]+)");
    private static final Pattern NID = Pattern.compile("nid=(0x[0-9a-fA-F]+)");
    private static final Pattern STATE = Pattern.compile("java\\.lang\\.Thread\\.State:\\s*(\\w+)");
    private static final Pattern LOCKED =
            Pattern.compile("^\\s+- locked <(0x[0-9a-fA-F]+)> \\(a ([^)]*)\\)");
    private static final Pattern WAITING_TO_LOCK =
            Pattern.compile("^\\s+- waiting to lock <(0x[0-9a-fA-F]+)> \\(a ([^)]*)\\)");
    private static final Pattern PARKING =
            Pattern.compile("^\\s+- parking to wait for\\s+<(0x[0-9a-fA-F]+)> \\(a ([^)]*)\\)");
    private static final Pattern WAITING_ON =
            Pattern.compile("^\\s+- waiting on <(0x[0-9a-fA-F]+)> \\(a ([^)]*)\\)");

    private static final String[] ALL_STATES = {
            "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "NEW", "TERMINATED"
    };

    /** 解析中间态。 */
    private static final class Entry {
        String name = "";
        String tid = "";
        String nid = "";
        String state = "UNKNOWN";
        final List<String> stack = new ArrayList<>();
        final List<String> lockedAddresses = new ArrayList<>();
        final Map<String, String> lockClasses = new LinkedHashMap<>();
        String waitingLock = "";
        String waitingClass = "";
    }

    private ThreadDumpAnalyzer() {
    }

    public static Map<String, Object> analyze(String text) {
        List<Entry> entries = parse(text);
        return report(entries, text);
    }

    private static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        for (String raw : text.split("\\R")) {
            String line = raw.stripTrailing();
            Matcher header = HEADER.matcher(line);
            if (header.find()) {
                current = new Entry();
                current.name = header.group(1);
                String rest = header.group(3);
                Matcher tid = TID.matcher(rest);
                if (tid.find()) {
                    current.tid = tid.group(1);
                }
                Matcher nid = NID.matcher(rest);
                if (nid.find()) {
                    current.nid = nid.group(1);
                }
                entries.add(current);
                continue;
            }
            if (current == null) {
                continue;
            }
            if (line.isBlank()) {
                current = null;
                continue;
            }
            Matcher state = STATE.matcher(line);
            if (state.find()) {
                current.state = state.group(1);
                continue;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("at ")) {
                current.stack.add(trimmed);
                continue;
            }
            Matcher locked = LOCKED.matcher(line);
            if (locked.find()) {
                current.lockedAddresses.add(locked.group(1));
                current.lockClasses.put(locked.group(1), locked.group(2));
                continue;
            }
            Matcher waiting = WAITING_TO_LOCK.matcher(line);
            if (waiting.find()) {
                current.waitingLock = waiting.group(1);
                current.waitingClass = waiting.group(2);
                continue;
            }
            Matcher parking = PARKING.matcher(line);
            if (parking.find()) {
                current.waitingLock = parking.group(1);
                current.waitingClass = parking.group(2);
                continue;
            }
            Matcher waitingOn = WAITING_ON.matcher(line);
            if (waitingOn.find()) {
                current.lockClasses.put(waitingOn.group(1), waitingOn.group(2));
            }
        }
        return entries;
    }

    private static Map<String, Object> report(List<Entry> entries, String rawText) {
        Map<String, Integer> stateCount = new LinkedHashMap<>();
        for (String state : ALL_STATES) {
            stateCount.put(state, 0);
        }
        List<Map<String, Object>> threads = new ArrayList<>();
        for (Entry entry : entries) {
            stateCount.merge(entry.state, 1, Integer::sum);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.name);
            item.put("tid", entry.tid);
            item.put("nid", entry.nid);
            item.put("state", entry.state);
            item.put("stack", entry.stack);
            threads.add(item);
        }

        // 锁地址 → 持有者
        Map<String, String> lockOwner = new LinkedHashMap<>();
        Map<String, String> lockOwnerClass = new LinkedHashMap<>();
        for (Entry entry : entries) {
            for (String address : entry.lockedAddresses) {
                lockOwner.put(address, entry.name);
                lockOwnerClass.put(address, entry.lockClasses.getOrDefault(address, "unknown"));
            }
        }

        // 线程 → 它等待的锁的持有者（有向边），只有这种边才可能成环
        Map<String, String> edges = new LinkedHashMap<>();
        Map<String, String> wantDescription = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.waitingLock.isEmpty()) {
                continue;
            }
            wantDescription.put(entry.name, entry.waitingLock + " (" + entry.waitingClass + ")");
            String owner = lockOwner.get(entry.waitingLock);
            if (owner != null && !owner.equals(entry.name)) {
                edges.put(entry.name, owner);
            }
        }

        List<List<String>> cycles = findCycles(edges);
        List<Map<String, Object>> deadlocks = new ArrayList<>();
        for (List<String> cycle : cycles) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (String name : cycle) {
                Entry entry = entries.stream().filter(e -> e.name.equals(name)).findFirst().orElse(null);
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("name", name);
                member.put("holds", entry == null ? "" : String.join(", ", entry.lockedAddresses));
                member.put("wants", wantDescription.getOrDefault(name, ""));
                members.add(member);
            }
            deadlocks.add(Map.of("threads", members));
        }

        List<Map<String, Object>> states = new ArrayList<>();
        stateCount.forEach((state, count) -> {
            if (count > 0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("state", state);
                item.put("count", count);
                states.add(item);
            }
        });
        states.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));

        List<Map<String, Object>> findings = buildFindings(entries, deadlocks, stateCount, rawText);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", entries.size());
        result.put("states", states);
        result.put("threads", threads);
        result.put("deadlocks", deadlocks);
        result.put("findings", findings);
        return result;
    }

    /** 有向图找环（每个节点最多一条出边，环形结构很简单）。 */
    private static List<List<String>> findCycles(Map<String, String> edges) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String start : edges.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            Map<String, Integer> position = new LinkedHashMap<>();
            List<String> path = new ArrayList<>();
            String cursor = start;
            while (cursor != null && !visited.contains(cursor)) {
                if (position.containsKey(cursor)) {
                    cycles.add(new ArrayList<>(path.subList(position.get(cursor), path.size())));
                    break;
                }
                position.put(cursor, path.size());
                path.add(cursor);
                cursor = edges.get(cursor);
            }
            visited.addAll(path);
        }
        return cycles;
    }

    private static List<Map<String, Object>> buildFindings(List<Entry> entries,
                                                           List<Map<String, Object>> deadlocks,
                                                           Map<String, Integer> stateCount,
                                                           String rawText) {
        List<Map<String, Object>> findings = new ArrayList<>();

        if (!deadlocks.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Map<String, Object> group : deadlocks) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> members = (List<Map<String, Object>>) group.get("threads");
                for (Map<String, Object> member : members) {
                    names.add(member.get("name") + " 等 " + member.get("wants")
                            + "（持有 " + member.get("holds") + "）");
                }
            }
            findings.add(finding("ERROR", "检测到 " + deadlocks.size() + " 处死锁环路",
                    "线程之间形成了循环等待：A 持有 1 等 2，B 持有 2 等 1。死锁不会自愈。"
                            + "如果栈里是 synchronized，只能重启；如果是 ReentrantLock，可以中断相关线程。",
                    names));
        }

        int blocked = stateCount.getOrDefault("BLOCKED", 0);
        if (blocked >= 5) {
            List<String> evidence = new ArrayList<>();
            entries.stream().filter(e -> "BLOCKED".equals(e.state)).limit(5)
                    .forEach(e -> evidence.add(e.name + " → " + topFrame(e)));
            findings.add(finding("WARN", blocked + " 个线程处于 BLOCKED（等 synchronized 锁）",
                    "锁竞争激烈或临界区太长。看每个线程的 lockOwnerName，确认持锁线程是不是在做 IO/RPC。",
                    evidence));
        }

        // 按栈顶聚合：大量线程卡在同一处，是最有价值的线索
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.stack.isEmpty() || "RUNNABLE".equals(entry.state)) {
                continue;
            }
            String key = topFrame(entry);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.name);
        }
        grouped.entrySet().stream()
                .filter(e -> e.getValue().size() >= 5)
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(3)
                .forEach(e -> findings.add(finding("WARN",
                        e.getValue().size() + " 个线程卡在同一处：" + shorten(e.getKey()),
                        "同一栈顶通常意味着共享资源被耗尽或下游变慢（连接池、HTTP 客户端、锁、队列）。"
                                + "先看这个方法在等什么，再看是谁没还给它。",
                        e.getValue().stream().limit(5).toList())));

        int total = entries.size();
        if (total > 150) {
            findings.add(finding("WARN", "线程总数偏多：" + total,
                    "线程多意味着上下文切换和内存开销。按线程名前缀聚合，看是哪个池子建的、为什么没复用。",
                    List.of("建议：jstack 输出里按线程名前缀统计")));
        }

        long ioWait = entries.stream()
                .filter(e -> "RUNNABLE".equals(e.state))
                .filter(e -> !e.stack.isEmpty())
                .filter(e -> e.stack.get(0).contains("socketRead") || e.stack.get(0).contains("readBytes")
                        || e.stack.get(0).contains("FileInputStream"))
                .count();
        if (ioWait >= 3) {
            findings.add(finding("INFO", ioWait + " 个线程在等网络/文件 IO（状态显示 RUNNABLE 但没占 CPU）",
                    "JVM 把阻塞 IO 也算作 RUNNABLE，所以「RUNNABLE 很多」不等于「CPU 很高」。"
                            + "判断 CPU 要看 getThreadCpuTime，而不是线程状态。",
                    List.of("典型栈顶：java.net.SocketInputStream.socketRead0")));
        }

        if (!rawText.contains("Full thread dump") && !rawText.contains("thread dump")) {
            findings.add(finding("INFO", "输入看起来不完全是标准 jstack 输出",
                    "如果粘贴的是 jcmd / arthas / 只截取了一部分，解析结果可能不完整。",
                    List.of("标准格式以 \"Full thread dump\" 开头")));
        }

        if (findings.isEmpty()) {
            findings.add(finding("INFO", "没有发现明显异常",
                    "线程状态和锁关系都很健康。如果业务确实卡了，可以多抓几次 jstack（间隔 5 秒）对比，"
                            + "或检查 GC 日志、下游依赖。",
                    List.of("总线程数 " + total)));
        }

        findings.sort((a, b) -> Integer.compare(weight((String) b.get("level")), weight((String) a.get("level"))));
        return findings;
    }

    private static int weight(String level) {
        return switch (level) {
            case "ERROR" -> 3;
            case "WARN" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static String topFrame(Entry entry) {
        // 优先展示「第一个业务帧」：跳过 JDK 框架帧，否则所有等待线程都会显示 Unsafe.park
        for (String frame : entry.stack) {
            String text = frame.startsWith("at ") ? frame.substring(3) : frame;
            if (!text.startsWith("java.") && !text.startsWith("jdk.")
                    && !text.startsWith("sun.") && !text.startsWith("com.sun.")) {
                return frame;
            }
        }
        return entry.stack.isEmpty() ? "(无堆栈)" : entry.stack.get(0);
    }

    private static String shorten(String frame) {
        String text = frame.replaceFirst("^at ", "");
        return text.length() > 110 ? text.substring(0, 110) + "…" : text;
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
