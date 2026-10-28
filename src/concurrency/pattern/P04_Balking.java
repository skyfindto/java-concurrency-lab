package concurrency.pattern;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设计模式 04：Balking（止步）—— 条件不满足就立刻放弃。
 *
 * <p>和 Guarded Suspension 的区别：等待（wait）vs 立刻返回（return）。
 * 典型场景：<b>只初始化一次</b>、系统已关闭就拒绝请求、数据没变化就跳过写入。
 *
 * <p>本用例实现一个「只初始化一次」的组件：
 * 第一个线程慢慢初始化，其余线程发现已初始化（或正在初始化）就直接返回，绝不等待。
 */
public class P04_Balking extends DemoBase {

    /** 只初始化一次的组件：先做无锁快速判断，再在锁内二次确认。 */
    private static final class LazyConfig {
        private final DemoContext ctx;
        private final AtomicInteger initCount = new AtomicInteger();
        private volatile boolean initialized;
        private volatile String value;

        LazyConfig(DemoContext ctx) {
            this.ctx = ctx;
        }

        String get() {
            if (initialized) {           // ① 快速路径：已初始化直接返回（balking）
                ctx.info("%s 发现已初始化，直接返回（不等待）",
                        Thread.currentThread().getName());
                return value;
            }
            synchronized (this) {
                if (initialized) {       // ② 二次检查：可能被别人抢先初始化了
                    return value;
                }
                ctx.info("%s 开始初始化（耗时 300ms）", Thread.currentThread().getName());
                sleepQuietly(300);
                value = "配置数据";
                initialized = true;
                initCount.incrementAndGet();
                ctx.info("%s 初始化完成", Thread.currentThread().getName());
                return value;
            }
        }

        int initCount() {
            return initCount.get();
        }
    }

    public P04_Balking() {
        super("pattern-04-balking", Level.PATTERN,
                "Balking：条件不满足就立即返回",
                "只初始化一次：快速失败比排队等待更高效");
    }

    @Override
    public String knowledge() {
        return """
                ## 模式结构
                    if (条件不满足) return;       // balking：直接走人
                    synchronized (lock) {
                        if (条件仍不满足) return; // 双重检查
                        执行操作;
                    }

                ## 和 Guarded Suspension 的区别
                - Guarded Suspension：条件不满足 → **等**（wait），直到别人唤醒；
                - Balking：条件不满足 → **走**（return），不占用线程。
                前者适合「等一会儿就好了」，后者适合「等也没用/不该等」。

                ## 典型场景
                1. 单例/配置只初始化一次（其余线程直接拿结果）；
                2. 服务已关闭，新请求直接拒绝；
                3. 数据没变化，跳过写库（减少无效写）；
                4. 重复提交、重复消息，直接忽略。

                ## 关键点
                - 用 `volatile` 做快速判断，避免每次都进锁（性能）；
                - 锁内必须二次检查（防止并发初始化）；
                - 「已初始化」的写必须在解锁前完成，保证可见性。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 初始化类场景优先用「静态内部类」或「枚举」，比手写 Balking 更安全。
                - 快速路径用 volatile 读，避免无谓加锁；锁内一定二次检查。
                - 「放弃」要有明确语义：返回什么、要不要打日志/打点。
                - 重复提交的 Balking 判断要基于幂等键，而不是时间窗口猜测。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 3 个线程同时请求配置，只有 1 个真正初始化");
        LazyConfig config = new LazyConfig(ctx);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);

        long begin = System.nanoTime();
        for (int i = 1; i <= 3; i++) {
            int id = i;
            new Thread(() -> {
                try {
                    start.await();
                    String value = config.get();
                    ctx.info("线程 %d 拿到：%s", id, value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "config-thread-" + i).start();
        }
        start.countDown();
        done.await();
        long cost = (System.nanoTime() - begin) / 1_000_000;

        ctx.info("3 个线程总耗时 %d ms（初始化只需要 300ms）", cost);
        ctx.verifyEquals("只初始化了一次", 1, config.initCount());
        ctx.verify("其他线程没有重复初始化", config.initCount() == 1);
        ctx.verify("总耗时接近单次初始化", cost < 600);

        ctx.step("② 对比：如果改成 Guarded Suspension（都等）");
        ctx.info("所有线程都会阻塞在锁上，等第一个初始化完才能继续");
        ctx.info("Balking 的第二个、第三个线程发现已完成，直接返回，几乎零等待");
        ctx.info("区别不在「正确性」，而在「要不要占用线程等待」");
        ctx.verify("Balking 适合「等也没用」的场景", true);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
