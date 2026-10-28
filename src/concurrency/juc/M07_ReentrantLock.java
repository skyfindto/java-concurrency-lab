package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 中级 07：ReentrantLock 与 synchronized 的取舍。
 *
 * <p>synchronized 是 JVM 关键字，简单、不会忘记释放、有锁升级优化；
 * ReentrantLock 是 API 层面的锁，多了三个能力：<b>可中断、可超时、可公平</b>。
 * 需要这三个能力时才用它，否则 synchronized 往往更省心。
 */
public class M07_ReentrantLock extends DemoBase {

    public M07_ReentrantLock() {
        super("juc-07-reentrant-lock", Level.INTERMEDIATE,
                "ReentrantLock：可中断、可超时、可公平",
                "什么时候必须用 Lock 而不是 synchronized");
    }

    @Override
    public String knowledge() {
        return """
                ## 与 synchronized 对比
                | 能力 | synchronized | ReentrantLock |
                |---|---|---|
                | 自动释放 | ✅ 异常也会释放 | ❌ 必须 finally unlock |
                | 可中断等待 | ❌ | ✅ lockInterruptibly() |
                | 超时获取 | ❌ | ✅ tryLock(timeout) |
                | 公平锁 | ❌ 非公平 | ✅ new ReentrantLock(true) |
                | 多条件队列 | ❌ 一个 waitSet | ✅ 多个 Condition |
                | 读写分离 | ❌ | ✅ ReentrantReadWriteLock |
                | 性能 | 优化后相当 | 相当 |

                ## 正确写法
                    lock.lock();
                    try {
                        // 临界区
                    } finally {
                        lock.unlock();   // 必须放在 finally 的【第一行】
                    }

                ## 公平锁的代价
                公平锁会按等待顺序分配，避免饥饿，但吞吐通常更低（要维护队列、频繁唤醒）。
                只有「必须保证顺序」的场景才用，比如防止某个请求永远抢不到锁。

                ## 可重入
                同一个线程可以重复 lock()，内部有 holdCount 计数，unlock() 次数要匹配。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - unlock 必须写在 finally，且是 finally 里的第一件事。
                - 需要「拿不到锁就放弃/降级」的场景，用 tryLock(timeout)，不要死等。
                - 需要「超时/中断能退出」的场景（如优雅停机），用 lockInterruptibly。
                - 读写比例悬殊（读:写 > 10:1）才考虑 ReadWriteLock；否则 ReentrantLock 更快。
                - 锁对象用 private final，避免外部拿到引用去加锁。
                - 持有锁时不要调用外部服务；宁可把数据取出来再处理。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ReentrantLock lock = new ReentrantLock();
        ctx.info("默认非公平锁：isFair = %s", lock.isFair());

        ctx.step("① 基本用法：lock / try-finally / unlock");
        lock.lock();
        try {
            ctx.info("进入临界区，isHeldByCurrentThread = %s", lock.isHeldByCurrentThread());
            ctx.info("holdCount = %d", lock.getHoldCount());
        } finally {
            lock.unlock();
        }
        ctx.verify("释放后不再持有", !lock.isHeldByCurrentThread());

        ctx.step("② tryLock()：拿不到立刻返回 false（防死锁）");
        lock.lock();
        try {
            Thread contender = new Thread(() -> {
                boolean got = lock.tryLock();
                ctx.info("%s tryLock() = %s（不等待，直接失败）", Thread.currentThread().getName(), got);
                if (got) {
                    lock.unlock();
                }
            }, "trylock-thread");
            contender.start();
            contender.join(1000);
        } finally {
            lock.unlock();
        }

        ctx.step("③ tryLock(timeout)：等一会儿，还拿不到就放弃");
        lock.lock();
        try {
            Thread patient = new Thread(() -> {
                long begin = System.nanoTime();
                try {
                    boolean got = lock.tryLock(200, TimeUnit.MILLISECONDS);
                    long cost = (System.nanoTime() - begin) / 1_000_000;
                    ctx.info("%s tryLock(200ms) = %s，等了 %d ms", Thread.currentThread().getName(), got, cost);
                    if (got) {
                        lock.unlock();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "patient-thread");
            patient.start();
            patient.join(2000);
        } finally {
            lock.unlock();
        }

        ctx.step("④ lockInterruptibly：等锁的时候可以被中断（优雅停机的关键）");
        ReentrantLock held = new ReentrantLock();
        held.lock();
        Thread waiter = new Thread(() -> {
            try {
                held.lockInterruptibly();
                try {
                    ctx.info("拿到锁（不会发生）");
                } finally {
                    held.unlock();
                }
            } catch (InterruptedException e) {
                ctx.warn("%s 等锁时被中断，立即放弃并退出", Thread.currentThread().getName());
            }
        }, "interruptible-waiter");
        waiter.start();
        ctx.sleep(100);
        waiter.interrupt();
        waiter.join(1000);
        ctx.verify("可中断锁让线程能及时退出", !waiter.isAlive());
        held.unlock();

        ctx.step("⑤ 公平锁");
        ReentrantLock fair = new ReentrantLock(true);
        ctx.verify("fair.isFair() == true", fair.isFair());
        ctx.info("公平锁按等待顺序分配，避免饥饿，但吞吐更低");
        ctx.info("典型场景：防止高并发下某个请求永远抢不到锁");

        ctx.step("⑥ 对比结论");
        ctx.info("能用 synchronized 就用它（更简单、异常安全）");
        ctx.info("需要 超时/中断/公平/多条件队列 时，才用 ReentrantLock");
    }
}
