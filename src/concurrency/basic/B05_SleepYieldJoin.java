package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;

/**
 * 初级 05：sleep / yield / join / priority 的正确用法。
 *
 * <p>这四个方法经常被混用，但它们解决的是完全不同的问题：
 * <ul>
 *   <li>sleep：我就是不想跑，让出 CPU（<b>不释放锁</b>）；</li>
 *   <li>yield：我跑够了，愿意让出这一轮时间片（只是建议，JVM 可以不理会）；</li>
 *   <li>join：等另一个线程结束，是最朴素的「线程间协作」；</li>
 *   <li>priority：优先级提示，跨平台不可靠，不要用它做业务逻辑。</li>
 * </ul>
 */
public class B05_SleepYieldJoin extends DemoBase {

    public B05_SleepYieldJoin() {
        super("basic-05-sleep-yield-join", Level.BASIC,
                "sleep / yield / join / 优先级",
                "最常用的四个 Thread 方法，坑最多，尤其「sleep 不释放锁」");
    }

    @Override
    public String knowledge() {
        return """
                ## Thread.sleep(ms)
                - 当前线程进入 TIMED_WAITING，让出 CPU 给别的线程；
                - **不会释放已持有的 synchronized 锁**（这是最常见的误解）；
                - 精度依赖操作系统时钟，通常有几毫秒到几十毫秒的误差，别拿它做精确定时；
                - 被中断时抛 InterruptedException。

                ## Thread.yield()
                - 只是给调度器一个「我可以让出」的提示，线程仍处于 RUNNABLE；
                - 在 Linux/Windows 上效果差异很大，几乎不可观测，不要依赖它做同步。

                ## Thread.join()
                - join() 等价于「等待目标线程终止」，内部就是 wait/notifyAll；
                - join(ms) 带超时，超时后目标线程仍在运行，调用者继续往下走；
                - 生产环境优先用 CountDownLatch / CompletableFuture 做多线程协同，语义更清晰。

                ## 线程优先级
                - 1~10，默认 5；只是给 OS 的提示，Linux 下基本无效（还依赖 nice 值）；
                - 永远不要用优先级来保证执行顺序或正确性。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 不要在 synchronized 块里 sleep：你睡了，别人全在门口排队。
                - 需要「等一会儿再重试」用 ScheduledExecutorService，而不是 sleep 轮询。
                - join() 不设超时是隐患：目标线程若卡死，调用者一起卡死。至少 join(timeout)。
                - 定时任务不要用 sleep 实现，会漂移（每次执行时间累加），要用调度器。
                - 用 TimeUnit.SECONDS.sleep(1) 比 Thread.sleep(1000) 更可读，且不易算错。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① sleep 的精度：请求 200ms，实际可能多一点");
        long begin = System.nanoTime();
        Thread.sleep(200);
        long elapsed = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("请求 sleep 200ms，实际耗时 %d ms", elapsed);
        ctx.verify("sleep 至少睡够了请求时长", elapsed >= 200);

        ctx.step("② 关键：sleep 不释放锁，其他线程只能在门口干等");
        Object lock = new Object();
        CountDownLatch inLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                inLock.countDown();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "sleep-holder");
        holder.setDaemon(true);
        holder.start();
        inLock.await();

        Thread waiter = new Thread(() -> {
            long t0 = System.nanoTime();
            synchronized (lock) {
                ctx.info("等待者拿到锁，共等了 %d ms", (System.nanoTime() - t0) / 1_000_000);
            }
        }, "sleep-waiter");
        waiter.setDaemon(true);
        long t0 = System.nanoTime();
        waiter.start();
        waiter.join(3000);
        long waited = (System.nanoTime() - t0) / 1_000_000;
        ctx.info("等待者实际被阻塞了 %d ms（说明 sleep 期间锁没释放）", waited);
        ctx.verify("sleep 期间持有锁", waited >= 300);

        ctx.step("③ yield 只是建议：线程状态仍是 RUNNABLE");
        Thread yielder = new Thread(() -> Thread.yield(), "yield-demo");
        yielder.start();
        yielder.join();
        ctx.info("yield() 不会让线程进入等待状态，调度器可以立刻又选中它");

        ctx.step("④ join(timeout)：等，但只等这么久");
        Thread slow = new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "slow-task");
        slow.setDaemon(true);
        slow.start();
        slow.join(100);
        ctx.verify("join(100) 超时返回时线程还活着", slow.isAlive());
        ctx.info("join(100) 返回，slow-task 状态 = %s", slow.getState());
        slow.join();
        ctx.verify("无参 join 会一直等到线程结束", slow.getState() == Thread.State.TERMINATED);

        ctx.step("⑤ 优先级：只是提示，不要依赖它");
        Thread low = new Thread(() -> { }, "low-priority");
        low.setPriority(Thread.MIN_PRIORITY);
        Thread high = new Thread(() -> { }, "high-priority");
        high.setPriority(Thread.MAX_PRIORITY);
        ctx.info("low.priority=%d, high.priority=%d，但谁先跑由 OS 调度器决定",
                low.getPriority(), high.getPriority());
        ctx.verify("优先级范围是 1..10", low.getPriority() == 1 && high.getPriority() == 10);
    }
}
