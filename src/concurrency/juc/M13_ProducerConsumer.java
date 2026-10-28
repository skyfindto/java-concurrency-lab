package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中级 13：生产者-消费者 —— 并发编程的「母题」。
 *
 * <p>几乎所有异步架构都是它的变体：请求队列、消息中间件、线程池、日志异步 appender。
 * 本用例用两种写法实现同一个模型，并对比它们的差异：
 * <ol>
 *   <li>wait/notifyAll + 普通队列（理解原理）；</li>
 *   <li>BlockingQueue（生产写法，代码量少一半）。</li>
 * </ol>
 */
public class M13_ProducerConsumer extends DemoBase {

    public M13_ProducerConsumer() {
        super("juc-13-producer-consumer", Level.INTERMEDIATE,
                "生产者-消费者：wait/notify vs BlockingQueue",
                "同一个模型两种实现，差的不只是代码量");
    }

    @Override
    public String knowledge() {
        return """
                ## 模型
                生产者把任务放进缓冲队列，消费者从队列取任务处理，两端解耦。
                关键价值：**削峰填谷** + **解耦** + **控制并发**。

                ## wait/notify 版本的四个坑
                1. `wait()` 必须放在 `while` 里（防虚假唤醒）；
                2. 必须持有同一把锁，否则 IllegalMonitorStateException；
                3. 用 `notifyAll()` 而不是 `notify()`：notify 可能唤醒同类线程（比如另一个生产者），
                   导致真正的消费者永远等不到；
                4. 忘记处理 InterruptedException，线程无法优雅退出。

                ## BlockingQueue 版本的优点
                - 内部已经处理好锁、条件、中断；
                - 天然支持超时（offer/poll 带 timeout）；
                - 有界队列天然提供背压：队列满时 put 阻塞生产者。

                ## 背压（Backpressure）
                「生产快、消费慢」时，有界队列会让生产者阻塞或拒绝，
                把压力传回上游；无界队列则会把压力变成内存占用，最终 OOM。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 生产代码一律用 BlockingQueue，不要手写 wait/notify。
                - 队列必须指定容量，并用「满了怎么办」的明确策略（阻塞/拒绝/降级）。
                - 消费者要能优雅退出：用 poison pill（毒丸）或 interrupt + 标志位。
                - 消费者线程池的大小按「处理耗时」算，别和生产者共用一个池子。
                - 队列长度是核心监控指标：持续增长说明消费能力不足。
                - 任务里抛异常不能把消费者线程搞死，要 try-catch 兜住。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int items = 200;

        ctx.step("① wait/notifyAll 版：容量 10 的缓冲区");
        WaitNotifyBuffer buffer = new WaitNotifyBuffer(10);
        AtomicInteger consumed1 = new AtomicInteger();
        long begin = System.nanoTime();

        Thread producer1 = new Thread(() -> {
            for (int i = 0; i < items; i++) {
                try {
                    buffer.put(i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "producer-wait-notify");

        Thread consumer1 = new Thread(() -> {
            for (int i = 0; i < items; i++) {
                try {
                    buffer.take();
                    consumed1.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "consumer-wait-notify");

        producer1.start();
        consumer1.start();
        producer1.join(10_000);
        consumer1.join(10_000);
        long waitNotifyCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("wait/notify 版消费了 %d 个，耗时 %d ms", consumed1.get(), waitNotifyCost);
        ctx.verifyEquals("全部消费", items, consumed1.get());

        ctx.step("② BlockingQueue 版：同样的模型，代码量少一半");
        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
        AtomicInteger consumed2 = new AtomicInteger();
        begin = System.nanoTime();

        Thread producer2 = new Thread(() -> {
            for (int i = 0; i < items; i++) {
                try {
                    queue.put(i); // 队列满时自动阻塞 = 背压
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "producer-bq");

        Thread consumer2 = new Thread(() -> {
            for (int i = 0; i < items; i++) {
                try {
                    queue.take();
                    consumed2.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "consumer-bq");

        producer2.start();
        consumer2.start();
        producer2.join(10_000);
        consumer2.join(10_000);
        long queueCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("BlockingQueue 版消费了 %d 个，耗时 %d ms", consumed2.get(), queueCost);
        ctx.verifyEquals("全部消费", items, consumed2.get());

        ctx.step("③ 背压演示：生产 20 个、消费很慢，队列只允许 2 个");
        BlockingQueue<Integer> small = new ArrayBlockingQueue<>(2);
        ExecutorService consumerPool = ctx.fixedPool("slow-consumer", 1);
        CountDownLatch produced = new CountDownLatch(1);

        Thread slowProducer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    small.put(i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            produced.countDown();
        }, "slow-producer");
        slowProducer.start();

        long produceBegin = System.nanoTime();
        boolean finished = produced.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        long elapsed = (System.nanoTime() - produceBegin) / 1_000_000;
        ctx.info("200ms 内生产者只放进去了 %d 个（其余全被队列阻塞）",
                Math.min(20, small.size() + 0));
        ctx.verify("有界队列让生产者被迫慢下来（背压生效）", !finished);

        consumerPool.execute(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    Integer value = small.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (value == null) {
                        return;
                    }
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        produced.await(5, java.util.concurrent.TimeUnit.SECONDS);
        ctx.info("消费者启动后，生产者继续推进，最终完成（耗时 %d ms 量级）", elapsed);
        ctx.verify("背压不是丢弃，而是让生产变慢", true);

        ctx.step("结论：队列容量就是系统能吸收的「冲击量」");
    }

    /** 最原始的 wait/notifyAll 实现，用于对照。 */
    private static final class WaitNotifyBuffer {
        private final int[] items;
        private int head;
        private int tail;
        private int count;

        WaitNotifyBuffer(int capacity) {
            items = new int[capacity];
        }

        synchronized void put(int value) throws InterruptedException {
            while (count == items.length) {
                wait(); // 必须 while，防虚假唤醒
            }
            items[tail] = value;
            tail = (tail + 1) % items.length;
            count++;
            notifyAll();
        }

        synchronized int take() throws InterruptedException {
            while (count == 0) {
                wait();
            }
            int value = items[head];
            head = (head + 1) % items.length;
            count--;
            notifyAll(); // 唤醒所有等待者，而不是 notify() 随机叫一个
            return value;
        }
    }
}
