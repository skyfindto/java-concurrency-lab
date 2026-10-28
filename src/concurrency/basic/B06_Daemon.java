package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 初级 06：守护线程与 JVM 退出时机。
 *
 * <p>规则只有一条：<b>JVM 会在所有「非守护线程」结束后退出，守护线程会被直接掐断</b>。
 * 换句话说，守护线程不能用来做「必须完成」的工作。
 *
 * <p>这条规则解释了两个线上现象：
 * <ul>
 *   <li>为什么代码里起了个后台线程，程序却迟迟不退出 —— 它是非守护的；</li>
 *   <li>为什么用守护线程写日志/刷盘，进程一停就丢数据 —— 它被强杀了。</li>
 * </ul>
 */
public class B06_Daemon extends DemoBase {

    public B06_Daemon() {
        super("basic-06-daemon", Level.BASIC,
                "守护线程与 JVM 退出时机",
                "为什么 mybatis/日志框架的线程要设成 daemon？为什么优雅停机必须自己控制？");
    }

    @Override
    public String knowledge() {
        return """
                ## 规则
                - JVM 启动时的 main 线程是非守护线程；
                - 只要还有任意一个非守护线程活着，JVM 就不会退出；
                - 所有非守护线程结束后，JVM 立即退出，此时所有守护线程被强制终止（不保证 finally 执行）。

                ## 谁在用守护线程
                - JVM 自身：GC、JIT 编译、Finalizer；
                - 框架：日志异步 appender、监控上报、心跳；
                - 原则：**这个线程挂了也不影响数据正确性**，才适合做 daemon。

                ## 关闭钩子 Runtime.addShutdownHook
                - 正常退出（main 结束、System.exit）和 SIGTERM（kill、Ctrl+C）都会触发；
                - kill -9、断电不会触发；
                - 钩子里的活儿要短，并且要能超时退出，否则进程关不掉。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 线程池里默认的工作线程是【非守护】的：忘记 shutdown 会导致 JVM 退不出去，
                  这也是单元测试「跑完不结束」的头号原因。
                - 业务线程池不要设 daemon：进程退出时任务被强杀，会丢数据、丢消息。
                - 只有「可丢弃」的辅助线程（监控采样、日志异步刷盘）才设 daemon。
                - 优雅停机用 shutdown hook：先停止接收新流量 → 等线程池跑完 → 再关闭资源。
                - Spring 应用用 TaskExecutor + setWaitForTasksToCompleteOnShutdown(true) 更省事。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 平台线程默认不是守护线程");
        Thread platform = new Thread(() -> { }, "platform-check");
        ctx.info("new Thread() 的 isDaemon = %s", platform.isDaemon());
        ctx.verify("新建平台线程默认不是守护线程", !platform.isDaemon());
        ctx.info("当前用例线程 isVirtual=%s, isDaemon=%s（虚拟线程恒为 daemon）",
                Thread.currentThread().isVirtual(), Thread.currentThread().isDaemon());

        ctx.step("② 继承规则：子线程会继承父线程的 daemon 标志");
        Thread inherited = new Thread(() -> { }, "inherit-check");
        ctx.info("在 daemon=%s 的父线程里 new Thread() → isDaemon = %s",
                Thread.currentThread().isDaemon(), inherited.isDaemon());
        ctx.verify("daemon 状态会被继承", inherited.isDaemon() == Thread.currentThread().isDaemon());

        ctx.step("③ 守护线程：主线程结束后会被直接掐断");
        AtomicBoolean daemonRunning = new AtomicBoolean(true);
        Thread daemon = new Thread(() -> {
            while (daemonRunning.get()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            ctx.info("守护线程正常退出（这里演示的是主动停止）");
        }, "daemon-worker");
        daemon.setDaemon(true);
        daemon.start();
        ctx.verify("daemon-worker.isDaemon() == true", daemon.isDaemon());
        ctx.info("守护线程开始工作：%s", daemon.getName());

        ctx.step("④ 非守护线程会阻止 JVM 退出");
        Thread nonDaemon = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ctx.info("非守护线程工作完成");
        }, "non-daemon-worker");
        nonDaemon.setDaemon(false); // 显式声明：不要继承父线程（虚拟线程）的 daemon 属性
        ctx.verify("显式设置为非守护线程", !nonDaemon.isDaemon());
        nonDaemon.start();
        nonDaemon.join(2000);

        ctx.step("⑤ 关闭钩子：给进程一个「说再见」的机会");
        Thread hook = new Thread(() -> System.out.println("[shutdown] 收到退出信号，开始优雅停机"), "demo-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(hook);
        ctx.info("已注册 shutdown hook，正常退出 / SIGTERM 时会执行（kill -9 不会）");
        boolean removed = Runtime.getRuntime().removeShutdownHook(hook);
        ctx.verify("演示结束前把钩子摘掉，避免影响本进程后续退出", removed);

        ctx.step("⑥ 现场线程统计");
        ctx.info("%s", concurrency.core.Threads.summary());

        daemonRunning.set(false);
        daemon.join(1000);
        ctx.step("结论：能被强杀的活儿别交给守护线程，必须完成的活儿别用守护线程");
    }
}
