# 02 · JUC 工具速查

> 对应用例：`juc-01` ~ `juc-18`

---

## 1. Executor 家族

```
Executor（execute）
 └─ ExecutorService（submit / shutdown / awaitTermination / invokeAll）
     ├─ ThreadPoolExecutor        ← 生产用这个
     ├─ ScheduledThreadPoolExecutor（延迟/周期）
     └─ ForkJoinPool（分治 + 工作窃取）
```

### ThreadPoolExecutor 七个参数

```java
new ThreadPoolExecutor(
    corePoolSize,        // 常驻线程
    maximumPoolSize,     // 上限
    keepAliveTime,       // 非核心线程空闲存活
    unit,
    workQueue,           // 必须有界
    threadFactory,       // 必须命名
    handler              // 拒绝策略，必须打点
);
```

### 执行流程（务必记住顺序）

```
核心线程未满 → 建核心线程
      ↓ 满
队列未满 → 入队
      ↓ 满
线程数 < max → 建非核心线程
      ↓ 满
拒绝策略
```

> **反直觉点**：队列满了才会扩容。无界队列 = 永远只有 core 个线程，永远不会拒绝。

### 拒绝策略

| 策略 | 行为 | 适用 |
|---|---|---|
| AbortPolicy | 抛 `RejectedExecutionException` | 核心链路 + 上层降级 |
| CallerRunsPolicy | 调用者线程执行（背压） | 内部批处理 |
| DiscardPolicy | 静默丢弃 | 可丢的埋点 |
| DiscardOldestPolicy | 丢最老的 | 只要最新数据 |

### Executors 的四个坑

| 工厂方法 | 坑 |
|---|---|
| `newFixedThreadPool(n)` | 无界 `LinkedBlockingQueue`，任务堆积 OOM |
| `newSingleThreadExecutor()` | 同上，且只有 1 个线程 |
| `newCachedThreadPool()` | `max = Integer.MAX_VALUE`，线程数暴涨 |
| `newScheduledThreadPool(n)` | 无界延迟队列，周期任务堆积 |

### 参数计算

- CPU 密集：`N + 1`
- IO 密集：`N × (1 + 等待/计算)`
- **Little's Law**：`并发数 = QPS × RT`；线程数至少等于并发数
- 队列容量 ≈ 可接受排队延迟 ÷ 平均处理耗时

---

## 2. Future / CompletableFuture

| API | 说明 |
|---|---|
| `submit(Callable)` | 返回 Future |
| `get()` | 阻塞，异常包在 `ExecutionException` |
| `get(timeout)` | 超时抛 `TimeoutException`，**任务不停** |
| `Future.cancel(true)` | 中断任务（真 Future 才有效） |
| `CompletableFuture.cancel(true)` | 只让 future 取消，**不中断**底层任务 |
| `CompletableFuture.supplyAsync(fn, pool)` | 异步有返回值 |
| `thenApply` / `thenCompose` | 同步转换 / 串联异步 |
| `thenCombine` | 合并两个结果 |
| `allOf` / `anyOf` | 全部完成 / 任一完成 |
| `exceptionally` / `handle` | 异常兜底 |
| `orTimeout(ms)` | 超时（**不停止底层任务**） |

**三个坑**

1. 不传 Executor 会用 `ForkJoinPool.commonPool()`，阻塞任务会拖垮整个 JVM；
2. `orTimeout` 只让等待方超时，任务继续跑；
3. `submit` 的异常不 get 就永远不知道。

### 2.1 为什么不能裸用 commonPool

`commonPool` 是**全 JVM 共享**的、按核数-1 建线程的工作窃取池，设计目标是「短小的 CPU 任务」：

- 阻塞任务（DB / HTTP / sleep）会一直占住 worker，池子被占满后**整个 JVM** 的
  `parallelStream`、以及其他库的默认异步任务一起卡住；
