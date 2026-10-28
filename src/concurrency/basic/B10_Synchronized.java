package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 初级 10：synchronized 到底锁了什么。
 *
 * <p>一句话：<b>synchronized 锁的是「对象」，不是「代码块」</b>。
 * 实例方法锁 this，静态方法锁 Class 对象，同步块锁括号里那个引用。
 * 只有锁的是同一个对象，才会互斥。
 */
public class B10_Synchronized extends DemoBase {

    /** 被锁保护的业务对象。 */
    private static final class MonitorService {
        private final DemoContext ctx;

        MonitorService(DemoContext ctx) {
            this.ctx = ctx;
        }

        /** 写法一：实例方法 → 锁 this。 */
        synchronized void slow(String tag) {
            sleepQuietly(300);
            ctx.info("%s 执行完毕（持锁期间 Thread.holdsLock=true）", tag);
        }

        /** 写法二：同步块 → 锁括号里的对象。 */
        void slowBlock(String tag) {
            synchronized (this) {
                sleepQuietly(300);
                ctx.info("%s 执行同步块完毕", tag);
            }
        }

        private static void sleepQuietly(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public B10_Synchronized() {
        super("basic-10-synchronized", Level.BASIC,
                "synchronized 原理：锁对象、可重入、锁升级",
                "为什么两个 synchronized 方法「有时候」并不互斥？");
    }

    @Override
    public String knowledge() {
        return """
                ## 锁的是对象
                - `synchronized void m()`      → 锁 `this`
                - `static synchronized void m()` → 锁 `Xxx.class`（Class 对象）
                - `synchronized (obj) { }`      → 锁 `obj` 这个引用指向的对象

                ## 对象头与锁升级
                Mark Word 里记录锁状态。JDK 的优化路径大致是：
                - 无锁 → 偏向锁（一个线程反复进入，CAS 一次把线程 ID 写进对象头）
                - 偏向锁 → 轻量级锁（出现第二个线程，CAS 自旋抢锁）
                - 轻量级 → 重量级（自旋失败/竞争激烈，进入 monitor，线程阻塞挂起）
                注：偏向锁在 JDK15 被废弃（JEP 374），JDK18 起默认关闭，因为撤销成本高、现代应用竞争多。
                另外还有锁消除（逃逸分析发现锁不会逃逸）和锁粗化（把连续的加解锁合并）。

                ## 可重入
                同一个线程可以重复获取同一把锁，monitor 里维护一个计数器（重入次数），
                解锁时递减到 0 才真正释放。所以同步方法可以安全地调用同类的另一个同步方法。

                ## 内存语义
                unlock 之前的所有写，对随后 lock 同一把锁的线程可见（happens-before 规则之二）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 锁对象必须是所有竞争方都拿得到的同一个对象，且最好用 `private final Object lock = new Object()`。
                - 千万不要锁 String 常量、Integer 缓存对象（-128~127）：它们是全局共享的，可能和别人的代码死锁。
                - 不要在锁里做 IO、RPC、sleep：临界区越长，吞吐越低，超时越容易雪崩。
                - 加锁顺序要全局统一，否则必然死锁（见 basic-12）。
                - 能用无锁（CAS/不可变/线程封闭）就别用锁；能缩小粒度就别用大锁。
                - synchronized 是非公平锁，不保证等待线程的先后顺序，不要依赖它做排队。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService pool = ctx.fixedPool("sync-demo-pool", 2);

        ctx.step("① 同一个实例：两个线程争同一把 this 锁 → 串行（约 600ms）");
        MonitorService shared = new MonitorService(ctx);
        long t0 = System.nanoTime();
        Future<?> f1 = pool.submit(() -> shared.slow("线程A"));
        Future<?> f2 = pool.submit(() -> shared.slow("线程B"));
        f1.get();
        f2.get();
        long sameInstance = (System.nanoTime() - t0) / 1_000_000;
        ctx.info("两个线程总耗时 %d ms（每个方法 sleep 300ms）", sameInstance);
        ctx.verify("同一实例 → 互斥串行", sameInstance >= 550);

        ctx.step("② 两个不同实例：锁的是不同的 this → 并行（约 300ms）");
        MonitorService a = new MonitorService(ctx);
        MonitorService b = new MonitorService(ctx);
        long t1 = System.nanoTime();
        Future<?> f3 = pool.submit(() -> a.slow("实例A-线程"));
        Future<?> f4 = pool.submit(() -> b.slow("实例B-线程"));
        f3.get();
        f4.get();
        long differentInstance = (System.nanoTime() - t1) / 1_000_000;
        ctx.info("两个线程总耗时 %d ms", differentInstance);
        ctx.verify("不同实例 → 并行执行", differentInstance < 550);
        ctx.info("结论：synchronized 保护的是「这个对象」，不是「这个方法」");

        ctx.step("③ 可重入：同一个线程可以重复拿同一把锁");
        class Reentrant {
            synchronized void outer() {
                ctx.info("outer() 持有锁，重入计数 = 1");
                inner();
            }

            synchronized void inner() {
                ctx.info("inner() 同一线程再次进入，计数 = 2，holdsLock=%s", Thread.holdsLock(this));
                ctx.verify("同步方法内部可以安全调用另一个同步方法", Thread.holdsLock(this));
            }
        }
        new Reentrant().outer();

        ctx.step("④ 锁错对象的经典坑");
        Integer lockA = 100;
        Integer lockB = 100;
        ctx.info("Integer 100 == Integer 100 ? %s（-128~127 有缓存，指向同一个对象）", lockA == lockB);
        String s1 = "lock";
        String s2 = "lock";
        ctx.info("字符串常量 \"lock\" == \"lock\" ? %s（常量池，全局唯一）", s1 == s2);
        ctx.warn("锁 Integer/String 常量 = 锁了一个全局对象，随时可能和别人的代码互相阻塞");
        ctx.verify("正确姿势：private final Object lock = new Object()", true);

        ctx.step("⑤ 静态方法锁的是 Class 对象，和实例锁互不干扰");
        ctx.info("synchronized void m()       → 锁 this");
        ctx.info("static synchronized void m() → 锁 Xxx.class");
        ctx.info("两者不是同一把锁，可以同时执行");
    }
}
