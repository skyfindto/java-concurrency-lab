# 01 · 线程基础与 JMM

> 对应用例：`basic-01` ~ `basic-13`
> 启动控制台后，在「用例库 → 初级」逐个运行，每个用例的输出就是本文的实证。

---

## 1. 线程的三种创建方式与代价

| 方式 | 返回值 | 异常 | 生产可用 |
|---|---|---|---|
| `new Thread(runnable)` | ✗ | 走 UncaughtExceptionHandler | ✗ |
| `Callable + FutureTask` | ✓ | 封装进 Future | 仅学习 |
| `ExecutorService` | ✓ | 封装进 Future | ✓ |
| `Thread.ofVirtual()` | ✓ | 同上 | ✓（IO 密集） |

**创建 ≠ 执行**：`new Thread()` 只是堆上的对象，`start()` 才向 OS 申请线程。
`run()` 是普通方法调用，仍在当前线程执行（`basic-03`）。

**代价**：平台线程默认栈 1MB，创建几十微秒，切换陷入内核；虚拟线程栈按需增长，纳秒级创建。

---

## 2. 六种状态与排障映射

| 状态 | 触发 | jstack 里看到 | 排查含义 |
|---|---|---|---|
| NEW | new 完未 start | — | 正常 |
| RUNNABLE | 运行/就绪/等 IO | `runnable` | 多且 CPU 高 → 死循环 |
| BLOCKED | 等 synchronized | `waiting for monitor entry` | 锁竞争，找 lockOwner |
| WAITING | wait/join/park/lock | `waiting on condition` | 等依赖/连接池 |
| TIMED_WAITING | sleep/wait(t)/join(t) | `sleeping`/`parking` | 看超时是否合理 |
| TERMINATED | run 结束 | — | 正常 |

> 关键区别：**等 `ReentrantLock` 是 WAITING，不是 BLOCKED**。

---

## 3. 中断：Java 唯一的协作式取消

```java
// 生产者：请求停止
thread.interrupt();

// 消费者：两种响应方式
while (!Thread.currentThread().isInterrupted()) { work(); }   // 轮询

try { Thread.sleep(1000); }
catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // 恢复标志，交给上层
    return;
}
```

**必须记住的三点**

1. `interrupt()` 只是设置标志，线程不检查就永远不停；
2. 阻塞方法（sleep/wait/join/lockInterruptibly/take）会抛 `InterruptedException` 并**清除**标志；
3. `Thread.interrupted()` 读并清除，`isInterrupted()` 只读。

**别做的事**：`catch (InterruptedException e) { }` 空吞 —— 这是优雅停机失败的常见原因。

---

## 4. Java 内存模型（JMM）

### 4.1 三个特性

- **原子性**：操作不可分割。`i++` 不是原子的（读-改-写）。
- **可见性**：一个线程的写，另一个线程何时能看到。默认**没有保证**。
- **有序性**：编译器和 CPU 会重排序，单线程语义不变，多线程可能出错。

### 4.2 happens-before 八条规则

1. 程序顺序：同线程内，前 → 后；
2. 监视器锁：unlock HB 后续 lock（同一把锁）；
3. volatile：写 HB 后续读；
4. 线程启动：`start()` HB 新线程内所有操作；
5. 线程终止：线程内所有操作 HB 其他线程 `join()` 返回；
6. 中断：`interrupt()` HB 被中断线程检测到中断；
7. 终结器：构造完成 HB `finalize()` 开始；
8. 传递性：A HB B，B HB C ⇒ A HB C。

**没有 HB 关系 = 未定义行为**。可能在你的机器上一万次都对，上线就挂。

### 4.3 volatile 的两个作用

- 可见性：读写直接怼主内存（内存屏障）；
- 有序性：禁止重排。

**不做的事**：不保证原子性。`volatile int i; i++` 照样丢更新（`basic-11`）。

**DCL 单例为什么必须 volatile**：`instance = new Singleton()` 分三步（分配内存 → 初始化 → 赋值），
没有 volatile 时第 3 步可能重排到第 2 步之前，别的线程会拿到「构造了一半」的对象。

```java
private static volatile Singleton instance;

static Singleton getInstance() {
    Singleton local = instance;
    if (local == null) {
        synchronized (Singleton.class) {
            local = instance;
            if (local == null) {
                instance = local = new Singleton();
            }
        }
    }
    return local;
}
```

> 更省心的写法：**静态内部类**或**枚举**，天然线程安全 + 延迟加载。

### 4.4 final 与安全发布

- final 字段在构造完成后对所有线程可见（前提：构造期间 `this` 没有逸出）；
- 安全发布的五种方式：静态初始化、final、volatile、锁、并发容器。

---

## 5. 锁：synchronized 到底锁谁

| 写法 | 锁对象 |
|---|---|
| `synchronized void m()` | `this` |
| `static synchronized void m()` | `Xxx.class` |
| `synchronized (obj) {}` | `obj` |

- **可重入**：同一线程可重复获取，monitor 计数；
- **锁升级**：无锁 → 偏向锁（JDK15 废弃、JDK18 移除）→ 轻量级锁（CAS 自旋）→ 重量级锁（阻塞挂起）；
- **内存语义**：unlock 前的写，对随后 lock 同一把锁的线程可见。

**三大坑**

1. 锁 `String` 常量 / `Integer` 缓存对象 → 锁了全局对象，可能和别人死锁；
2. 锁对象被重新赋值（`lock = new Object()`）→ 新的 synchronized 块锁的是新对象；
3. 锁里做 IO/RPC/sleep → 临界区变长，吞吐暴跌（`adv-10`）。

---

## 6. 死锁

四个必要条件：互斥、占有且等待、不可剥夺、循环等待。

**工程上最有效**：破坏循环等待 —— 全局统一加锁顺序。

```java
// 反例：A 先 lock1 再 lock2，B 先 lock2 再 lock1
// 正例：所有线程都按 id 从小到大加锁
```

**检测**：`ThreadMXBean.findDeadlockedThreads()`（`obs-03` 有完整示例）。

**解除**：synchronized 死锁只能重启；ReentrantLock 可以用 `interrupt` 打断 `lockInterruptibly`。

---

## 7. 快速自测

1. `i++` 为什么不是原子的？画出字节码步骤。
2. 为什么 `sleep()` 期间其他线程进不来？换成 `wait()` 呢？
3. DCL 里去掉 volatile 会怎样？什么场景下才会出问题？
4. `Collections.unmodifiableList` 和 `List.copyOf` 的区别？
5. 两个线程分别 `synchronized(A)` 和 `synchronized(B)`，会互斥吗？

答案都在对应用例的输出里。