- 普通阻塞**不会**触发扩容（只有声明了 `ManagedBlocker` 才会），所以它是真“卡死”；
- 没有隔离、没有队列上限、没有拒绝策略、没有业务维度的指标；
- 线程名固定是 `ForkJoinPool.commonPool-worker-N`，jstack 里分不清业务。

所以：**业务代码永远显式传自己的线程池**；commonPool 只适合「纯 CPU、极短、无阻塞」的小计算。

### 2.2 get() 与 join()

| 维度 | `get()` | `join()` |
|---|---|---|
| 异常 | 受检 `InterruptedException` / `ExecutionException` | 非受检 `CompletionException` |
| 超时 | 支持 `get(timeout, unit)` | 不支持，只能配合 `orTimeout` |
| 可中断 | 是 | **否**（忽略中断继续等，结束后才恢复标志） |
| 来源 | `Future` 接口 | `CompletableFuture` 独有 |

- 异常链都是「包一层」：`get` → `ExecutionException(cause=原始异常)`；`join` → `CompletionException(cause=原始异常)`；
- 取消时两者都直接抛 `CancellationException`；
- 结论：**编排链内部用 `join()`**（少一层受检异常），**边界用 `get(timeout)` 或 `orTimeout`**（要超时/可中断）。
  说「禁止 get」是错的，`join()` 不响应中断，优雅停机时反而要用 `get`。

### 2.3 整体超时加在哪

```java
// 写法一：在聚合后的 future 上加 deadline（最常用）
try {
    String r = CompletableFuture.allOf(a, b, c)
            .thenApply(v -> a.join() + b.join() + c.join())
            .orTimeout(800, TimeUnit.MILLISECONDS)
            .join();
} catch (CompletionException e) {
    return fallback();            // TimeoutException
}

// 写法二：超时给默认值，不抛异常
String r = cf.completeOnTimeout("默认值", 500, TimeUnit.MILLISECONDS).join();

// 写法三：要「真正取消」，用真 Future（cancel(true) 才会 interrupt）
Future<String> f = pool.submit(task);
try { return f.get(800, MILLISECONDS); }
catch (TimeoutException e) { f.cancel(true); return fallback(); }

// 写法四：invokeAll 超时会自动取消未完成的任务
List<Future<T>> rs = pool.invokeAll(tasks, 800, TimeUnit.MILLISECONDS);
```

**关键认知**：`orTimeout` / `CompletableFuture.cancel(true)` 都**不会中断底层任务**
（`mayInterruptIfRunning` 被忽略），线程仍被占着。超时保护的是「调用方」，不是「线程池」。
真正的防线是：线程池隔离 + 任务自己检查 deadline + 用真 Future 取消。

### 2.4 thenApply 跑在哪个线程

- 不新开线程，运行在**完成上游的那个线程**上；
- 上游已完成（如 `completedFuture`）时，就在**当前线程同步执行**；
- 所以只放纯内存转换（DO→VO、金额换算、取字段、裁剪列表），不要阻塞；
- 想换线程：`thenApplyAsync(fn, pool)`；想串另一个异步调用：`thenCompose`。

---

## 3. 锁

| 需求 | 选择 |
|---|---|
| 简单互斥 | `synchronized` |
| 可中断 / 可超时 / 公平 | `ReentrantLock` |
| 读写分离（读远多于写） | `ReentrantReadWriteLock` |
| 读极多写极少 + 极致性能 | `StampedLock`（不可重入） |
| 多条件等待 | `ReentrantLock + Condition` |
| 计数/标志位 | `Atomic*` / `volatile` |

```java
lock.lock();
try {
    // 临界区：只放共享状态读写
} finally {
    lock.unlock();
}
```

**锁降级**：写锁 → 获取读锁 → 释放写锁（合法，用于「写完立刻读」）。
**锁升级**：读锁 → 写锁（自己等自己，死锁）。

---

## 4. 同步器

