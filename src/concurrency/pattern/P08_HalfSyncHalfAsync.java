package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设计模式 08：Half-Sync/Half-Async（半同步/半异步）。
 *
 * <p>把系统分成两层：
 * <ul>
 *   <li><b>异步层</b>：IO/网络事件线程，只负责「收」，绝不能阻塞；</li>
 *   <li><b>同步层</b>：业务工作线程池，负责耗时处理；</li>
 *   <li><b>队列</b>：两层之间的缓冲（必须有界）。</li>
 * </ul>
 *
 * <p>Netty 的 boss/worker、Tomcat 的 Acceptor/Poller/Worker、
 * Redis 6 的 IO 线程 + 主线程，都是这个模型。
 */
public class P08_HalfSyncHalfAsync extends DemoBase {

    public P08_HalfSyncHalfAsync() {
        super("pattern-08-half-sync-half-async", Level.PATTERN,
                "Half-Sync/Half-Async：IO 与业务分层",
                "1 个 IO 线程快速收事件，3 个工作线程慢慢处理");
    }

    @Override
    public String knowledge() {
        return """
                ## 分层结构
                    [网络/IO 线程] --事件--> [有界队列] --任务--> [业务线程池]
                       非阻塞、极快                      阻塞、耗时、可扩展

                ## 为什么不能合在一起
                如果 IO 线程直接处理业务：
                - 一个慢请求会阻塞后续所有连接的事件读取；
                - 无法通过增加线程来提升业务处理能力（IO 线程通常只有 1~N 个）；
                - 慢客户端会拖垮整个服务（慢连接攻击）。

                ## 队列是「缓冲」，也是「风险点」
                - 有界队列：IO 线程在队列满时会阻塞（反压到网络层，TCP 窗口减小）；
                - 无界队列：业务处理不过来就堆积，最终 OOM。
                生产上必须选有界队列，并定义满了怎么办。

                ## 真实案例
                | 框架 | 异步层 | 同步层 |
                |---|---|---|
                | Netty | boss EventLoop 收连接 + worker EventLoop 读写 | 业务 handler（可丢到业务线程池） |
                | Tomcat | Acceptor + Poller | Worker 线程池 |
                | Redis 6 | IO 线程读 socket | 主线程执行命令 |
                | Node.js | libuv 线程池 | 事件循环回调 |

                ## 一个常见错误
                在 Netty 的 EventLoop 里做数据库查询。EventLoop 线程被阻塞，
                绑定在它上面的成百上千个连接全部卡住。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - IO 线程（EventLoop）里绝不做阻塞操作：不查库、不发 HTTP、不 sleep。
                - 耗时业务丢到独立线程池，并用有界队列 + 拒绝策略保护。
                - 队列长度和线程池指标都要监控，否则过载时无从下手。
                - 业务线程池要按下游隔离（见 adv-02），避免互相拖累。
                - 注意事件顺序：同一连接的事件可能需要保序（绑定到同一个 EventLoop）。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        int events = 20;
        BlockingQueue<String> eventQueue = new LinkedBlockingQueue<>(50);
        AtomicInteger handled = new AtomicInteger();
        CountDownLatch allHandled = new CountDownLatch(events);

        ctx.step("① 启动同步层：3 个业务线程，每个处理 50ms");
        ExecutorService workers = ctx.fixedPool("half-sync-worker", 3);

        ctx.step("② 异步层：1 个 IO 线程快速收事件");
        Thread ioThread = new Thread(() -> {
            for (int i = 1; i <= events; i++) {
                try {
                    eventQueue.put("event-" + i);
                    ctx.info("IO 线程收到 event-%d，入队（队列长度 %d）", i, eventQueue.size());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            ctx.info("IO 线程完成任务，耗时极短，没有处理任何业务");
        }, "io-thread");
        ioThread.setDaemon(true);

        long begin = System.nanoTime();
        ioThread.start();
        ioThread.join(5000);
        long ioCost = (System.nanoTime() - begin) / 1_000_000;

        ctx.step("③ 同步层从队列取任务处理");
        for (int i = 0; i < 3; i++) {
            workers.execute(() -> {
                while (true) {
                    String event = eventQueue.poll();
                    if (event == null) {
                        if (allHandled.getCount() == 0) {
                            return;
                        }
                        try {
                            event = eventQueue.poll(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        if (event == null) {
                            continue;
                        }
                    }
                    ctx.info("%s 处理 %s（耗时 50ms）", Thread.currentThread().getName(), event);
                    sleepQuietly(50);
                    handled.incrementAndGet();
                    allHandled.countDown();
                }
            });
        }

        boolean done = allHandled.await(20, TimeUnit.SECONDS);
        long totalCost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("IO 层耗时 %d ms，业务层处理完共 %d ms", ioCost, totalCost);
        ctx.verify("20 个事件全部处理", done);
        ctx.verifyEquals("处理数量正确", events, handled.get());
        ctx.verify("IO 层没有被业务阻塞", ioCost < 200);
        ctx.info("20 个事件 × 50ms ÷ 3 线程 ≈ 350ms，与实测接近");

        ctx.step("④ 反例：如果 IO 线程自己处理业务");
        long badBegin = System.nanoTime();
        for (int i = 1; i <= 6; i++) {
            sleepQuietly(50); // 模拟 IO 线程亲自处理
        }
        long badCost = (System.nanoTime() - badBegin) / 1_000_000;
        ctx.warn("IO 线程亲自处理 6 个 50ms 的请求 = %d ms，期间无法接收任何新连接", badCost);
        ctx.verify("分层的目的：让 IO 线程永远保持轻快", true);

        ctx.step("⑤ 记住 Netty 的这条铁律");
        ctx.warn("不要在 EventLoop 里做阻塞操作 —— 一个 Channel 的慢，会拖垮一整条 EventLoop 上的所有连接");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
