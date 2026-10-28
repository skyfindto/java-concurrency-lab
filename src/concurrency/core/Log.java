package concurrency.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 用例专用日志：带「序号 + 时间 + 线程名」，既能实时推给页面，也能事后回放。
 *
 * <p>为什么要自己写一个？因为本项目的核心就是「看清线程行为」：
 * 每行日志都必须带上线程名，并且要能按序号增量拉取（页面轮询用）。
 * SLF4J/Logback 也能做，但会引入依赖，这个项目刻意保持「零第三方依赖」。
 *
 * <p>关键设计：
 * <ul>
 *   <li>环形缓冲：只保留最近 {@code capacity} 行，长时间运行的用例不会撑爆内存；</li>
 *   <li>序号单调递增：页面用 {@code since(seq)} 做增量拉取；</li>
 *   <li>监听器异常被吞掉：一个页面连接断了不能影响业务线程。</li>
 * </ul>
 */
public final class Log {

    /** 日志级别。STEP / OK 是教学用级别，方便页面区分「讲解」和「结果」。 */
    public enum Level {
        STEP, INFO, OK, WARN, ERROR
    }

    /** 一行日志。 */
    public record Line(long seq, long ts, Level level, String thread, String msg) {

        private static final DateTimeFormatter TIME =
                DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

        public String time() {
            return TIME.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()));
        }

        /** 单字符标记，终端里一眼区分级别。 */
        public String tag() {
            return switch (level) {
                case STEP -> "▶";
                case INFO -> "·";
                case OK -> "✔";
                case WARN -> "!";
                case ERROR -> "✘";
            };
        }

        /** 命令行回放格式。 */
        public String render() {
            return "%s %s [%-22s] %s".formatted(time(), tag(), thread, msg);
        }
    }

    private final int capacity;
    private final Deque<Line> buffer = new ArrayDeque<>();
    private final List<Consumer<Line>> listeners = new CopyOnWriteArrayList<>();
    private long seq;
    private volatile boolean echo;

    public Log() {
        this(3000);
    }

    public Log(int capacity) {
        this.capacity = Math.max(100, capacity);
    }

    /** 是否同时打印到控制台（CLI / IDEA 直接运行时打开）。 */
    public Log echo(boolean on) {
        this.echo = on;
        return this;
    }

    public void subscribe(Consumer<Line> listener) {
        listeners.add(listener);
    }

    public void unsubscribe(Consumer<Line> listener) {
        listeners.remove(listener);
    }

    public void step(String fmt, Object... args) {
        write(Level.STEP, fmt, args);
    }

    public void info(String fmt, Object... args) {
        write(Level.INFO, fmt, args);
    }

    public void ok(String fmt, Object... args) {
        write(Level.OK, fmt, args);
    }

    public void warn(String fmt, Object... args) {
        write(Level.WARN, fmt, args);
    }

    public void error(String fmt, Object... args) {
        write(Level.ERROR, fmt, args);
    }

    /**
     * 写一行日志。线程安全：序号与环形缓冲在同一把锁内更新，
     * 保证「序号小的行一定先入缓冲」，页面增量拉取不会漏行。
     */
    public void write(Level level, String fmt, Object... args) {
        String msg = format(fmt, args);
        Line line;
        synchronized (this) {
            line = new Line(++seq, System.currentTimeMillis(), level, Thread.currentThread().getName(), msg);
            buffer.addLast(line);
            while (buffer.size() > capacity) {
                buffer.removeFirst();
            }
        }
        if (echo) {
            System.out.println(line.render());
        }
        for (Consumer<Line> listener : listeners) {
            try {
                listener.accept(line);
            } catch (RuntimeException ignored) {
                // 订阅方（页面/控制台）出问题，不能影响业务线程
            }
        }
    }

    /** 取序号大于 {@code fromSeq} 的所有日志（增量拉取）。 */
    public synchronized List<Line> since(long fromSeq) {
        List<Line> out = new ArrayList<>();
        for (Line line : buffer) {
            if (line.seq() > fromSeq) {
                out.add(line);
            }
        }
        return out;
    }

    public synchronized List<Line> all() {
        return List.copyOf(buffer);
    }

    public synchronized long lastSeq() {
        return seq;
    }

    private static String format(String fmt, Object[] args) {
        if (fmt == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            // 没有占位符就不做格式化，避免 % 被当成格式符抛异常
            return fmt;
        }
        try {
            return String.format(fmt, args);
        } catch (RuntimeException e) {
            return fmt + " " + java.util.Arrays.toString(args);
        }
    }
}
