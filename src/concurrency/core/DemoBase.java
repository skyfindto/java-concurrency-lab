package concurrency.core;

/**
 * {@link Demo} 的骨架实现，固定 id / 层级 / 标题 / 摘要，减少样板代码。
 */
public abstract class DemoBase implements Demo {

    private final String id;
    private final Level level;
    private final String title;
    private final String summary;

    protected DemoBase(String id, Level level, String title, String summary) {
        this.id = id;
        this.level = level;
        this.title = title;
        this.summary = summary;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final Level level() {
        return level;
    }

    @Override
    public final String title() {
        return title;
    }

    @Override
    public final String summary() {
        return summary;
    }
}
