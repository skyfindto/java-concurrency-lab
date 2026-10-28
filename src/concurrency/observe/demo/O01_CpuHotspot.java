package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.observe.ThreadInspector;

import java.util.List;
import java.util.Map;

/**
 * 进阶 01：谁在吃 CPU —— 用 ThreadMXBean 定位热点线程。
 *
 * <p>线上 CPU 飙高时的标准动作：
 * <ol>
 *   <li>{@code top -Hp <pid>} 找到最忙的线程（十进制 tid）；</li>
 *   <li>转成十六进制，去 jstack 里找 {@code nid=0x...}；</li>
 *   <li>看它卡在哪个方法的哪一行。</li>
 * </ol>
 *
 * <p>本用例用纯 Java 把前两步自动化：采样每个线程的 CPU 时间，算增量，
 * 直接告诉你「哪个线程在这段时间里烧了最多 CPU」。
 * 这就是 Arthas {@code thread -n} 命令的原理。
 */
public class O01_CpuHotspot extends DemoBase {

    private static volatile boolean running = true;

    public O01_CpuHotspot() {
        super("obs-01-cpu-hotspot", Level.OBSERVE,
                "定位 CPU 热点线程",
                "不登服务器，用 Java API 找出「谁在烧 CPU」");
    }

    @Override
    public String knowledge() {
        return """
                ## 命令行的做法
                    top -Hp <pid>                 # 找到最忙的线程（十进制 tid）
                    printf '%x\\n' <tid>          # 转十六进制
                    jstack <pid> | grep -A 30 nid=0x<hex>
                    # 或者：jcmd <pid> Thread.print
                    # 或者：arthas 的 thread -n 3

                ## Java API 的做法（本用例）
                `ThreadMXBean.getThreadCpuTime(tid)` 返回该线程的累计 CPU 纳秒数。
                采样两次，做差，就得到「这段时间里谁最耗 CPU」。

                ## 三种「高 CPU」的典型栈
                1. **业务死循环**：栈顶在你自己的代码里反复出现同一个方法；
                2. **GC 线程忙**：GC task thread 占用高 → 内存压力大（用 jstat 确认）；
                3. **JIT 编译线程**：启动阶段正常，持续高就有问题。

                ## 排除法
                - 如果所有线程 CPU 都不高，但机器 CPU 高 → 可能是别的进程/容器；
                - 如果 GC 占比高 → 查堆、查大对象、查内存泄漏；
                - 如果某个线程长期 RUNNABLE 且 CPU 高 → 看栈顶是不是循环/正则/序列化。

                ## 生产建议
                把「CPU 采样 + 热点线程栈」做成一个定时任务，
                CPU 超过阈值时自动 dump 并告警，比等用户投诉快得多。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 定位 CPU 问题先看「线程级」而不是「进程级」，否则只能瞎猜。
                - 连续采样 3~5 次，避免把偶发计算误判为死循环。
                - 拿到的线程名要能对应到业务（这就是为什么线程要命名）。
                - 线上用 async-profiler 生成火焰图，能看到方法级的时间占比。
                - 高 CPU 不一定是死循环：正则回溯、JSON 序列化大对象、频繁 GC 都很常见。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 制造 2 个 CPU 热点线程（持续做浮点运算）");
        running = true;
        Thread hot1 = new Thread(() -> busyLoop(), "hotspot-worker-1");
        Thread hot2 = new Thread(() -> busyLoop(), "hotspot-worker-2");
        hot1.setDaemon(true);
        hot2.setDaemon(true);
        hot1.start();
        hot2.start();
        ctx.sleep(200);

        ctx.step("② 采样 800ms，按 CPU 时间排序");
        List<Map<String, Object>> top = ThreadInspector.topCpuThreads(5, 800);
        ctx.info("%-28s %-14s %10s %10s", "线程名", "状态", "CPU(ms)", "占窗口比");
        for (Map<String, Object> item : top) {
            ctx.info("%-28s %-14s %10s %9s%%",
                    item.get("name"), item.get("state"), item.get("cpuMs"), item.get("percentOfWindow"));
        }

        boolean found = top.stream()
                .anyMatch(item -> String.valueOf(item.get("name")).startsWith("hotspot-worker"));
        ctx.verify("热点线程被识别出来", found);
        if (!top.isEmpty()) {
            ctx.info("最忙的线程：%s，占了采样窗口 %s%% 的 CPU",
                    top.get(0).get("name"), top.get(0).get("percentOfWindow"));
        }

        ctx.step("③ 停止热点线程（演示结束，不要在页面里留一个烧 CPU 的线程）");
        running = false;
        hot1.join(2000);
        hot2.join(2000);
        ctx.verify("热点线程已停止", !hot1.isAlive() && !hot2.isAlive());

        ctx.step("④ 线上对应操作");
        ctx.info("top -Hp <pid> → 找最忙 tid → printf '%%x\\n' → jstack | grep nid=0x...");
        ctx.info("或者用 Arthas：thread -n 3（直接给出最忙的 3 个线程栈）");
        ctx.info("或者用 async-profiler：./profiler.sh -d 30 -f flame.html <pid>");
        ctx.verify("定位 CPU 问题 = 线程级采样 + 栈分析", true);
    }

    private static void busyLoop() {
        double x = 1;
        while (running) {
            x = Math.sin(x) + Math.sqrt(x + 1);
            if (x > 1e9) {
                x = 1; // 防止溢出成 Infinity 后被优化
            }
            SINK = x;
        }
    }

    private static volatile double SINK;
}
