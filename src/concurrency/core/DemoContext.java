package concurrency.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用例运行上下文：日志、取消、断言、以及「用例自己创建的线程池」的托管。
 *
 * <p>为什么要托管线程池？因为教学用例最容易犯的错就是「池子创建了不关」，
 * 跑完 60 个用例后 JVM 里飘着几百个非守护线程，页面进程退出不了。
 * {@link DemoRunner} 会在用例结束时自动 {@link #close()}，把用例名下的池子优雅关掉。
 *
 * <p>另外提供三件套，让用例代码本身就是「生产写法示范」：
 * <ul>
 *   <li>{@link #executor} / {@link #fixedPool}：带命名的线程工厂 + 拒绝计数 + 监控注册；</li>
 *   <li>{@link #sleep} / {@link #checkpoint}：可被取消打断的等待；</li>
 *   <li>{@link #verify}：轻量断言，失败也继续跑完，页面上汇总通过/失败数。</li>
 * </ul>
 */
public final class DemoContext implements AutoCloseable {

    /** 用例被取消（超时或人工停止）时抛出，用于快速退出，不要当作 Bug。 */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("用例已取消");
        }
    }

    private final Demo demo;
    private final Log log;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<ExecutorService> ownedPools = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger okCount = new AtomicInteger();
    private final AtomicInteger failCount = new AtomicInteger();
    private volatile boolean cancelled;

    DemoContext(Demo demo, Log log) {
        this.demo = demo;
        this.log = log;
    }

    public Demo demo() {
        return demo;
    }

    public Log log() {
        return log;
    }

    // ---------------------------------------------------------------- 日志

    public void step(String fmt, Object... args) {
        log.step(fmt, args);
    }

    public void info(String fmt, Object... args) {
        log.info(fmt, args);
    }

    public void ok(String fmt, Object... args) {
        log.ok(fmt, args);
    }

    public void warn(String fmt, Object... args) {
        log.warn(fmt, args);
    }

    public void error(String fmt, Object... args) {
        log.error(fmt, args);
    }

    /** 打印当前线程信息，几乎每个用例开头都会用。 */
    public void whoAmI() {
        Thread t = Thread.currentThread();
        log.info("当前线程：name=%s, id=%d, virtual=%s, daemon=%s",
                t.getName(), t.threadId(), t.isVirtual(), t.isDaemon());
    }

    // ---------------------------------------------------------------- 断言

    /** 非致命断言：记录结果，不影响后续步骤。 */
    public boolean verify(String what, boolean condition) {
        if (condition) {
            okCount.incrementAndGet();
            log.ok("断言通过：%s", what);
        } else {
            failCount.incrementAndGet();
            log.error("断言失败：%s", what);
        }
        return condition;
    }

    public boolean verifyEquals(String what, Object expected, Object actual) {
        boolean eq = expected == null ? actual == null : expected.equals(actual);
        if (eq) {
            okCount.incrementAndGet();
            log.ok("断言通过：%s = %s", what, actual);
        } else {
            failCount.incrementAndGet();
            log.error("断言失败：%s，期望=%s，实际=%s", what, expected, actual);
        }
        return eq;
    }

    public int okCount() {
        return okCount.get();
    }

    public int failCount() {
        return failCount.get();
    }

    // ---------------------------------------------------------------- 取消 / 等待

    public boolean cancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
    }

    /** 检查是否已取消，取消则抛出 {@link CancelledException} 让用例立刻退出。 */
    public void checkpoint() {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** 可被打断的 sleep：演示节奏控制用，同时示范「等待必须能被取消」。 */
    public void sleep(long millis) {
        checkpoint();
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        checkpoint();
    }

    /** 等待条件成立（轮询），超时返回 false。生产里更推荐 CountDownLatch / Condition。 */
    public boolean await(String what, long timeoutMillis, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
        log.warn("等待超时：%s（%d ms）", what, timeoutMillis);
        return false;
    }

    // ---------------------------------------------------------------- 线程池托管

    /**
     * 创建一个「生产规格」的线程池：命名线程 + 有界队列 + 拒绝计数 + 监控注册。
     * 用例结束时由框架自动关闭。
     */
    public ThreadPoolExecutor executor(String name, int core, int max, int queueCapacity) {
        return pool(name, core, max, new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                PoolMonitor.rejection(name));
    }

    public ThreadPoolExecutor pool(String name, int core, int max,
                                   BlockingQueue<Runnable> queue, RejectedExecutionHandler handler) {
        return pool(name, core, max, queue, Threads.named(name), handler);
    }

    /** 支持自定义线程工厂的版本（自定义命名/异常处理器时用）。 */
    public ThreadPoolExecutor pool(String name, int core, int max,
                                   BlockingQueue<Runnable> queue, java.util.concurrent.ThreadFactory factory,
                                   RejectedExecutionHandler handler) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                core, Math.max(core, max), 60L, TimeUnit.SECONDS,
                queue, factory, handler);
        pool.allowCoreThreadTimeOut(false);
        PoolMonitor.register(name, pool);
        ownedPools.add(pool);
        return pool;
    }

    public ThreadPoolExecutor fixedPool(String name, int size) {
        return executor(name, size, size, 1024);
    }

    /** 虚拟线程执行器（JDK21）：每个任务一个虚拟线程，用完即弃，不需要池化。 */
    public ExecutorService virtualExecutor(String name) {
        ExecutorService pool = Executors.newThreadPerTaskExecutor(Threads.named(name, true));
        PoolMonitor.registerLoose(name, pool);
        ownedPools.add(pool);
        return pool;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    /** 关闭用例名下所有线程池：先礼后兵，2 秒不结束就 shutdownNow。 */
    @Override
    public void close() {
        List<ExecutorService> copy;
        synchronized (ownedPools) {
            copy = new ArrayList<>(ownedPools);
            ownedPools.clear();
        }
        for (ExecutorService pool : copy) {
            try {
                pool.shutdown();
            } catch (RuntimeException ignored) {
                // 已被用例自己关掉
            }
        }
        for (ExecutorService pool : copy) {
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
