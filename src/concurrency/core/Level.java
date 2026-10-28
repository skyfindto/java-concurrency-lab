package concurrency.core;

/**
 * 用例难度分级。
 *
 * <p>整个项目按「入门 → 工具 → 生产 → 模式 → 观测」五层组织，
 * 每一层都对应一个真实工作中会追问的问题：
 *
 * <ul>
 *   <li>{@link #BASIC}：线程是什么？为什么我的变量被改了另一个线程看不见？</li>
 *   <li>{@link #INTERMEDIATE}：JUC 那么多类，到底该用哪个？参数怎么填？</li>
 *   <li>{@link #ADVANCED}：线上真敢用吗？超时、隔离、降级、优雅停机怎么做？</li>
 *   <li>{@link #PATTERN}：别人代码里的「优雅并发」到底用了什么套路？</li>
 *   <li>{@link #OBSERVE}：线程池满了、CPU 100%、卡死不动，怎么定位？</li>
 * </ul>
 */
public enum Level {

    BASIC("初级", "基本概念", 1),
    INTERMEDIATE("中级", "JUC 工具", 2),
    ADVANCED("高级", "生产实践", 3),
    PATTERN("设计模式", "并发模式", 4),
    OBSERVE("进阶", "观测排障", 5);

    private final String cn;
    private final String subtitle;
    private final int order;

    Level(String cn, String subtitle, int order) {
        this.cn = cn;
        this.subtitle = subtitle;
        this.order = order;
    }

    /** 中文名，例如「初级」。 */
    public String cn() {
        return cn;
    }

    /** 一句话说明，例如「JUC 工具」。 */
    public String subtitle() {
        return subtitle;
    }

    /** 由浅入深的排序值。 */
    public int order() {
        return order;
    }

    /** 前端/CLI 使用的短代号。 */
    public String code() {
        return name().toLowerCase();
    }
}
