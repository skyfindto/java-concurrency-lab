package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 初级 03：start() 与 run() 的区别，以及「线程只能启动一次」。
 *
 * <p>这是新手面试第一题，也是线上事故的常见来源：
 * 有人调了 {@code thread.run()} 以为异步了，结果所有任务都在主线程串行执行，
 * 接口 RT 从 50ms 变成 5s。
 */
public class B03_StartVsRun extends DemoBase {

    public B03_StartVsRun() {
        super("basic-03-start-vs-run", Level.BASIC,
                "start() vs run()：一个开线程，一个只是方法调用",
                "为什么 run() 不会异步？为什么同一个 Thread 不能 start 两次？");
    }

    @Override
    public String knowledge() {
        return """
                ## start() 做了什么
                1. 检查 threadStatus，非 0（已启动）直接抛 IllegalThreadStateException；
                2. 把线程加入 ThreadGroup；
                3. 调用 native 方法 start0()，由 JVM 创建/分配一个 OS 线程；
                4. 新线程执行 run()，run() 结束后 JVM 负责清理，状态变为 TERMINATED。

                ## run() 只是普通方法
                直接调用 t.run() 不会创建任何线程，它就是一次普通的方法调用，
                阻塞调用者、使用调用者的栈，异步、并发统统不存在。

                ## 为什么不能 start 两次
                Thread 对象里的 threadStatus 只允许从 0(NEW) 迁移一次。
                线程是「一次性」资源：执行完即销毁，状态不可逆。
                需要「重复执行」的场景，用线程池复用工作线程，而不是复用 Thread 对象。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 提交任务用 executor.execute()/submit()，不要自己 new Thread。
                - 如果一定要用 Thread，start() 之后不要保留引用去做「重启」。
                - 面试常问「线程池怎么复用线程」：工作线程跑一个 while 循环，从队列里不断取任务，
                  线程本身不退出，所以「复用」的是线程，不是 Thread 对象。
                - 看到 IllegalThreadStateException，先找是不是同一个 Thread 被 start 了两次。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 直接调用 run()：仍然在 main 线程里执行");
        Thread thread = new Thread(
                () -> ctx.info("run() 内部：当前线程 = %s", Thread.currentThread().getName()),
                "never-started");
        thread.run();
        ctx.info("调用方：当前线程 = %s", Thread.currentThread().getName());
        ctx.verify("run() 没有启动新线程（状态仍是 NEW）", thread.getState() == Thread.State.NEW);

        ctx.step("② 调用 start()：JVM 创建新线程执行 run()");
        Thread real = new Thread(
                () -> ctx.info("start() 内部：当前线程 = %s", Thread.currentThread().getName()),
                "really-started");
        real.start();
        real.join();
        ctx.verifyEquals("start() 之后线程执行完毕", Thread.State.TERMINATED, real.getState());

        ctx.step("③ start() 两次 → IllegalThreadStateException");
        try {
            real.start();
            ctx.error("居然没抛异常？不符合预期");
        } catch (IllegalThreadStateException e) {
            ctx.ok("第二次 start() 抛出 %s", e.getClass().getName());
            ctx.verify("异常类型正确", "java.lang.IllegalThreadStateException".equals(e.getClass().getName()));
        }

        ctx.step("④ 需要重复执行：交给线程池复用工作线程");
        ExecutorService pool = ctx.fixedPool("reuse-pool", 1);
        Set<String> workers = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                workers.add(Thread.currentThread().getName());
                ctx.info("第 %d 个任务由 %s 执行", workers.size(), Thread.currentThread().getName());
            }).get();
        }
        ctx.verifyEquals("3 个任务复用同 1 个工作线程", 1, workers.size());

        ctx.step("结论");
        ctx.info("run()  = 同步调用，骗自己");
        ctx.info("start()= 真正异步，但线程只能用一次");
        ctx.info("线程池 = 复用工作线程，生产唯一解");
    }
}
