package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.core.Threads;
import concurrency.observe.FaultLab;
import concurrency.observe.Diagnostics;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进阶 06：故障演练 —— 线程泄漏。
 *
 * <p>线程泄漏比内存泄漏更「显眼」，也更容易致命：
 * 每个线程默认 1MB 栈，几千个线程就能把内存吃光，
 * 而且上下文切换会让整个系统变慢。
 *
 * <p>本用例让线程数持续增长，观察诊断报告如何用「线程名前缀聚合」定位源头。
 */
public class O06_FaultThreadLeak extends DemoBase {

    public O06_FaultThreadLeak() {
        super("obs-06-fault-thread-leak", Level.OBSERVE,
                "故障演练：线程泄漏",
                "线程数 1 秒涨 50 个，怎么定位是谁在创建");
    }

    @Override
    public String knowledge() {
        return """
                ## 线程泄漏的常见原因
                1. 每次请求 new Thread，没有池化（最常见）；
                2. 线程池没有 shutdown，反复创建新池（Spring 里每次 new）；
                3. 定时任务每次执行都新建调度器；
                4. 第三方客户端每次调用都创建线程（连接池配置错误）；
                5. 线程池的 maximumPoolSize 设成 Integer.MAX_VALUE，流量一冲就爆。

                ## 现场表现
                - `jstack | grep '^"' | wc -l` 持续增长；
                - 内存缓慢上涨（每个线程 ~1MB 栈）；
                - 上下文切换升高，RT 变慢；
                - 最终 `unable to create native thread`。

                ## 排查路径
                1. 看线程总数趋势（本用例的 threading 指标）；
                2. 按线程名前缀聚合：`jstack | grep '^"' | awk -F'"' '{print $2}' | sed 's/-[0-9]*$//' | sort | uniq -c | sort -rn`；
                3. 找到增长的那个前缀 → 回代码里搜 `new Thread` / `newFixedThreadPool`；
                4. 用 dump 的栈确认创建点。

                ## 诊断规则（本项目实现）
                线程数 > max(200, 核数×32) 时，
                按 `名字前缀` 聚合，输出 top 6，直接指出「谁在造线程」。

                ## 处置
                - 改成共享线程池 + 有界队列；
                - 确保每个池都有 shutdown / @PreDestroy；
                - 加线程数告警（比如 > 500 持续 5 分钟）；
                - 用 Micrometer 的 jvm_threads_live_threads 指标。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 永远不要「每次请求 new Thread」，用线程池或虚拟线程。
                - 每个线程池都要有明确的生命周期归属（谁创建、谁关闭）。
                - 线程数、新建速率都要监控；只看当前值会漏掉「持续增长」。
                - 线程名带业务前缀，泄漏时一眼定位（这是本用例能自动聚合的前提）。
                - 用虚拟线程时要注意：ThreadMXBean 看不到它们，需要 jcmd Thread.dump_to_file。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        var bean = ManagementFactory.getThreadMXBean();

        ctx.step("① 记录基线");
        int before = bean.getThreadCount();
        ctx.info("当前平台线程数 = %d", before);

        ctx.step("② 启动泄漏演练：每 200ms 新增 10 个线程");
        Map<String, Object> started = FaultLab.start("leak");
        ctx.info("演练启动：%s", started.get("detail"));

        ctx.sleep(1500);
        int during = bean.getThreadCount();
        ctx.info("1.5 秒后线程数 = %d（增长了 %d）", during, during - before);
        ctx.verify("线程数明显增长", during > before + 30);

        ctx.step("③ 按前缀聚合，找出「谁在造线程」");
        Map<String, Integer> prefixes = new LinkedHashMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            String prefix = name.contains("-") ? name.substring(0, name.indexOf('-')) : name;
            prefixes.merge(prefix, 1, Integer::sum);
        }
        prefixes.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(6)
                .forEach(e -> ctx.info("  %-18s %d 个", e.getKey(), e.getValue()));
        ctx.verify("定位到泄漏线程的前缀 lab-leak", prefixes.getOrDefault("lab", 0) > 30);

        ctx.step("④ 一键诊断的结论");
        Map<String, Object> diagnosis = Diagnostics.diagnose();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) diagnosis.get("findings");
        findings.stream()
                .filter(f -> String.valueOf(f.get("title")).contains("线程总数"))
                .findFirst()
                .ifPresentOrElse(f -> {
                    ctx.warn("%s", f.get("title"));
                    ctx.info("  %s", f.get("detail"));
                    @SuppressWarnings("unchecked")
                    List<String> evidence = (List<String>) f.get("evidence");
                    if (evidence != null) {
                        evidence.forEach(e -> ctx.info("  · %s", e));
                    }
                }, () -> ctx.warn("本次线程数还没超过诊断阈值（200），多跑一会儿就能触发"));

        ctx.step("⑤ 停止演练，确认线程回落");
        FaultLab.stopAll();
        ctx.sleep(800);
        int after = bean.getThreadCount();
        ctx.info("停止后线程数 = %d（峰值 %d）", after, during);
        ctx.verify("泄漏线程被回收，线程数下降", after < during);

        ctx.step("⑥ 线上的排查命令");
        ctx.info("jstack <pid> | grep '^\"' | wc -l");
        ctx.info("jstack <pid> | grep '^\"' | awk -F'\"' '{print $2}' | sed 's/-[0-9]*$//' | sort | uniq -c | sort -rn");
        ctx.info("%s", Threads.summary());
        ctx.verify("线程泄漏 = 数量趋势 + 名字前缀 + 创建栈", true);
    }
}
