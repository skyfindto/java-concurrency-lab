package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 中级 09：Condition —— 自己实现一个有界阻塞队列。
 *
 * <p>{@code Condition} 就是 {@code Object.wait/notify} 的升级版：
 * 一把锁可以挂多个条件队列，<b>「队列不满」和「队列不空」各自等待，互不干扰</b>。
 * 用 synchronized 只有一个 waitSet，就只能 notifyAll，唤醒所有线程再抢锁，效率低还容易误唤醒。
 *
 * <p>手写一遍有界队列，你就彻底理解 ArrayBlockingQueue 是怎么实现的了。
 */
public class M09_Condition extends DemoBase {

    /** 用两个 Condition 实现的有界阻塞队列。 */
    private static final class BoundedQueue<T> {
        private final Object[] items;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition notEmpty = lock.newCondition();
        private int head;
        private int tail;
        private int count;

        BoundedQueue(int capacity) {
            items = new Object[capacity];
        }

        void put(T item) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                // 必须用 while 而不是 if：被唤醒后要重新检查条件（防虚假唤醒）
                while (count == items.length) {
                    notFull.await();
                }
                items[tail] = item;
                tail = (tail + 1) % items.length;
                count++;
                notEmpty.signal(); // 只唤醒一个等待「非空」的消费者
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        T take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (count == 0) {
                    notEmpty.await();
                }
                T item = (T) items[head];
                items[head] = null;
                head = (head + 1) % items.length;
                count--;
                notFull.signal(); // 只唤醒一个等待「不满」的生产者
                return item;
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }
    }

    public M09_Condition() {
        super("juc-09-condition", Level.INTERMEDIATE,
                "Condition：手写有界阻塞队列",
                "一把锁 + 两个条件队列 = ArrayBlockingQueue 的核心");
    }

    @Override
    public String knowledge() {
        return """
                ## Condition vs Object.wait/notify
                - `await()` ≈ `wait()`，`signal()` ≈ `notify()`，`signalAll()` ≈ `notifyAll()`；
                - wait/notify 必须配合 synchronized；Condition 必须由对应的 Lock 创建；
                - 一个 Lock 可以有多个 Condition：生产者和消费者各自等待自己的条件，
                  `signal()` 能精确唤醒「该醒的那一批」，而不是全部。

                ## 为什么必须用 while 而不是 if
                线程被唤醒不代表条件一定成立：
                - 可能是虚假唤醒（spurious wakeup，JVM 允许）；
                - 可能被 signalAll 唤醒但条件已被别人抢走；
                - 可能被中断后重新进入。
                所以标准写法是：`while (!条件) { condition.await(); }`

                ## 有界队列的三个要素
                1. 环形数组（head/tail/count）避免搬移元素；
                2. notFull / notEmpty 两个条件；
                3. 每次 put 之后 signal(notEmpty)，take 之后 signal(notFull)。

                ## 现成的实现
                ArrayBlockingQueue（数组 + 一把锁 + 两个 Condition）、
                LinkedBlockingQueue（两把锁 + 两个 Condition，吞吐更高）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 不要自己写阻塞队列，用 ArrayBlockingQueue / LinkedBlockingQueue。
                - 但「用两个 Condition 分别等待不同条件」这个模式要会：任何有状态的协作都能套。
                - await 必须放在 while 循环里；signal 之后仍然要重新检查条件。
                - await 会释放锁，返回前重新加锁，所以唤醒后看到的状态可能已经变了。
                - 记得处理 InterruptedException：要么向上抛，要么恢复中断标志。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("场景：容量为 2 的队列，生产者放 6 个，消费者每 150ms 取一个");
        BoundedQueue<String> queue = new BoundedQueue<>(2);
        List<String> consumed = java.util.Collections.synchronizedList(new ArrayList<>());

        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 6; i++) {
                try {
                    queue.put("item-" + i);
                    ctx.info("生产 item-%d，当前 size=%d", i, queue.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            for (int i = 1; i <= 6; i++) {
                try {
                    Thread.sleep(150);
                    String item = queue.take();
                    consumed.add(item);
                    ctx.info("消费 %s，当前 size=%d", item, queue.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "consumer");

        producer.start();
        consumer.start();
        producer.join(10_000);
        consumer.join(10_000);

        ctx.verifyEquals("6 个元素全部消费", 6, consumed.size());
        ctx.verify("队列已空", queue.size() == 0);
        ctx.info("生产者在 size=2 时会被 notFull.await() 挂起，消费后才被唤醒");

        ctx.step("对比：用 synchronized + wait/notify 只有一个等待队列");
        ctx.info("synchronized(obj) { while (满) obj.wait(); ... obj.notifyAll(); }");
        ctx.info("生产者被唤醒后还要判断「是不是满了」，消费者也可能被无谓唤醒，效率低");
        ctx.info("Condition 把两类等待分开：notFull.signal() 只叫生产者，notEmpty.signal() 只叫消费者");
    }
}
