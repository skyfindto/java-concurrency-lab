package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.observe.FaultLab;
import concurrency.observe.ThreadInspector;

import java.util.List;
import java.util.Map;

/**
 * 进阶 05：故障演练 —— CPU 100% 怎么查。
 *
 * <p>「纸上谈兵」和「亲手处理过一次」完全是两回事。本用例主动制造 CPU 打满，
 * 然后走一遍完整的排查路径：指标 → 热点线程 → 栈 → 根因 → 处置。
 */
public class O05_FaultCpu extends DemoBase {

    public O05_FaultCpu() {
        super("obs-05-fault-cpu", Level.OBSERVE,
                "故障演练：CPU 被打满",
                "制造热点 → 采样定位 → 诊断结论 → 停止恢复");
    }

    @Override
    public String knowledge() {
        return """
                ## 现场表现
                - 接口 RT 飙升，但错误率可能不高（线程都在跑，只是慢）；
                - `top` 看到 java 进程 CPU 接近 100%×核数；
                - 监控上「系统负载」持续高于核数。

                ## 排查路径（5 分钟）
                1. `top -Hp <pid>` 找到最忙的线程；
                2. `printf '%x\\n' <tid>` 转十六进制；
                3. `jstack <pid> | grep -A 30 nid=0x<hex>` 看栈；
                4. 重复 2~3 次确认是同一条栈（偶发 vs 持续）；
                5. 定位到代码后判断：死循环 / 正则回溯 / 大对象序列化 / 加解密 / 频繁 GC。

                ## 常见根因
                | 栈特征 | 根因 | 处置 |
                |---|---|---|
                | 业务方法反复出现 | 死循环 / while 没退出条件 | 修代码 |
                | Pattern / Matcher | 正则灾难性回溯 | 优化正则 / 加超时 |
                | JSON.toJSONString | 大对象序列化 | 缩减字段 / 分页 |
                | GC task thread | 内存压力 | 查堆 / 查泄漏 |
                | C2 CompilerThread | 启动预热 | 正常，持续高要查代码热点 |

                ## 紧急止血
                - 限流 / 摘流量，先保住其他实例；
                - 扩容（如果是流量型）；
                - 重启（如果确认是死循环且无法热修）；
                - 保留 `jstack`、`jcmd GC.heap_info`、火焰图，事后分析。

                ## 预防
                - 循环必须有退出条件 + 超时；
                - 正则避免嵌套量词，必要时加超时；
                - 引入 async-profiler 定期采样，建立 CPU 基线；
                - CPU 超过阈值自动 dump + 告警。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 别看到 CPU 高就扩容：先确认是流量型还是代码型。
                - 抓现场优先于重启，重启前至少留一份 jstack 和 GC 日志。
                - 火焰图（async-profiler）比 jstack 更能定位「谁在耗时」。
                - 演练要定期做，并且要在预发环境验证告警链路是通的。
                - 演练脚本要有自动恢复机制，别把演练变成真故障。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 启动演练：4 个线程持续做浮点运算");
        Map<String, Object> startResult = FaultLab.start("cpu");
        ctx.info("演练启动：%s", startResult.get("detail"));
        ctx.sleep(800);

        ctx.step("② 采样：谁在吃 CPU");
        List<Map<String, Object>> top = ThreadInspector.topCpuThreads(5, 600);
        for (Map<String, Object> item : top) {
            ctx.info("%-26s %-10s CPU=%sms (%s%%)",
                    item.get("name"), item.get("state"),
                    item.get("cpuMs"), item.get("percentOfWindow"));
        }
        boolean foundLab = top.stream()
                .anyMatch(item -> String.valueOf(item.get("name")).startsWith("lab-cpu-burner"));
        ctx.verify("定位到演练制造的热点线程", foundLab);

        ctx.step("③ 一键诊断会给出什么结论");
        Map<String, Object> diagnosis = concurrency.observe.Diagnostics.diagnose();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) diagnosis.get("findings");
        findings.stream().limit(3).forEach(f -> {
            ctx.warn("[%s] %s", f.get("level"), f.get("title"));
            ctx.info("      %s", f.get("detail"));
            @SuppressWarnings("unchecked")
            List<String> evidence = (List<String>) f.get("evidence");
            if (evidence != null) {
                evidence.forEach(e -> ctx.info("      · %s", e));
            }
        });
        boolean reported = findings.stream()
                .anyMatch(f -> String.valueOf(f.get("title")).contains("CPU"));
        ctx.verify("诊断报告识别出 CPU 热点", reported);

        ctx.step("④ 对应的命令行操作");
        ctx.info("top -Hp %d              # 找最忙的线程", ProcessHandle.current().pid());
        ctx.info("printf '%%x\\n' <tid>      # 十进制转十六进制");
        ctx.info("jstack %d | grep -A 20 nid=0x<hex>", ProcessHandle.current().pid());
        ctx.info("async-profiler: ./profiler.sh -d 30 -f flame.html %d", ProcessHandle.current().pid());

        ctx.step("⑤ 停止演练，确认恢复");
        FaultLab.stopAll();
        ctx.sleep(700);
        List<Map<String, Object>> after = ThreadInspector.topCpuThreads(5, 400);
        boolean labGone = after.stream()
                .noneMatch(item -> String.valueOf(item.get("name")).startsWith("lab-cpu-burner"));
        ctx.verify("演练线程已停止，CPU 回落", labGone);

        ctx.step("⑥ 复盘：这次演练教会我们什么");
        ctx.info("1. 热点线程用 CPU 时间增量就能定位，不需要登机器");
        ctx.info("2. 线程名必须能辨认来源（lab-cpu-burner → 一眼看出是谁造的）");
        ctx.info("3. 诊断要有「证据」：线程名 + CPU 占比，而不是一句「CPU 高」");
        ctx.info("4. 演练结束必须自动恢复，避免演练变故障");
    }
}
