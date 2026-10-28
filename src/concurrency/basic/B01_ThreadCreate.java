package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 初级 01：线程创建的 5 种方式。
 *
 * <p>核心认知：<b>「创建线程」和「提交任务」是两件事</b>。
 * 前三种方式让你直接操纵线程，后两种让你只关心任务、把线程管理交给运行时。
 */
public class B01_ThreadCreate extends DemoBase {

    public B01_ThreadCreate() {
        super("basic-01-thread-create", Level.BASIC,
                "线程创建的 5 种方式",
                "从 new Thread 到虚拟线程：同样是「开一个线程」，代价差 1000 倍");
    }

    @Override
    public String knowledge() {
        return """
                ## 5 种创建方式
                - 继承 Thread：最原始，但 Java 单继承，业务类没法再继承别的类，不推荐。
                - 实现 Runnable：把「任务」和「线程」解耦，是 Callable 的基础。
                - Callable + FutureTask：能拿返回值、能抛受检异常（Runnable 都不行）。
                - 线程池 ExecutorService：生产唯一推荐，复用线程、控制并发、可监控。
                - Thread.ofVirtual()：JDK21 虚拟线程，栈按需增长，可以开百万个。

                ## 创建 ≠ 执行
                new Thread() 只是在堆里造了个普通对象；start() 才向操作系统申请线程并进入就绪队列。
                直接调用 run() 只是普通方法调用，仍然跑在调用者线程里（见 basic-03）。

                ## 代价对比（量级）
                - 平台线程：默认栈 1MB（-Xss），创建/切换要陷入内核，几千个就吃力，上下文切换 ~1-10 微秒。
                - 虚拟线程：堆上的对象，JVM 调度，初始栈几百字节，百万级可行；但只适合 IO 密集（等锁/等网络）。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 业务代码不要 new Thread()：没有命名、没有池化、没有监控、没有拒绝策略，出问题无法治理。
                - 任务用 Runnable / Callable 表达，执行交给统一的 ExecutorService。
                - 一定给线程起名字（自定义 ThreadFactory），否则 jstack 里全是 pool-1-thread-3，无法定位业务。
                - 虚拟线程不要池化：用 Executors.newVirtualThreadPerTaskExecutor()，用完即弃。
                - 虚拟线程适合「等 IO」，不适合 CPU 密集；内部用 synchronized 长时间阻塞还会 pin 住载体线程。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.whoAmI();

        ctx.step("① 实现 Runnable → 交给 Thread（最基础的组合）");
        Thread t1 = new Thread(
                () -> ctx.info("我是子线程 %s，正在执行 Runnable", Thread.currentThread().getName()),
                "demo-runnable");
        t1.start();
        t1.join();
        ctx.verify("子线程已终止", t1.getState() == Thread.State.TERMINATED);

        ctx.step("② 继承 Thread（能用，但把任务和线程绑死了，不推荐）");
        class MyThread extends Thread {
            MyThread() {
                super("demo-extends-thread");
            }

            @Override
            public void run() {
                ctx.info("我是 %s，通过继承 Thread 创建", getName());
            }
        }
        MyThread t2 = new MyThread();
        t2.start();
        t2.join();

        ctx.step("③ Callable + FutureTask：唯一能「带返回值 + 抛异常」的原始方式");
        FutureTask<Integer> task = new FutureTask<>(() -> {
            int sum = 0;
            for (int i = 1; i <= 100; i++) {
                sum += i;
            }
            return sum;
        });
        Thread t3 = new Thread(task, "demo-future-task");
        t3.start();
        ctx.verifyEquals("FutureTask 计算 1..100 的和", 5050, task.get());

        ctx.step("④ 线程池：生产环境的唯一正确答案");
        ExecutorService pool = ctx.fixedPool("demo-create-pool", 2);
        Future<String> future = pool.submit(() -> "返回值来自 " + Thread.currentThread().getName());
        ctx.info("%s", future.get());

        ctx.step("⑤ 虚拟线程（JDK21）：几 KB 就能开一个");
        Thread virtual = Thread.ofVirtual().name("demo-virtual-thread").start(
                () -> ctx.info("我是虚拟线程：isVirtual=%s，name=%s",
                        Thread.currentThread().isVirtual(), Thread.currentThread().getName()));
        virtual.join();
        ctx.verify("虚拟线程 isVirtual() == true", virtual.isVirtual());
        ctx.verify("平台线程 isVirtual() == false", !t1.isVirtual());

        ctx.step("小结：任务（做什么）与执行器（谁来跑）分离，是并发代码可治理的前提");
    }
}
