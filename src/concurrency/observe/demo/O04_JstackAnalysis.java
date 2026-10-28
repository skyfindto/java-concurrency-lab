package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.observe.ThreadDumpAnalyzer;

import java.util.List;
import java.util.Map;

/**
 * 进阶 04：jstack 文本分析 —— 把「人眼看栈」变成「机器给结论」。
 *
 * <p>线上拿到一份 jstack，人工看通常要十几分钟：几百个线程、几十个锁地址。
 * 本用例把分析逻辑代码化：解析线程、构建锁等待图、找环、按栈聚合、
 * 输出可直接执行的结论。
 *
 * <p>页面上的「jstack 分析」tab 就是把这个能力做成了在线工具。
 */
public class O04_JstackAnalysis extends DemoBase {

    /** 一份真实感很强的线程转储：包含 1 处死锁 + 6 个等待连接池的线程。 */
    private static final String SAMPLE_DUMP = """
            Full thread dump OpenJDK 64-Bit Server VM (21.0.2+13-58 mixed mode, sharing):

            "http-nio-8080-exec-1" #31 daemon prio=5 os_prio=0 cpu=12.00ms elapsed=120.34s tid=0x000001f8e8c0a000 nid=0x1f waiting for monitor entry  [0x000000a1c5dff000]
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat com.example.order.OrderService.createOrder(OrderService.java:88)
            \t- waiting to lock <0x00000000a1b2c3d4> (a java.lang.Object)
            \tat com.example.web.OrderController.submit(OrderController.java:42)
            \tat java.base/java.lang.Thread.run(java.base@21.0.2/Thread.java:1583)

            "http-nio-8080-exec-2" #32 daemon prio=5 os_prio=0 cpu=8.00ms elapsed=120.34s tid=0x000001f8e8c0b000 nid=0x20 runnable  [0x000000a1c5eff000]
               java.lang.Thread.State: RUNNABLE
            \tat java.base/java.net.SocketInputStream.socketRead0(java.base@21.0.2/Native Method)
            \tat java.base/java.net.SocketInputStream.read(java.base@21.0.2/SocketInputStream.java:171)
            \tat com.example.web.HttpServer.handle(HttpServer.java:70)

            "order-worker-1" #41 prio=5 os_prio=0 cpu=201.00ms elapsed=118.00s tid=0x000001f8e8c0c000 nid=0x29 waiting for monitor entry  [0x000000a1c5fff000]
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat com.example.order.OrderRepository.lockB(OrderRepository.java:120)
            \t- waiting to lock <0x00000000aaaabbbb> (a java.lang.Object)
            \tat com.example.order.OrderService.transfer(OrderService.java:88)
            \t- locked <0x00000000ccccdddd> (a java.lang.Object)

            "order-worker-2" #42 prio=5 os_prio=0 cpu=198.00ms elapsed=118.00s tid=0x000001f8e8c0d000 nid=0x2a waiting for monitor entry  [0x000000a1c60ff000]
               java.lang.Thread.State: BLOCKED (on object monitor)
            \tat com.example.order.OrderRepository.lockA(OrderRepository.java:110)
            \t- waiting to lock <0x00000000ccccdddd> (a java.lang.Object)
            \tat com.example.order.OrderService.transfer(OrderService.java:92)
            \t- locked <0x00000000aaaabbbb> (a java.lang.Object)

            "http-nio-8080-exec-10" #40 daemon prio=5 os_prio=0 cpu=3.00ms elapsed=120.00s tid=0x000001f8e8c0e000 nid=0x28 waiting on condition  [0x000000a1c61ff000]
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(java.base@21.0.2/Native Method)
            \t- parking to wait for  <0x00000000deadbeef> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)
            \tat com.example.order.OrderDao.findByUserId(OrderDao.java:55)

            "http-nio-8080-exec-11" #43 daemon prio=5 os_prio=0 cpu=3.00ms elapsed=120.00s tid=0x000001f8e8c0f000 nid=0x2b waiting on condition  [0x000000a1c62ff000]
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(java.base@21.0.2/Native Method)
            \t- parking to wait for  <0x00000000deadbeef> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)
            \tat com.example.order.OrderDao.findByUserId(OrderDao.java:55)

            "http-nio-8080-exec-12" #44 daemon prio=5 os_prio=0 cpu=3.00ms elapsed=120.00s tid=0x000001f8e8c10000 nid=0x2c waiting on condition  [0x000000a1c63ff000]
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(java.base@21.0.2/Native Method)
            \t- parking to wait for  <0x00000000deadbeef> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)
            \tat com.example.order.OrderDao.findByUserId(OrderDao.java:55)

            "http-nio-8080-exec-13" #45 daemon prio=5 os_prio=0 cpu=3.00ms elapsed=120.00s tid=0x000001f8e8c11000 nid=0x2d waiting on condition  [0x000000a1c64ff000]
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(java.base@21.0.2/Native Method)
            \t- parking to wait for  <0x00000000deadbeef> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)
            \tat com.example.order.OrderDao.findByUserId(OrderDao.java:55)

            "http-nio-8080-exec-14" #46 daemon prio=5 os_prio=0 cpu=3.00ms elapsed=120.00s tid=0x000001f8e8c12000 nid=0x2e waiting on condition  [0x000000a1c65ff000]
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(java.base@21.0.2/Native Method)
            \t- parking to wait for  <0x00000000deadbeef> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)
            \tat com.example.order.OrderDao.findByUserId(OrderDao.java:55)

            "http-nio-8080-exec-15" #47 daemon prio=5 os_prio=0 cpu=3.00ms elapsed=120.00s tid=0x000001f8e8c13000 nid=0x2f waiting on condition  [0x000000a1c66ff000]
               java.lang.Thread.State: WAITING (parking)
            \tat java.base/jdk.internal.misc.Unsafe.park(java.base@21.0.2/Native Method)
            \t- parking to wait for  <0x00000000deadbeef> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
            \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)
            \tat com.example.order.OrderDao.findByUserId(OrderDao.java:55)

            "GC Thread#0" #9 prio=5 os_prio=0 cpu=1200.00ms elapsed=121.00s tid=0x000001f8e8b00000 nid=0x9 runnable  [0x0000000000000000]
               java.lang.Thread.State: RUNNABLE

            "C2 CompilerThread0" #6 daemon prio=9 os_prio=0 cpu=800.00ms elapsed=121.00s tid=0x000001f8e8a00000 nid=0x6 waiting on condition  [0x0000000000000000]
               java.lang.Thread.State: RUNNABLE
               No compile task
            """;

