package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.List;

/**
 * 初级 09：happens-before 与 JMM —— 并发正确性的「法律条文」。
 *
 * <p>可见性问题不能靠「试」来解决，要靠规则。<b>happens-before（HB）</b> 就是规则：
 * 如果 A happens-before B，那么 A 的所有写操作对 B 都可见，且 A 不会被重排到 B 之后。
 *
 * <p>本用例用一个「volatile 搭桥」的实验来验证 HB 的传递性：
 * 普通变量本身没有同步，但借助对 volatile 的写/读，写线程的所有修改都能被读线程看到。
 */
public class B09_HappensBefore extends DemoBase {

    private static final class Config {
        int threshold;     // 普通字段
        String name;       // 普通字段
        volatile boolean published; // volatile 作为「发布信号」
    }

    /** 演示 final 字段的安全发布：对象一旦构造完成，final 字段的值对所有线程可见。 */
    private static final class ImmutableConfig {
        final int maxConnections;
        final List<String> hosts;

        ImmutableConfig(int maxConnections, List<String> hosts) {
            this.maxConnections = maxConnections;
            this.hosts = List.copyOf(hosts); // 防御性拷贝，外部改不动
        }
    }

    public B09_HappensBefore() {
        super("basic-09-happens-before", Level.BASIC,
                "happens-before 与 JMM：可见性的法律条文",
                "volatile 搭桥：普通字段为什么突然就可见了？");
    }

    @Override
    public String knowledge() {
        return """
                ## 8 条 happens-before 规则
                1. 程序顺序：同一线程内，前面的操作 HB 后面的操作。
                2. 监视器锁：对同一把锁的 unlock HB 后续的 lock。
                3. volatile：对 volatile 变量的写 HB 后续对该变量的读。
                4. 线程启动：Thread.start() HB 新线程中的任何操作。
                5. 线程终止：线程中的所有操作 HB 其他线程从 join() 返回。
                6. 中断：对线程 interrupt() HB 被中断线程检测到中断。
                7. 终结器：对象构造完成 HB 其 finalize() 开始。
                8. 传递性：A HB B，B HB C，则 A HB C。

                ## 关键理解
                HB 描述的是「可见性保证」，不是「执行顺序」。
                没有 HB 关系的两个操作，JVM 和 CPU 可以任意重排、缓存、优化。
                此时程序的行为是「未定义的」—— 可能在你的机器上跑一万次都对。

                ## volatile 搭桥（本用例演示）
                写线程：threshold = 200; name = "prod"; published = true;（volatile 写）
                读线程：if (published) { 读 threshold / name }
                volatile 写之前的【所有】写，对看到 published=true 的线程都可见。
                这就是很多框架「先填数据，再置 ready 标志」的原理。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 想保证可见性，就问自己：这两个线程之间有没有 HB 关系？靠哪条规则建立的？
                - 分享可变对象时，用「不可变对象 + volatile/final」是最省心的组合。
                - 集合类发布后不要再改；要用快照，用 List.copyOf / Map.copyOf（真不可变）。
                - 上线前 Code Review 重点看：共享字段有没有 volatile、集合有没有被并发改、单例是否安全发布。
                - 不要用「加个 sleep 就好了」来修可见性 Bug，那是把概率当保证。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① volatile 搭桥：普通字段借助 volatile 的 HB 关系安全发布");
        Config config = new Config();
        Thread writer = new Thread(() -> {
            config.threshold = 200;
            config.name = "prod";
            config.published = true; // volatile 写：它之前的写全部对读者可见
            ctx.info("写线程完成发布：threshold=200, name=prod, published=true");
        }, "config-writer");

        Thread reader = new Thread(() -> {
            while (!config.published) {
                Thread.onSpinWait();
            }
            ctx.info("读线程看到 published=true，读到 threshold=%d, name=%s",
                    config.threshold, config.name);
            ctx.verifyEquals("HB 保证 threshold 可见", 200, config.threshold);
            ctx.verifyEquals("HB 保证 name 可见", "prod", config.name);
        }, "config-reader");

        reader.start();
        writer.start();
        writer.join(2000);
        reader.join(2000);

        ctx.step("② final 字段：对象构造完成后，final 字段一定可见");
        ImmutableConfig immutable = new ImmutableConfig(50, List.of("10.0.0.1", "10.0.0.2"));
        Thread finalReader = new Thread(() -> {
            ctx.info("读到 maxConnections=%d, hosts=%s",
                    immutable.maxConnections, immutable.hosts);
            ctx.verifyEquals("final 字段可见", 50, immutable.maxConnections);
        }, "final-reader");
        finalReader.start();
        finalReader.join(2000);

        ctx.step("③ 反例：没有 HB 关系，就没有任何保证");
        ctx.warn("下面这段代码【不能】保证读到 200，只是本次可能读到：");
        ctx.info("   int threshold; boolean published;  // 都没有 volatile");
        ctx.info("   写: threshold=200; published=true;");
        ctx.info("   读: while(!published){}; print(threshold);  // 可能是 0");
        ctx.info("没有 volatile 时，JIT 甚至可以把 published=true 重排到 threshold=200 之前");

        ctx.step("④ 用一句话总结");
        ctx.info("volatile / 锁 / final / start / join，本质都是在【建立 HB 关系】");
        ctx.info("并发正确性 = 用这些关系把「写」和「读」串起来");
    }
}
