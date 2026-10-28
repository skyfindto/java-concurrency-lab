package concurrency.observe.demo;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进阶 07：故障演练 —— 无界队列把「过载」变成「OOM」。
 *
 * <p>无界队列最坏的地方不是它慢，而是它<b>看起来很健康</b>：
 * 接口 RT 不变、错误率不变，只有内存悄悄上涨。
 * 等你发现时，通常已经在 Full GC 或者直接 OOM。
 */
public class O07_FaultQueueBacklog extends DemoBase {

    public O07_FaultQueueBacklog() {
        super("obs-07-fault-queue-backlog", Level.OBSERVE,
                "故障演练：无界队列堆积",
                "生产 5000、消费 200/s，无界队列会堆到多大");
    }

    @Override
    public String knowledge() {
        return """
                ## 现象
                - 接口 RT 正常（因为任务只是入队，没有变慢）；
                - 错误率正常；
                - 堆内存缓慢上涨，Full GC 越来越频繁；
                - 最终 OOM 或长时间 STW。

                ## 为什么危险
                有界队列满了会**立刻**表现出「拒绝/变慢」，这是可观测的信号。
                无界队列把过载「藏」进内存，故障延迟暴露 —— 而延迟暴露 = 来不及处理。

                ## 量化
                假设每个任务 1KB，生产 1000/s、消费 200/s：
                - 1 分钟积压 48000 个 ≈ 48MB；
                - 1 小时 ≈ 2.8GB。
                如果任务是「整个订单对象」或者「文件内容」，放大 100 倍。

                ## 排查
                1. 看 GC 日志：老年代持续增长、Full GC 后不下降；
                2. `jcmd <pid> GC.heap_info` 看各代使用率；
                3. `jmap -histo:live <pid>` 看哪个类实例数最多；
                4. 线程 dump 看队列（LinkedBlockingQueue 的对象数）；
                5. 找到「生产者比消费者快」的那条链路。

                ## 处置
                - 立即：扩容消费者 / 限流生产者 / 重启；
                - 根治：换成有界队列 + 拒绝策略 + 背压；
                - 预防：队列水位监控、内存告警、压测。

                ## 一个反模式
                「队列老是满，那把容量调大一点吧」—— 这只是在延长爆炸时间，
                真正的问题（消费能力不足）一点没解决。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 所有队列必须有界，包括线程池、业务缓冲、本地缓存。
                - 队列容量按「可接受延迟」算，不要按「内存能放多少」算。
                - 队列水位是核心监控指标，80% 就该告警。
                - 消费能力不足时先限流，再扩容，最后才是加队列。
                - 大对象不要放队列，放引用/ID，消费时再查。
                - 定期压测「生产 2 倍于消费」的场景，验证系统会「变慢」而不是「OOM」。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 无界队列：生产 5000 个，消费速度只有 200/s");
        BlockingQueue<Integer> unbounded = new java.util.concurrent.LinkedBlockingQueue<>();
        AtomicInteger consumed = new AtomicInteger();
        Thread consumer = new Thread(() -> {
            while (true) {
                try {
                    Integer value = unbounded.poll();
                    if (value == null) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        Thread.sleep(5);
                        continue;
                    }
                    Thread.sleep(5); // 模拟慢消费：每个任务处理 5ms（约 200/s）
                    consumed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "slow-consumer");
        consumer.setDaemon(true);
        consumer.start();

        long begin = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            unbounded.put(i);
        }
        long produceCost = (System.nanoTime() - begin) / 1_000_000;
        int backlog = unbounded.size();
        ctx.info("生产者 %d ms 内塞入 5000 个任务，无界队列立刻积压 %d 个", produceCost, backlog);
        ctx.info("此刻接口 RT 完全正常 —— 因为任务只是「入队成功」");
        ctx.verify("无界队列不会阻塞生产者", produceCost < 500);
        ctx.verify("积压量巨大", backlog > 4000);

        ctx.step("② 换算成内存");
        long perTaskBytes = 1024; // 假设每个任务 1KB
        double backlogMb = backlog * perTaskBytes / 1024.0 / 1024.0;
        ctx.info("若每个任务 1KB，当前积压 = %.1f MB", backlogMb);
        ctx.info("持续 1 小时（生产 1000/s、消费 200/s）≈ %.1f GB",
                800.0 * 3600 * perTaskBytes / 1024 / 1024 / 1024);
        ctx.warn("这些对象都在老年代，Full GC 会越来越频繁");

        ctx.step("③ 有界队列：同样的压力，立刻表现出背压");
        BlockingQueue<Integer> bounded = new ArrayBlockingQueue<>(100);
        AtomicInteger rejected = new AtomicInteger();
        long boundedBegin = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            if (!bounded.offer(i)) {
                rejected.incrementAndGet(); // 满了：拒绝，而不是无限堆积
            }
        }
        long boundedCost = (System.nanoTime() - boundedBegin) / 1_000_000;
        ctx.info("有界队列（100）：%d ms 处理完这 5000 次提交，拒绝 %d 个",
                boundedCost, rejected.get());
        ctx.verify("有界队列把「积压」变成「拒绝」", rejected.get() > 4000);

        ctx.step("④ 两种策略的对比");
        ctx.info("无界：不报错、不变慢、内存涨 → 故障发现最晚");
        ctx.info("有界：拒绝率上升、RT 上升 → 立刻可见，可以降级/限流");
        ctx.info("工程上宁可「快速失败」，也不要「静默堆积」");

        ctx.step("⑤ 排查命令");
        ctx.info("jcmd <pid> GC.heap_info          # 各代使用率");
        ctx.info("jmap -histo:live <pid> | head -20 # 实例数最多的类");
        ctx.info("jstack <pid> | grep -A 5 LinkedBlockingQueue");
        ctx.verify("队列必须有界，且要监控水位", true);
    }
}
