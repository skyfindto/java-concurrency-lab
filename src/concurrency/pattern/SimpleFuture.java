package concurrency.pattern;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手写一个极简 Future（Promise），用来讲清楚「异步结果占位」的原理。
 *
 * <p>不是线程安全的完整实现，但覆盖了核心语义：
 * 一个线程负责 {@link #complete}，另一个线程 {@link #get} 等待；
 * 支持超时；结果只允许写一次。
 */
public final class SimpleFuture<T> {

    private final AtomicBoolean completed = new AtomicBoolean();
    private T value;
    private Throwable error;
    private boolean done;

    /** 异步任务成功完成。 */
    public boolean complete(T result) {
        if (!completed.compareAndSet(false, true)) {
            return false;
        }
        synchronized (this) {
            value = result;
            done = true;
            notifyAll();
        }
        return true;
    }

    /** 异步任务失败。 */
    public boolean completeExceptionally(Throwable throwable) {
        if (!completed.compareAndSet(false, true)) {
            return false;
        }
        synchronized (this) {
            error = throwable;
            done = true;
            notifyAll();
        }
        return true;
    }

    /** 阻塞取结果，无限等待。 */
    public T get() throws Exception {
        synchronized (this) {
            while (!done) {
                wait();
            }
        }
        return result();
    }

    /** 带超时的 get：超时抛 TimeoutException，而不是永远卡住。 */
    public T get(long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (this) {
            while (!done) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("等待结果超时：" + timeoutMillis + "ms");
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
        return result();
    }

    public synchronized boolean isDone() {
        return done;
    }

    private T result() throws Exception {
        if (error != null) {
            throw new Exception("异步任务失败", error);
        }
        return value;
    }
}