    public O04_JstackAnalysis() {
        super("obs-04-jstack-analysis", Level.OBSERVE,
                "jstack 文本分析：自动找死锁和阻塞点",
                "解析 12 个线程的转储，输出 3 条可执行结论");
    }

    @Override
    public String knowledge() {
        return """
                ## 一份 jstack 里到底看什么
                1. **线程总数与状态分布**：BLOCKED 多 → 锁竞争；WAITING 多 → 等依赖；RUNNABLE 多且 CPU 不高 → 等 IO；
                2. **同一栈顶的线程数**：6 个线程都停在 HikariPool.getConnection → 连接池耗尽；
                3. **锁的持有者与等待者**：`- waiting to lock <0x...>` 和 `- locked <0x...>` 可以还原等待链；
                4. **死锁环**：等待链成环就是死锁（jstack 自己也会打印 "Found one Java-level deadlock"）。

                ## 本用例的自动分析逻辑
                - 解析线程头（name / tid / nid / state / stack）；
                - 解析 `- locked` / `- waiting to lock` / `- parking to wait for`；
                - 用「锁地址 → 持有者」建图，再做环检测找死锁；
                - 按「应用栈顶」（跳过 java./jdk. 框架帧）聚合，找出大量线程的共同阻塞点；
                - 输出 findings（ERROR/WARN/INFO）。

                ## 抓取方式
                    jstack -l <pid> > dump.txt
                    jcmd <pid> Thread.print > dump.txt
                    kill -3 <pid>            # 输出到应用日志
                建议抓 3 次，间隔 5 秒，对比才能看出「卡住」还是「正在跑」。

                ## 常见结论对照表
                | 现象 | 根因 | 处理 |
                |---|---|---|
                | 大量 WAITING 在连接池 | 连接池耗尽 / 慢 SQL | 扩池 + 查慢查询 |
                | 大量 BLOCKED 在同一把锁 | 临界区太长 | 缩小锁范围 |
                | 死锁环 | 加锁顺序不一致 | 统一顺序 / 可中断锁 |
                | RUNNABLE 且 CPU 高 | 死循环 / 大计算 | 看栈顶方法 |
                | 线程数持续增长 | 线程泄漏 | 查线程名前缀 |
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 排障第一步永远是「抓现场」，而不是重启了事。
                - 至少抓 3 份 dump，间隔几秒，静态的一份可能只是恰好卡在某一刻。
                - 结合 CPU 采样：状态是 RUNNABLE 但 CPU 低，说明在等 IO，不是死循环。
                - 把这份分析逻辑接入巡检脚本，故障时自动出结论。
                - 死锁、连接池耗尽、线程泄漏这三类问题，90% 都能从 dump 里直接看出来。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 解析样本：12 个线程，其中 2 个死锁、6 个等连接池");
        Map<String, Object> report = ThreadDumpAnalyzer.analyze(SAMPLE_DUMP);
        ctx.info("解析出线程数：%s", report.get("total"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> states = (List<Map<String, Object>>) report.get("states");
        ctx.info("状态分布：%s", states);

        ctx.step("② 死锁检测：两个 worker 用相反顺序拿锁");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadlocks = (List<Map<String, Object>>) report.get("deadlocks");
        ctx.info("检测到 %d 处死锁环", deadlocks.size());
        ctx.verify("自动检出死锁", !deadlocks.isEmpty());
        for (Map<String, Object> group : deadlocks) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> members = (List<Map<String, Object>>) group.get("threads");
            for (Map<String, Object> member : members) {
                ctx.info("  %s：持有 %s，等待 %s",
                        member.get("name"), member.get("holds"), member.get("wants"));
            }
        }

        ctx.step("③ 关键结论（findings）");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) report.get("findings");
        for (Map<String, Object> finding : findings) {
            ctx.info("[%s] %s", finding.get("level"), finding.get("title"));
            ctx.info("      %s", finding.get("detail"));
        }

        boolean hasConnectionPoolFinding = findings.stream()
                .anyMatch(f -> String.valueOf(f.get("title")).contains("HikariPool")
                        || String.valueOf(f.get("title")).contains("连接"));
        ctx.verify("识别出「大量线程等待连接池」", hasConnectionPoolFinding);

        ctx.step("④ 如果是线上，接下来的动作");
        ctx.info("1. 先确认死锁线程的业务影响（订单转账卡住）");
        ctx.info("2. 保存 dump 文件，保留现场");
        ctx.info("3. 扩容连接池或重启实例恢复，同时排查是谁在长时间占用连接");
        ctx.info("4. 死锁：统一加锁顺序，并加死锁巡检告警");
        ctx.verify("dump 分析 = 状态分布 + 栈聚合 + 锁图", true);

        ctx.step("⑤ 试试页面的「jstack 分析」");
        ctx.info("把任意一份 jstack 文本粘进去，可以直接得到同样的结论");
    }
}
