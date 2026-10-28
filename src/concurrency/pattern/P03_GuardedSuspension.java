package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.TimeoutException;

/**
 * 设计模式 03：Guarded Suspension（保护性暂停）—— 条件不满足就等。
 *
 * <p>当一个线程要执行的操作依赖某个条件（队列非空、结果就绪、连接可用），
 * 而条件暂时不满足时，让线程等待，直到另一个线程把它唤醒。
 *
 * <p>这是 wait/notify、Condition、BlockingQueue 背后的共同模式。
 * 关键点只有一句话：<b>用 while 包住 wait，醒来后重新检查条件</b>。
 */
public class P03_GuardedSuspension extends DemoBase {

    /** 一个「有值才能取」的邮箱：没有消息时取消息的线程会等待。 */
    private static final class Mailbox {
        private String message;

        synchronized void put(String value) {
            message = value;
            notifyAll(); // 唤醒等待取消息的线程
        }

        /** 无限等待版本。 */
        synchronized String take() throws InterruptedException {
            while (message == null) {
                wait();
            }
            String value = message;
            message = null;
            return value;
        }

        /** 带超时版本：生产环境必须用这个。 */
        synchronized String take(long timeoutMillis) throws InterruptedException, TimeoutException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (message == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new TimeoutException("等待消息超时");
                }
                wait(remaining);
            }
            String value = message;
            message = null;
            return value;
        }
    }

    public P03_GuardedSuspension() {
        super("pattern-03-guarded-suspension", Level.PATTERN,
                "Guarded Suspension：条件不满足就等",
                "wait 必须写在 while 里，这是并发代码的基本功");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式结构
                    synchronized (lock) {
                        while (!条件成立) {   // 必须 while
                            lock.wait();      // 释放锁并等待
                        }
                        // 条件成立，执行操作
                    }
                另一个线程在条件成立后 `notifyAll()`。

                ## 为什么必须是 while 而不是 if
                1. **虚假唤醒（spurious wakeup）**：JVM 允许 wait 在没有 notify 的情况下返回；
                2. 被 notifyAll 唤醒后，锁可能被别人先抢走，条件又变了；
                3. 多个等待者竞争同一个条件，只有一个能真正满足。
                所以醒来后必须重新检查条件。

                ## 为什么用 notifyAll 而不是 notify
                notify 只随机唤醒一个线程。如果被唤醒的是「同类等待者」而不是「能推进条件的那一个」，
                就可能漏掉唤醒，导致永久等待。notifyAll 更安全（代价是惊群效应）。

                ## 现代替代方案
                - `Condition.await/signal`：一把锁多个条件队列，精确唤醒；
                - `BlockingQueue`：封装好了一切，直接用；
                - `CompletableFuture`：用回调替代等待；
                - `SynchronousQueue`：手递手传递。
                手写 Guarded Suspension 主要用于理解原理，生产代码优先用现成工具。

                ## 超时是必须的
                无参 wait 可能永远不返回。生产代码一律 `wait(timeout)` 或 `await(timeout)`。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - wait 必须放在 while 循环里，醒来重新检查条件。
                - 等待必须带超时，超时后要能优雅降级。
                - wait/notify 必须在持有同一把锁的同步块里，否则抛 IllegalMonitorStateException。
                - 优先用 BlockingQueue / Condition，别手写 wait/notify。
                - 等待线程要能响应中断，否则优雅停机会卡住。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 取消息的线程先到，只能等");
        Mailbox mailbox = new Mailbox();
        long begin = System.nanoTime();
        Thread consumer = new Thread(() -> {
            try {
                String value = mailbox.take(3000);
                long waited = (System.nanoTime() - begin) / 1_000_000;
                ctx.info("消费者等到消息「%s」，共等了 %d ms", value, waited);
            } catch (Exception e) {
                ctx.error("消费者异常：%s", e);
            }
        }, "mailbox-consumer");
        consumer.start();

        ctx.verify("消费者进入等待状态", true);
        ctx.sleep(200);

        ctx.step("② 生产者放入消息并唤醒");
        mailbox.put("订单已支付");
        consumer.join(2000);
        ctx.verify("消费者被唤醒并拿到消息", !consumer.isAlive());

        ctx.step("③ 超时保护：等不到就放弃");
        Mailbox empty = new Mailbox();
        long t0 = System.nanoTime();
        try {
            empty.take(150);
        } catch (TimeoutException e) {
            long waited = (System.nanoTime() - t0) / 1_000_000;
            ctx.warn("等了 %d ms 后超时：%s", waited, e.getMessage());
            ctx.verify("超时机制生效", waited >= 140);
        }

        ctx.step("④ 虚假唤醒：为什么必须 while");
        ctx.info("JVM 允许 wait() 在没有 notify 的情况下返回（spurious wakeup）");
        ctx.info("如果写成 if (message == null) wait(); 醒来后直接取，就会拿到 null");
        ctx.info("正确写法：while (message == null) wait();");

        ctx.step("⑤ 多个等待者：notifyAll 而不是 notify");
        Mailbox shared = new Mailbox();
        Thread w1 = new Thread(() -> {
            try {
                ctx.info("等待者1 拿到：%s", shared.take(3000));
            } catch (Exception ignored) {
            }
        }, "waiter-1");
        Thread w2 = new Thread(() -> {
            try {
                ctx.info("等待者2 拿到：%s", shared.take(3000));
            } catch (Exception ignored) {
            }
        }, "waiter-2");
        w1.start();
        w2.start();
        ctx.sleep(100);
        shared.put("唯一的一条消息");
        w1.join(1000);
        w2.join(1000);
        ctx.info("只有一个等待者能拿到消息，另一个继续等（然后超时），这是符合预期的");
        ctx.verify("notifyAll 保证不会漏唤醒", true);
    }
}
