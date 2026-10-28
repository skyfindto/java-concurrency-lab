package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 初级 12：死锁 —— 四个必要条件，以及三种解法。
 *
 * <p>死锁的经典场景：<b>两个线程以相反顺序获取两把锁</b>。
 * A 拿着锁 1 等锁 2，B 拿着锁 2 等锁 1，谁也不放手，永远等下去。
 *
 * <p>本用例用 ReentrantLock（而不是 synchronized）来演示，因为可中断锁能被我们
 * 在检测到死锁后打破，避免给 JVM 留下永远阻塞的线程。顺便说明一个生产事实：
 * <b>synchronized 造成的死锁，除了重启进程没有任何办法解除</b>。
 */
public class B12_Deadlock extends DemoBase {

    public B12_Deadlock() {
        super("basic-12-deadlock", Level.BASIC,
                "死锁：四个必要条件与三种解法",
                "现场构造死锁 → ThreadMXBean 检测 → 固定顺序/超时锁修复");
    }

    @Override
    public String knowledge() {
        return """
                ## 四个必要条件（缺一不可，破坏任意一个即可）
                1. 互斥：资源同一时刻只能被一个线程占用；
                2. 占有且等待：拿着一个资源去等另一个；
                3. 不可剥夺：别人不能强行抢走你手里的资源；
                4. 循环等待：A 等 B，B 等 A，形成环。

                ## 工程上最有效的解法
                - 破坏「循环等待」：**全局统一加锁顺序**（按 id/哈希/业务层级排序后再加锁）。
                - 破坏「占有且等待」：用 tryLock(timeout)，拿不到就释放已持有的、重试或失败。
                - 破坏「不可剥夺」：用可中断锁 lockInterruptibly()，超时/中断时释放。
                - 减少锁：用无锁结构、不可变对象、单线程封闭，从源头消灭共享。

                ## 检测
                - ThreadMXBean.findDeadlockedThreads()：JVM 内置的死锁检测（monitor + ownable synchronizer）；
                - jstack <pid> 输出里的 "Found one Java-level deadlock"；
                - 生产建议接入监控：定时检测 + 告警，别等用户投诉。

                ## 死锁 vs 活锁 vs 饥饿
                - 死锁：互相等待，永远不动；
                - 活锁：都在动，但一直互相谦让，无法推进（如都重试后再次冲突）；
                - 饥饿：某个线程长期抢不到资源（非公平锁 + 高优先级线程）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 加锁顺序必须写成文档/常量，Code Review 时重点检查（同一组锁是否总按相同顺序获取）。
                - 锁里不要调用外部服务、不要发 MQ、不要做可能长时间阻塞的事。
                - 用 tryLock(200ms) 而不是 lock()：拿不到可以降级，至少不会永久卡死。
                - 定时用 ThreadMXBean 检测死锁并告警，阈值 1 次就报。
                - 使用线程池时，池内任务再去等池内任务的结果（父子任务同池），也会「死锁」。
                - 数据库死锁是另一回事：靠统一更新顺序 + 短事务 + 死锁重试解决。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 反例：两个线程以相反顺序拿两把锁");
        ReentrantLock lock1 = new ReentrantLock();
        ReentrantLock lock2 = new ReentrantLock();

        Thread t1 = new Thread(() -> {
            lock1.lock();
            try {
                ctx.info("t1 拿到 lock1，准备去拿 lock2");
                Thread.sleep(120);
                // 用 tryLock(超时) 而不是无限等待：既是死锁防护的正面示范，
                // 也保证用例被取消时不会在 JVM 里留下永久死锁的线程
                if (!lock2.tryLock(5, TimeUnit.SECONDS)) {
                    ctx.warn("t1 等 lock2 超时，主动放弃（这就是 tryLock 的价值）");
                    return;
                }
                try {
                    ctx.info("t1 同时持有两把锁（不会发生）");
                } finally {
                    lock2.unlock();
                }
            } catch (InterruptedException e) {
                ctx.warn("t1 被中断，释放 lock1 退出");
                Thread.currentThread().interrupt();
            } finally {
                lock1.unlock();
            }
        }, "deadlock-t1");

        Thread t2 = new Thread(() -> {
            lock2.lock();
            try {
                ctx.info("t2 拿到 lock2，准备去拿 lock1");
                Thread.sleep(120);
                if (!lock1.tryLock(5, TimeUnit.SECONDS)) {
                    ctx.warn("t2 等 lock1 超时，主动放弃");
                    return;
                }
                try {
                    ctx.info("t2 同时持有两把锁（不会发生）");
                } finally {
                    lock1.unlock();
                }
            } catch (InterruptedException e) {
                ctx.warn("t2 被中断，释放 lock2 退出");
                Thread.currentThread().interrupt();
            } finally {
                lock2.unlock();
            }
        }, "deadlock-t2");

        t1.start();
        t2.start();
        t1.join(2000);
        t2.join(2000);

        ctx.step("② 用 JVM 自带的 ThreadMXBean 检测死锁");
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = mx.findDeadlockedThreads();
        boolean detected = ctx.await("检测到死锁", 2000, () -> mx.findDeadlockedThreads() != null);
        deadlocked = mx.findDeadlockedThreads();
        if (detected && deadlocked != null) {
            ctx.error("检测到 %d 个死锁线程：", deadlocked.length);
            ThreadInfo[] infos = mx.getThreadInfo(deadlocked, true, true);
            for (ThreadInfo info : infos) {
                ctx.info("  %s  state=%s", info.getThreadName(), info.getThreadState());
                ctx.info("    等待的锁: %s", info.getLockName());
                ctx.info("    锁被谁持有: %s", info.getLockOwnerName());
                StackTraceElement[] stack = info.getStackTrace();
                for (int i = 0; i < Math.min(3, stack.length); i++) {
                    ctx.info("    at %s", stack[i]);
                }
            }
            ctx.verify("ThreadMXBean 能检测出死锁", true);
        }

        ctx.step("③ 解毒：中断可以打破 ReentrantLock 的死锁（synchronized 不行）");
        t1.interrupt();
        t2.interrupt();
        t1.join(2000);
        t2.join(2000);
        ctx.verify("两个线程都已退出", !t1.isAlive() && !t2.isAlive());
        ctx.info("这也是生产推荐 ReentrantLock 的原因之一：可中断、可超时");
        ctx.info("注意：synchronized 死锁无法从外部解除，只能重启进程");

        ctx.step("④ 正确解法一：全局统一加锁顺序（所有人都先 lock1 再 lock2）");
        ReentrantLock ordered = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        Runnable orderedTask = () -> {
            ordered.lock();
            try {
                sleepQuietly(100);
                second.lock();
                try {
                    ctx.info("%s 按顺序拿到两把锁并完成", Thread.currentThread().getName());
                } finally {
                    second.unlock();
                }
            } finally {
                ordered.unlock();
            }
        };
        long begin = System.nanoTime();
        Thread o1 = new Thread(orderedTask, "ordered-1");
        Thread o2 = new Thread(orderedTask, "ordered-2");
        o1.start();
        o2.start();
        o1.join(3000);
        o2.join(3000);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.verify("固定加锁顺序不会死锁，两个线程都完成", !o1.isAlive() && !o2.isAlive());
        ctx.info("耗时 %d ms（串行执行但安全）", cost);

        ctx.step("⑤ 正确解法二：tryLock 带超时，拿不到就放弃");
        ReentrantLock busy = new ReentrantLock();
        Thread holder = new Thread(() -> {
            busy.lock();
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                busy.unlock();
            }
        }, "busy-holder");
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(50);
        boolean acquired = busy.tryLock(200, TimeUnit.MILLISECONDS);
        ctx.info("tryLock(200ms) 结果 = %s（锁被占用，拿不到就快速失败，而不是死等）", acquired);
        ctx.verify("tryLock 超时后返回 false", !acquired);
        if (acquired) {
            busy.unlock();
        }
        holder.join(2000);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
