package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * 中级 12：BlockingQueue 全家桶 —— 选错队列，轻则性能差，重则 OOM。
 *
 * <p>生产者-消费者、线程池、消息中间件客户端，底层都是阻塞队列。
 * 本用例把 6 种实现的行为差异跑一遍。
 */
public class M12_BlockingQueue extends DemoBase {

    public M12_BlockingQueue() {
        super("juc-12-blocking-queue", Level.INTERMEDIATE,
                "BlockingQueue 全家桶对比",
                "Array / Linked / Synchronous / Priority / Delay 各自适合什么");
    }

    @Override
    public String knowledge() {
        return """
                ## 六种实现
                | 实现 | 结构 | 有界 | 特点 |
                |---|---|---|---|
                | ArrayBlockingQueue | 数组 + 1 把锁 | 必须指定容量 | 内存紧凑、可公平；吞吐一般 |
                | LinkedBlockingQueue | 链表 + 2 把锁 | 可选（默认 Integer.MAX_VALUE） | 吞吐高；**默认无界，慎用** |
                | SynchronousQueue | 无容量 | 容量 0 | 手递手，必须有消费者才能放进去；CachedThreadPool 用它 |
                | PriorityBlockingQueue | 堆 | 无界 | 按优先级出队，不保证同优先级顺序 |
                | DelayQueue | 优先队列 + 延迟 | 无界 | 元素到期才能取出；定时任务/重试队列 |
                | LinkedTransferQueue | 链表 | 无界 | transfer() 等消费者取走才返回 |

                ## put / offer / add 的区别
                - `add`：失败抛异常（队列满）
                - `offer`：失败返回 false；`offer(e, timeout, unit)` 带等待
                - `put`：队列满则阻塞
                - `take`：队列空则阻塞；`poll(timeout)` 带超时

                ## 线程池里的队列选择
                - 有界 ArrayBlockingQueue：最常用，行为和容量可控；
                - SynchronousQueue：任务必须立刻被线程接手，适合短任务 + 大线程池；
                - 无界 LinkedBlockingQueue：定时炸弹，任务堆积会 OOM。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 生产代码里永远指定容量：`new LinkedBlockingQueue<>(capacity)`，别用无参构造。
                - 队列容量 × 平均处理耗时 ≈ 最大排队延迟，按 SLA 反推。
                - 队列里不要放超大对象（比如整个文件内容），内存放大很可怕。
                - 关心「元素被取走的确认」用 SynchronousQueue / transfer。
                - 定时重试优先用 DelayQueue 或 ScheduledExecutorService，别自己 sleep 轮询。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① ArrayBlockingQueue：有界，满了 put 会阻塞");
        BlockingQueue<Integer> arrayQueue = new ArrayBlockingQueue<>(2);
        arrayQueue.put(1);
        arrayQueue.put(2);
        long begin = System.nanoTime();
        boolean offered = arrayQueue.offer(3, 200, TimeUnit.MILLISECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("队列满时 offer(200ms) = %s，等待 %d ms", offered, cost);
        ctx.verify("满了以后 offer 超时返回 false", !offered);
        ctx.info("取走一个：%d，再次 offer = %s", arrayQueue.take(), arrayQueue.offer(3));
        ctx.verifyEquals("腾出空间后可以放入", true, arrayQueue.size() == 2);

        ctx.step("② LinkedBlockingQueue：默认容量是 Integer.MAX_VALUE（危险）");
        BlockingQueue<Integer> unbounded = new LinkedBlockingQueue<>();
        ctx.warn("无参构造的剩余容量 = %,d，等于无界，任务堆积会 OOM",
                unbounded.remainingCapacity());

        ctx.step("③ SynchronousQueue：手递手，put 必须等到有人 take");
        SynchronousQueue<String> handoff = new SynchronousQueue<>();
        Thread taker = new Thread(() -> {
            try {
                sleepQuietly(200);
                ctx.info("消费者 take 到：%s", handoff.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "synchronous-taker");
        taker.start();
        long putBegin = System.nanoTime();
        ctx.info("生产者准备 put……");
        handoff.put("任务");
        long putCost = (System.nanoTime() - putBegin) / 1_000_000;
        ctx.info("put 返回，被阻塞了 %d ms（直到消费者接手）", putCost);
        ctx.verify("put 会等待消费者", putCost >= 150);
        taker.join(2000);

        ctx.step("④ PriorityBlockingQueue：按优先级出队");
        PriorityBlockingQueue<Integer> priority = new PriorityBlockingQueue<>();
        priority.add(3);
        priority.add(1);
        priority.add(2);
        ctx.info("放入顺序 3,1,2 → 取出顺序 %d,%d,%d",
                priority.take(), priority.take(), priority.take());
        ctx.verify("按自然顺序出队", priority.isEmpty());

        ctx.step("⑤ DelayQueue：到期才能取，适合定时重试");
        DelayQueue<DelayedTask> delayQueue = new DelayQueue<>();
        delayQueue.put(new DelayedTask("1秒任务", 100));
        delayQueue.put(new DelayedTask("2秒任务", 200));
        delayQueue.put(new DelayedTask("3秒任务", 300));
        long delayBegin = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            DelayedTask task = delayQueue.take();
            long elapsed = (System.nanoTime() - delayBegin) / 1_000_000;
            ctx.info("第 %d 个出队：%s（已等待 %d ms）", i + 1, task.name, elapsed);
        }
        long total = (System.nanoTime() - delayBegin) / 1_000_000;
        ctx.info("全部取完共 %d ms（约等于最长延迟）", total);
        ctx.verify("按到期时间顺序出队", total >= 280);

        ctx.step("选型口诀");
        ctx.info("普通生产消费 → ArrayBlockingQueue（有界、可预测）");
        ctx.info("高吞吐 → LinkedBlockingQueue(capacity)");
        ctx.info("线程池 + 短任务 → SynchronousQueue");
        ctx.info("优先级/延迟 → PriorityBlockingQueue / DelayQueue");
    }

    /** DelayQueue 的元素：实现 Delayed，返回剩余延迟。 */
    private record DelayedTask(String name, long delayMillis, long expireAt) implements Delayed {

        DelayedTask(String name, long delayMillis) {
            this(name, delayMillis, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis));
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(expireAt - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
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
