package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中级 11：CountDownLatch / CyclicBarrier / Phaser —— 线程间的「集合点」。
 *
 * <p>三者都用于多线程协同，但方向不同：
 * <ul>
 *   <li>CountDownLatch：<b>一个线程等 N 个线程</b>（主线程等所有子任务完成），一次性；</li>
 *   <li>CyclicBarrier：<b>N 个线程互相等</b>，到齐了才一起继续，可循环使用；</li>
 *   <li>Phaser：更灵活的分阶段同步，支持动态注册/注销参与方。</li>
 * </ul>
 */
public class M11_LatchBarrierPhaser extends DemoBase {

    public M11_LatchBarrierPhaser() {
        super("juc-11-latch-barrier-phaser", Level.INTERMEDIATE,
                "CountDownLatch / CyclicBarrier / Phaser",
                "等所有人到齐：三种集合点的区别与选择");
    }

    @Override
    public String knowledge() {
        return """
                ## CountDownLatch（一次性）
                - `new CountDownLatch(n)`，`countDown()` 减一，`await()` 等归零；
                - **不能重置**，用完就废；
                - 常见用法：主线程等待 N 个并行任务全部完成；模拟并发起跑（先 await，再一起冲）。

                ## CyclicBarrier（可循环）
                - `new CyclicBarrier(n, action)`，每个线程 `await()`，第 n 个到达时执行 action，然后全部放行；
                - 可以循环使用（回合制）；一个线程超时/被中断会让 barrier 破裂（BrokenBarrierException）；
                - 常见用法：分阶段计算，每阶段结束同步一次。

                ## Phaser（最灵活）
                - 支持动态 `register()` / `arriveAndDeregister()`；
                - `arriveAndAwaitAdvance()` 相当于「到齐再继续」；
                - 适合「参与者数量不固定」的分阶段任务。

                ## 选择建议
                - 只是等所有任务结束 → CountDownLatch（或 CompletableFuture.allOf）；
                - 多轮、每轮都要同步 → CyclicBarrier；
                - 参与方动态变化 → Phaser。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 主线程 await 一定要带超时：`latch.await(30, SECONDS)`，否则子任务卡死你也跟着卡死。
                - CountDownLatch 的 countDown 必须写在 finally，否则异常会让所有等待者永久阻塞。
                - CyclicBarrier 的 await 要处理 BrokenBarrierException / TimeoutException。
                - 用 CompletableFuture.allOf 往往比裸 CountDownLatch 更简洁（还能拿结果）。
                - 别把 latch 当锁用：它只做「计数到零」的同步，不保护任何数据。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        latchDemo(ctx);
        barrierDemo(ctx);
        phaserDemo(ctx);
    }

    private void latchDemo(DemoContext ctx) throws Exception {
        ctx.step("① CountDownLatch：主线程等 4 个并行任务完成");
        int workers = 4;
        CountDownLatch done = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        long begin = System.nanoTime();

        for (int i = 1; i <= workers; i++) {
            int id = i;
            new Thread(() -> {
                try {
                    start.await();       // 等发令枪，让 4 个线程同时起跑
                    sleepQuietly(200);
                    ctx.info("worker-%d 完成", id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "latch-worker-" + i).start();
        }

        start.countDown();
        boolean finished = done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.verify("4 个任务全部完成", finished);
        ctx.info("4 个 200ms 的任务并行完成，总耗时 %d ms", cost);
        ctx.verify("并行而非串行", cost < 600);

        ctx.step("CountDownLatch 是一次性的：计数到 0 后不能重置");
        ctx.info("需要重复使用请用 CyclicBarrier 或新建一个 latch");
    }

    private void barrierDemo(DemoContext ctx) throws Exception {
        ctx.step("② CyclicBarrier：3 个线程每轮集合，集合后一起继续（可复用）");
        AtomicInteger round = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(3, () ->
                ctx.info("第 %d 轮：3 个线程到齐，执行 barrierAction", round.incrementAndGet()));

        CountDownLatch allDone = new CountDownLatch(3);
        for (int i = 1; i <= 3; i++) {
            int id = i;
            new Thread(() -> {
                try {
                    for (int r = 1; r <= 2; r++) {
                        sleepQuietly(100);
                        ctx.info("worker-%d 完成第 %d 轮工作，到达栅栏", id, r);
                        barrier.await();
                        ctx.info("worker-%d 通过第 %d 轮栅栏", id, r);
                    }
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            }, "barrier-worker-" + i).start();
        }
        allDone.await(10, java.util.concurrent.TimeUnit.SECONDS);
        ctx.verifyEquals("跑了两轮，barrierAction 执行两次", 2, round.get());
        ctx.verify("栅栏可复用，不是一次性的", true);
    }

    private void phaserDemo(DemoContext ctx) throws Exception {
        ctx.step("③ Phaser：分阶段同步，支持动态参与方");
        Phaser phaser = new Phaser(1); // 先注册主线程，再启动 worker
        AtomicInteger phaseReached = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(3);

        for (int i = 1; i <= 3; i++) {
            int id = i;
            phaser.register();
            new Thread(() -> {
                try {
                    ctx.info("worker-%d 开始第一阶段", id);
                    int phase = phaser.arriveAndAwaitAdvance(); // 等所有人完成第一阶段
                    ctx.info("worker-%d 进入第二阶段（phase=%d）", id, phase);
                    phaseReached.incrementAndGet();
                } finally {
                    phaser.arriveAndDeregister();
                    done.countDown();
                }
            }, "phaser-worker-" + i).start();
        }

        sleepQuietly(50);
        phaser.arriveAndDeregister(); // 主线程退出参与方，由 3 个 worker 推进阶段
        boolean allArrived = done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        ctx.verify("3 个 worker 都通过了阶段栅栏", allArrived);
        ctx.verifyEquals("3 个 worker 都到达了第二阶段", 3, phaseReached.get());
        ctx.verify("Phaser 已终止（没有参与方）", phaser.isTerminated());
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
