package concurrency.observe;

import concurrency.core.PoolMonitor;
import concurrency.core.Threads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 故障演练场：主动制造线上最常见的 5 类并发故障，然后用「一键诊断 / jstack」去排查。
 *
 * <p>为什么要有这个东西？因为「见过故障」和「读过博客」是两回事。
 * 亲手造一次 CPU 打满、线程泄漏、池子打满，再看诊断报告，你对指标的敏感度会完全不一样。
 *
 * <p>所有演练都做了保护：
 * <ul>
 *   <li>线程全部 daemon，进程不会因为演练退不出去；</li>
 *   <li>内存演练有上限（不超过堆的 1/3），不会把服务直接 OOM 掉；</li>
 *   <li>可以一键全部停止；死锁演练用的是可中断锁，停止时会自动解开。</li>
 * </ul>
 */
public final class FaultLab {

    /** 防止 JIT 把空转循环优化掉。 */
    private static volatile double sink;

    private record Task(String detail, long since, String stopNote, Runnable stop) {
    }

    private static final Map<String, Task> ACTIVE = new ConcurrentHashMap<>();
    private static final Set<String> SUPPORTED =
            new LinkedHashSet<>(List.of("cpu", "deadlock", "leak", "heap", "saturate"));
    private static final AtomicInteger LEAK_SEQ = new AtomicInteger();

    private FaultLab() {
    }

