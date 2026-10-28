package concurrency.juc;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中级 14：并发容器 —— ConcurrentHashMap 与 CopyOnWriteArrayList。
 *
 * <p>普通 HashMap / ArrayList 在多线程下会丢数据、破坏结构、甚至死循环（JDK7 HashMap 扩容）。
 * 并发容器用 CAS + synchronized 分段/桶级锁解决，但要理解它们的<b>弱一致性</b>语义。
 */
public class M14_ConcurrentCollections extends DemoBase {

    public M14_ConcurrentCollections() {
        super("juc-14-concurrent-collections", Level.INTERMEDIATE,
                "ConcurrentHashMap 与 CopyOnWriteArrayList",
                "computeIfAbsent 真的原子吗？并发下 size 可信吗？");
    }

    @Override
    public String knowledge() {
        return """
                ## HashMap 为什么不能并发
                - JDK7：并发扩容可能形成环形链表，导致 get 死循环（CPU 100%）；
                - JDK8+：不会再成环，但仍会丢数据、结构错乱、size 不准。
                多线程读写必须换 ConcurrentHashMap，或加锁。

                ## ConcurrentHashMap 要点（JDK8+）
                - 数组 + 链表/红黑树，锁粒度是「桶」（CAS + synchronized 锁头节点）；
                - 不允许 null key/value（避免并发下的歧义）；
                - `computeIfAbsent` / `putIfAbsent` / `merge` / `compute` 是**原子**的；
                - `size()` 是**估算值**（统计 baseCount + CounterCell），并发下不精确；
                - 迭代器是**弱一致**的：遍历时不抛 ConcurrentModificationException，
                  但可能看到、也可能看不到遍历期间的其他修改。

                ## CopyOnWriteArrayList
                - 写时复制：add/set 会复制整个数组，开销大；
                - 读完全无锁，迭代的是快照，不会 CME；
                - 适合「读多写极少」（监听器列表、白名单），写多会非常慢。

                ## 其他
                - Collections.synchronizedMap：全局锁，性能差；
                - ConcurrentSkipListMap：有序 + 并发，适合需要排序的场景。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 「先查再写」一定要用 computeIfAbsent / putIfAbsent / merge，别写 if (containsKey) put。
                - 缓存场景推荐 Caffeine（有容量上限、过期、统计），而不是裸 CHM。
                - CHM 的 size() 只能用来观察趋势，不能用来做业务判断（比如「最多 100 个」）。
                - 遍历时不要在循环里做写操作，即使不抛异常，语义也很容易写错。
                - COW 只用于读远多于写的场景；写操作 O(n) 拷贝，频繁写会 GC 暴增。
                - 不要用 synchronized 包住整个 CHM 操作，那等于放弃了并发能力。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① computeIfAbsent 的原子性：8 个线程同时初始化，只会创建一次");
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        AtomicInteger createCount = new AtomicInteger();
        ExecutorService pool = ctx.fixedPool("chm-pool", 8);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);

        for (int i = 0; i < 8; i++) {
            pool.execute(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                cache.computeIfAbsent("config", key -> {
                    createCount.incrementAndGet();
                    sleepQuietly(50); // 放大竞态窗口
                    return "初始化完成";
                });
                done.countDown();
            });
        }
        gate.countDown();
        done.await();
        ctx.info("8 个线程并发 computeIfAbsent，映射函数执行了 %d 次", createCount.get());
        ctx.verifyEquals("原子操作只执行一次", 1, createCount.get());
        ctx.info("换成 if (!map.containsKey) map.put(...)，这里会是 8 次");

        ctx.step("② putIfAbsent / merge：也是原子操作");
        ConcurrentHashMap<String, Integer> counter = new ConcurrentHashMap<>();
        counter.putIfAbsent("click", 0);
        ctx.verifyEquals("putIfAbsent 不覆盖已存在的值", 0, counter.putIfAbsent("click", 100));
        counter.merge("click", 1, Integer::sum);
        counter.merge("click", 1, Integer::sum);
        ctx.verifyEquals("merge 原子累加", 2, counter.get("click"));

        ctx.step("③ 弱一致迭代：遍历时并发写，不抛 ConcurrentModificationException");
        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 100; i++) {
            map.put(i, i);
        }
        AtomicInteger seen = new AtomicInteger();
        Thread writer = new Thread(() -> {
            for (int i = 100; i < 200; i++) {
                map.put(i, i);
                sleepQuietly(1);
            }
        }, "map-writer");
        writer.start();
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            seen.incrementAndGet();
            sleepQuietly(1);
        }
        writer.join();
        ctx.info("遍历过程中看到 %d 个元素（最终 size=%d）", seen.get(), map.size());
        ctx.verify("迭代不会抛 CME", true);
        ctx.info("弱一致 ≠ 强一致：你看到的是「某个瞬间」的视图，不是快照");

        ctx.step("④ CopyOnWriteArrayList：读无锁，写复制");
        CopyOnWriteArrayList<String> listeners = new CopyOnWriteArrayList<>();
        listeners.add("listener-A");
        listeners.add("listener-B");

        Thread registering = new Thread(() -> {
            sleepQuietly(100);
            listeners.add("listener-C");
            ctx.info("注册线程添加了 listener-C");
        }, "register");
        registering.start();

        for (String listener : listeners) {
            ctx.info("事件通知 → %s", listener);
            sleepQuietly(100);
        }
        registering.join();
        ctx.info("遍历结束后列表 = %s（新加的 C 不会出现在本次迭代里）", listeners);
        ctx.verify("迭代的是快照，写操作不影响正在进行的遍历", listeners.size() == 3);

        ctx.step("⑤ 性能/安全提醒");
        ctx.warn("CHM 的 size() 在并发下只是估算值，不要用它做「最多 N 个」的限流");
        ctx.warn("COW 每次 add 都复制整个数组，写多场景会 GC 飙升");
        ctx.info("需要缓存淘汰/统计，用 Caffeine；需要排序，用 ConcurrentSkipListMap");

        ctx.step("⑥ 反例：先判断再写入，窗口期就是 Bug");
        ctx.info("if (!map.containsKey(k)) { map.put(k, v); }   // 非原子");
        ctx.info("map.computeIfAbsent(k, x -> v);               // 原子");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
