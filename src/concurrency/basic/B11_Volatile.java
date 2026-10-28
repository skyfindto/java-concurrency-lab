package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 初级 11：volatile 的语义与双重检查锁（DCL）单例。
 *
 * <p>volatile 只有两个作用：<b>保证可见性</b>、<b>禁止指令重排序</b>。
 * 它<b>不保证原子性</b>，所以 volatile 修饰的计数器依然会丢更新。
 *
 * <p>本用例做三个实验：volatile 计数丢更新、DCL 单例、volatile 状态标志。
 */
public class B11_Volatile extends DemoBase {

    /** 用 volatile 修饰计数器，验证它依然不原子。 */
    private static final class VolatileCounter {
        volatile int value;
    }

    /** 正确的 DCL 单例：instance 必须 volatile。 */
    private static final class Singleton {
        private static volatile Singleton instance;
        private static int initCount;

        private Singleton() {
            initCount++;
        }

        static Singleton getInstance() {
            Singleton local = instance;      // 第一次检查（不加锁，读 volatile）
            if (local == null) {
                synchronized (Singleton.class) {
                    local = instance;        // 第二次检查（可能已被别的线程创建）
                    if (local == null) {
                        local = new Singleton(); // 分配内存 → 初始化 → 赋值
                        instance = local;
                    }
                }
            }
            return local;
        }
    }

    public B11_Volatile() {
        super("basic-11-volatile", Level.BASIC,
                "volatile 的语义与 DCL 单例",
                "可见性 ≠ 原子性：volatile 计数器照样丢更新");
    }

    @Override
    public String knowledge() {
        return """
                ## volatile 做了两件事
                1. 可见性：写 volatile 变量会立刻刷新到主内存，读会强制从主内存读；
                   底层是内存屏障（LoadLoad/StoreStore/LoadStore/StoreLoad）。
                2. 有序性：禁止 volatile 写之前的操作被重排到写之后，禁止 volatile 读之后的操作被重排到读之前。

                ## volatile 不做什么
                - 不保证原子性：`volatile int i; i++` 依然是「读-改-写」三步。
                - 不保证多个变量之间的原子性：两个 volatile 变量的更新不是原子的。
                - 不加锁：不会阻塞，也没有互斥性。

                ## DCL 为什么要 volatile
                `instance = new Singleton()` 不是原子的，分三步：
                    1. 分配内存
                    2. 调用构造器初始化
                    3. 把引用赋给 instance
                没有 volatile 时，CPU/JIT 可能把 3 排到 2 之前。此时另一个线程在第一次检查时
                看到 instance != null，直接返回 —— 拿到一个【构造了一半】的对象。
                volatile 禁止了这个重排序。

                ## 什么时候用 volatile
                - 状态标志位（volatile boolean running）；
                - 「先写数据、再置 ready」的安全发布（配合 HB 传递性）；
                - DCL 单例的 instance。
                其他场景优先用原子类或锁。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 用「停止标志」控制线程生命周期时，写成 volatile boolean 或用 AtomicBoolean。
                - 别用 volatile 做计数器：AtomicInteger / LongAdder / 锁才对。
                - 单例优先用「静态内部类」或「枚举」，天然线程安全且延迟加载，比手写 DCL 稳。
                - 如果一个共享变量需要和别的变量保持一致性，volatile 不够，必须加锁。
                - 读多写少、只做标志位的场景，volatile 性能最好（无锁无阻塞）。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① volatile 计数器：8 个线程各加 5 万次，仍然会丢更新");
        VolatileCounter counter = new VolatileCounter();
        int threads = 8;
        int perThread = 50_000;
        int expected = threads * perThread;

        ExecutorService pool = ctx.executor("volatile-pool", threads, threads, 64);
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
                for (int j = 0; j < perThread; j++) {
                    counter.value++; // volatile 只保证可见，不保证原子
                }
                done.countDown();
            });
        }
        gate.countDown();
        done.await();
        ctx.info("期望 %,d，实际 %,d，丢失 %,d 次", expected, counter.value, expected - counter.value);
        ctx.verify("volatile 不能保证原子性（结果 ≤ 期望值）", counter.value <= expected);

        ctx.step("② volatile 状态标志：优雅停止的正确打开方式");
        CountDownLatch started = new CountDownLatch(1);
        AtomicState state = new AtomicState();
        Thread worker = new Thread(() -> {
            started.countDown();
            while (state.running) {
                Thread.onSpinWait();
            }
            ctx.info("工作线程看到 running=false，干净退出");
        }, "volatile-flag-worker");
        worker.start();
        started.await();
        ctx.sleep(50);
        state.running = false;
        worker.join(2000);
        ctx.verify("volatile 标志位可以可靠地停止线程", !worker.isAlive());

        ctx.step("③ DCL 单例：多线程并发获取，必须是同一个实例");
        ExecutorService getters = ctx.fixedPool("singleton-getter", 8);
        CountDownLatch getGate = new CountDownLatch(1);
        java.util.Set<Singleton> instances = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Future<?>[] futures = new Future<?>[16];
        for (int i = 0; i < futures.length; i++) {
            futures[i] = getters.submit(() -> {
                getGate.await();
                instances.add(Singleton.getInstance());
                return null;
            });
        }
        getGate.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        ctx.info("16 个线程拿到 %d 个不同实例（构造器执行了 %d 次）",
                instances.size(), Singleton.initCount);
        ctx.verifyEquals("DCL 单例只被创建一次", 1, Singleton.initCount);
        ctx.verifyEquals("所有线程拿到同一个实例", 1, instances.size());

        ctx.step("④ 去掉 volatile 会怎样？");
        ctx.warn("没有 volatile 时，instance = new Singleton() 的三步可能重排");
        ctx.warn("别的线程会看到「非 null 但构造未完成」的对象 —— 这种 Bug 概率极低、排查极难");
        ctx.info("所以：DCL 的 instance 字段【必须】volatile");
    }

    /** 独立的 volatile 标志持有者，避免和用例实例状态耦合。 */
    private static final class AtomicState {
        volatile boolean running = true;
    }
}
