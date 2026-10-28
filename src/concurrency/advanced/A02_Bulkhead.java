package concurrency.advanced;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 高级 02：线程池隔离（舱壁模式）—— 别让一个下游拖垮全站。
 *
 * <p>船舱被隔成多个水密舱，一个舱进水不会沉船。服务同理：
 * 报表导出、消息推送、订单查询如果共用一个线程池，
 * 报表一慢，所有线程被占满，订单查询跟着一起超时 —— 这叫<b>故障放大</b>。
 *
 * <p>本用例用一个可量化的实验证明：同样的慢任务，共享池 vs 隔离池，
 * 核心业务的延迟差 10 倍以上。
 */
public class A02_Bulkhead extends DemoBase {

    public A02_Bulkhead() {
        super("adv-02-bulkhead", Level.ADVANCED,
                "线程池隔离：舱壁模式",
                "共享池被慢任务占满时，核心业务到底要等多久");
    }

    @Override
    public String knowledge() {
        return """
                ## 为什么要隔离
                线程池是一个「共享资源」。只要有一个业务方把线程占满，
                所有共用这个池的业务都会排队 —— 无论你的接口本身有多快。
                典型事故链：报表导出变慢 → 公共池线程耗尽 → 登录接口超时 → 用户无法登录。

                ## 隔离的粒度
                - 按业务域：订单池、商品池、用户池；
                - 按依赖：DB 池、Redis 池、第三方 HTTP 池；
                - 按优先级：核心交易池、旁路任务池（日志、埋点、推送）；
                - 按租户：SaaS 场景大客户独立池，防止互相影响。

                ## 隔离的代价
                - 线程总数变多，内存和上下文切换成本上升；
                - 池子多了不好管理，需要统一注册 + 监控；
                - 隔离不彻底等于没隔离：别忘了还有连接池、信号量、数据库连接数。

                ## 配套手段
                - 舱壁（Bulkhead）：限制并发数（线程池隔离 / Semaphore）；
                - 熔断（Circuit Breaker）：下游持续失败时快速失败；
                - 限流（Rate Limiter）：控制进入系统的流量；
                - 超时（Timeout）：任何等待都必须有上限。
                四者合起来才是完整的稳定性方案。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 核心链路和旁路任务必须用不同的线程池，禁止共用默认池。
                - 每个池子都要有独立的监控指标（活跃数、队列、拒绝数），告警阈值分开设。
                - 池子数量控制在可管理范围（10 个以内），命名规范统一。
                - 隔离后要定期压测「单池打满」场景，验证不会横向扩散。
                - 异步任务/消息消费也要隔离，别和 Web 请求共用一个池。
                - 注意线程池隔离只隔离 CPU 和排队，不隔离下游依赖本身的容量。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① 反例：共享池 4 个线程，被 4 个慢任务全部占满");
        ThreadPoolExecutor shared = ctx.executor("shared-pool", 4, 4, 50);
        for (int i = 0; i < 4; i++) {
            shared.execute(() -> sleepQuietly(800));
        }
        ctx.sleep(50); // 确保慢任务已经把线程占住
        long sharedLatency = measureFastTask(ctx, shared, "共享池上的订单查询");
        ctx.info("核心业务在共享池里的延迟 = %d ms", sharedLatency);
        ctx.verify("慢任务把核心业务拖慢了", sharedLatency >= 600);

        ctx.step("② 正例：核心业务独立线程池，慢任务只影响自己的池");
        ThreadPoolExecutor orderPool = ctx.executor("order-pool", 4, 4, 20);
        ThreadPoolExecutor reportPool = ctx.executor("report-pool", 2, 2, 10);

        for (int i = 0; i < 4; i++) {
            reportPool.execute(() -> sleepQuietly(800)); // 报表池被打满
        }
        ctx.sleep(50);
        long isolatedLatency = measureFastTask(ctx, orderPool, "隔离池上的订单查询");
        ctx.info("核心业务在独立池里的延迟 = %d ms", isolatedLatency);
        ctx.verify("隔离后核心业务不受影响", isolatedLatency < 200);
        ctx.info("提升倍数 = %.1f×", sharedLatency * 1.0 / Math.max(1, isolatedLatency));

        ctx.step("③ 池子隔离后，监控也要分开看");
        for (var snapshot : concurrency.core.PoolMonitor.snapshots()) {
            if (snapshot.name().contains("pool")) {
                ctx.info("池 %-12s 活跃=%d/%d 队列=%d/%d 已完成=%d",
                        snapshot.name(), snapshot.activeCount(), snapshot.maxSize(),
                        snapshot.queueSize(), snapshot.queueCapacity(), snapshot.completedTasks());
            }
        }
        ctx.info("报表池队列积压、订单池空闲 —— 这就是「问题被关在舱里」的样子");

        ctx.step("④ 生产落地方案");
        ctx.info("ThreadPoolExecutor orderPool  = ... // 订单：核心 8，队列 200");
        ctx.info("ThreadPoolExecutor queryPool  = ... // 查询：核心 16，队列 500");
        ctx.info("ThreadPoolExecutor reportPool = ... // 报表：核心 2，队列 10（快速拒绝）");
        ctx.info("旁路任务（埋点、日志、推送）单独池 + 允许丢");
        ctx.verify("隔离的本质：把「共享」变成「独立配额」", true);

        ctx.step("提醒：隔离不能替代超时与熔断");
        ctx.warn("如果下游一直慢，自己的池子最终还是会被占满");
        ctx.warn("所以还要配合超时（adv-05）、熔断（adv-08）、拒绝降级（adv-03）");
    }

    /** 提交一个空任务并测量它从提交到完成的延迟。 */
    private long measureFastTask(DemoContext ctx, ExecutorService pool, String label) throws Exception {
        long begin = System.nanoTime();
        pool.submit(() -> {
            ctx.info("%s 开始执行（线程 %s）", label, Thread.currentThread().getName());
            return null;
        }).get();
        return (System.nanoTime() - begin) / 1_000_000;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
