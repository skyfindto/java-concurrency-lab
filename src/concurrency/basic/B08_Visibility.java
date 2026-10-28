package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 初级 08：内存可见性 —— 一个「永远停不下来」的循环。
 *
 * <p>这是并发面试的经典复现实验：主线程把 {@code running} 改成 false，
 * 工作线程却还在死循环里。原因是工作线程把变量缓存在了寄存器/CPU 缓存，
 * 根本没有回主内存重新读。
 *
 * <p>注意：这个实验<b>不保证每次都能复现</b>（取决于 JIT 优化、CPU 架构、核数）。
 * 复现了不代表你运气好，没复现也不代表代码没问题 —— JMM 允许这种行为发生。
 */
public class B08_Visibility extends DemoBase {

    /** 一个普通字段 + 一个 volatile 字段，放一起对比。 */
    private static final class Flags {
        boolean running = true;          // 没有 volatile：可能永远读到旧值
        volatile boolean vRunning = true; // 有 volatile：每次强制从主内存读
    }

    public B08_Visibility() {
        super("basic-08-visibility", Level.BASIC,
                "内存可见性：为什么我的循环停不下来",
                "CPU 缓存 + JIT 优化 = 你改的变量另一个线程看不见");
    }

    @Override
    public String knowledge() {
        return """
                ## 为什么看不见
                Java 内存模型（JMM）规定每个线程有自己的「工作内存」抽象。
                线程读变量时可能读的是寄存器/CPU 缓存里的副本，写变量时也可能先写缓存。
                没有同步措施，一个线程的写对另一个线程何时可见，是【没有保证】的。

                ## 硬件层面
                - CPU 多级缓存（L1/L2/L3）导致缓存不一致；
                - 编译器/JIT 会做重排序、甚至把循环里的读提到循环外（hoisting）；
                - 现代 CPU 还有 Store Buffer / Invalidate Queue，写不会立刻全局可见。

                ## 可见性的三种解法
                - volatile：读写都直接怼主内存（通过内存屏障实现），保证可见性 + 禁止重排序；
                - synchronized / Lock：解锁前的写，对随后获得同一把锁的线程可见（happens-before）；
                - final：正确构造后，final 字段对所有线程可见。

                ## 一个反直觉的点
                volatile 保证的是「可见性」和「有序性」，**不保证原子性**。
                volatile int i; i++ 依然会丢更新。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 用作「停止标志」的布尔量必须 volatile 或用 AtomicBoolean。
                - 不要用「睡一会儿」来掩盖可见性问题，那是碰运气。
                - 双重检查锁的单例，instance 字段必须 volatile（否则可能拿到半构造对象）。
                - 无状态服务天然线程安全；有状态才需要同步。先想办法消灭共享状态。
                - 线上「改了配置不生效」「开关关不掉」先怀疑可见性/缓存，而不是玄学。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 非 volatile 标志：有可能 1.2 秒都看不到停止信号");
        Flags flags = new Flags();
        AtomicBoolean sawWithoutVolatile = new AtomicBoolean(false);

        Thread staleReader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200);
            long spins = 0;
            boolean saw = false;
            while (System.nanoTime() < deadline) {
                if (!flags.running) { // 可能被优化成只读一次
                    saw = true;
                    break;
                }
                spins++;
            }
            sawWithoutVolatile.set(saw);
            ctx.info("普通字段读者：%s（自旋 %,d 次）",
                    saw ? "看到了停止信号" : "1.2s 内没看到停止信号 → 可见性问题复现", spins);
        }, "plain-flag-reader");
        staleReader.start();

        ctx.sleep(100);
        ctx.info("主线程把 running 改成 false");
        flags.running = false;
        staleReader.join(3000);

        if (sawWithoutVolatile.get()) {
            ctx.warn("本次没有复现可见性问题（JIT 未优化/机器缓存同步及时），但不代表安全");
            ctx.warn("在服务端 JIT 充分预热后，这个循环卡死是常态");
        } else {
            ctx.ok("复现成功：主线程已经改了 running=false，读者线程却看不到");
        }

        ctx.step("② volatile 标志：每次读都直接访问主内存");
        Thread volatileReader = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2000);
            long spins = 0;
            boolean saw = false;
            while (System.nanoTime() < deadline) {
                if (!flags.vRunning) {
                    saw = true;
                    break;
                }
                spins++;
            }
            ctx.info("volatile 读者：%s（自旋 %,d 次）", saw ? "立刻看到停止信号" : "居然没看到", spins);
            if (saw) {
                ctx.verify("volatile 保证可见性", true);
            }
        }, "volatile-flag-reader");
        volatileReader.start();
        ctx.sleep(100);
        flags.vRunning = false;
        volatileReader.join(3000);
        ctx.verify("volatile 读者一定能退出", !volatileReader.isAlive());

        ctx.step("③ 结论");
        ctx.info("可见性没有「大概没问题」，只有「有保证」和「没保证」");
        ctx.info("标志位 → volatile；计数器 → Atomic/LongAdder；复合逻辑 → 锁");
    }
}
