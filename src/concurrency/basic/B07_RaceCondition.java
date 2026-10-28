package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 初级 07：竞态条件 —— {@code count++} 到底发生了什么。
 *
 * <p>这是所有并发 Bug 的源头：<b>读-改-写</b> 不是原子操作。
 * 两个线程同时读到 100，各自加 1，各自写回 101，一次自增就凭空消失了。
 *
 * <p>用三种写法对比同一个 80 万次自增：普通变量、AtomicInteger、synchronized。
 * 你会亲眼看到普通变量丢了多少次。
 */
public class B07_RaceCondition extends DemoBase {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 100_000;

    public B07_RaceCondition() {
        super("basic-07-race-condition", Level.BASIC,
                "竞态条件：count++ 不是原子操作",
                "8 个线程各加 10 万次，普通变量到底丢了多少？");
    }

    @Override
    public String knowledge() {
        return """
                ## count++ 的字节码本质（三个步骤）
                    getfield  count      // 1. 从堆里读到线程栈
                    iconst_1
                    iadd                 // 2. 寄存器里 +1
                    putfield  count      // 3. 写回堆
                两个线程可能同时做完第 1 步（都读到 100），然后各自写回 101，丢失一次自增。

                ## 三种修复思路
                - 互斥：synchronized / ReentrantLock，把「读-改-写」包成临界区；
                - 原子类：AtomicInteger 用 CAS（compareAndSwap）硬件指令，无锁但会自旋；
                - 避免共享：把计数拆成每线程一个 LongAdder Cell，最后 sum（空间换竞争）。

                ## 不只是 i++
                check-then-act（先判断后操作）也是竞态：
                    if (!map.containsKey(k)) map.put(k, v);   // 两步之间存在窗口
                正确写法：map.computeIfAbsent(k, ...) / putIfAbsent。

                ## 复合操作清单
                - i++ / i-- / i += n
                - 先查后改（containsKey + put、get + set）
                - 懒初始化（if (instance == null) instance = new ...）
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 共享可变状态是万恶之源。优先用「不可变对象 + 线程封闭」消除共享。
                - 计数场景直接用 AtomicLong / LongAdder，别自己 synchronized 一个 int。
                - Map 的「判断再写入」用 computeIfAbsent / putIfAbsent / merge，它们内部是原子的。
                - 单例懒加载用静态内部类或枚举，别手写双重检查（除非你很清楚 volatile 的作用）。
                - 写并发代码时先问：这段代码被两个线程同时执行，会不会出错？而不是「应该不会」。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int expected = THREADS * PER_THREAD;
        ctx.info("场景：%d 个线程，每个线程自增 %,d 次，期望结果 = %,d", THREADS, PER_THREAD, expected);

        int[] unsafe = new int[1];
        AtomicInteger atomic = new AtomicInteger();
        long[] locked = new long[1];
        Object monitor = new Object();

        ExecutorService pool = ctx.executor("race-pool", THREADS, THREADS, 64);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            pool.execute(() -> {
                try {
                    startGate.await(); // 让所有线程尽量同时起跑，放大竞争
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < PER_THREAD; i++) {
                    unsafe[0]++;                       // ① 有竞态
                    atomic.incrementAndGet();          // ② CAS，无锁原子
                    synchronized (monitor) {
                        locked[0]++;                   // ③ 互斥，正确但有开销
                    }
                }
                done.countDown();
            });
        }

        ctx.step("三个计数器同时开跑……");
        long begin = System.nanoTime();
        startGate.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.verify("所有线程在 30s 内完成", finished);
        ctx.info("总耗时 %d ms", cost);

        ctx.step("结果对比");
        ctx.info("普通 int[]          = %,d（丢失 %,d 次）", unsafe[0], expected - unsafe[0]);
        ctx.info("AtomicInteger      = %,d", atomic.get());
        ctx.info("synchronized 计数  = %,d", locked[0]);

        ctx.verify("普通变量大概率丢更新（< 期望值）", unsafe[0] <= expected);
        ctx.verifyEquals("AtomicInteger 精确等于期望值", expected, atomic.get());
        ctx.verifyEquals("synchronized 精确等于期望值", (long) expected, locked[0]);

        if (unsafe[0] == expected) {
            ctx.warn("这次没丢更新属于侥幸（线程少/机器核少），并不代表代码是安全的");
        } else {
            ctx.ok("亲眼看到丢失 %,d 次自增：这就是竞态条件", expected - unsafe[0]);
        }

        ctx.step("补充：volatile 也救不了 i++，它只保证可见性，不保证原子性（见 basic-11）");
    }
}
