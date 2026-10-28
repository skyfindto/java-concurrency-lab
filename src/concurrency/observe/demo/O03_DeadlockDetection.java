package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;
import concurrency.observe.Diagnostics;
import concurrency.observe.ThreadInspector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进阶 03：死锁检测 —— 让 JVM 自己告诉你「谁锁住了谁」。
 *
 * <p>死锁是线上最「安静」的故障：CPU 不高、内存正常、日志没有异常，
 * 但请求就是不返回。靠人肉 jstack 看几百个线程很慢，
 * {@code ThreadMXBean.findDeadlockedThreads()} 一行代码就能定位。
 *
 * <p>本用例构造一次真实死锁，然后用 JDK 自带的检测能力把它揪出来，
 * 最后演示如何安全地解除（ReentrantLock 可中断）。
 */
public class O03_DeadlockDetection extends DemoBase {

    public O03_DeadlockDetection() {
        super("obs-03-deadlock-detection", Level.OBSERVE,
                "死锁检测：ThreadMXBean 的自动巡检",
                "构造死锁 → 自动检测 → 输出锁链路 → 解除");
    }

    @Override
    public String knowledge() {
        return """
                ## 检测 API
                    ThreadMXBean mx = ManagementFactory.getThreadMXBean();
                    long[] ids = mx.findDeadlockedThreads();
                    if (ids != null) { ... }
                它同时支持 `synchronized`（monitor）和 `ReentrantLock`（ownable synchronizer）。

                ## 输出里有什么
                `ThreadInfo` 提供：
                - `getThreadName()` / `getThreadState()`；
                - `getLockName()`：它在等哪个锁；
                - `getLockOwnerName()`：这个锁被谁持有；
                - `getStackTrace()`：卡在哪一行。
                把这几项串起来，就是完整的「等待链」。

                ## 生产巡检脚本
                    @Scheduled(fixedDelay = 30_000)
                    public void checkDeadlock() {
                        long[] ids = mx.findDeadlockedThreads();
                        if (ids != null) {
                            alert("死锁: " + names(ids));
                            dumpThreads();   // 保存现场，便于事后分析
                        }
                    }
                注意：检测本身有开销（会挂起线程），不要每秒跑。

                ## 死锁 vs 「假死」
                - 死锁：findDeadlockedThreads 有返回，锁成环；
                - 线程池耗尽：线程都在 WAITING 等下游，没有环，但同样没响应；
                - 队列堆积：线程在跑，只是处理不过来。
                三者表现相似，必须靠线程栈 + 指标区分。

                ## 解除
                - synchronized：无解，只能重启；
                - ReentrantLock：`thread.interrupt()` 可以让 `lockInterruptibly()` 抛出 InterruptedException；
                - 数据库死锁：数据库自己会检测并回滚一方，业务侧重试即可。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 定时巡检死锁并告警，不要等用户报障。
                - 加锁顺序全局统一，是预防死锁成本最低的手段。
                - 需要「超时/可中断」的锁用 ReentrantLock，给系统留一条自救通道。
                - 检测到死锁时先 dump 现场再重启，否则永远不知道根因。
                - 线程池里的父子任务同池等待，也会造成「死锁」，检测 API 却查不出来。
                - 死锁修复后补一个压测用例，防止回归。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 构造死锁：两个线程以相反顺序拿两把锁");
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            lockA.lock();
            try {
                ctx.info("t1 拿到 lockA，准备拿 lockB");
                sleepQuietly(200);
                if (!lockB.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    ctx.warn("t1 等 lockB 超时，主动放弃");
                    return;
                }
                try {
                    ctx.info("t1 同时持有两把锁（不会发生）");
                } finally {
                    lockB.unlock();
                }
            } catch (InterruptedException e) {
                ctx.warn("t1 被中断，放弃等待");
                Thread.currentThread().interrupt();
            } finally {
                lockA.unlock();
            }
        }, "deadlock-holder-1");

        Thread t2 = new Thread(() -> {
            lockB.lock();
            try {
                ctx.info("t2 拿到 lockB，准备拿 lockA");
                sleepQuietly(200);
                if (!lockA.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    ctx.warn("t2 等 lockA 超时，主动放弃");
                    return;
                }
                try {
                    ctx.info("t2 同时持有两把锁（不会发生）");
                } finally {
                    lockA.unlock();
                }
            } catch (InterruptedException e) {
                ctx.warn("t2 被中断，放弃等待");
                Thread.currentThread().interrupt();
            } finally {
                lockB.unlock();
            }
        }, "deadlock-holder-2");

        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        ctx.step("② 等死锁形成，然后检测");
        boolean formed = ctx.await("死锁形成", 3000,
                () -> !ThreadInspector.deadlocks().get("deadlocks").equals(List.of()));
        ctx.info("死锁已形成：%s", formed);

        Map<String, Object> report = ThreadInspector.deadlocks();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) report.get("deadlocks");
        ctx.verify("检测到死锁", !groups.isEmpty());

        if (!groups.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> threads = (List<Map<String, Object>>) groups.get(0).get("threads");
            ctx.error("死锁线程数：%d", threads.size());
            for (Map<String, Object> thread : threads) {
                ctx.info("  %s（%s）", thread.get("name"), thread.get("state"));
                ctx.info("    等待锁：%s", thread.get("lockName"));
                ctx.info("    锁持有者：%s", thread.get("lockOwnerName"));
                @SuppressWarnings("unchecked")
                List<String> stack = (List<String>) thread.get("stack");
                if (stack != null && !stack.isEmpty()) {
                    ctx.info("    栈顶：%s", stack.get(0));
                }
            }
        }

        ctx.step("③ 一键诊断也会报出来");
        Map<String, Object> diagnosis = Diagnostics.diagnose();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) diagnosis.get("findings");
        boolean reported = findings.stream()
                .anyMatch(f -> String.valueOf(f.get("title")).contains("死锁"));
        ctx.verify("诊断报告包含死锁项", reported);
        findings.stream()
                .filter(f -> String.valueOf(f.get("title")).contains("死锁"))
                .findFirst()
                .ifPresent(f -> {
                    ctx.info("诊断结论：%s", f.get("title"));
                    ctx.info("处置建议：%s", f.get("detail"));
                });

        ctx.step("④ 解除死锁：中断可中断锁");
        t1.interrupt();
        t2.interrupt();
        t1.join(2000);
        t2.join(2000);
        ctx.verify("两个线程都已退出", !t1.isAlive() && !t2.isAlive());

        boolean cleared = ctx.await("死锁消失", 2000,
                () -> ((List<?>) ThreadInspector.deadlocks().get("deadlocks")).isEmpty());
        ctx.verify("死锁已被解除", cleared);

        ctx.step("⑤ 生产建议");
        ctx.info("定时巡检 findDeadlockedThreads()，发现即告警 + dump 现场");
        ctx.info("synchronized 死锁无法解除，只能重启 —— 所以核心链路优先用 ReentrantLock");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
