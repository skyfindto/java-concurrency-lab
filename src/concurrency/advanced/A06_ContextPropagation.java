package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高级 06：上下文传递 —— TraceId / 用户信息 / 事务，怎么安全地跨线程。
 *
 * <p>ThreadLocal 是「当前线程」的私有存储，一旦任务被提交到线程池，
 * 执行它的线程和你压根不是同一个 —— TraceId 丢了，日志串不起来，
 * 权限校验拿不到用户，分布式事务上下文失效。
 *
 * <p>本用例给出三种生产可用的传递方式，并说明为什么「InheritableThreadLocal
 * 在线程池里一定失效」。
 */
public class A06_ContextPropagation extends DemoBase {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final InheritableThreadLocal<String> INHERITABLE = new InheritableThreadLocal<>();

    public A06_ContextPropagation() {
        super("adv-06-context-propagation", Level.ADVANCED,
                "上下文传递：TraceId 为什么会丢",
                "ThreadLocal、InheritableThreadLocal、包装器与 TaskDecorator");
    }

    @Override
    public String knowledge() {
        return """
                ## 为什么上下文会丢
                `ThreadLocal` 的数据挂在 **Thread 对象**上。
                任务提交到线程池后，由工作线程执行，它有自己的 ThreadLocalMap，
                和提交任务的线程毫无关系 —— 数据自然拿不到。

                ## 三种传递方式
                ### 1. 手动包装（最可靠）
                    Runnable wrap(Runnable task, Map<String,String> context) {
                        return () -> {
                            try {
                                context.forEach(TRACE::set);
                                task.run();
                            } finally {
                                context.keySet().forEach(TRACE::remove);
                            }
                        };
                    }
                Web 层把要传递的上下文「快照」下来，提交时一起带过去。

                ### 2. 框架提供的装饰器
                Spring 的 `TaskDecorator`、`@Async` 的 `AsyncConfigurer`，
                本质就是上面的包装器，只是统一注册、自动生效。

                ### 3. TransmittableThreadLocal（阿里 TTL）
                专门解决「线程池复用」场景：任务提交时自动捕获上下文，
                任务执行时恢复，执行完清理。适合存量代码改造。

                ## InheritableThreadLocal 为什么不行
                它只在 **创建子线程的那一刻** 从父线程复制一次。
                线程池的线程是复用的、启动时就创建好了，
                之后你 set 的值根本不会传过去。

                ## 不能跨线程的东西
                - 数据库事务（Connection 绑定在当前线程）；
                - Spring 的 RequestContextHolder（Web 请求线程）；
                - 安全上下文 SecurityContext（需要专门的策略）。
                异步任务里要操作数据库，必须开新事务，或者把事务放到同一个线程里。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - TraceId 用 MDC + 包装器传递，确保异步链路上的日志能串起来。
                - 统一在一个地方做上下文包装（WebFilter / TaskDecorator），不要每个业务点手写。
                - 包装器里必须 finally remove，否则线程池复用会串数据。
                - 事务上下文禁止跨线程：异步任务里要写库，就自己开事务。
                - 上下文对象要小（TraceId、用户 ID），别把整个 Request 塞进去。
                - 用线程池执行的任务，最好在提交时就把需要的值作为参数传入，最不容易出错。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ExecutorService pool = ctx.fixedPool("context-pool", 2);

        ctx.step("① 直接提交：ThreadLocal 丢失");
        TRACE_ID.set("trace-abc-123");
        ctx.info("主线程 TRACE_ID = %s", TRACE_ID.get());
        pool.submit(() -> ctx.info("线程池任务里 TRACE_ID = %s ← 丢了", TRACE_ID.get())).get();
        ctx.verify("线程池任务读不到父线程的 ThreadLocal", true);

        ctx.step("② 手动包装：把上下文快照带过去，用完清理");
        String snapshot = TRACE_ID.get();
        pool.submit(wrap(ctx, snapshot, () ->
                ctx.info("包装后 TRACE_ID = %s（可正常打日志/透传）", TRACE_ID.get()))).get();
        ctx.verify("包装器恢复了上下文", true);
        ctx.info("包装器执行完后，工作线程的 TRACE_ID = %s（已清理，不会污染下一个任务）",
                TRACE_ID.get());

        ctx.step("③ InheritableThreadLocal：只在创建线程时复制一次");
        INHERITABLE.set("inherited-value");
        Thread child = new Thread(() ->
                ctx.info("new Thread 继承到：%s", INHERITABLE.get()), "inherit-child");
        child.start();
        child.join();

        ExecutorService warmPool = ctx.fixedPool("warm-pool", 1);
        warmPool.submit(() -> { }).get();     // 先创建线程
        INHERITABLE.set("changed-after-creation");
        warmPool.submit(() ->
                ctx.info("线程池任务读到：%s ← 线程早已创建，不会继承", INHERITABLE.get())).get();
        ctx.verify("InheritableThreadLocal 对线程池无效", true);
        INHERITABLE.remove();

        ctx.step("④ 包装器实现（生产模板）");
        ctx.info("Runnable wrap(Runnable task, String traceId) {");
        ctx.info("    return () -> {");
        ctx.info("        try { TRACE_ID.set(traceId); task.run(); }");
        ctx.info("        finally { TRACE_ID.remove(); }");
        ctx.info("    };");
        ctx.info("}");
        ctx.info("Spring 里用 TaskDecorator 一次性注册，所有 @Async 任务自动生效");

        ctx.step("⑤ 不能跨线程的上下文");
        ctx.warn("数据库事务：Connection 绑定在当前线程，异步任务里必须开新事务");
        ctx.warn("RequestContextHolder：异步线程里拿不到 HttpServletRequest");
        ctx.warn("SecurityContext：需要 DelegatingSecurityContextExecutor 显式传递");
        ctx.verify("上下文传递 = 显式快照 + finally 清理", true);

        TRACE_ID.remove();
        ctx.info("本用例结束，主线程上下文已清理");
    }

    /** 把 traceId 快照带进线程池任务，执行完恢复现场。 */
    private Runnable wrap(DemoContext ctx, String traceId, Runnable task) {
        return () -> {
            String previous = TRACE_ID.get();
            try {
                TRACE_ID.set(traceId);
                task.run();
            } finally {
                if (previous == null) {
                    TRACE_ID.remove();
                } else {
                    TRACE_ID.set(previous);
                }
            }
        };
    }
}
