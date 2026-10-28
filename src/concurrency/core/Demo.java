package concurrency.core;

/**
 * 一个「可运行的并发知识点用例」。
 *
 * <p>设计意图：把「知识」和「能跑的代码」绑在一起。
 * 每个实现类既可以在 Web 控制台里点一下运行，也可以 {@code java -cp ... Cli --run xxx}
 * 在命令行跑，还可以直接在 IDEA 里 new 出来调 {@code run(ctx)}。
 *
 * <p>实现约定：
 * <ul>
 *   <li>{@link #id()} 全局唯一，建议 {@code 层级-序号-英文短名}，便于搜索；</li>
 *   <li>{@link #knowledge()} 用纯文本 + 换行，前端会渲染成知识点卡片；</li>
 *   <li>{@link #bestPractice()} 写「生产上怎么写 / 常见坑」，这是最值钱的部分；</li>
 *   <li>{@link #run(DemoContext)} 里用 {@code ctx.step()} 分步骤，
 *       用 {@code ctx.verify()} 做断言，让输出本身就是一份讲义。</li>
 * </ul>
 */
public interface Demo {

    /** 全局唯一 id，例如 {@code basic-07-race-condition}。 */
    String id();

    /** 所属难度层级。 */
    Level level();

    /** 用例标题（中文，尽量一句话说清「演示什么」）。 */
    String title();

    /** 一句话摘要，列表页展示。 */
    default String summary() {
        return "";
    }

    /** 知识点讲解（多行纯文本）。 */
    default String knowledge() {
        return "";
    }

    /** 最佳实践 / 生产注意点（多行纯文本）。 */
    default String bestPractice() {
        return "";
    }

    /** 超时保护：超过该时长由 Runner 强制中断，避免用例把控制台挂死。 */
    default long timeoutMillis() {
        return 30_000L;
    }

    /** 用例主体。异常会被 Runner 捕获并标记为失败，不会影响其他用例。 */
    void run(DemoContext ctx) throws Exception;
}