    public static synchronized Map<String, Object> start(String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!SUPPORTED.contains(name)) {
            result.put("ok", false);
            result.put("detail", "不支持的演练类型：" + name);
            return result;
        }
        if (ACTIVE.containsKey(name)) {
            result.put("ok", true);
            result.put("detail", "「" + name + "」已在运行中");
            return result;
        }
        String detail = switch (name) {
            case "cpu" -> startCpu();
            case "deadlock" -> startDeadlock();
            case "leak" -> startLeak();
            case "heap" -> startHeap();
            case "saturate" -> startSaturate();
            default -> "未知演练";
        };
        Task task = ACTIVE.get(name);
        result.put("ok", task != null);
        result.put("detail", detail);
        return result;
    }

    public static synchronized Map<String, Object> stopAll() {
        for (Map.Entry<String, Task> entry : new ArrayList<>(ACTIVE.entrySet())) {
            try {
                entry.getValue().stop().run();
            } catch (RuntimeException ignored) {
                // 停止失败不影响其他演练
            }
        }
        ACTIVE.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("detail", "所有演练已停止");
        return result;
    }

    public static Map<String, Object> status() {
        List<Map<String, Object>> running = new ArrayList<>();
        ACTIVE.forEach((name, task) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("detail", task.detail());
            item.put("since", task.since());
            item.put("stopNote", task.stopNote());
            running.add(item);
        });
        running.sort((a, b) -> Long.compare((Long) a.get("since"), (Long) b.get("since")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running);
        result.put("supported", new ArrayList<>(SUPPORTED));
        return result;
    }

    // ------------------------------------------------------------ 各类演练

    private static String startCpu() {
        int count = Math.max(2, Runtime.getRuntime().availableProcessors());
        AtomicBoolean stop = new AtomicBoolean();
        for (int i = 0; i < count; i++) {
            Thread t = new Thread(() -> {
                double x = 1;
                while (!stop.get()) {
                    x = Math.sin(x) + Math.sqrt(x + 1);
                    sink = x; // 写 volatile，防止循环被优化掉
                }
            }, "lab-cpu-burner-" + i);
            t.setDaemon(true);
            t.start();
        }
        ACTIVE.put("cpu", new Task(count + " 个线程持续做浮点运算，CPU 打满",
                System.currentTimeMillis(), "停止即释放 CPU",
                () -> stop.set(true)));
        return "已启动 " + count + " 个 CPU 打满线程，刷新看 CPU 曲线和热点线程";
    }

    private static String startDeadlock() {
        java.util.concurrent.locks.ReentrantLock a = new java.util.concurrent.locks.ReentrantLock();
        java.util.concurrent.locks.ReentrantLock b = new java.util.concurrent.locks.ReentrantLock();

        Thread t1 = new Thread(() -> {
            a.lock();
            try {
                sleep(200);
                b.lockInterruptibly();
                try {
                    sleep(5000);
                } finally {
                    b.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                a.unlock();
            }
        }, "lab-deadlock-1");
        t1.setDaemon(true);

        Thread t2 = new Thread(() -> {
            b.lock();
            try {
                sleep(200);
                a.lockInterruptibly();
                try {
                    sleep(5000);
                } finally {
                    a.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                b.unlock();
            }
        }, "lab-deadlock-2");
        t2.setDaemon(true);

        t1.start();
        t2.start();
        ACTIVE.put("deadlock", new Task("两个线程以相反顺序持有两把锁，形成环路",
                System.currentTimeMillis(),
                "这里用 ReentrantLock 便于演示后中断解除；生产中的 synchronized 死锁只能重启",
                () -> {
                    t1.interrupt();
                    t2.interrupt();
                }));
        return "死锁已形成：lab-deadlock-1 持 a 等 b，lab-deadlock-2 持 b 等 a";
    }

    private static String startLeak() {
        List<Thread> created = java.util.Collections.synchronizedList(new ArrayList<>());
        ScheduledExecutorService spawner = Executors.newSingleThreadScheduledExecutor(
                Threads.named("lab-leak-spawner", true));
        spawner.scheduleAtFixedRate(() -> {
            if (created.size() >= 2000) {
                return;
            }
            for (int i = 0; i < 10; i++) {
                Thread t = new Thread(() -> sleep(600_000), "lab-leak-" + LEAK_SEQ.incrementAndGet());
                t.setDaemon(true);
                t.start();
                created.add(t);
            }
        }, 0, 200, TimeUnit.MILLISECONDS);

        ACTIVE.put("leak", new Task("每秒新增约 50 个平台线程，模拟线程泄漏",
                System.currentTimeMillis(),
                "停止会中断并回收全部演练线程",
                () -> {
                    spawner.shutdownNow();
                    synchronized (created) {
                        created.forEach(Thread::interrupt);
                        created.clear();
                    }
                }));
        return "线程泄漏已开始：观察线程数曲线与线程名前缀 topN";
    }

    private static String startHeap() {
        List<byte[]> blobs = new ArrayList<>();
        long max = Runtime.getRuntime().maxMemory();
        long cap = Math.min((long) (max * 0.3), 400L * 1024 * 1024);
        AtomicLong allocated = new AtomicLong();

        ScheduledExecutorService allocator = Executors.newSingleThreadScheduledExecutor(
                Threads.named("lab-heap-allocator", true));
        allocator.scheduleAtFixedRate(() -> {
            if (allocated.get() >= cap) {
                return;
            }
            try {
                byte[] blob = new byte[8 * 1024 * 1024];
                blob[0] = 1;
                blobs.add(blob);
                allocated.addAndGet(blob.length);
            } catch (OutOfMemoryError e) {
                allocator.shutdown();
            }
        }, 0, 120, TimeUnit.MILLISECONDS);

        ACTIVE.put("heap", new Task("每 120ms 分配 8MB 并强引用，上限 " + (cap / 1024 / 1024) + "MB",
                System.currentTimeMillis(),
                "停止会释放引用并触发一次 GC",
                () -> {
                    allocator.shutdownNow();
                    blobs.clear();
                    System.gc();
                }));
        return "内存增长演练已开始：观察堆使用率与 GC 次数";
    }

    private static String startSaturate() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), Threads.named("lab-saturate"),
                PoolMonitor.counting("lab-saturate", new ThreadPoolExecutor.AbortPolicy()));
        PoolMonitor.register("lab-saturate", pool);

        int rejected = 0;
        for (int i = 0; i < 40; i++) {
            try {
                pool.execute(() -> sleep(8000));
            } catch (RejectedExecutionException e) {
                rejected++;
            }
        }
        final int rejectedCount = rejected;
        ACTIVE.put("saturate", new Task("2 个核心线程 + 队列 2，提交 40 个 8 秒任务，拒绝 " + rejectedCount + " 个",
                System.currentTimeMillis(),
                "停止会 shutdownNow 并注销监控",
                () -> {
                    pool.shutdownNow();
                    PoolMonitor.unregister("lab-saturate");
                }));
        return "线程池已打满：2 个线程在跑、2 个排队、其余全部被拒绝";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
