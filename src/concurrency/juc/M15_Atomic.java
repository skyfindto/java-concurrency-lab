package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 中级 15：原子类与 LongAdder —— 无锁并发的两块基石。
 *
 * <p>原子类靠 CPU 的 CAS 指令（compare-and-swap）实现无锁并发；
 * LongAdder 更进一步，用「分段累加」把竞争分散到多个 Cell 上，高并发下快一个数量级。
 *
 * <p>本用例包含一个经典的 ABA 实验，这是所有「无锁栈/队列」实现必须面对的问题。
 */
public class M15_Atomic extends DemoBase {

    public M15_Atomic() {
        super("juc-15-atomic", Level.INTERMEDIATE,
                "原子类、CAS 与 LongAdder",
                "手写 CAS 循环、复现 ABA、实测 LongAdder 的分段优势");
    }

    @Override
    public String knowledge() {
        return """
                ## CAS 是什么
                `compareAndSet(expected, newValue)`：如果当前值 == expected，
                就原子地改成 newValue 并返回 true，否则返回 false。
                底层是一条 CPU 指令（x86 的 `lock cmpxchg`），不需要加锁。

                ## 手写 CAS 自旋
                    int old;
                    do {
                        old = atomic.get();
                    } while (!atomic.compareAndSet(old, old + 1));
                这就是 AtomicInteger.incrementAndGet() 的本质。

                ## ABA 问题
                线程 1 读到值 A；线程 2 把 A 改成 B，又改回 A；
                线程 1 执行 CAS(A → C) 成功，但它以为「什么都没变」。
                对计数器无害，对「链表/栈」这种结构是致命的（可能把已删除的节点接回去）。
                解决：加版本号（AtomicStampedReference）或使用不可变快照。

                ## LongAdder 为什么快
                AtomicLong 所有线程争同一个变量，高并发下 CAS 失败重试严重；
                LongAdder 内部有一个 base + Cell[] 数组，不同线程累加到不同 Cell，
                sum() 时再加起来 —— 用「空间」换「竞争」。
                代价：sum() 不是强一致的瞬时值（并发时可能读到中间状态）。

                ## 常用原子类
                AtomicInteger/Long/Boolean、AtomicReference、AtomicIntegerArray、
                AtomicStampedReference（带版本号）、LongAdder/DoubleAdder（高并发计数）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 计数器优先用 LongAdder（高并发写），需要精确瞬时值再用 AtomicLong。
                - CAS 自旋要设上限或退让（Thread.onSpinWait），否则高竞争下 CPU 空转。
                - 更新多个相关字段时，用 AtomicReference 包一个不可变对象，而不是多个原子变量。
                - ABA 敏感场景（无锁数据结构）必须用版本号或加锁。
                - 原子类不能替代锁：它只保证「单个变量的一次操作」原子，不保证多步逻辑。
                - 统计类场景（QPS、成功率）用 LongAdder，读的时候接受轻微误差。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 手写 CAS 自旋，等价于 incrementAndGet");
        AtomicInteger counter = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            int old;
            do {
                old = counter.get();
            } while (!counter.compareAndSet(old, old + 1));
        }
        ctx.verifyEquals("CAS 自旋 5 次后结果", 5, counter.get());

        ctx.step("② ABA 问题现场复现");
        AtomicReference<String> ref = new AtomicReference<>("A");
        String observed = ref.get();          // 线程 1 读到 A
        ctx.info("线程 1 读到：%s", observed);

        ref.compareAndSet("A", "B");          // 线程 2 改成 B
        ref.compareAndSet("B", "A");          // 线程 2 又改回 A
        ctx.info("线程 2 把 A → B → A，值看起来没变");

        boolean casResult = ref.compareAndSet(observed, "C");
        ctx.info("线程 1 执行 CAS(A → C) = %s", casResult);
        ctx.verify("CAS 成功了，但它并不知道中间发生过 A→B→A", casResult);

        ctx.step("③ 用 AtomicStampedReference 加版本号解决 ABA");
        AtomicStampedReference<String> stamped = new AtomicStampedReference<>("A", 0);
        int stamp = stamped.getStamp();
        String value = stamped.getReference();

        stamped.compareAndSet("A", "B", stamp, stamp + 1);
        stamped.compareAndSet("B", "A", stamp + 1, stamp + 2);

        boolean stampedResult = stamped.compareAndSet(value, "C", stamp, stamp + 1);
        ctx.info("带版本号的 CAS = %s（版本已变，更新失败）", stampedResult);
        ctx.verify("版本号让 ABA 失效", !stampedResult);

        ctx.step("④ LongAdder vs AtomicLong：8 线程各加 20 万次");
        int threads = 8;
        int perThread = 200_000;
        long atomicCost = measure(ctx, threads, perThread, true);
        long adderCost = measure(ctx, threads, perThread, false);
        ctx.info("AtomicLong 耗时 %d ms", atomicCost);
        ctx.info("LongAdder  耗时 %d ms", adderCost);
        ctx.info("并发越高、竞争越激烈，LongAdder 的优势越明显（分段累加）");

        ctx.step("⑤ 正确性验证");
        LongAdder adder = new LongAdder();
        ExecutorService pool = ctx.fixedPool("adder-pool", threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < 10_000; j++) {
                    adder.increment();
                }
                done.countDown();
            });
        }
        gate.countDown();
        done.await();
        ctx.verifyEquals("LongAdder 结果精确", 80_000L, adder.sum());

        ctx.step("结论");
        ctx.info("Atomic* → 单个变量、需要精确值、低到中等竞争");
        ctx.info("LongAdder → 高并发统计，读的时候能接受最终一致");
    }

    /** 同样的累加量，分别用 AtomicLong / LongAdder，返回耗时。 */
    private long measure(DemoContext ctx, int threads, int perThread, boolean useAtomic) throws Exception {
        AtomicLong atomic = new AtomicLong();
        LongAdder adder = new LongAdder();
        ExecutorService pool = ctx.fixedPool(useAtomic ? "atomic-bench" : "adder-bench", threads);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long begin = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    if (useAtomic) {
                        atomic.incrementAndGet();
                    } else {
                        adder.increment();
                    }
                }
                done.countDown();
            });
        }
        gate.countDown();
        done.await();
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.verify(useAtomic ? "AtomicLong 结果精确" : "LongAdder 结果精确",
                (useAtomic ? atomic.get() : adder.sum()) == (long) threads * perThread);
        return cost;
    }
}
