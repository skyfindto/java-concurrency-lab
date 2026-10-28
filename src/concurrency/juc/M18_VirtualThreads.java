package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中级 18：虚拟线程（JDK21）—— 并发模型的代际变化。
 *
 * <p>平台线程 = 1:1 映射到 OS 线程，创建贵、栈大（默认 1MB）、数量上千就吃力。
 * 虚拟线程 = M:N 映射，由 JVM 调度到少量「载体线程」上，栈按需增长，
 * 阻塞（sleep/IO/锁）时自动让出载体线程 —— <b>线程便宜到可以「一个请求一个线程」</b>。
 *
 * <p>本用例实测：10 万个虚拟线程、1000 个 IO 任务的两种模型对比。
 */
public class M18_VirtualThreads extends DemoBase {

    public M18_VirtualThreads() {
        super("juc-18-virtual-threads", Level.INTERMEDIATE,
                "虚拟线程：10 万个线程是什么体验",
                "JDK21 虚拟线程 vs 平台线程，什么时候该用、什么时候不该用");
    }

    @Override
    public String knowledge() {
        return """
                ## 是什么
                JDK21 正式发布的轻量级线程（JEP 444）。不再是 OS 线程的一对一映射，
                而是由 JVM 调度到 ForkJoinPool 的「载体线程」（carrier）上运行。
                阻塞操作会让虚拟线程**卸载**（unmount），载体线程转去跑别的虚拟线程。

                ## 关键差异
                | 维度 | 平台线程 | 虚拟线程 |
                |---|---|---|
                | 栈 | 默认 1MB，固定 | 按需增长（几百字节起） |
                | 创建成本 | 微秒~毫秒级 | 纳秒级 |
                | 数量级 | 几千 | 百万 |
                | 调度 | OS | JVM |
                | 适合 | CPU 密集 | IO 密集 |
                | 池化 | 必须 | **不要池化** |

                ## 怎么用
                    Thread.ofVirtual().name("vt-", 0).start(task);
                    Executors.newVirtualThreadPerTaskExecutor()
                第二个最适合替换现有的「一个请求一个任务」场景。

                ## 三个注意事项
                1. **不要池化**：虚拟线程创建极便宜，池化反而增加复杂度；
                2. **synchronized 会 pin 住载体线程**：JDK21 里在 synchronized 块中阻塞，
                   虚拟线程无法卸载，载体被占住；JDK24 已修复，21 上建议改用 ReentrantLock；
                3. **ThreadLocal 慎用**：百万线程 × 每个 ThreadLocal = 巨大内存开销，
                   能用参数传递就别用 ThreadLocal。

                ## 不适合场景
                CPU 密集任务不会因为虚拟线程变快（核数就那么多），
                而且大量 CPU 任务会挤占载体线程，反而更慢。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - IO 密集服务用 `Executors.newVirtualThreadPerTaskExecutor()` 直接替换固定池。
                - 不要为虚拟线程设置池大小，也不要复用虚拟线程对象。
                - 避免在虚拟线程里长时间持有 synchronized；改用 ReentrantLock。
                - 控制并发用 Semaphore 限流，而不是靠线程池大小（虚拟线程没有「池大小」概念）。
                - 监控要看载体线程（平台线程），虚拟线程数量本身没有意义。
                - ThreadLocal 尽量换成方法参数或请求上下文对象。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 创建 10 万个虚拟线程，每个睡 100ms");
        int count = 100_000;
        AtomicInteger finished = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(count);
        long begin = System.nanoTime();
        for (int i = 0; i < count; i++) {
            threads.add(Thread.ofVirtual().name("vt-" + i).unstarted(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finished.incrementAndGet();
            }));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        long elapsed = (System.nanoTime() - begin) / 1_000_000;
        ctx.info("10 万个虚拟线程全部完成，耗时 %d ms", elapsed);
        ctx.verifyEquals("全部执行完毕", count, finished.get());
        ctx.verify("创建的是虚拟线程", threads.get(0).isVirtual());

        ctx.step("② 对比：1000 个「等待 10ms」的任务，两种模型");
        int tasks = 1000;

        ExecutorService platformPool = ctx.fixedPool("platform-io", 10);
        long platformCost = runTasks(platformPool, tasks, 10);

        ExecutorService virtualPool = ctx.virtualExecutor("virtual-io");
        long virtualCost = runTasks(virtualPool, tasks, 10);

        ctx.info("平台线程池（10 线程）耗时 %4d ms", platformCost);
        ctx.info("虚拟线程（每任务一线程）耗时 %4d ms", virtualCost);
        ctx.info("虚拟线程快 %.1f 倍", platformCost * 1.0 / Math.max(1, virtualCost));
        ctx.verify("虚拟线程在 IO 密集场景优势明显", virtualCost < platformCost);

        ctx.step("③ 虚拟线程为什么快：等 IO 时让出载体线程");
        ctx.info("平台线程：等 IO 时 OS 线程被挂起，一个线程只能服务一个请求");
        ctx.info("虚拟线程：等 IO 时 unmount，载体线程立刻去跑其他虚拟线程");
        ctx.info("所以 10 个载体线程可以「同时」服务 10000 个等待中的请求");

        ctx.step("④ 注意事项（JDK21 上尤其重要）");
        ctx.warn("不要池化虚拟线程：newVirtualThreadPerTaskExecutor 用完即弃");
        ctx.warn("不要在虚拟线程里长时间持有 synchronized：会 pin 住载体线程（JDK24 才修复）");
        ctx.warn("ThreadLocal 在百万线程下开销巨大，优先用参数传递");
        ctx.info("CPU 密集任务用虚拟线程没有收益，甚至更慢");

        ctx.step("⑤ 单测/演示中判断虚拟线程");
        Thread vt = Thread.ofVirtual().name("check").start(() -> { });
        vt.join();
        Thread platform = new Thread(() -> { }, "platform-check");
        ctx.verify("Thread.isVirtual() 可判断", vt.isVirtual());
        ctx.verify("平台线程 isVirtual()=false", !platform.isVirtual());
        ctx.info("本用例自身也跑在虚拟线程上（isVirtual=%s），所以别拿 currentThread 判断",
                Thread.currentThread().isVirtual());
    }

    private long runTasks(ExecutorService pool, int tasks, long sleepMillis) throws Exception {
        CountDownLatch done = new CountDownLatch(tasks);
        long begin = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            pool.execute(() -> {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(60, TimeUnit.SECONDS);
        return (System.nanoTime() - begin) / 1_000_000;
    }
}
