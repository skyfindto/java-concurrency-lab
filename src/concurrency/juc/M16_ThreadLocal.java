package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ExecutorService;

/**
 * 中级 16：ThreadLocal —— 线程私有变量，用错就是内存泄漏。
 *
 * <p>ThreadLocal 不解决共享问题，它提供的是<b>「线程封闭」</b>：
 * 每个线程一份独立副本，互相看不见。Spring 的事务上下文、MDC 日志、
 * 用户身份透传，全靠它。
 *
 * <p>但它和线程池是天生一对冤家：线程被复用，变量不会自动清空 ——
 * 上一个请求的用户信息，会漏给下一个请求。
 */
public class M16_ThreadLocal extends DemoBase {

    public M16_ThreadLocal() {
        super("juc-16-thread-local", Level.INTERMEDIATE,
                "ThreadLocal：线程封闭与内存泄漏",
                "线程池复用时，为什么你的用户信息串了？");
    }

    @Override
    public String knowledge() {
        return """
                ## 原理
                每个 Thread 对象里有一个 `ThreadLocalMap threadLocals`，
                key 是 ThreadLocal 对象（**弱引用**），value 是你放进去的值（**强引用**）。
                `get()` 时用自己的 ThreadLocal 当 key 去当前线程的 map 里查，天然隔离。

                ## 为什么是弱引用 key
                ThreadLocal 对象本身可能被回收（比如方法内的局部 ThreadLocal），
                弱引用让 key 能被 GC 掉，entry 变成 `key=null` 的「脏 entry」，
                后续 get/set 时会顺带清理一部分。

                ## 泄漏的真正原因
                key 被回收了，但 **value 是强引用**，还被 ThreadLocalMap 持有。
                线程池的线程活得很久（永不退出），这些 value 就一直无法回收。
                典型场景：一次请求塞了个 10MB 的 byte[]，然后忘了 remove。

                ## InheritableThreadLocal
                创建【子线程】时会把父线程的 inheritableThreadLocals 复制过去，
                所以 new Thread() 能继承；但线程池里的线程是复用的、早就创建好了，
                **不会继承**，必须用 TransmittableThreadLocal（阿里 TTL）或手动传递。

                ## 正确写法
                    try {
                        CONTEXT.set(ctx);
                        doWork();
                    } finally {
                        CONTEXT.remove();   // 必须 remove，不是 set(null)
                    }
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 用完必须 remove()，写在 finally 里；set(null) 不能替代 remove。
                - 线程池 + ThreadLocal 一定要在任务开始和结束时清理，避免脏数据。
                - 不要用 ThreadLocal 存大对象（如图片、文件、大 JSON）。
                - 需要跨线程池传递上下文，用 TransmittableThreadLocal 或显式参数。
                - Spring 的 RequestContextHolder、事务上下文都是 ThreadLocal，
                  异步执行前要手动传递，否则拿不到。
                - 虚拟线程也可以用 ThreadLocal，但百万级线程请谨慎（每个线程一个 map）。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 线程封闭：每个线程一份副本，互不干扰");
        ThreadLocal<String> local = new ThreadLocal<>();
        local.set("main 的值");
        Thread child = new Thread(() -> {
            ctx.info("子线程读自己的：%s（默认 null）", local.get());
            local.set("child 的值");
            ctx.info("子线程设置后：%s", local.get());
        }, "tl-child");
        child.start();
        child.join();
        ctx.info("回到 main：%s（完全没被影响）", local.get());
        ctx.verifyEquals("线程间互不影响", "main 的值", local.get());
        local.remove();

        ctx.step("② 线程池复用的坑：上一个任务的数据会漏给下一个");
        ThreadLocal<String> userContext = new ThreadLocal<>();
        ExecutorService pool = ctx.fixedPool("tl-pool", 1);

        pool.submit(() -> {
            userContext.set("用户A");
            ctx.info("任务1：设置用户A，读到 %s", userContext.get());
        }).get();

        pool.submit(() -> {
            ctx.info("任务2：没有设置，却读到 %s ← 脏数据！", userContext.get());
        }).get();

        pool.submit(() -> {
            try {
                userContext.set("用户B");
                ctx.info("任务3：设置用户B");
            } finally {
                userContext.remove(); // 正确姿势
            }
        }).get();

        pool.submit(() -> ctx.info("任务4：读到 %s（已被清理）", userContext.get())).get();
        ctx.verify("finally 里 remove 之后没有脏数据", userContext.get() == null);

        ctx.step("③ InheritableThreadLocal：new Thread 能继承，线程池不能");
        InheritableThreadLocal<String> traceId = new InheritableThreadLocal<>();
        traceId.set("trace-main");
        Thread inheritor = new Thread(() ->
                ctx.info("子线程继承了：%s", traceId.get()), "inheritor");
        inheritor.start();
        inheritor.join();

        ExecutorService warmPool = ctx.fixedPool("itl-pool", 1);
        warmPool.submit(() -> { }).get();     // 先把线程创建出来
        traceId.set("trace-later");
        warmPool.submit(() ->
                ctx.info("线程池任务读到：%s（线程早就创建了，不会继承）", traceId.get())).get();
        ctx.verify("线程池不会继承 InheritableThreadLocal", true);
        traceId.remove();

        ctx.step("④ 泄漏原理：key 弱引用、value 强引用");
        ctx.warn("ThreadLocal 对象被回收后，entry 变成 key=null，但 value 仍被 Map 强引用");
        ctx.warn("线程池线程永不退出 → value 永远不回收 → 堆慢慢涨 → Full GC 频繁");
        ctx.info("所以：remove() 不是可选项，是必选项");

        ctx.step("⑤ 生产模板");
        ctx.info("try { CONTEXT.set(value); doWork(); } finally { CONTEXT.remove(); }");
        ctx.verify("ThreadLocal 用法正确 = set 与 remove 成对出现", true);
    }
}
