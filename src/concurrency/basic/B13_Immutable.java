package concurrency.basic;

import concurrency.core.DemoBase;
import concurrency.core.DemoContext;
import concurrency.core.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 初级 13：不可变对象与安全发布。
 *
 * <p>并发问题的最优解往往不是「加锁」，而是<b>根本不共享可变状态</b>。
 * 不可变对象一旦构造完成就不会再变，天然线程安全，不需要任何同步。
 *
 * <p>本用例揭示一个高频误区：{@code Collections.unmodifiableList()} 只是<b>视图</b>，
 * 底层 list 改了它照样变；{@code List.copyOf()} 才是真正的快照。
 */
public class B13_Immutable extends DemoBase {

    /** record 自动生成 final 字段 + 全参构造 + equals/hashCode/toString，是最省事的不可变对象。 */
    private record Order(long id, String user, List<String> items, long amountCents) {
        Order {
            // 紧凑构造器里做防御性拷贝和校验
            items = List.copyOf(items);
            if (amountCents < 0) {
                throw new IllegalArgumentException("金额不能为负");
            }
        }
    }

    public B13_Immutable() {
        super("basic-13-immutable", Level.BASIC,
                "不可变对象与安全发布",
                "Collections.unmodifiableList 不是不可变！List.copyOf 才是");
    }

    @Override
    public String knowledge() {
        return """
                ## 怎么做一个不可变对象
                1. 类声明为 final（防止子类破坏）；
                2. 所有字段 private final；
                3. 构造器里对可变入参做【防御性拷贝】（List.copyOf / Map.copyOf / clone）；
                4. 不提供任何 setter，返回可变字段时返回拷贝；
                5. （可选）构造器校验不变量，不合法直接抛异常。

                ## 不可变的几种「强度」
                - `Collections.unmodifiableList(list)`：**视图**，只读，但底层 list 变化会透传；
                - `List.copyOf(list)` / `List.of(...)`：**真不可变**，拷贝且不可改，改就抛 UnsupportedOperationException；
                - `String` / `Integer` / `LocalDate` / record：本身不可变。
                注意：`Arrays.asList()` 返回的是固定长度列表，set 可以，add 不行。

                ## 安全发布
                一个对象被多个线程使用前，必须让它「完全构造完成」且对别人可见：
                - 静态初始化（类加载时完成，JVM 保证）；
                - final 字段（构造完成后对所有线程可见）；
                - volatile / AtomicReference 引用；
                - 锁保护的发布；
                - 并发容器（ConcurrentHashMap.put 有 HB 语义）。
                否则就是「不安全发布」：别的线程可能看到 null 字段或半初始化对象。

                ## record 的优势
                record 天生 final 字段、无 setter、equals/hashCode 自动生成，
                是写 DTO / 配置 / 事件对象的最佳选择。
                """;
    }

    @Override
    public String bestPractice() {
        return """
                - 共享配置、事件、消息体一律做成不可变对象，从源头消灭一半并发问题。
                - 对外暴露集合时用 List.copyOf，不要返回内部 list 本体。
                - Immutable 不等于「性能差」：省掉了锁竞争，读多写少场景通常更快。
                - 修改不可变对象用「创建一个新对象」代替原地修改（函数式风格）。
                - 组合多个不可变对象时注意：持有可变对象的引用会破坏不可变性。
                """;
    }

    @Override
    public void run(DemoContext ctx) throws Exception {
        ctx.step("① unmodifiableList 只是视图，不是快照");
        List<String> mutable = new ArrayList<>(List.of("A", "B"));
        List<String> unmodifiableView = Collections.unmodifiableList(mutable);
        List<String> snapshot = List.copyOf(mutable);
        ctx.info("修改前：view=%s, snapshot=%s", unmodifiableView, snapshot);
        mutable.add("C");
        ctx.info("往原 list 加一个 C 之后：");
        ctx.info("  unmodifiableList = %s（跟着变了！）", unmodifiableView);
        ctx.info("  List.copyOf      = %s（纹丝不动，真快照）", snapshot);
        ctx.verify("unmodifiableList 会透传底层修改", unmodifiableView.size() == 3);
        ctx.verify("List.copyOf 是独立快照", snapshot.size() == 2);

        ctx.step("② 真不可变：尝试修改直接抛异常");
        try {
            snapshot.add("D");
            ctx.error("居然能改？不符合预期");
        } catch (UnsupportedOperationException e) {
            ctx.ok("List.copyOf 的结果不可修改：%s", e.getClass().getSimpleName());
            ctx.verify("真不可变集合拒绝写入", true);
        }

        ctx.step("③ record 不可变对象：final 字段 + 防御性拷贝");
        Order order = new Order(1001L, "alice", new ArrayList<>(List.of("book", "pen")), 12_800L);
        ctx.info("订单：%s", order);
        try {
            order.items().add("hack");
            ctx.error("items 居然可修改？");
        } catch (UnsupportedOperationException e) {
            ctx.ok("record 内部集合已防御性拷贝，外部改不动");
        }
        try {
            new Order(1002L, "bob", List.of(), -1);
        } catch (IllegalArgumentException e) {
            ctx.ok("构造器校验生效：%s", e.getMessage());
        }

        ctx.step("④ CopyOnWriteArrayList：用「写时复制」换取读操作完全无锁");
        CopyOnWriteArrayList<String> cow = new CopyOnWriteArrayList<>(List.of("v1", "v2"));
        Thread reader = new Thread(() -> {
            for (String item : cow) { // 迭代的是快照，不会抛 ConcurrentModificationException
                ctx.info("读者遍历到 %s", item);
                sleepQuietly(50);
            }
        }, "cow-reader");
        reader.start();
        reader.join(300);
        cow.add("v3"); // 写操作复制整个数组，读者看不到新值
        reader.join(2000);
        ctx.info("读者遍历结束后，list 实际内容 = %s", cow);
        ctx.verify("COW 迭代不会抛 ConcurrentModificationException", !reader.isAlive());

        ctx.step("⑤ 结论");
        ctx.info("能不可变就不可变；必须可变就用并发容器或加锁");
        ctx.info("发布共享对象前，确保它已经「构造完成」且对别人可见");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
