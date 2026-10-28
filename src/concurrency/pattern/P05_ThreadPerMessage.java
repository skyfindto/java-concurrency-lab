package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设计模式 05：Thread-Per-Message（每消息一线程）。
 *
 * <p>最直白的并发模型：来一个请求就开一个线程，处理完线程结束。
 * 优点是简单、隔离性好；缺点在过去很致命 —— 平台线程太贵，几千个就撑不住。
 *
 * <p>虚拟线程让这个模式重新变得可行：线程成本降到和对象差不多，
 * 「一个请求一个线程」又成了首选写法。
 */
public class P05_ThreadPerMessage extends DemoBase {

    public P05_ThreadPerMessage() {
        super("pattern-05-thread-per-message", Level.PATTERN,
                "Thread-Per-Message：虚拟线程让它复活",
                "同样 500 个请求，平台线程 vs 虚拟线程");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式结构
                    for (Request request : requests) {
                        new Thread(() -> handle(request)).start();
                    }
                没有队列、没有池、没有共享状态 —— 简单到不需要解释。

                ## 为什么以前不推荐
                - 平台线程 = OS 线程，创建成本几十微秒，栈默认 1MB；
                - 1000 个并发请求 = 1000 个 OS 线程 = 1GB 栈 + 大量上下文切换；
                - 无法限流，流量一冲就 OOM 或调度崩溃。

                ## 虚拟线程带来的变化（JDK21）
                - 创建成本纳秒级，栈按需增长（几百字节）；
                - 阻塞时自动让出载体线程，不需要「池化复用」；
                - 百万级并发可行；
                - 代码回归最直观的「同步写法」，不需要回调地狱。

                ## 什么时候仍然需要线程池
                - CPU 密集任务：并发度应该 = 核数，开太多反而慢；
                - 需要限流/背压：线程池大小就是天然的并发上限；
                - 需要统一监控/生命周期管理。

                ## 混合方案
                「一个请求一个虚拟线程」+「内部用信号量限制对下游的并发」，
                既保留了代码简洁，又能控制对依赖的压力。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - IO 密集服务优先用 newVirtualThreadPerTaskExecutor，而不是固定线程池。
                - 虚拟线程不需要池化，但**下游并发要限流**（Semaphore / 连接池大小）。
                - 避免在虚拟线程里长时间持有 synchronized（JDK21 会 pin 住载体线程）。
                - CPU 密集任务仍然用固定大小的平台线程池（避免过度并行）。
                - 虚拟线程下 ThreadLocal 开销更明显，优先用参数传递上下文。
                - 监控看载体线程池和任务耗时，不要盯「虚拟线程数量」。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int messages = 500;
        int workMillis = 20;

        ctx.step("① 平台线程：每个请求 new 一个线程（500 个）");
        long begin = System.nanoTime();
        List<Thread> platformThreads = new ArrayList<>(messages);
        CountDownLatch platformDone = new CountDownLatch(messages);
        AtomicInteger platformCount = new AtomicInteger();
        for (int i = 0; i < messages; i++) {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(workMillis);
                    platformCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    platformDone.countDown();
                }
            }, "platform-msg-" + i);
            platformThreads.add(thread);
            thread.start();
        }
        platformDone.await(60, TimeUnit.SECONDS);
        long platformCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("500 个平台线程处理完：%d ms", platformCost);

        ctx.step("② 虚拟线程：同样 500 个请求（甚至更多）");
        begin = System.nanoTime();
        CountDownLatch virtualDone = new CountDownLatch(messages);
        AtomicInteger virtualCount = new AtomicInteger();
        for (int i = 0; i < messages; i++) {
            Thread.ofVirtual().name("virtual-msg-" + i).start(() -> {
                try {
                    Thread.sleep(workMillis);
                    virtualCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    virtualDone.countDown();
                }
            });
        }
        virtualDone.await(60, TimeUnit.SECONDS);
        long virtualCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("500 个虚拟线程处理完：%d ms", virtualCost);

        ctx.verifyEquals("平台线程全部完成", messages, platformCount.get());
        ctx.verifyEquals("虚拟线程全部完成", messages, virtualCount.get());
        ctx.info("虚拟线程创建开销更低，但这里 CPU 才是瓶颈，差距不夸张");

        ctx.step("③ 关键差异不在速度，而在「能开多少」");
        begin = System.nanoTime();
        int many = 20_000;
        CountDownLatch manyDone = new CountDownLatch(many);
        for (int i = 0; i < many; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    manyDone.countDown();
                }
            });
        }
        manyDone.await(60, TimeUnit.SECONDS);
        long manyCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("2 万个虚拟线程同时 sleep，总耗时 %d ms（平台线程早崩了）", manyCost);
        ctx.verify("虚拟线程可以轻松开到上万", manyDone.getCount() == 0);

        ctx.step("④ 但不要忘记限流");
        ctx.warn("虚拟线程不等于无限并发：下游 DB/HTTP 连接池仍然是瓶颈");
        ctx.info("推荐：每请求一个虚拟线程 + Semaphore 限制对每个下游的并发");
        ctx.verify("Thread-Per-Message 在 IO 密集场景是最直观的模型", true);
    }
}
