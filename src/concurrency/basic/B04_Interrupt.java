package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

/**
 * 初级 04：中断机制 —— Java 唯一的「协作式取消」。
 *
 * <p>Java 没有安全的强制终止线程的手段（stop() 已废弃，suspend() 已废弃）。
 * 唯一正确的姿势是：<b>线程 A 给线程 B 发一个信号（interrupt），线程 B 在安全点自己决定退出</b>。
 *
 * <p>三个极易混淆的 API：
 * <ul>
 *   <li>{@code interrupt()}：设置中断标志；如果目标线程阻塞在 sleep/wait/join 上，会抛 InterruptedException 并【清除】标志；</li>
 *   <li>{@code isInterrupted()}：只读，不清除；</li>
 *   <li>{@code Thread.interrupted()}：读并【清除】标志，容易踩坑。</li>
 * </ul>
 */
public class B04_Interrupt extends DemoBase {

    public B04_Interrupt() {
        super("basic-04-interrupt", Level.BASIC,
                "中断机制：线程之间的「请停下来」",
                "interrupt / isInterrupted / InterruptedException 到底谁清标志位");
    }

    @Override
    public String knowledge() {
        return """
                ## 中断不是「杀死线程」
                interrupt() 只是把目标线程的中断标志置为 true（一个 volatile 字段），
                线程是否响应完全取决于它自己是否检查标志 / 是否阻塞在可中断方法上。

                ## 两种响应方式
                1. 阻塞中被中断：sleep / wait / join / Lock.lockInterruptibly / 阻塞队列 take
                   会抛 InterruptedException，并且【清除】中断标志。
                2. 运行中轮询：用 while (!Thread.currentThread().isInterrupted()) 检查标志，主动退出。

                ## 为什么 catch 到 InterruptedException 后要再 interrupt() 一次？
                因为异常抛出时标志已经清掉了。如果你的方法无法处理（不能向上抛），
                必须恢复标志，让上层调用者还能感知到「该停了」。这叫「中断状态传递」。
                千万别 catch 之后什么都不做，这是最经典的中断吞噬。

                ## 不可中断的场景
                - 普通 IO（Socket 读）不响应中断，需要关闭底层资源来打断；
                - synchronized 等锁不可中断，改用 ReentrantLock.lockInterruptibly()；
                - CPU 密集的纯计算循环，只有在检查标志的地方才能退出。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 线程池的任务要响应中断，否则 shutdownNow() 也停不掉它，会导致优雅停机失败。
                - 捕获 InterruptedException 后：要么继续向上抛，要么 Thread.currentThread().interrupt() 恢复标志。
                - 不要在 catch 块里只打一行日志就吞掉。
                - 用 Thread.currentThread().isInterrupted() 做循环条件，而不是自己维护一个 boolean（除非能保证 volatile）。
                - 业务代码不要调用 Thread.interrupted()，它会静默清掉标志。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 轮询标志：线程自己检查、自己退出（协作式取消）");
        Thread cooperative = new Thread(() -> {
            long spins = 0;
            while (!Thread.currentThread().isInterrupted()) {
                spins++;
            }
            ctx.info("发现中断标志，主动退出循环，共自旋 %d 次", spins);
        }, "cooperative-worker");
        cooperative.start();
        ctx.sleep(100);
        ctx.info("主线程调用 cooperative.interrupt()");
        cooperative.interrupt();
        cooperative.join(2000);
        ctx.verify("协作式取消成功，线程已终止", cooperative.getState() == Thread.State.TERMINATED);

        ctx.step("② 阻塞中被中断：抛 InterruptedException，且标志被清除");
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(10_000);
                ctx.warn("没有被中断？不符合预期");
            } catch (InterruptedException e) {
                ctx.info("捕获 InterruptedException，此刻 isInterrupted=%s（标志已被清除）",
                        Thread.currentThread().isInterrupted());
                Thread.currentThread().interrupt();
                ctx.info("恢复中断标志后 isInterrupted=%s（交给上层继续处理）",
                        Thread.currentThread().isInterrupted());
            }
        }, "sleeping-worker");
        sleeper.start();
        ctx.sleep(100);
        sleeper.interrupt();
        sleeper.join(2000);
        ctx.verify("阻塞线程被中断后终止", sleeper.getState() == Thread.State.TERMINATED);

        ctx.step("③ isInterrupted 与 interrupted 的区别（演示完会清空标志，避免污染后续用例）");
        Thread.currentThread().interrupt();
        ctx.info("刚中断主线程：isInterrupted=%s", Thread.currentThread().isInterrupted());
        ctx.info("调用 Thread.interrupted() = %s（读取并清除）", Thread.interrupted());
        ctx.info("再次 isInterrupted=%s", Thread.currentThread().isInterrupted());
        ctx.verify("interrupted() 会清除标志", !Thread.currentThread().isInterrupted());

        ctx.step("④ 对比：被吞掉中断的线程，shutdownNow 也救不回来");
        Thread swallower = new Thread(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                // 错误示范：吞掉中断，不恢复标志
                ctx.warn("错误示范：捕获后吞掉中断，继续装作没事");
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ctx.warn("这个线程在被中断后还多活了 300ms —— 真实故障里可能是几分钟");
        }, "interrupt-swallower");
        swallower.start();
        ctx.sleep(50);
        swallower.interrupt();
        swallower.join(2000);

        ctx.step("结论：中断是「请求」，不是「命令」，任务必须配合才能停得干净");
    }
}
