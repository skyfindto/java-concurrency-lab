package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * 中级 08：ReadWriteLock / StampedLock —— 读写分离的两种做法。
 *
 * <p>典型场景：本地缓存、配置表、路由表 —— 读多写少。
 * 用互斥锁会把并发读串行化；读写锁让「读读并发」，只有写才独占。
 */
public class M08_ReadWriteLock extends DemoBase {

    /** 被锁保护的数据。 */
    private volatile int value;

    public M08_ReadWriteLock() {
        super("juc-08-read-write-lock", Level.INTERMEDIATE,
                "ReadWriteLock 与 StampedLock 读写分离",
                "读读并行、读写互斥、锁降级、乐观读");
    }

    @Override
    public String knowledge() {
        return """
                ## ReentrantReadWriteLock
                - 读锁（共享锁）：多个线程可同时持有，只要没有写锁；
                - 写锁（排他锁）：独占，读写都不能进；
                - 支持「锁降级」：持有写锁 → 获取读锁 → 释放写锁（合法）；
                - 「锁升级」会死锁：持有读锁再去拿写锁，自己等自己。

                ## 适用条件
                读操作远多于写（通常 > 10:1），且读操作耗时不可忽略。
                如果读操作极短（只是读个 volatile 字段），读写锁的 CAS 开销反而更大。

                ## StampedLock（JDK8+）
                - 三种模式：writeLock、readLock、**tryOptimisticRead（乐观读）**；
                - 乐观读不阻塞、不挂起，读完之后 `validate(stamp)` 校验版本号，
                  没过就退回悲观读 —— 适合「读多写极少」；
                - **不可重入**，且不支持 Condition，用错会死锁，代码要格外小心；
                - 乐观读非常适合「先读快照、后校验」的场景。

                ## 饥饿问题
                读写锁默认非公平，读多时写线程可能饿死；可传 fair=true 构造公平锁。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 缓存类场景优先考虑 ConcurrentHashMap + computeIfAbsent，比手写读写锁简单。
                - 用读写锁时先确认读操作是否真的耗时；否则直接用 volatile 或原子类。
                - 锁降级是合法且有价值的（写完立刻读自己写的数据），锁升级禁止。
                - StampedLock 只在极端读多写少且压测证明有收益时使用，不重入是硬伤。
                - 加锁前后都要用 try/finally，读锁写锁都不能漏 unlock。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        value = 0;

        ctx.step("① 读读并发：两个读线程同时持锁，总耗时 ≈ 300ms");
        CountDownLatch readGate = new CountDownLatch(1);
        long begin = System.nanoTime();
        Thread r1 = reader(ctx, rw, "reader-1", readGate);
        Thread r2 = reader(ctx, rw, "reader-2", readGate);
        r1.start();
        r2.start();
        readGate.countDown();
        r1.join(3000);
        r2.join(3000);
        long readRead = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("两个读线程总耗时 %d ms", readRead);
        ctx.verify("读读之间不互斥", readRead < 550);

        ctx.step("② 读写互斥：写锁独占，读线程要等");
        CountDownLatch writeGate = new CountDownLatch(1);
        begin = System.nanoTime();
        Thread writer = new Thread(() -> {
            rw.writeLock().lock();
            try {
                value = 42;
                ctx.info("写线程持有写锁，value = 42");
                sleepQuietly(300);
            } finally {
                rw.writeLock().unlock();
            }
        }, "writer");
        Thread blockedReader = reader(ctx, rw, "blocked-reader", writeGate);
        writer.start();
        ctx.sleep(50);
        blockedReader.start();
        writeGate.countDown();
        writer.join(3000);
        blockedReader.join(3000);
        long readWrite = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("写 + 读总耗时 %d ms（读被写锁挡住了）", readWrite);
        ctx.verify("读写互斥", readWrite >= 500);

        ctx.step("③ 锁降级：写锁 → 读锁 → 释放写锁");
        rw.writeLock().lock();
        try {
            value = 100;
            rw.readLock().lock();   // 在持有写锁时获取读锁，合法
            try {
                ctx.info("降级后读到 value = %d", value);
            } finally {
                rw.writeLock().unlock(); // 先释放写锁，仍持读锁
            }
            ctx.info("此时写锁已释放，读锁仍由当前线程持有");
        } finally {
            rw.readLock().unlock();
        }
        ctx.info("注意：反过来「读锁 → 写锁」会自己等自己，直接死锁");

        ctx.step("④ StampedLock 乐观读：不加锁先读，最后校验");
        StampedLock sl = new StampedLock();
        long stamp = sl.tryOptimisticRead();   // 不阻塞、不挂起
        int snapshot = value;                  // 读快照
        boolean valid = sl.validate(stamp);    // 期间有没有人写？
        ctx.info("乐观读到的值 = %d，校验结果 = %s（没写入就无需加锁）", snapshot, valid);
        ctx.verify("无写入时乐观读有效", valid);

        long writeStamp = sl.writeLock();
        try {
            value = 999;
        } finally {
            sl.unlockWrite(writeStamp);
        }
        ctx.info("另一个写操作完成后，旧 stamp 是否仍有效？%s", sl.validate(stamp));
        ctx.verify("写入后旧 stamp 失效，必须重读", !sl.validate(stamp));

        ctx.step("StampedLock 的乐观读适合「读多写极少」的场景，比如路由表、配置快照");
        ctx.info("但它不可重入、没有 Condition，代码复杂度高，别滥用");
    }

    /** 读线程：拿读锁 → 打印 → sleep 300ms → 释放。 */
    private Thread reader(DemoContext ctx, ReentrantReadWriteLock rw, String name, CountDownLatch gate) {
        Thread thread = new Thread(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            rw.readLock().lock();
            try {
                ctx.info("%s 持有读锁，读到 value = %d", name, value);
                sleepQuietly(300);
            } finally {
                rw.readLock().unlock();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
