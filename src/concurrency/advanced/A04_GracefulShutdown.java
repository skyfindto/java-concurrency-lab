package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 04：优雅停机 —— 从 SIGTERM 到「零丢失」。
 *
 * <p>发布、扩缩容、机器回收，每天都会发生。如果停机姿势不对，
 * 正在处理的任务会被直接砍掉：订单状态不一致、消息重复、用户看到 500。
 *
 * <p>正确顺序：<b>摘流量 → 停止接收新任务 → 等待在途任务 → 超时兜底 → 释放资源</b>。
 */
public class A04_GracefulShutdown extends DemoBase {

    public A04_GracefulShutdown() {
        super("adv-04-graceful-shutdown", Level.ADVANCED,
                "优雅停机：shutdown 与 shutdownNow 的代价",
                "同样 20 个任务，优雅停机全保住，粗暴停机会丢多少");
    }

    @Override
    public String knowledge() {
        return """
                ## 一次完整的优雅停机
                1. **摘流量**：从注册中心下线、K8s 把 Pod 标记为 Terminating，
                   等待负载均衡把请求转走（通常 5~15 秒）；
                2. **停止接收新任务**：`executor.shutdown()`，Web 容器停止 accept；
                3. **等待在途任务**：`awaitTermination(timeout)`，让正在跑的任务完成；
                4. **超时兜底**：还没结束就 `shutdownNow()`，记录丢失的任务并告警；
                5. **释放资源**：关闭连接池、刷盘、关闭 MQ 消费者、提交 offset。

                ## shutdown vs shutdownNow
                - `shutdown()`：不接收新任务，队列里的任务继续执行完 → **不丢任务**；
                - `shutdownNow()`：中断工作线程，返回未执行的任务 → **会丢任务**，
                  只适合「必须马上停」或超时兜底。

                ## Spring Boot 里怎么做
                    server.shutdown=graceful
                    spring.lifecycle.timeout-per-shutdown-phase=30s
                `@PreDestroy` 里 shutdown 线程池；设置
                `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)`。

                ## K8s 的两个易错点
                - 只配 `terminationGracePeriodSeconds` 不够，还要 `preStop` sleep 几秒等摘流量；
                - SIGTERM 后立即退出，会导致「Pod 已下线但请求还在飞」的 502。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 所有线程池都要注册到统一的「优雅停机管理器」，由 JVM shutdown hook 触发。
                - awaitTermination 必须有超时，且超时后要记录「丢弃了多少任务」。
                - Web 容器的优雅停机时间和 K8s 的 gracePeriod 要匹配（前者 ≤ 后者）。
                - 消息消费者：先停止拉取，再处理完在途消息，最后提交 offset 并关闭。
                - 定时任务：停机时把「正在执行」的状态落库，重启后能续跑。
                - 灰度发布前先演练停机：`kill -TERM <pid>` 观察日志与在途请求。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 优雅停机：20 个 100ms 的任务，一个都不丢");
        ThreadPoolExecutor pool = ctx.executor("graceful-pool", 4, 4, 100);
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(4);
        for (int i = 0; i < 20; i++) {
            pool.execute(() -> {
                started.countDown();
                sleepQuietly(100);
                completed.incrementAndGet();
            });
        }
        started.await();
        long begin = System.nanoTime();
        pool.shutdown(); // 不再接收新任务，队列里的继续跑
        boolean terminated = pool.awaitTermination(10, TimeUnit.SECONDS);
        long cost = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("shutdown + awaitTermination = %s，完成 %d/20，耗时 %d ms",
                terminated, completed.get(), cost);
        ctx.verify("所有任务都完成了", completed.get() == 20);
        ctx.verify("线程池已完全终止", pool.isTerminated());

        ctx.step("② 粗暴停机：shutdownNow 直接砍，在途任务被中断");
        ThreadPoolExecutor brutal = ctx.executor("brutal-pool", 4, 4, 100);
        AtomicInteger completed2 = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        CountDownLatch started2 = new CountDownLatch(4);
        for (int i = 0; i < 20; i++) {
            brutal.execute(() -> {
                started2.countDown();
                try {
                    Thread.sleep(300);
                    completed2.incrementAndGet();
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                }
            });
        }
        started2.await();
        ctx.sleep(100); // 让任务跑起来
        List<Runnable> neverRan = brutal.shutdownNow();
        brutal.awaitTermination(5, TimeUnit.SECONDS);
        ctx.warn("shutdownNow 后：完成 %d，被中断 %d，队列里还有 %d 个没跑",
                completed2.get(), interrupted.get(), neverRan.size());
        ctx.verify("粗暴停机导致任务丢失", completed2.get() < 20);
        ctx.info("丢失 = 中断 %d + 队列 %d = %d 个任务",
                interrupted.get(), neverRan.size(), interrupted.get() + neverRan.size());

        ctx.step("③ 生产模板：先礼后兵");
        ctx.info("// 1. 从注册中心下线，等待 10s 让流量走掉");
        ctx.info("// 2. pool.shutdown();");
        ctx.info("// 3. if (!pool.awaitTermination(30, SECONDS)) {");
        ctx.info("//        lost = pool.shutdownNow();");
        ctx.info("//        log.error(\"强制停机，丢弃 {} 个任务\", lost.size());");
        ctx.info("//    }");
        ctx.info("// 4. 关闭连接池 / MQ consumer / 刷盘");
        ctx.verify("shutdown 与 shutdownNow 的取舍 = 可用性 vs 停机速度", true);

        ctx.step("④ Spring Boot 配置");
        ctx.info("server.shutdown=graceful");
        ctx.info("spring.lifecycle.timeout-per-shutdown-phase=30s");
        ctx.info("ThreadPoolTaskExecutor#setWaitForTasksToCompleteOnShutdown(true)");
        ctx.info("ThreadPoolTaskExecutor#setAwaitTerminationSeconds(30)");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