| 工具 | 语义 | 一次性 |
|---|---|---|
| `CountDownLatch` | 一个线程等 N 个 | ✓ |
| `CyclicBarrier` | N 个线程互相等，可循环 | ✗ |
| `Phaser` | 分阶段 + 动态注册 | ✗ |
| `Semaphore` | 控制并发数量 | — |
| `Exchanger` | 两线程交换数据 | — |

- Semaphore 与锁的区别：**持有者不必是释放者**，用于限流/资源池。
- `await` 一律带超时；`countDown` 一律放 finally。

---

## 5. 并发容器

| 容器 | 特点 | 注意 |
|---|---|---|
| `ConcurrentHashMap` | 桶级锁 + CAS，读无锁 | 不允许 null；`size()` 是估算；迭代弱一致 |
| `CopyOnWriteArrayList` | 写时复制，读无锁 | 写多会 GC 爆炸 |
| `ArrayBlockingQueue` | 有界数组 + 1 锁 | 最常用 |
| `LinkedBlockingQueue` | 2 把锁，吞吐高 | **默认无界**，必须传容量 |
| `SynchronousQueue` | 容量 0，手递手 | CachedThreadPool 用 |
| `PriorityBlockingQueue` | 优先级 | 无界，不保证同优先级顺序 |
| `DelayQueue` | 到期才可取 | 定时重试 |
| `LinkedTransferQueue` | transfer 等消费者取走 | 高吞吐 |

**原子更新三件套**：`computeIfAbsent` / `putIfAbsent` / `merge`，都是原子的。
不要写 `if (!map.containsKey(k)) map.put(k, v)`。

---

## 6. 原子类与 LongAdder

```java
// CAS 自旋
int old;
do { old = atomic.get(); } while (!atomic.compareAndSet(old, old + 1));
```

- **ABA**：A→B→A 后 CAS 仍成功 → 用 `AtomicStampedReference` 加版本号；
- **LongAdder**：分段累加，高并发计数吞吐远高于 AtomicLong，但 `sum()` 是最终一致。

---

## 7. ThreadLocal

- 每个 Thread 一个 `ThreadLocalMap`，key 是 ThreadLocal（弱引用），value 是值（强引用）；
- **线程池 + 不 remove = 数据串号 + 内存泄漏**；
- 标准写法：

```java
try {
    CTX.set(value);
    doWork();
} finally {
    CTX.remove();
}
```

- `InheritableThreadLocal` 只在**创建子线程**时复制，线程池无效。

---

## 8. ForkJoin 与虚拟线程

**ForkJoinPool**

- 工作窃取：自己队列头 LIFO，偷别人队尾 FIFO；
- 适合可递归拆分的 CPU 密集计算；
- `parallelStream` 用 commonPool（并行度 = 核数-1），阻塞任务会污染全局。

**虚拟线程（JDK21）**

| 维度 | 平台线程 | 虚拟线程 |
|---|---|---|
| 栈 | 1MB 固定 | 按需增长 |
| 创建 | 微秒级 | 纳秒级 |
| 数量 | 几千 | 百万 |
| 池化 | 必须 | **禁止** |
| 适合 | CPU 密集 | IO 密集 |

- 用 `Executors.newVirtualThreadPerTaskExecutor()`；
- JDK21 里 `synchronized` 内阻塞会 pin 住载体线程（JDK24 修复），建议改 `ReentrantLock`；
- 并发控制靠 `Semaphore`，不是靠池大小。

---

## 9. 选型速查

| 场景 | 首选 |
|---|---|
| 提交任务 | `ThreadPoolExecutor`（有界队列 + 命名 + 拒绝策略） |
| 异步编排 | `CompletableFuture` |
| 等待多个任务 | `CompletableFuture.allOf` / `CountDownLatch` |
| 限流 | `Semaphore` / 令牌桶 |
| 缓存 | `ConcurrentHashMap` + `computeIfAbsent`（或 Caffeine） |
| 计数 | `LongAdder` |
| 定时 | `ScheduledExecutorService` / `DelayQueue` |
| IO 密集服务 | 虚拟线程 |
| CPU 密集计算 | `ForkJoinPool` / 固定线程池 |
