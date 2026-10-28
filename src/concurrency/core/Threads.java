package concurrency.core;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程相关的工厂方法集合。
 *
 * <p>生产环境第一条军规：<b>给每个线程起名字</b>。
 * 否则 jstack 里全是 {@code pool-1-thread-3}，出问题时你根本不知道是哪个业务的池子。
 */
public final class Threads {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Threads() {
    }

    /** 全局自增序号，给线程命名用（演示代码里方便生成唯一后缀）。 */
    public static int nextId() {
        return SEQ.incrementAndGet();
    }

    /** 命名线程工厂，附带兜底异常处理器（未捕获异常不再静默丢失）。 */
    public static ThreadFactory named(String prefix) {
        return named(prefix, false);
    }

    public static ThreadFactory named(String prefix, boolean daemon) {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + seq.incrementAndGet());
            thread.setDaemon(daemon);
            thread.setUncaughtExceptionHandler((t, e) ->
                    System.err.println("[FATAL] 线程 " + t.getName() + " 未捕获异常: " + e));
            return thread;
        };
    }

    /** 当前线程的简短信息，日志里用。 */
    public static String current() {
        Thread t = Thread.currentThread();
        return t.getName() + "(id=" + t.threadId() + ")";
    }

    /** 打印当前 JVM 所有线程的概览，排查线程泄漏时最直观。 */
    public static String summary() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        int estimate = root.activeCount();
        Thread[] threads = new Thread[estimate * 2 + 16];
        int n = root.enumerate(threads, true);
        long daemon = 0;
        long virtual = 0;
        for (int i = 0; i < n; i++) {
            if (threads[i].isDaemon()) {
                daemon++;
            }
            if (threads[i].isVirtual()) {
                virtual++;
            }
        }
        return "线程总数=" + n + "（守护=" + daemon + ", 虚拟=" + virtual + "）";
    }
}
