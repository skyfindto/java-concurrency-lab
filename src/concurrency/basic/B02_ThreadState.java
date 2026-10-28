package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;

/**
 * 初级 02：线程的 6 种状态与生命周期。
 *
 * <p>排障时你看到的 jstack 输出，第一行就是 {@code java.lang.Thread.State: WAITING}。
 * 搞清楚 6 个状态分别由什么操作触发，是读懂线程转储的前提。
 *
 * <p>特别容易混淆的一点：
 * <ul>
 *   <li>等 synchronized 锁 → BLOCKED；</li>
 *   <li>等 ReentrantLock / wait() / join() / CountDownLatch → WAITING；</li>
 *   <li>带超时的等待（sleep / wait(t) / join(t)）→ TIMED_WAITING。</li>
 * </ul>
 */
public class B02_ThreadState extends DemoBase {

    public B02_ThreadState() {
        super("basic-02-thread-state", Level.BASIC,
                "线程的 6 种状态与生命周期",
                "NEW / RUNNABLE / BLOCKED / WAITING / TIMED_WAITING / TERMINATED 由谁触发");
    }

    @Override
    public String knowledge() {
        return """
                ## 状态定义（java.lang.Thread.State）
                - NEW：new 出来了，还没 start()。
                - RUNNABLE：可运行。注意 JVM 不区分「就绪」和「运行中」，也在等 IO。
                - BLOCKED：等待进入 synchronized 临界区（等 monitor 锁），或调用了 Object.wait 后想重新进入。
                - WAITING：无限期等待，必须被别人唤醒。wait() / join() / park() / Lock.lock() 都在这里。
                - TIMED_WAITING：带超时的等待。sleep(ms) / wait(ms) / join(ms) / parkNanos。
                - TERMINATED：run() 正常结束或抛出未捕获异常。

                ## 状态迁移
                NEW -(start)-> RUNNABLE
                RUNNABLE -(抢不到 monitor)-> BLOCKED ->(拿到锁)-> RUNNABLE
                RUNNABLE -(wait/lock/join)-> WAITING -(notify/unlock/结束)-> RUNNABLE
                RUNNABLE -(sleep(ms))-> TIMED_WAITING -(超时/被中断)-> RUNNABLE
                任意 -> TERMINATED

                ## 排障意义
                - 大量 BLOCKED：有人在长时间持锁 → 找 lock 的持有者。
                - 大量 WAITING 在同一个方法：多半是连接池耗尽或下游超时。
                - 大量 TIMED_WAITING 在 sleep：代码里有轮询等待，考虑换成事件通知。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 不要用 Thread.stop()（已废弃）：它会在任意字节码位置强行终止，让对象处于不一致状态。
                - 停止线程用「中断 + 标志位」协作式退出（见 basic-04）。
                - 线上排查先看状态分布：BLOCKED 多查锁，WAITING 多查依赖，RUNNABLE 多查死循环和 GC。
                - 线程状态是采样值，单次看到什么不代表一直如此，要连续观察。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① NEW：对象创建了，但还没 start()");
        CountDownLatch gate = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "state-worker");
        worker.setDaemon(true);
        ctx.verifyEquals("start() 之前的状态", Thread.State.NEW, worker.getState());

        ctx.step("② RUNNABLE → WAITING：CountDownLatch.await() 让线程无限期等待");
        worker.start();
        boolean waiting = ctx.await("进入 WAITING", 2000, () -> worker.getState() == Thread.State.WAITING);
        ctx.info("worker 当前状态 = %s", worker.getState());
        ctx.verify("CountDownLatch.await() 触发 WAITING", waiting);

        ctx.step("③ 放行 → TERMINATED：run() 结束");
        gate.countDown();
        worker.join(2000);
        ctx.info("worker 当前状态 = %s", worker.getState());
        ctx.verifyEquals("结束后状态", Thread.State.TERMINATED, worker.getState());

        ctx.step("④ TIMED_WAITING：sleep(ms) 是带超时的等待");
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "state-sleeper");
        sleeper.setDaemon(true);
        sleeper.start();
        boolean timed = ctx.await("进入 TIMED_WAITING", 2000,
                () -> sleeper.getState() == Thread.State.TIMED_WAITING);
        ctx.info("sleeper 当前状态 = %s", sleeper.getState());
        ctx.verify("sleep() 触发 TIMED_WAITING", timed);
        sleeper.join(3000);

        ctx.step("⑤ BLOCKED：等的是 synchronized 的 monitor 锁");
        Object lock = new Object();
        CountDownLatch inMonitor = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                inMonitor.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "lock-holder");
        holder.setDaemon(true);

        Thread contender = new Thread(() -> {
            synchronized (lock) {
                ctx.info("contender 终于拿到锁了");
            }
        }, "lock-contender");
        contender.setDaemon(true);

        holder.start();
        inMonitor.await();
        contender.start();
        boolean blocked = ctx.await("contender 进入 BLOCKED", 2000,
                () -> contender.getState() == Thread.State.BLOCKED);
        ctx.info("contender 当前状态 = %s（锁被 %s 持有）", contender.getState(), holder.getName());
        ctx.verify("争抢 synchronized 锁触发 BLOCKED", blocked);

        release.countDown();
        holder.join(2000);
        contender.join(2000);

        ctx.step("⑥ 记住这条排障映射");
        ctx.info("BLOCKED       → synchronized 锁竞争，找持锁线程");
        ctx.info("WAITING       → wait/join/lock/连接池，等别人叫醒");
        ctx.info("TIMED_WAITING → sleep/带超时的等待，看超时设置是否合理");
        ctx.info("RUNNABLE 且 CPU 高 → 死循环、正则回溯、频繁 GC");
        ctx.verify("6 种状态全部覆盖", true);
    }
}
