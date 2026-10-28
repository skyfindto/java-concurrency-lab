(() => {
  'use strict';

  /* =========================================================
   * 常量
   * ======================================================= */

  const ROUTES = ['demos', 'pools', 'threads', 'lab', 'dump'];
  const ROUTE_TITLES = {
    demos: '用例库',
    pools: '线程池观测',
    threads: '线程与死锁',
    lab: '排障演练场',
    dump: 'jstack 分析',
  };
  const LEVEL_ORDER = ['basic', 'intermediate', 'advanced', 'pattern', 'observe'];
  const LEVEL_META = {
    basic: { cn: '初级', subtitle: '基本概念' },
    intermediate: { cn: '中级', subtitle: '进阶机制' },
    advanced: { cn: '高级', subtitle: '并发疑难' },
    pattern: { cn: '设计模式', subtitle: '并发模式' },
    observe: { cn: '进阶', subtitle: '观测与排障' },
  };
  const MAX_LOGS = 5000;
  const RUN_POLL_MS = 320;
  const INT_FORMATTER = new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: 0,
  });
  const RUN_STATUS_META = {
    RUNNING: { label: '运行中', cls: 'status-running' },
    OK: { label: '通过', cls: 'status-ok' },
    FAILED: { label: '失败', cls: 'status-failed' },
    TIMEOUT: { label: '超时', cls: 'status-timeout' },
    CANCELLED: { label: '已取消', cls: 'status-cancelled' },
    IDLE: { label: '未运行', cls: 'status-idle' },
  };
  const LOG_LEVELS = new Set(['STEP', 'INFO', 'OK', 'WARN', 'ERROR']);
  const STATE_ORDER = [
    'RUNNABLE',
    'BLOCKED',
    'WAITING',
    'TIMED_WAITING',
    'NEW',
    'TERMINATED',
  ];
  const STATE_LABELS = {
    RUNNABLE: '运行',
    BLOCKED: '阻塞',
    WAITING: '等待',
    TIMED_WAITING: '限时等待',
    NEW: '新建',
    TERMINATED: '终止',
  };
  const LABS = [
    {
      name: 'cpu',
      title: 'CPU 打满',
      desc: '启动多个自旋计算线程，把进程 CPU 推到接近 100%，用于观察算力型故障。',
      symptom: 'CPU 持续高位、请求 RT 上升，但线程池队列可能并不堆积。',
      watch: 'CPU%，以及 RUNNABLE 线程的栈顶是否集中在业务计算。',
      commands: ['top -H -p <pid>', 'jstack <pid>', 'jcmd <pid> Thread.print'],
    },
    {
      name: 'deadlock',
      title: '构造死锁',
      desc: '让两个线程按相反顺序获取两把锁，制造循环等待，演示可被 ThreadMXBean 检测到的死锁。',
      symptom: '相关线程进入 BLOCKED，业务调用不返回，CPU 通常不高。',
      watch: '线程状态、锁持有者，以及「线程与死锁」页的死锁告警。',
      commands: ['jstack -l <pid>', 'jcmd <pid> Thread.print -l'],
    },
    {
      name: 'leak',
      title: '线程泄漏',
      desc: '持续创建不退出且未被回收的线程，观察线程数增长与线程栈形态。',
      symptom: '线程数持续上涨，可能伴随 native memory 增长和创建线程失败。',
      watch: '线程总数、线程名前缀、线程状态分布与峰值。',
      commands: ['jstack <pid>', 'jcmd <pid> Thread.print', 'top -H -p <pid>'],
    },
    {
      name: 'heap',
      title: '堆内存增长',
      desc: '由后台线程持续持有对象，推动堆占用增长，用于观察 GC 与 OOM 前的信号。',
      symptom: '堆 used 上升、GC 次数/耗时增加，严重时出现 OOM。',
      watch: '堆 used/max、GC 统计，以及对象分配速率。',
      commands: ['jcmd <pid> GC.heap_info', 'jmap -histo:live <pid>', 'jstat -gcutil <pid> 1000'],
    },
    {
      name: 'saturate',
      title: '线程池打满',
      desc: '向演练线程池持续提交慢任务，占满核心线程和队列，触发拒绝策略。',
      symptom: '活跃线程达到上限、队列接近容量，rejected 开始增长。',
      watch: '线程池卡片的队列进度、rejected 指标与本页诊断结果。',
      commands: ['jstack <pid>', 'arthas dashboard', 'jcmd <pid> Thread.print'],
    },
  ];

  const SAMPLE_JSTACK = `2026-10-28 21:30:12
Full thread dump OpenJDK 64-Bit Server VM (25.372-b07 mixed mode):

"main" #1 prio=5 os_prio=0 cpu=188.42ms elapsed=42.18s tid=0x00007f8e2c00a800 nid=0x1a03 waiting on condition  [0x00007f8e2f5fe000]
   java.lang.Thread.State: WAITING (parking)
        at jdk.internal.misc.Unsafe.park(java.base@17.0.8/Native Method)
        - parking to wait for  <0x00000000f0a2c3d0> (a java.util.concurrent.CountDownLatch$Sync)
        at java.util.concurrent.locks.LockSupport.park(java.base@17.0.8/LockSupport.java:211)
        at com.example.concurrent.DemoMain.main(DemoMain.java:28)

"http-nio-8080-exec-1" #24 prio=5 os_prio=0 cpu=912.30ms elapsed=38.44s tid=0x00007f8e24021a00 nid=0x2b03 waiting on condition  [0x00007f8e1d2f9000]
   java.lang.Thread.State: WAITING (parking)
        at jdk.internal.misc.Unsafe.park(java.base@17.0.8/Native Method)
        - parking to wait for  <0x00000000f0b1d8e0> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
        at java.util.concurrent.locks.LockSupport.park(java.base@17.0.8/LockSupport.java:211)
        at java.util.concurrent.LinkedBlockingQueue.take(java.base@17.0.8/LinkedBlockingQueue.java:435)
        at java.util.concurrent.ThreadPoolExecutor.getTask(java.base@17.0.8/ThreadPoolExecutor.java:1062)
        at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@17.0.8/ThreadPoolExecutor.java:1128)
        at java.util.concurrent.ThreadPoolExecutor$Worker.run(java.base@17.0.8/ThreadPoolExecutor.java:628)
        at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61)
        at java.lang.Thread.run(java.base@17.0.8/Thread.java:833)

"pool-1-thread-1" #32 prio=5 os_prio=0 cpu=75.11ms elapsed=31.02s tid=0x00007f8e241f8a00 nid=0x3107 waiting for monitor entry  [0x00007f8e1caf9000]
   java.lang.Thread.State: BLOCKED (on object monitor)
        at com.example.concurrent.LockDemo$Worker.run(LockDemo.java:41)
        - waiting to lock <0x00000000f0c1a2b0> (a java.lang.Object)
        - locked <0x00000000f0c1a2c0> (a java.lang.Object)
        at java.lang.Thread.run(java.base@17.0.8/Thread.java:833)

"pool-1-thread-2" #33 prio=5 os_prio=0 cpu=78.09ms elapsed=31.02s tid=0x00007f8e241fa000 nid=0x3208 waiting for monitor entry  [0x00007f8e1c9f9000]
   java.lang.Thread.State: BLOCKED (on object monitor)
        at com.example.concurrent.LockDemo$Worker.run(LockDemo.java:46)
        - waiting to lock <0x00000000f0c1a2c0> (a java.lang.Object)
        - locked <0x00000000f0c1a2b0> (a java.lang.Object)
        at java.lang.Thread.run(java.base@17.0.8/Thread.java:833)

"Reference Handler" #2 daemon prio=10 os_prio=0 cpu=1.12ms elapsed=42.17s tid=0x00007f8e2c0ab800 nid=0x1a04 waiting on condition  [0x00007f8e2f4fe000]
   java.lang.Thread.State: RUNNABLE
        at java.lang.ref.Reference.waitForReferencePendingList(java.base@17.0.8/Native Method)
        at java.lang.ref.Reference.processPendingReferences(java.base@17.0.8/Reference.java:253)
        at java.lang.ref.Reference$ReferenceHandler.run(java.base@17.0.8/Reference.java:215)

Found one Java-level deadlock:
=============================
"pool-1-thread-1":
  waiting to lock monitor 0x00007f8e1c006a08 (object 0x00000000f0c1a2b0, a java.lang.Object),
  which is held by "pool-1-thread-2"
"pool-1-thread-2":
  waiting to lock monitor 0x00007f8e1c006f18 (object 0x00000000f0c1a2c0, a java.lang.Object),
  which is held by "pool-1-thread-1"

Java stack information for the threads listed above:
===================================================
"pool-1-thread-1":
        at com.example.concurrent.LockDemo$Worker.run(LockDemo.java:41)
        - waiting to lock <0x00000000f0c1a2b0> (a java.lang.Object)
        - locked <0x00000000f0c1a2c0> (a java.lang.Object)
"pool-1-thread-2":
        at com.example.concurrent.LockDemo$Worker.run(LockDemo.java:46)
        - waiting to lock <0x00000000f0c1a2c0> (a java.lang.Object)
        - locked <0x00000000f0c1a2b0> (a java.lang.Object)

Found 1 deadlock.

JNI global refs: 18, weak refs: 0`;

  /* =========================================================
   * 状态
   * ======================================================= */

  const dom = {};
  const state = {
    route: null,
    inFlight: new Set(),
    timers: {
      global: null,
      runPoll: null,
    },
    runPollToken: 0,
    system: null,
    demos: {
      data: null,
      loading: false,
      error: '',
    },
    selectedDemoId: null,
    demoFilter: 'all',
    demoQuery: '',
    run: null,
    runStarting: false,
    pools: {
      items: [],
      histories: new Map(),
      ts: 0,
      loaded: false,
      error: '',
    },
    threads: {
      data: null,
      deadlocks: [],
      deadlockTs: 0,
      loaded: false,
      deadlocksLoaded: false,
      error: '',
      deadlockError: '',
      query: '',
      filter: 'ALL',
      expanded: new Set(),
    },
    lab: {
      status: null,
      error: '',
      diagnose: null,
      diagnoseError: '',
      diagnosing: false,
      starting: '',
      stopping: false,
    },
    dump: {
      raw: '',
      result: null,
      error: '',
      analyzing: false,
      query: '',
      expanded: new Set(),
      analyzedAt: 0,
    },
    errorBar: {
      lastShownAt: 0,
    },
  };

  /* =========================================================
   * 基础工具
   * ======================================================= */

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    if (attrs) {
      for (const [key, value] of Object.entries(attrs)) {
        if (value === null || value === undefined || value === false) continue;
        if (key === 'class') {
          node.className = value;
        } else if (key === 'text') {
          node.textContent = String(value);
        } else if (key === 'dataset') {
          for (const [dataKey, dataValue] of Object.entries(value)) {
            node.dataset[dataKey] = String(dataValue);
          }
        } else if (key === 'style') {
          for (const [styleKey, styleValue] of Object.entries(value)) {
            node.style[styleKey] = styleValue;
          }
        } else if (key === 'disabled' || key === 'hidden' || key === 'checked') {
          node[key] = Boolean(value);
        } else if (key.startsWith('on') && typeof value === 'function') {
          node.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (value === true) {
          node.setAttribute(key, '');
        } else {
          node.setAttribute(key, String(value));
        }
      }
    }
    appendChildren(node, children);
    return node;
  }

  function appendChildren(parent, children) {
    for (const child of children) {
      if (child === null || child === undefined || child === false) continue;
      if (Array.isArray(child)) {
        appendChildren(parent, child);
      } else if (child instanceof Node) {
        parent.appendChild(child);
      } else {
        parent.appendChild(document.createTextNode(String(child)));
      }
    }
  }

  function clearNode(node) {
    if (node) node.replaceChildren();
    return node;
  }

  function byId(id) {
    return document.getElementById(id);
  }

  function asString(value) {
    return value === null || value === undefined ? '' : String(value);
  }

  function asNumber(value, fallback = 0) {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
  }

  function asArray(value) {
    return Array.isArray(value) ? value : [];
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function fmtInt(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    return INT_FORMATTER.format(Math.round(n));
  }

  function fmtCompact(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    const abs = Math.abs(n);
    if (abs >= 1e9) return `${(n / 1e9).toFixed(1).replace(/\.0$/, '')}B`;
    if (abs >= 1e6) return `${(n / 1e6).toFixed(1).replace(/\.0$/, '')}M`;
    if (abs >= 1000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`;
    return fmtInt(n);
  }

  function fmtCpu(value) {
    const n = Number(value);
    if (!Number.isFinite(n)) return '—';
    const safe = Math.max(0, n);
    if (safe > 0 && safe < 10) return safe.toFixed(1);
    return String(Math.round(safe));
  }

  function fmtDuration(ms) {
    const n = Number(ms);
    if (!Number.isFinite(n) || n < 0) return '—';
    if (n < 1000) return `${Math.round(n)}ms`;
    if (n < 60000) {
      const seconds = n / 1000;
      return `${seconds < 10 ? seconds.toFixed(2) : seconds.toFixed(1)}s`;
    }
    const totalSeconds = Math.floor(n / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
  }

  function fmtTimeout(ms) {
    const n = Number(ms);
    if (!Number.isFinite(n) || n <= 0) return '—';
    if (n < 1000) return `${Math.round(n)}ms`;
    return `${n / 1000}s`;
  }

  function fmtClockSpan(ms) {
    const totalSeconds = Math.max(0, Math.floor(Number(ms) / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
      return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(
        2,
        '0'
      )}`;
    }
    return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }

  function fmtTime(epochMs) {
    const n = Number(epochMs);
    if (!Number.isFinite(n) || n <= 0) return '—';
    try {
      return new Date(n).toLocaleTimeString('zh-CN', { hour12: false });
    } catch (_) {
      return '—';
    }
  }

  function fmtSince(since) {
    const n = Number(since);
    if (!Number.isFinite(n) || n <= 0) return '—';
    return fmtClockSpan(Date.now() - n);
  }

  function normalizeLogLevel(level) {
    const upper = asString(level).toUpperCase();
    return LOG_LEVELS.has(upper) ? upper : 'INFO';
  }

  function runStatusMeta(status) {
    const key = asString(status).toUpperCase();
    return RUN_STATUS_META[key] || { label: key || '未知', cls: 'status-idle' };
  }

  function statusPill(status) {
    const meta = runStatusMeta(status);
    return el('span', { class: `status-pill ${meta.cls}`, text: meta.label });
  }

  function stateKey(raw) {
    return asString(raw || 'UNKNOWN')
      .toLowerCase()
      .replace(/_/g, '-');
  }

  function stateClass(raw) {
    const key = stateKey(raw);
    const known = [
      'runnable',
      'blocked',
      'waiting',
      'timed-waiting',
      'new',
      'terminated',
    ];
    return known.includes(key) ? `state-${key}` : 'state-unknown';
  }

  function stateLabel(raw) {
    const upper = asString(raw).toUpperCase();
    return STATE_LABELS[upper] || upper || '未知';
  }

  function levelCode(code) {
    const key = asString(code);
    return LEVEL_ORDER.includes(key) ? key : 'basic';
  }

  function levelLabel(demo) {
    const code = asString(demo && demo.level);
    return asString(demo && demo.levelCn) || (LEVEL_META[code] && LEVEL_META[code].cn) || code || '其他';
  }

  function findingLevel(level) {
    const upper = asString(level).toUpperCase();
    if (upper === 'ERROR') return 'error';
    if (upper === 'WARN' || upper === 'WARNING') return 'warn';
    return 'info';
  }

  function normalizeFindings(list) {
    return asArray(list)
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        level: asString(item.level).toUpperCase() || 'INFO',
        title: asString(item.title) || '未命名发现',
        detail: asString(item.detail),
        evidence: asArray(item.evidence).map(asString),
      }));
  }

  function normalizeStates(states) {
    return asArray(states)
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        state: asString(item.state).toUpperCase() || 'UNKNOWN',
        count: Math.max(0, Math.round(asNumber(item.count))),
      }));
  }

  function normalizedStateEntries(states, fallbackTotal = 0) {
    const map = new Map();
    for (const entry of normalizeStates(states)) {
      map.set(entry.state, (map.get(entry.state) || 0) + entry.count);
    }
    const entries = Array.from(map, ([stateName, count]) => ({
      state: stateName,
      count,
    }));
    const knownIndex = (stateName) => {
      const index = STATE_ORDER.indexOf(stateName);
      return index === -1 ? STATE_ORDER.length : index;
    };
    entries.sort((a, b) => {
      const diff = knownIndex(a.state) - knownIndex(b.state);
      return diff !== 0 ? diff : a.state.localeCompare(b.state);
    });
    if (entries.length === 0) {
      return [{ state: 'UNKNOWN', count: Math.max(0, fallbackTotal) }];
    }
    return entries;
  }

  function createStateDistribution(states, total) {
    const entries = normalizedStateEntries(states, total);
    const sum = entries.reduce((acc, entry) => acc + entry.count, 0);
    const safeTotal = Math.max(asNumber(total), sum, 0);
    const bar = el('div', { class: 'state-bar', role: 'img', 'aria-label': '线程状态分布' });
    const legend = el('div', { class: 'state-legend' });

    for (const entry of entries) {
      if (entry.count <= 0) continue;
      const pct = safeTotal > 0 ? (entry.count / safeTotal) * 100 : 0;
      bar.appendChild(
        el('span', {
          class: `state-bar-seg ${stateClass(entry.state)}`,
          style: { width: `${Math.max(0.4, pct)}%` },
          title: `${entry.state} ${fmtInt(entry.count)}`,
        })
      );
    }

    for (const entry of entries) {
      if (entry.count <= 0 && entries.length > 1) continue;
      legend.appendChild(
        el(
          'span',
          { class: 'state-legend-item', title: entry.state },
          el('span', {
            class: `state-legend-dot ${stateClass(entry.state)}`,
            'aria-hidden': 'true',
          }),
          `${stateLabel(entry.state)} `,
          el('strong', { text: fmtInt(entry.count) })
        )
      );
    }

    return el('div', {}, bar, legend);
  }

  function createPageHead(title, desc, actions) {
    return el(
      'div',
      { class: 'page-head' },
      el(
        'div',
        {},
        el('h1', { class: 'page-title', text: title }),
        desc ? el('p', { class: 'page-desc', text: desc }) : null
      ),
      actions && actions.length
        ? el('div', { class: 'page-actions' }, ...actions)
        : null
    );
  }

  function createLoading(text) {
    return el(
      'div',
      { class: 'loading-state' },
      el('span', { class: 'spinner', 'aria-hidden': 'true' }),
      el('span', { text: text || '正在加载…' })
    );
  }

  function createInlineError(message, retry) {
    const children = [
      el('span', { text: '⚠', 'aria-hidden': 'true' }),
      el('span', { text: message || '请求失败' }),
    ];
    if (typeof retry === 'function') {
      children.push(
        el('button', {
          class: 'btn btn-sm',
          type: 'button',
          text: '重试',
          onclick: retry,
        })
      );
    }
    return el('div', { class: 'inline-error', role: 'alert' }, ...children);
  }

  function createEmpty(title, desc, extras) {
    return el(
      'div',
      { class: 'empty-state' },
      el('div', { class: 'empty-mark', text: '{ }', 'aria-hidden': 'true' }),
      el('div', { class: 'empty-title', text: title }),
      desc ? el('div', { class: 'empty-desc', text: desc }) : null,
      ...(Array.isArray(extras) ? extras : extras ? [extras] : [])
    );
  }

  /* =========================================================
   * 安全 Markdown 渲染
   * ======================================================= */

  function appendInlineMarkdown(parent, text) {
    const source = asString(text);
    const pattern = /(\*\*[^*]+\*\*|`[^`]+`)/g;
    let lastIndex = 0;
    let match;
    while ((match = pattern.exec(source)) !== null) {
      if (match.index > lastIndex) {
        parent.appendChild(document.createTextNode(source.slice(lastIndex, match.index)));
      }
      const token = match[0];
      if (token.startsWith('**')) {
        parent.appendChild(el('strong', { text: token.slice(2, -2) }));
      } else {
        parent.appendChild(el('code', { text: token.slice(1, -1) }));
      }
      lastIndex = match.index + token.length;
    }
    if (lastIndex < source.length) {
      parent.appendChild(document.createTextNode(source.slice(lastIndex)));
    }
  }

  function renderMarkdown(host, text) {
    if (!host) return;
    clearNode(host);
    const source = asString(text).replace(/\r\n/g, '\n');
    if (!source.trim()) {
      host.appendChild(el('p', { class: 'muted', text: '暂无内容' }));
      return;
    }

    let list = null;
    for (const rawLine of source.split('\n')) {
      const line = rawLine.trimEnd();
      if (!line.trim()) {
        list = null;
        continue;
      }
      const heading = line.match(/^#{2,3}\s+(.+)$/);
      if (heading) {
        list = null;
        host.appendChild(el('h3', { text: heading[1] }));
        continue;
      }
      const item = line.match(/^\s*[-*]\s+(.+)$/);
      if (item) {
        if (!list) {
          list = el('ul');
          host.appendChild(list);
        }
        const li = el('li');
        appendInlineMarkdown(li, item[1]);
        list.appendChild(li);
        continue;
      }
      list = null;
      const p = el('p');
      appendInlineMarkdown(p, line);
      host.appendChild(p);
    }
  }

  /* =========================================================
   * 错误条
   * ======================================================= */

  function showError(message, force) {
    if (!dom.errorBar || !dom.errorText) return;
    const now = Date.now();
    if (!force && now - state.errorBar.lastShownAt < 8000) return;
    state.errorBar.lastShownAt = now;
    dom.errorText.textContent = asString(message) || '请求失败';
    dom.errorBar.hidden = false;
  }

  function hideError() {
    if (!dom.errorBar) return;
    dom.errorBar.hidden = true;
    state.errorBar.lastShownAt = Date.now();
  }

  /* =========================================================
   * API
   * ======================================================= */

  async function request(path, options, timeoutMs) {
    const controller = new AbortController();
    const timeout = window.setTimeout(
      () => controller.abort(),
      timeoutMs || 12000
    );
    try {
      const response = await fetch(path, {
        cache: 'no-store',
        ...options,
        signal: controller.signal,
      });
      if (!response.ok) {
        let detail = '';
        try {
          const body = await response.text();
          detail = body.replace(/\s+/g, ' ').trim().slice(0, 180);
        } catch (_) {
          detail = '';
        }
        throw new Error(`HTTP ${response.status}${detail ? ` · ${detail}` : ''}`);
      }
      if (response.status === 204) return null;
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        try {
          return await response.json();
        } catch (_) {
          throw new Error('响应不是有效 JSON');
        }
      }
      const text = await response.text();
      if (!text) return null;
      try {
        return JSON.parse(text);
      } catch (_) {
        throw new Error('响应不是有效 JSON');
      }
    } catch (error) {
      if (error && error.name === 'AbortError') {
        throw new Error('请求超时');
      }
      if (error instanceof TypeError) {
        throw new Error('无法连接后端服务');
      }
      throw error;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  function apiGet(path) {
    return request(path, { method: 'GET' });
  }

  function apiPost(path, body, contentType, timeoutMs) {
    const options = { method: 'POST' };
    if (body !== null && body !== undefined) {
      options.body = body;
      if (contentType) {
        options.headers = { 'Content-Type': contentType };
      }
    }
    return request(path, options, timeoutMs);
  }

  function guarded(key, label, task) {
    if (state.inFlight.has(key)) return;
    state.inFlight.add(key);
    Promise.resolve()
      .then(task)
      .catch((error) => {
        showError(`${label}：${error && error.message ? error.message : '未知错误'}`);
      })
      .finally(() => {
        state.inFlight.delete(key);
      });
  }

  /* =========================================================
   * 顶栏与路由
   * ======================================================= */

  function parseRoute() {
    const raw = asString(window.location.hash)
      .replace(/^#\/?/, '')
      .split('?')[0]
      .trim();
    return ROUTES.includes(raw) ? raw : null;
  }

  // 支持深链：#/demos/<demo-id>，便于把某个用例直接发给别人
  function routeDemoId() {
    const raw = asString(window.location.hash)
      .replace(/^#\/?/, '')
      .split('?')[0]
      .trim();
    const parts = raw.split('/');
    if (parts.length < 2) return null;
    try {
      return decodeURIComponent(parts[1]) || null;
    } catch (error) {
      return null;
    }
  }

  function updateTabs() {
    document.querySelectorAll('.tab[data-route]').forEach((tab) => {
      const active = tab.dataset.route === state.route;
      tab.classList.toggle('active', active);
      if (active) {
        tab.setAttribute('aria-current', 'page');
      } else {
        tab.removeAttribute('aria-current');
      }
    });
  }

  function enterRoute(route) {
    if (state.route === route && dom.view && dom.view.childElementCount > 0) {
      updateTabs();
      return;
    }
    stopRunPolling();
    state.route = route;
    updateTabs();
    document.title = `${ROUTE_TITLES[route] || '观测台'} · Java 并发知识库`;

    switch (route) {
      case 'demos':
        renderDemosShell();
        if (!state.demos.data && !state.demos.loading && !state.demos.error) {
          void loadDemos();
        }
        if (state.run && state.run.status === 'RUNNING') {
          startRunPolling();
        }
        break;
      case 'pools':
        renderPoolsShell();
        fetchPools();
        break;
      case 'threads':
        renderThreadsShell();
        fetchThreads();
        fetchDeadlocks();
        break;
      case 'lab':
        renderLabShell();
        fetchLabStatus();
        break;
      case 'dump':
        renderDumpShell();
        break;
      default:
        break;
    }
  }

  function onHashChange() {
    const route = parseRoute();
    if (!route) {
      window.history.replaceState(null, '', '#/demos');
      enterRoute('demos');
      return;
    }
    enterRoute(route);
    if (route === 'demos' && state.demos.data) {
      const wanted = routeDemoId();
      if (wanted && state.demos.data.demos.some((demo) => demo.id === wanted)) {
        state.selectedDemoId = wanted;
        renderDemoList();
        renderDemoDetail();
      }
    }
  }

  function globalTick() {
    if (document.hidden) return;
    refreshSystem();
    if (state.route === 'pools') {
      fetchPools();
    } else if (state.route === 'threads') {
      fetchThreads();
      fetchDeadlocks();
    } else if (state.route === 'lab') {
      fetchLabStatus();
    }
  }

  function startGlobalPoller() {
    if (state.timers.global) return;
    state.timers.global = window.setInterval(globalTick, 1000);
    globalTick();
  }

  function onVisibilityChange() {
    if (document.hidden) {
      stopRunPolling();
      return;
    }
    globalTick();
    if (state.route === 'demos' && state.run && state.run.status === 'RUNNING') {
      startRunPolling();
    }
  }

  /* =========================================================
   * JVM 胶囊
   * ======================================================= */

  function setCapsuleValue(node, text, ratio) {
    if (!node) return;
    node.textContent = text;
    node.classList.toggle('warn', ratio >= 0.8 && ratio < 0.92);
    node.classList.toggle('danger', ratio >= 0.92);
  }

  function updateJvmCapsule() {
    const system = state.system;
    if (!dom.jvmCpu || !dom.jvmHeap || !dom.jvmThreads) return;
    if (!system) {
      dom.jvmCpu.textContent = '--';
      dom.jvmHeap.textContent = '-- / --';
      dom.jvmThreads.textContent = '--';
      return;
    }

    let cpuRatio = asNumber(system.processCpuLoad, -1);
    if (cpuRatio < 0) cpuRatio = asNumber(system.cpuLoad, 0);
    cpuRatio = clamp(cpuRatio, 0, 1.5);
    setCapsuleValue(dom.jvmCpu, `${fmtCpu(cpuRatio * 100)}%`, cpuRatio);

    const heapUsed = Math.max(0, asNumber(system.heapUsed));
    const heapMax = Math.max(0, asNumber(system.heapMax));
    const heapRatio = heapMax > 0 ? heapUsed / heapMax : 0;
    setCapsuleValue(
      dom.jvmHeap,
      `${fmtInt(heapUsed)} / ${fmtInt(heapMax)} MB`,
      heapRatio
    );

    setCapsuleValue(dom.jvmThreads, fmtInt(system.threadCount), 0);

    if (dom.jvmCapsule) {
      dom.jvmCapsule.title = `进程 CPU ${fmtCpu(
        cpuRatio * 100
      )}% · 堆 ${fmtInt(heapUsed)}/${fmtInt(heapMax)} MB · 线程 ${fmtInt(
        system.threadCount
      )}`;
    }
  }

  function refreshSystem() {
    guarded('system', 'JVM 指标获取失败', async () => {
      const data = await apiGet('/api/system');
      if (!data || typeof data !== 'object') {
        throw new Error('响应格式异常');
      }
      state.system = data;
      updateJvmCapsule();
    });
  }

  /* =========================================================
   * 用例库
   * ======================================================= */

  function normalizeDemosResponse(data) {
    if (!data || !Array.isArray(data.demos)) {
      throw new Error('响应缺少 demos 字段');
    }
    const rawLevels = asArray(data.levels)
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        code: asString(item.code),
        cn: asString(item.cn),
        subtitle: asString(item.subtitle),
        count: Math.max(0, Math.round(asNumber(item.count))),
      }))
      .filter((item) => item.code);

    const levels = rawLevels.length
      ? rawLevels.sort(
          (a, b) =>
            (LEVEL_ORDER.indexOf(a.code) === -1 ? 99 : LEVEL_ORDER.indexOf(a.code)) -
            (LEVEL_ORDER.indexOf(b.code) === -1 ? 99 : LEVEL_ORDER.indexOf(b.code))
        )
      : LEVEL_ORDER.map((code) => ({
          code,
          cn: LEVEL_META[code].cn,
          subtitle: LEVEL_META[code].subtitle,
          count: 0,
        }));

    const demos = asArray(data.demos)
      .filter((item) => item && typeof item === 'object' && asString(item.id))
      .map((item) => ({
        id: asString(item.id),
        level: asString(item.level) || 'basic',
        levelCn: asString(item.levelCn),
        title: asString(item.title) || asString(item.id),
        summary: asString(item.summary),
        knowledge: asString(item.knowledge),
        bestPractice: asString(item.bestPractice),
        timeout: Math.max(0, asNumber(item.timeout)),
      }));

    const knownLevels = new Set(levels.map((item) => item.code));
    const extraLevels = [];
    for (const demo of demos) {
      if (!knownLevels.has(demo.level)) {
        knownLevels.add(demo.level);
        extraLevels.push({
          code: demo.level,
          cn: demo.levelCn || demo.level,
          subtitle: '',
          count: 0,
        });
      }
    }
    const allLevels = levels.concat(extraLevels);
    const counts = new Map();
    for (const demo of demos) {
      counts.set(demo.level, (counts.get(demo.level) || 0) + 1);
    }
    for (const level of allLevels) {
      if (!level.count) level.count = counts.get(level.code) || 0;
    }

    const total = Number.isFinite(Number(data.total))
      ? Math.max(0, Math.round(Number(data.total)))
      : demos.length;

    return { levels: allLevels, demos, total };
  }

  async function loadDemos() {
    if (state.demos.loading) return;
    state.demos.loading = true;
    state.demos.error = '';
    if (state.route === 'demos') {
      renderDemoSidebar();
      renderDemoDetail();
    }
    try {
      const data = await apiGet('/api/demos');
      const normalized = normalizeDemosResponse(data);
      state.demos.data = normalized;
      if (
        state.selectedDemoId &&
        !normalized.demos.some((demo) => demo.id === state.selectedDemoId)
      ) {
        state.selectedDemoId = null;
      }
      // 深链优先，其次自动选中第一个用例，避免首屏右侧空白
      const wanted = routeDemoId();
      if (wanted && normalized.demos.some((demo) => demo.id === wanted)) {
        state.selectedDemoId = wanted;
      } else if (!state.selectedDemoId && normalized.demos.length > 0) {
        state.selectedDemoId = normalized.demos[0].id;
      }
    } catch (error) {
      state.demos.error = error && error.message ? error.message : '加载失败';
      showError(`用例列表加载失败：${state.demos.error}`, true);
    } finally {
      state.demos.loading = false;
      if (state.route === 'demos') {
        renderDemoSidebar();
        renderDemoDetail();
      }
    }
  }

  function renderDemosShell() {
    const page = el('div', { class: 'tab-page' });
    const totalBadge = el('span', {
      class: 'badge',
      id: 'demoTotalBadge',
      text: state.demos.data ? `${fmtInt(state.demos.data.total)} 个用例` : '用例加载中',
    });
    page.appendChild(
      createPageHead(
        '并发用例库',
        '逐个运行知识点用例，观察真实线程行为与断言输出。',
        [
          el('span', { class: 'badge badge-accent', text: '5 个层级' }),
          totalBadge,
        ]
      )
    );

    const levelChips = el('div', { class: 'chips', id: 'demoLevelChips' });
    const demoCount = el('span', { class: 'small', id: 'demoCount', text: '—' });
    const searchInput = el('input', {
      class: 'search-input',
      id: 'demoSearch',
      type: 'search',
      value: state.demoQuery,
      placeholder: '搜索标题 / id / 摘要',
      'aria-label': '搜索用例',
      autocomplete: 'off',
      oninput: (event) => {
        state.demoQuery = event.target.value;
        renderDemoList();
      },
    });

    const sidebar = el(
      'aside',
      { class: 'demo-sidebar', 'aria-label': '用例列表' },
      el(
        'div',
        { class: 'sidebar-tools' },
        el(
          'div',
          { class: 'sidebar-meta' },
          el('span', { text: '层级筛选' }),
          demoCount
        ),
        levelChips,
        el('div', { class: 'search-box' }, searchInput)
      ),
      el('div', { class: 'demo-list', id: 'demoList' })
    );

    const detail = el('section', {
      class: 'demo-detail',
      id: 'demoDetail',
      'aria-label': '用例详情',
    });

    page.appendChild(el('div', { class: 'demos-layout' }, sidebar, detail));
    dom.view.replaceChildren(page);
    renderDemoSidebar();
    renderDemoDetail();
  }

  function renderDemoSidebar() {
    const chipsHost = byId('demoLevelChips');
    const listHost = byId('demoList');
    const countHost = byId('demoCount');
    if (!chipsHost || !listHost) return;

    const totalBadge = byId('demoTotalBadge');
    const data = state.demos.data;
    if (totalBadge) {
      totalBadge.textContent = data ? `${fmtInt(data.total)} 个用例` : '用例加载中';
    }

    if (!data) {
      clearNode(chipsHost);
      chipsHost.appendChild(el('span', { class: 'small muted', text: '正在读取层级…' }));
      renderDemoList();
      return;
    }

    clearNode(chipsHost);
    const allChip = el(
      'button',
      {
        class: `chip${state.demoFilter === 'all' ? ' active' : ''}`,
        type: 'button',
        'aria-pressed': state.demoFilter === 'all' ? 'true' : 'false',
        onclick: () => {
          state.demoFilter = 'all';
          renderDemoSidebar();
        },
      },
      '全部 ',
      el('span', { class: 'chip-count', text: fmtInt(data.total) })
    );
    chipsHost.appendChild(allChip);

    for (const level of data.levels) {
      const active = state.demoFilter === level.code;
      chipsHost.appendChild(
        el(
          'button',
          {
            class: `chip${active ? ' active' : ''}`,
            type: 'button',
            'aria-pressed': active ? 'true' : 'false',
            title: level.subtitle || level.cn,
            onclick: () => {
              state.demoFilter = level.code;
              renderDemoSidebar();
            },
          },
          `${level.cn} `,
          el('span', { class: 'chip-count', text: fmtInt(level.count) })
        )
      );
    }

    if (countHost) {
      countHost.textContent = `${fmtInt(data.demos.length)} 个可用`;
    }
    renderDemoList();
  }

  function demoMatchesQuery(demo, query) {
    if (!query) return true;
    const haystack = `${demo.title} ${demo.id} ${demo.summary}`.toLowerCase();
    return haystack.includes(query);
  }

  function renderDemoList() {
    const listHost = byId('demoList');
    const countHost = byId('demoCount');
    if (!listHost) return;
    clearNode(listHost);

    const data = state.demos.data;
    if (!data) {
      if (state.demos.loading) {
        listHost.appendChild(createLoading('正在加载用例…'));
      } else if (state.demos.error) {
        listHost.appendChild(
          el(
            'div',
            { class: 'sidebar-empty' },
            createInlineError(state.demos.error, () => {
              void loadDemos();
            })
          )
        );
      }
      if (countHost && !state.demos.loading) countHost.textContent = '—';
      return;
    }

    const query = state.demoQuery.trim().toLowerCase();
    const filtered = data.demos.filter(
      (demo) =>
        (state.demoFilter === 'all' || demo.level === state.demoFilter) &&
        demoMatchesQuery(demo, query)
    );

    if (countHost) {
      countHost.textContent = `${fmtInt(filtered.length)} / ${fmtInt(data.demos.length)}`;
    }

    if (filtered.length === 0) {
      listHost.appendChild(
        el('div', { class: 'sidebar-empty', text: '没有匹配的用例，试试调整筛选或关键词。' })
      );
      return;
    }

    for (const level of data.levels) {
      const group = filtered.filter((demo) => demo.level === level.code);
      if (!group.length) continue;
      const groupNode = el(
        'div',
        { class: 'demo-group' },
        el(
          'div',
          { class: 'demo-group-title' },
          el('span', { text: level.cn }),
          el('span', { class: 'group-count', text: fmtInt(group.length) })
        )
      );
      for (const demo of group) {
        const active = demo.id === state.selectedDemoId;
        groupNode.appendChild(
          el(
            'button',
            {
              class: `demo-item${active ? ' active' : ''}`,
              type: 'button',
              title: demo.title,
              'aria-current': active ? 'true' : null,
              onclick: () => {
                state.selectedDemoId = demo.id;
                window.history.replaceState(null, '', `#/demos/${encodeURIComponent(demo.id)}`);
                renderDemoList();
                renderDemoDetail();
              },
            },
            el('span', { class: 'demo-item-title', text: demo.title }),
            el('span', { class: 'demo-item-id', text: demo.id }),
            el('span', { class: 'demo-item-summary', text: demo.summary || '暂无摘要' })
          )
        );
      }
      listHost.appendChild(groupNode);
    }
  }

  function getSelectedDemo() {
    const data = state.demos.data;
    if (!data || !state.selectedDemoId) return null;
    return data.demos.find((demo) => demo.id === state.selectedDemoId) || null;
  }

  function renderDemoDetail() {
    const host = byId('demoDetail');
    if (!host) return;
    clearNode(host);

    if (!state.demos.data) {
      if (state.demos.loading) {
        host.appendChild(createLoading('正在加载用例详情…'));
      } else if (state.demos.error) {
        const wrap = el('div', { style: { padding: '14px' } });
        wrap.appendChild(
          createInlineError(`用例列表加载失败：${state.demos.error}`, () => {
            void loadDemos();
          })
        );
        host.appendChild(wrap);
      } else {
        host.appendChild(createLoading('准备用例库…'));
      }
      return;
    }

    const demo = getSelectedDemo();
    if (!demo) {
      host.appendChild(
        createEmpty(
          '从左侧选择一个用例',
          '每个用例都包含知识点、最佳实践，以及可实时运行的断言日志。选择后可在这里运行、停止并查看输出。',
          el('div', { class: 'empty-cmd', text: '提示：可按层级筛选或搜索 id' })
        )
      );
      return;
    }

    const trackedRun =
      state.run && state.run.demoId === demo.id ? state.run : null;

    const overline = el(
      'div',
      { class: 'detail-overline' },
      el('span', {
        class: `badge level-badge ${levelCode(demo.level)}`,
        text: levelLabel(demo),
      }),
      el('span', { class: 'id-chip', text: demo.id }),
      el('span', { class: 'badge', text: `超时 ${fmtTimeout(demo.timeout)}` })
    );

    const head = el(
      'div',
      { class: 'detail-head' },
      overline,
      el('h2', { class: 'detail-title', text: demo.title }),
      demo.summary
        ? el('p', { class: 'detail-summary', text: demo.summary })
        : null,
      el('div', { class: 'runbar', id: 'demoRunbar' })
    );

    const doc = el('div', { class: 'demo-doc' });

    const knowledgeCard = el(
      'div',
      { class: 'doc-card' },
      el('div', { class: 'doc-card-head', text: '知识点' })
    );
    const knowledgeBody = el('div', { class: 'markdown' });
    renderMarkdown(knowledgeBody, demo.knowledge);
    knowledgeCard.appendChild(knowledgeBody);
    doc.appendChild(knowledgeCard);

    const practiceCard = el(
      'div',
      { class: 'doc-card best-practice' },
      el('div', { class: 'doc-card-head', text: '最佳实践' })
    );
    const practiceBody = el('div', { class: 'markdown' });
    renderMarkdown(practiceBody, demo.bestPractice || '暂无最佳实践说明。');
    practiceCard.appendChild(practiceBody);
    doc.appendChild(practiceCard);

    const consolePanel = createConsolePanel(trackedRun);
    host.appendChild(head);
    host.appendChild(doc);
    host.appendChild(consolePanel);

    renderRunbar();
    setupLogScroll();
  }

  function createConsolePanel(run) {
    const hasRun = Boolean(run);
    const countText = hasRun ? `${fmtInt(run.lines.length)} 行` : '0 行';
    const head = el(
      'div',
      { class: 'console-head' },
      el('div', { class: 'console-title', text: '运行日志' }),
      el(
        'div',
        { class: 'console-meta' },
        el('span', { id: 'logCount', text: countText }),
        el('span', { text: `最多 ${fmtInt(MAX_LOGS)} 行` })
      )
    );

    const logLines = el('div', {
      class: 'log-lines',
      id: 'logLines',
      dataset: { runId: hasRun ? run.runId : '' },
    });

    if (!hasRun) {
      logLines.appendChild(
        el('div', {
          class: 'log-empty',
          text: '运行用例后，这里会实时输出 STEP / INFO / OK / WARN / ERROR 日志。',
        })
      );
    } else if (run.lines.length === 0) {
      logLines.appendChild(
        el('div', {
          class: 'log-empty',
          text:
            run.status === 'RUNNING'
              ? '正在等待第一阶段日志…'
              : '本次运行没有产生日志。',
        })
      );
    } else {
      for (const line of run.lines.slice(-MAX_LOGS)) {
        logLines.appendChild(createLogLine(line));
      }
    }

    const scroll = el('div', {
      class: 'log-scroll',
      id: 'logScroll',
      role: 'log',
      'aria-label': '运行日志',
    }, logLines);
    const bottomButton = el(
      'button',
      {
        class: 'log-bottom',
        id: 'logBottomBtn',
        type: 'button',
        hidden: true,
        onclick: () => scrollToLogBottom(),
      },
      '回到底部 ↓'
    );

    return el(
      'section',
      { class: 'console-panel', 'aria-label': '运行日志控制台' },
      head,
      el('div', { class: 'console-body' }, scroll, bottomButton)
    );
  }

  function createLogLine(line) {
    const level = normalizeLogLevel(line.level);
    return el(
      'div',
      { class: `log-line level-${level.toLowerCase()}` },
      el('span', { class: 'log-time', text: asString(line.time) || '—' }),
      el('span', {
        class: 'log-thread',
        title: asString(line.thread),
        text: asString(line.thread) || '—',
      }),
      el('span', { class: 'log-msg', text: asString(line.msg) })
    );
  }

  function setupLogScroll() {
    const scroll = byId('logScroll');
    if (!scroll) return;
    scroll.addEventListener('scroll', updateLogBottomButton, { passive: true });
    window.requestAnimationFrame(() => {
      scroll.scrollTop = scroll.scrollHeight;
      updateLogBottomButton();
    });
  }

  function isLogAtBottom(scroll) {
    if (!scroll) return true;
    return scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 36;
  }

  function scrollToLogBottom() {
    const scroll = byId('logScroll');
    if (!scroll) return;
    scroll.scrollTop = scroll.scrollHeight;
    updateLogBottomButton();
  }

  function updateLogBottomButton() {
    const scroll = byId('logScroll');
    const button = byId('logBottomBtn');
    if (!scroll || !button) return;
    const atBottom = isLogAtBottom(scroll);
    button.hidden = atBottom;
    button.classList.toggle('log-paused', !atBottom);
  }

  function updateLogCount() {
    const count = byId('logCount');
    if (!count) return;
    count.textContent = state.run
      ? `${fmtInt(state.run.lines.length)} 行`
      : '0 行';
  }

  function renderRunbar() {
    const host = byId('demoRunbar');
    const demo = getSelectedDemo();
    if (!host || !demo) return;
    clearNode(host);

    const tracked =
      state.run && state.run.demoId === demo.id ? state.run : null;
    const otherRunning =
      state.run && state.run.status === 'RUNNING' && state.run.demoId !== demo.id
        ? state.run
        : null;

    const status = tracked ? tracked.status : 'IDLE';
    host.appendChild(statusPill(status));
    host.appendChild(
      el(
        'div',
        { class: 'run-metrics' },
        el(
          'span',
          {},
          '耗时 ',
          el('strong', {
            text: tracked ? fmtDuration(tracked.elapsed) : '—',
          })
        ),
        el('span', {}, '超时 ', el('strong', { text: fmtTimeout(demo.timeout) })),
        el(
          'span',
          { class: 'assert-stat' },
          el('span', {
            class: 'assert-ok',
            text: `✔ ${fmtInt(tracked ? tracked.ok : 0)}`,
          }),
          ' / ',
          el('span', {
            class: 'assert-fail',
            text: `✘ ${fmtInt(tracked ? tracked.failed : 0)}`,
          })
        )
      )
    );

    if (otherRunning) {
      host.appendChild(
        el('span', {
          class: 'run-hint',
          text: `另一用例运行中：${otherRunning.title || otherRunning.demoId}`,
        })
      );
    }

    let button;
    if (state.runStarting && !otherRunning) {
      button = el('button', {
        class: 'btn btn-primary',
        type: 'button',
        disabled: true,
      }, el('span', { class: 'spinner', 'aria-hidden': 'true' }), '启动中…');
    } else if (tracked && tracked.status === 'RUNNING') {
      const cancelling = Boolean(tracked.cancelling);
      button = el('button', {
        class: 'btn btn-danger',
        type: 'button',
        disabled: cancelling,
        text: cancelling ? '停止中…' : '停止',
        onclick: () => {
          void cancelDemoRun(demo);
        },
      });
    } else if (otherRunning) {
      button = el('button', {
        class: 'btn',
        type: 'button',
        disabled: true,
        text: '有运行中的用例',
      });
    } else {
      button = el('button', {
        class: 'btn btn-primary',
        type: 'button',
        text: tracked ? '再次运行' : '运行',
        onclick: () => {
          void startDemoRun(demo);
        },
      });
    }
    host.appendChild(button);

    if (tracked && tracked.error) {
      host.appendChild(el('div', { class: 'run-error', text: tracked.error }));
    }
  }

  function createRun(runId, demo) {
    return {
      runId,
      demoId: demo.id,
      title: demo.title,
      status: 'RUNNING',
      startedAt: Date.now(),
      finishedAt: 0,
      ok: 0,
      failed: 0,
      error: '',
      elapsed: 0,
      lines: [],
      seqSet: new Set(),
      nextSeq: 0,
      fetching: false,
      cancelling: false,
    };
  }

  async function startDemoRun(demo) {
    if (state.runStarting) return;
    if (
      state.run &&
      state.run.status === 'RUNNING'
    ) {
      return;
    }
    state.runStarting = true;
    renderRunbar();
    try {
      const response = await apiPost(
        `/api/demos/${encodeURIComponent(demo.id)}/run`
      );
      const runId = response && response.runId ? asString(response.runId) : '';
      if (!runId) throw new Error('后端未返回 runId');
      state.run = createRun(runId, demo);
      state.runStarting = false;
      if (state.route === 'demos') {
        renderDemoDetail();
        startRunPolling();
      }
    } catch (error) {
      state.runStarting = false;
      showError(
        `用例启动失败：${error && error.message ? error.message : '未知错误'}`,
        true
      );
      renderRunbar();
    }
  }

  async function cancelDemoRun(demo) {
    const run = state.run;
    if (!run || run.demoId !== demo.id || run.status !== 'RUNNING') return;
    if (run.cancelling) return;
    run.cancelling = true;
    renderRunbar();
    try {
      const response = await apiPost(
        `/api/runs/${encodeURIComponent(run.runId)}/cancel`
      );
      if (response && response.cancelled === false) {
        showError('后端未接受取消请求。', true);
      }
    } catch (error) {
      showError(
        `取消运行失败：${error && error.message ? error.message : '未知错误'}`,
        true
      );
    } finally {
      run.cancelling = false;
      renderRunbar();
    }
  }

  function normalizeRunLine(raw) {
    if (!raw || typeof raw !== 'object') return null;
    const seq = Number(raw.seq);
    if (!Number.isFinite(seq)) return null;
    return {
      seq,
      time: asString(raw.time),
      level: normalizeLogLevel(raw.level),
      thread: asString(raw.thread),
      msg: asString(raw.msg),
    };
  }

  function mergeRunLines(run, incoming) {
    const added = [];
    for (const raw of incoming) {
      const line = normalizeRunLine(raw);
      if (!line || run.seqSet.has(line.seq)) continue;
      run.seqSet.add(line.seq);
      run.lines.push(line);
      added.push(line);
    }
    added.sort((a, b) => a.seq - b.seq);

    if (run.lines.length > MAX_LOGS) {
      const removed = run.lines.splice(0, run.lines.length - MAX_LOGS);
      for (const line of removed) run.seqSet.delete(line.seq);
    }
    return added;
  }

  function applyRunSnapshot(run, data) {
    if (!data || typeof data !== 'object') return;
    const status = asString(data.status).toUpperCase();
    if (RUN_STATUS_META[status] && status !== 'IDLE') {
      run.status = status;
    }
    if (Number.isFinite(Number(data.startedAt))) {
      run.startedAt = Number(data.startedAt);
    }
    if (Number.isFinite(Number(data.finishedAt))) {
      run.finishedAt = Number(data.finishedAt);
    }
    if (Number.isFinite(Number(data.elapsed))) {
      run.elapsed = Math.max(0, Number(data.elapsed));
    }
    if (Number.isFinite(Number(data.ok))) {
      run.ok = Math.max(0, Math.round(Number(data.ok)));
    }
    if (Number.isFinite(Number(data.failed))) {
      run.failed = Math.max(0, Math.round(Number(data.failed)));
    }
    run.error = asString(data.error);
    if (data.title) run.title = asString(data.title);

    const added = mergeRunLines(run, asArray(data.lines));
    if (Number.isFinite(Number(data.nextSeq))) {
      run.nextSeq = Number(data.nextSeq);
    } else if (added.length) {
      run.nextSeq = Math.max(run.nextSeq, added[added.length - 1].seq);
    }

    if (state.route === 'demos') {
      renderRunbar();
      if (state.selectedDemoId === run.demoId) {
        appendRunLogLines(run, added);
        updateLogCount();
        const logLines = byId('logLines');
        const emptyLog = logLines
          ? logLines.querySelector('.log-empty')
          : null;
        if (emptyLog && run.lines.length === 0) {
          emptyLog.textContent =
            run.status === 'RUNNING'
              ? '正在等待第一阶段日志…'
              : '本次运行没有产生日志。';
        }
      }
    }
  }

  function appendRunLogLines(run, lines) {
    const logLines = byId('logLines');
    const scroll = byId('logScroll');
    if (!logLines || !scroll) return;
    if (logLines.dataset.runId !== run.runId) return;
    if (!lines || lines.length === 0) return;

    const stick = isLogAtBottom(scroll);
    const empty = logLines.querySelector('.log-empty');
    if (empty) empty.remove();

    for (const line of lines) {
      logLines.appendChild(createLogLine(line));
    }
    while (logLines.childElementCount > MAX_LOGS) {
      logLines.removeChild(logLines.firstElementChild);
    }

    if (stick) {
      window.requestAnimationFrame(() => {
        scroll.scrollTop = scroll.scrollHeight;
        updateLogBottomButton();
      });
    } else {
      updateLogBottomButton();
    }
  }

  async function pollRunOnce(run) {
    if (run.fetching) return;
    run.fetching = true;
    try {
      const data = await apiGet(
        `/api/runs/${encodeURIComponent(run.runId)}?from=${encodeURIComponent(
          String(run.nextSeq || 0)
        )}`
      );
      if (!data || typeof data !== 'object') {
        throw new Error('响应格式异常');
      }
      if (state.run !== run) return;
      applyRunSnapshot(run, data);
    } finally {
      run.fetching = false;
    }
  }

  function canPollRun(run) {
    return Boolean(
      run &&
        state.run === run &&
        run.status === 'RUNNING' &&
        state.route === 'demos' &&
        !document.hidden
    );
  }

  function stopRunPolling() {
    state.runPollToken += 1;
    if (state.timers.runPoll) {
      window.clearTimeout(state.timers.runPoll);
      state.timers.runPoll = null;
    }
  }

  function startRunPolling() {
    stopRunPolling();
    const run = state.run;
    if (!canPollRun(run)) return;
    const token = state.runPollToken;

    const loop = async () => {
      if (token !== state.runPollToken || !canPollRun(run)) return;
      let delay = RUN_POLL_MS;
      try {
        await pollRunOnce(run);
      } catch (error) {
        delay = 1000;
        if (token === state.runPollToken && state.run === run) {
          showError(
            `运行日志获取失败：${
              error && error.message ? error.message : '未知错误'
            }`
          );
        }
      }
      if (token !== state.runPollToken || !canPollRun(run)) return;
      state.timers.runPoll = window.setTimeout(loop, delay);
    };

    void loop();
  }

  /* =========================================================
   * 线程池观测
   * ======================================================= */

  function normalizePools(data) {
    if (!data || !Array.isArray(data.pools)) {
      throw new Error('响应缺少 pools 字段');
    }
    return asArray(data.pools)
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        name: asString(item.name) || '(unnamed-pool)',
        kind: asString(item.kind) || 'ThreadPoolExecutor',
        coreSize: Math.max(0, asNumber(item.coreSize)),
        maxSize: Math.max(0, asNumber(item.maxSize)),
        poolSize: Math.max(0, asNumber(item.poolSize)),
        activeCount: Math.max(0, asNumber(item.activeCount)),
        queueSize: Math.max(0, asNumber(item.queueSize)),
        queueCapacity: Math.max(0, asNumber(item.queueCapacity)),
        completedTasks: Math.max(0, asNumber(item.completedTasks)),
        totalTasks: Math.max(0, asNumber(item.totalTasks)),
        rejected: Math.max(0, asNumber(item.rejected)),
        largestPoolSize: Math.max(0, asNumber(item.largestPoolSize)),
        shutdown: Boolean(item.shutdown),
        threadCount: Math.max(0, asNumber(item.threadCount)),
      }));
  }

  function fetchPools() {
    guarded('pools', '线程池指标获取失败', async () => {
      try {
        const data = await apiGet('/api/pools');
        const pools = normalizePools(data);
        state.pools.items = pools;
        state.pools.ts = asNumber(data.ts);
        state.pools.loaded = true;
        state.pools.error = '';
        const seen = new Set();
        for (const pool of pools) {
          seen.add(pool.name);
          let history = state.pools.histories.get(pool.name);
          if (!history) {
            history = { active: [], queue: [] };
            state.pools.histories.set(pool.name, history);
          }
          history.active.push(pool.activeCount);
          history.queue.push(pool.queueSize);
          if (history.active.length > 60) {
            history.active.splice(0, history.active.length - 60);
          }
          if (history.queue.length > 60) {
            history.queue.splice(0, history.queue.length - 60);
          }
        }
        for (const key of Array.from(state.pools.histories.keys())) {
          if (!seen.has(key)) state.pools.histories.delete(key);
        }
        if (state.route === 'pools') updatePoolsView();
      } catch (error) {
        state.pools.error =
          error && error.message ? error.message : '加载失败';
        if (state.route === 'pools') updatePoolsView();
        throw error;
      }
    });
  }

  function renderPoolsShell() {
    const refreshButton = el('button', {
      class: 'btn btn-sm',
      type: 'button',
      text: '刷新',
      onclick: () => fetchPools(),
    });
    const page = el('div', { class: 'tab-page scrollable pools-page' });
    page.appendChild(
      createPageHead(
        '线程池观测',
        '实时采样线程池容量、队列与拒绝情况，每 1 秒刷新。',
        [
          el('a', { class: 'btn btn-sm', href: '#/demos', text: '去用例库' }),
          refreshButton,
        ]
      )
    );
    page.appendChild(
      el(
        'div',
        { class: 'pools-note' },
        el('span', { class: 'dot', 'aria-hidden': 'true' }),
        '运行中级 / 高级用例后，这里会出现真实的线程池。'
      )
    );
    page.appendChild(
      el(
        'div',
        { class: 'spread' },
        el('span', { class: 'pools-meta', id: 'poolsMeta', text: '等待采样…' }),
        el('span', { class: 'pools-meta', text: '采样间隔 1s · 保留 60 点' })
      )
    );
    page.appendChild(el('div', { class: 'section-gap', id: 'poolsContent' }));
    dom.view.replaceChildren(page);
    updatePoolsView();
  }

  function updatePoolsView() {
    const content = byId('poolsContent');
    const meta = byId('poolsMeta');
    if (!content) return;

    if (!state.pools.loaded) {
      if (meta) meta.textContent = state.pools.error ? '采样失败' : '等待采样…';
      clearNode(content);
      if (state.pools.error) {
        content.appendChild(
          createInlineError(`线程池指标获取失败：${state.pools.error}`, () => fetchPools())
        );
      } else {
        content.appendChild(createLoading('正在连接线程池指标…'));
      }
      return;
    }

    if (meta) {
      meta.textContent = `最近采样 ${fmtTime(state.pools.ts)} · ${fmtInt(
        state.pools.items.length
      )} 个线程池`;
    }

    clearNode(content);
    if (state.pools.items.length === 0) {
      content.appendChild(
        createEmpty(
          '暂无线程池数据',
          '运行中级 / 高级的用例后，这里会出现真实的线程池。也可以先查看基础用例，再观察队列和拒绝次数的变化。',
          el('a', { class: 'btn btn-primary btn-sm', href: '#/demos', text: '去运行用例' })
        )
      );
      return;
    }

    const normalPools = state.pools.items.filter(
      (pool) => !pool.name.includes('VirtualThreadPerTask')
    );
    const virtualPools = state.pools.items.filter((pool) =>
      pool.name.includes('VirtualThreadPerTask')
    );
    const sparkJobs = [];

    if (normalPools.length) {
      const grid = el('div', { class: 'pools-grid' });
      for (const pool of normalPools) {
        const built = createPoolCard(pool);
        grid.appendChild(built.node);
        sparkJobs.push(...built.sparkJobs);
      }
      content.appendChild(grid);
    }

    if (virtualPools.length) {
      const section = el('div', { class: 'virtual-pools' });
      section.appendChild(
        el('div', {
          class: 'virtual-section-head',
          text: '虚拟线程 · 无需池化',
        })
      );
      const grid = el('div', { class: 'virtual-grid' });
      for (const pool of virtualPools) {
        grid.appendChild(createVirtualPoolCard(pool));
      }
      section.appendChild(grid);
      content.appendChild(section);
    }

    window.requestAnimationFrame(() => {
      for (const job of sparkJobs) {
        drawSparkline(job.canvas, job.values, job.color);
      }
    });
  }

  function progressPercent(value, max) {
    if (!Number.isFinite(max) || max <= 0) return 0;
    return clamp((value / max) * 100, 0, 100);
  }

  function queueSeverity(percent) {
    if (percent > 95) return 'red';
    if (percent > 80) return 'yellow';
    return '';
  }

  function createProgressRow(label, valueText, percent, tone) {
    return el(
      'div',
      { class: 'pool-progress-row' },
      el(
        'div',
        { class: 'progress-label' },
        el('span', { text: label }),
        el('strong', { text: valueText })
      ),
      el(
        'div',
        {
          class: 'progress-track',
          role: 'progressbar',
          'aria-label': label,
          'aria-valuemin': '0',
          'aria-valuemax': '100',
          'aria-valuenow': String(Math.round(percent)),
        },
        el('div', {
          class: `progress-fill${tone ? ` ${tone}` : ''}`,
          style: { width: `${percent}%` },
        })
      )
    );
  }

  function createPoolCard(pool) {
    const activePercent = progressPercent(pool.activeCount, pool.poolSize);
    const queuePercent = progressPercent(pool.queueSize, pool.queueCapacity);
    const queueTone = queueSeverity(queuePercent);
    const history = state.pools.histories.get(pool.name) || {
      active: [],
      queue: [],
    };

    const head = el(
      'div',
      { class: 'pool-card-head' },
      el(
        'div',
        { style: { minWidth: '0' } },
        el('div', { class: 'pool-name', text: pool.name, title: pool.name }),
        el(
          'div',
          { class: 'pool-kind' },
          el('span', { class: 'badge', text: pool.kind })
        )
      ),
      el('span', {
        class: `badge ${pool.shutdown ? 'badge-red' : 'badge-green'}`,
        text: pool.shutdown ? '已关闭' : '运行中',
      })
    );

    const stats = el(
      'div',
      { class: 'pool-stats' },
      poolStat('已完成任务', fmtCompact(pool.completedTasks)),
      poolStat('提交总数', fmtCompact(pool.totalTasks)),
      poolStat(
        '拒绝次数',
        fmtInt(pool.rejected),
        pool.rejected > 0 ? 'rejected alert' : ''
      ),
      poolStat('最大池大小', fmtInt(pool.maxSize)),
      poolStat('历史峰值', fmtInt(pool.largestPoolSize)),
      poolStat('线程数', fmtInt(pool.threadCount))
    );

    const activeSpark = el('canvas', {
      class: 'sparkline',
      'aria-label': `${pool.name} 活跃线程 sparkline`,
      role: 'img',
    });
    const queueSpark = el('canvas', {
      class: 'sparkline',
      'aria-label': `${pool.name} 队列长度 sparkline`,
      role: 'img',
    });

    const sparks = el(
      'div',
      { class: 'pool-sparks' },
      el(
        'div',
        { class: 'spark-row' },
        el(
          'div',
          { class: 'spark-label' },
          '活跃线程',
          el('strong', { text: `${fmtInt(pool.activeCount)} / ${fmtInt(pool.poolSize)}` })
        ),
        el('div', { class: 'spark-wrap' }, activeSpark)
      ),
      el(
        'div',
        { class: 'spark-row' },
        el(
          'div',
          { class: 'spark-label' },
          '队列长度',
          el('strong', {
            text: `${fmtInt(pool.queueSize)} / ${fmtInt(pool.queueCapacity)}`,
          })
        ),
        el('div', { class: 'spark-wrap' }, queueSpark)
      )
    );

    const node = el(
      'article',
      { class: 'pool-card' },
      head,
      createProgressRow(
        '活跃 / 池大小',
        `${fmtInt(pool.activeCount)} / ${fmtInt(pool.poolSize)}`,
        activePercent,
        activePercent > 95 ? 'yellow' : ''
      ),
      createProgressRow(
        '队列',
        `${fmtInt(pool.queueSize)} / ${fmtInt(pool.queueCapacity)}`,
        queuePercent,
        queueTone
      ),
      stats,
      sparks
    );

    return {
      node,
      sparkJobs: [
        { canvas: activeSpark, values: history.active, color: '#38bdf8' },
        { canvas: queueSpark, values: history.queue, color: '#f59e0b' },
      ],
    };
  }

  function poolStat(label, value, extraClass) {
    return el(
      'div',
      { class: 'pool-stat' },
      el('span', { class: 'pool-stat-label', text: label }),
      el('span', {
        class: `pool-stat-value${extraClass ? ` ${extraClass}` : ''}`,
        text: value,
      })
    );
  }

  function createVirtualPoolCard(pool) {
    return el(
      'article',
      { class: 'pool-card virtual' },
      el(
        'div',
        { class: 'pool-card-head' },
        el(
          'div',
          { style: { minWidth: '0' } },
          el('div', { class: 'pool-name', text: pool.name, title: pool.name }),
          el(
            'div',
            { class: 'pool-kind' },
            el('span', { class: 'badge', text: pool.kind }),
            ' ',
            el('span', { class: 'badge badge-purple', text: '虚拟线程' })
          )
        )
      ),
      el('div', {
        class: 'virtual-desc',
        text: '虚拟线程无需池化，避免固定线程数限制并发。以下指标仅供参考，不绘制池容量曲线。',
      }),
      el(
        'div',
        { class: 'virtual-stats' },
        el('span', { text: `活跃 ${fmtInt(pool.activeCount)}` }),
        el('span', { text: `线程 ${fmtInt(pool.threadCount)}` }),
        el('span', { text: `已完成 ${fmtCompact(pool.completedTasks)}` }),
        el('span', { text: `拒绝 ${fmtInt(pool.rejected)}` })
      )
    );
  }

  function drawSparkline(canvas, values, color) {
    if (!canvas || !canvas.isConnected) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(40, Math.round(rect.width || 160));
    const height = Math.max(24, Math.round(rect.height || 43));
    const dpr = Math.max(1, window.devicePixelRatio || 1);
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);

    ctx.save();
    ctx.strokeStyle = 'rgba(120, 145, 170, 0.11)';
    ctx.lineWidth = 1;
    for (let i = 1; i < 3; i += 1) {
      const y = (height / 3) * i + 0.5;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }
    ctx.restore();

    const data = asArray(values).map((value) => Math.max(0, asNumber(value)));
    if (!data.length) {
      ctx.fillStyle = 'rgba(139, 153, 171, 0.4)';
      ctx.font = '9px "Cascadia Code", Consolas, monospace';
      ctx.fillText('等待采样', 6, height / 2 + 3);
      return;
    }

    const maxValue = Math.max(1, ...data);
    const padX = 3;
    const padY = 5;
    const usableW = Math.max(1, width - padX * 2);
    const usableH = Math.max(1, height - padY * 2);
    const points = data.map((value, index) => {
      const x =
        data.length === 1
          ? width - padX
          : padX + (index / (data.length - 1)) * usableW;
      const y = padY + usableH - (value / maxValue) * usableH;
      return { x, y };
    });

    if (points.length > 1) {
      const gradient = ctx.createLinearGradient(0, padY, 0, height);
      gradient.addColorStop(0, `${color}33`);
      gradient.addColorStop(1, `${color}00`);
      ctx.beginPath();
      ctx.moveTo(points[0].x, height - padY);
      for (const point of points) ctx.lineTo(point.x, point.y);
      ctx.lineTo(points[points.length - 1].x, height - padY);
      ctx.closePath();
      ctx.fillStyle = gradient;
      ctx.fill();

      ctx.beginPath();
      ctx.moveTo(points[0].x, points[0].y);
      for (let i = 1; i < points.length; i += 1) {
        ctx.lineTo(points[i].x, points[i].y);
      }
      ctx.strokeStyle = color;
      ctx.lineWidth = 1.4;
      ctx.lineJoin = 'round';
      ctx.stroke();
    }

    const last = points[points.length - 1];
    ctx.beginPath();
    ctx.arc(last.x, last.y, 2, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();
  }

  /* =========================================================
   * 线程与死锁
   * ======================================================= */

  function normalizeThread(raw) {
    if (!raw || typeof raw !== 'object') return null;
    return {
      tid: raw.tid === null || raw.tid === undefined ? '' : String(raw.tid),
      name: asString(raw.name) || '(unnamed)',
      state: asString(raw.state).toUpperCase() || 'UNKNOWN',
      daemon: Boolean(raw.daemon),
      priority: asNumber(raw.priority),
      cpuMs: asNumber(raw.cpuMs),
      blockedCount: asNumber(raw.blockedCount),
      waitedCount: asNumber(raw.waitedCount),
      lockName: asString(raw.lockName),
      lockOwnerName: asString(raw.lockOwnerName),
      stack: asArray(raw.stack).map(asString),
    };
  }

  function normalizeThreadsData(data) {
    if (!data || !Array.isArray(data.threads)) {
      throw new Error('响应缺少 threads 字段');
    }
    const threads = asArray(data.threads)
      .map(normalizeThread)
      .filter(Boolean);
    return {
      ts: asNumber(data.ts),
      total: Math.max(0, asNumber(data.total, threads.length)),
      states: normalizeStates(data.states),
      threads,
    };
  }

  function normalizeDeadlocksData(data) {
    if (!data || !Array.isArray(data.deadlocks)) {
      throw new Error('响应缺少 deadlocks 字段');
    }
    const deadlocks = asArray(data.deadlocks)
      .map((group) => {
        const list = Array.isArray(group)
          ? group
          : group && Array.isArray(group.threads)
          ? group.threads
          : [];
        if (!Array.isArray(list) || !list.length) return null;
        const threads = list
          .filter((item) => item && typeof item === 'object')
          .map((item) => ({
            tid: asString(item.tid),
            name: asString(item.name) || '(unnamed)',
            state: asString(item.state).toUpperCase() || 'BLOCKED',
            lockName: asString(item.lockName),
            lockOwnerName: asString(item.lockOwnerName),
            stack: asArray(item.stack).map(asString),
          }));
        return threads.length ? { threads } : null;
      })
      .filter(Boolean);
    return { ts: asNumber(data.ts), deadlocks };
  }

  function fetchThreads() {
    guarded('threads', '线程快照获取失败', async () => {
      try {
        const data = await apiGet('/api/threads');
        state.threads.data = normalizeThreadsData(data);
        state.threads.loaded = true;
        state.threads.error = '';
        if (state.route === 'threads') updateThreadsView();
      } catch (error) {
        state.threads.error =
          error && error.message ? error.message : '加载失败';
        if (state.route === 'threads') updateThreadsView();
        throw error;
      }
    });
  }

  function fetchDeadlocks() {
    guarded('deadlocks', '死锁检测失败', async () => {
      try {
        const data = await apiGet('/api/deadlocks');
        const normalized = normalizeDeadlocksData(data);
        state.threads.deadlocks = normalized.deadlocks;
        state.threads.deadlockTs = normalized.ts;
        state.threads.deadlocksLoaded = true;
        state.threads.deadlockError = '';
        if (state.route === 'threads') {
          updateDeadlockUI();
          updateThreadTable();
        }
      } catch (error) {
        state.threads.deadlockError =
          error && error.message ? error.message : '检测失败';
        if (state.route === 'threads') updateDeadlockUI();
        throw error;
      }
    });
  }

  function renderThreadsShell() {
    const makeRefreshButton = () =>
      el('button', {
        class: 'btn btn-sm',
        type: 'button',
        text: '刷新',
        onclick: () => {
          fetchThreads();
          fetchDeadlocks();
        },
      });
    const page = el('div', { class: 'tab-page scrollable thread-page' });
    page.appendChild(
      createPageHead(
        '线程与死锁',
        '查看 JVM 线程快照、状态分布与锁等待关系，每秒刷新。',
        [
          el('span', {
            class: 'badge',
            id: 'deadlockBadge',
            text: '死锁检测中',
          }),
          makeRefreshButton(),
        ]
      )
    );

    page.appendChild(el('div', { id: 'threadStats' }, createLoading('正在读取线程快照…')));
    page.appendChild(el('div', { id: 'deadlockBanner' }));

    const search = el('input', {
      class: 'search-input',
      id: 'threadSearch',
      type: 'search',
      value: state.threads.query,
      placeholder: '按线程名称搜索',
      'aria-label': '按线程名称搜索',
      autocomplete: 'off',
      oninput: (event) => {
        state.threads.query = event.target.value;
        updateThreadTable();
      },
    });

    const filter = el(
      'select',
      {
        class: 'select',
        id: 'threadStateFilter',
        'aria-label': '按线程状态筛选',
        onchange: (event) => {
          state.threads.filter = event.target.value;
          updateThreadTable();
        },
      },
      el('option', { value: 'ALL', text: '全部状态' })
    );

    const card = el(
      'section',
      { class: 'panel threads-card' },
      el(
        'div',
        { class: 'panel-head' },
        el(
          'div',
          {},
          el('div', { class: 'panel-title', text: '线程明细' }),
          el('div', {
            class: 'panel-sub',
            id: 'threadsMeta',
            text: '等待快照…',
          })
        ),
        el(
          'div',
          { class: 'thread-toolbar' },
          el('div', { class: 'search-box' }, search),
          filter,
          makeRefreshButton()
        )
      ),
      el('div', {
        class: 'table-wrap',
        id: 'threadsTableWrap',
      }, createLoading('正在读取线程列表…')),
      el('div', {
        class: 'thread-footnote',
        text: '数据来自 ThreadMXBean；虚拟线程不包含在 ThreadMXBean 的线程列表中。',
      })
    );

    page.appendChild(card);
    dom.view.replaceChildren(page);

    updateThreadsView();
    updateDeadlockUI();
  }

  function updateThreadsView() {
    if (state.route !== 'threads') return;
    const stats = byId('threadStats');
    const tableWrap = byId('threadsTableWrap');
    const meta = byId('threadsMeta');
    if (!stats || !tableWrap) return;

    if (!state.threads.data) {
      clearNode(stats);
      if (state.threads.error) {
        stats.appendChild(
          createInlineError(`线程快照获取失败：${state.threads.error}`, () => {
            fetchThreads();
            fetchDeadlocks();
          })
        );
      } else {
        stats.appendChild(createLoading('正在读取线程快照…'));
      }
      if (meta) meta.textContent = '等待快照…';
      clearNode(tableWrap);
      if (state.threads.error) {
        tableWrap.appendChild(
          createInlineError(state.threads.error, () => fetchThreads())
        );
      } else {
        tableWrap.appendChild(createLoading('正在读取线程列表…'));
      }
      return;
    }

    renderThreadStats();
    syncThreadStateFilter();
    updateDeadlockUI();
    updateThreadTable();
  }

  function renderThreadStats() {
    const host = byId('threadStats');
    if (!host || !state.threads.data) return;
    const data = state.threads.data;
    const daemonCount = data.threads.filter((thread) => thread.daemon).length;
    const totalFromStates = data.states.reduce((acc, item) => acc + item.count, 0);
    const total = Math.max(data.total, totalFromStates, data.threads.length);
    const distribution = createStateDistribution(data.states, total);

    clearNode(host);
    host.appendChild(
      el(
        'div',
        { class: 'thread-summary' },
        el(
          'div',
          { class: 'summary-card total-card' },
          el('span', { class: 'metric-label', text: '线程总数' }),
          el('span', { class: 'big-number', text: fmtInt(total) }),
          el(
            'div',
            { class: 'summary-foot' },
            el('span', { text: `守护线程 ${fmtInt(daemonCount)}` }),
            el('span', { text: `采集 ${fmtTime(data.ts)}` })
          )
        ),
        el(
          'div',
          { class: 'summary-card state-card' },
          el('span', { class: 'metric-label', text: '线程状态分布' }),
          distribution
        )
      )
    );
  }

  function syncThreadStateFilter() {
    const select = byId('threadStateFilter');
    if (!select || !state.threads.data) return;
    const states = state.threads.data.states.map((item) => item.state);
    const signature = states.join('|');
    if (select.dataset.signature !== signature) {
      select.dataset.signature = signature;
      clearNode(select);
      select.appendChild(el('option', { value: 'ALL', text: '全部状态' }));
      for (const stateName of states) {
        select.appendChild(
          el('option', {
            value: stateName,
            text: `${stateName} · ${stateLabel(stateName)}`,
          })
        );
      }
    }
    if (
      state.threads.filter !== 'ALL' &&
      !states.includes(state.threads.filter)
    ) {
      state.threads.filter = 'ALL';
    }
    select.value = state.threads.filter;
  }

  function collectDeadlockedNames() {
    const names = new Set();
    for (const group of state.threads.deadlocks) {
      for (const thread of group.threads) {
        if (thread.name) names.add(thread.name);
      }
    }
    return names;
  }

  function updateDeadlockUI() {
    if (state.route !== 'threads') return;
    const badge = byId('deadlockBadge');
    const banner = byId('deadlockBanner');
    if (!badge || !banner) return;
    clearNode(banner);

    if (!state.threads.deadlocksLoaded) {
      if (state.threads.deadlockError) {
        badge.className = 'badge badge-red';
        badge.textContent = '死锁检测失败';
        banner.appendChild(
          createInlineError(`死锁检测失败：${state.threads.deadlockError}`, () =>
            fetchDeadlocks()
          )
        );
      } else {
        badge.className = 'badge';
        badge.textContent = '死锁检测中';
      }
      return;
    }

    const groups = state.threads.deadlocks;
    if (!groups.length) {
      badge.className = 'badge badge-green';
      badge.textContent = '未发现死锁';
      return;
    }

    badge.className = 'badge badge-red';
    badge.textContent = `检测到 ${fmtInt(groups.length)} 组死锁`;
    const groupNodes = el('div', { class: 'deadlock-groups' });

    groups.forEach((group, index) => {
      const groupNode = el('div', { class: 'deadlock-group' });
      groupNode.appendChild(
        el('div', {
          class: 'deadlock-group-title',
          text: `DEADLOCK GROUP ${index + 1}`,
        })
      );
      for (const thread of group.threads) {
        groupNode.appendChild(
          el(
            'div',
            { class: 'deadlock-row' },
            el('span', { class: 'deadlock-thread', text: thread.name }),
            el('span', { class: 'state-pill state-blocked', text: thread.state }),
            thread.lockName
              ? el(
                  'span',
                  { class: 'deadlock-lock' },
                  '等待锁 ',
                  thread.lockName
                )
              : null,
            thread.lockOwnerName
              ? el(
                  'span',
                  { class: 'deadlock-lock' },
                  '持有者 ',
                  thread.lockOwnerName
                )
              : null
          )
        );
      }
      groupNodes.appendChild(groupNode);
    });

    banner.appendChild(
      el(
        'div',
        { class: 'deadlock-banner' },
        el(
          'div',
          { class: 'deadlock-banner-head' },
          el('span', { class: 'symbol', text: '⚠', 'aria-hidden': 'true' }),
          `检测到 ${fmtInt(groups.length)} 组死锁，相关线程已阻塞`
        ),
        groupNodes
      )
    );
  }

  function updateThreadTable() {
    if (state.route !== 'threads') return;
    const wrap = byId('threadsTableWrap');
    if (!wrap) return;
    clearNode(wrap);

    if (!state.threads.data) {
      wrap.appendChild(createLoading('正在读取线程列表…'));
      return;
    }

    const data = state.threads.data;
    const query = state.threads.query.trim().toLowerCase();
    const filter = state.threads.filter;
    const deadlockedNames = collectDeadlockedNames();
    const threads = data.threads.filter((thread) => {
      if (filter !== 'ALL' && thread.state !== filter) return false;
      if (query && !thread.name.toLowerCase().includes(query)) return false;
      return true;
    });

    const meta = byId('threadsMeta');
    if (meta) {
      meta.textContent = `更新于 ${fmtTime(data.ts)} · 显示 ${fmtInt(
        threads.length
      )} / ${fmtInt(data.total)} 个线程`;
    }

    if (threads.length === 0) {
      wrap.appendChild(
        el('div', {
          class: 'table-empty',
          text: query || filter !== 'ALL' ? '没有符合条件的线程。' : '当前没有线程数据。',
        })
      );
      return;
    }

    const tbody = el('tbody');
    for (const thread of threads) {
      const rowKey = thread.tid || thread.name;
      const expanded = state.threads.expanded.has(rowKey);
      const deadlocked = deadlockedNames.has(thread.name);
      const row = el(
        'tr',
        {
          class: `thread-row${expanded ? ' expanded' : ''}${
            deadlocked ? ' deadlocked' : ''
          }`,
          tabindex: '0',
          role: 'button',
          'aria-expanded': expanded ? 'true' : 'false',
          onclick: () => toggleThreadRow(rowKey),
          onkeydown: (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              toggleThreadRow(rowKey);
            }
          },
        },
        el(
          'td',
          { class: 'thread-name-cell' },
          el(
            'div',
            { class: 'thread-name' },
            el('span', { class: 'caret', text: '›', 'aria-hidden': 'true' }),
            el('span', { class: 'truncate', text: thread.name, title: thread.name }),
            thread.daemon ? el('span', { class: 'daemon-badge', text: 'DAEMON' }) : null
          ),
          thread.tid
            ? el('div', { class: 'thread-meta-line', text: `tid ${thread.tid}` })
            : null
        ),
        el(
          'td',
          {},
          el('span', {
            class: `state-pill ${stateClass(thread.state)}`,
            text: thread.state,
            title: stateLabel(thread.state),
          })
        ),
        el('td', { class: 'num-cell', text: fmtCompact(thread.cpuMs) }),
        el('td', {
          class: 'num-cell',
          text: `${fmtCompact(thread.blockedCount)} / ${fmtCompact(
            thread.waitedCount
          )}`,
        }),
        el(
          'td',
          { class: 'lock-cell' },
          thread.lockName
            ? el('span', {
                class: 'lock-name',
                text: thread.lockName,
                title: thread.lockName,
              })
            : el('span', { class: 'muted', text: '—' }),
          thread.lockOwnerName
            ? el('span', {
                class: 'lock-owner',
                text: `持有者 ${thread.lockOwnerName}`,
                title: thread.lockOwnerName,
              })
            : null
        )
      );
      tbody.appendChild(row);

      if (expanded) {
        const stackText = thread.stack.length
          ? thread.stack.join('\n')
          : '无堆栈信息';
        tbody.appendChild(
          el(
            'tr',
            { class: 'stack-row' },
            el(
              'td',
              { colspan: '5' },
              el(
                'div',
                { class: 'stack-block' },
                el('div', { class: 'stack-block-title', text: `STACK · ${thread.name}` }),
                el('pre', { class: 'stack-pre', text: stackText })
              )
            )
          )
        );
      }
    }

    const table = el(
      'table',
      { class: 'data-table' },
      el(
        'thead',
        {},
        el(
          'tr',
          {},
          el('th', { text: '线程名称' }),
          el('th', { text: '状态' }),
          el('th', { text: 'CPU ms' }),
          el('th', { text: 'blocked / waited' }),
          el('th', { text: '锁信息' })
        )
      ),
      tbody
    );
    wrap.appendChild(table);
  }

  function toggleThreadRow(key) {
    if (state.threads.expanded.has(key)) {
      state.threads.expanded.delete(key);
    } else {
      state.threads.expanded.add(key);
    }
    updateThreadTable();
  }

  /* =========================================================
   * 排障演练场
   * ======================================================= */

  function fetchLabStatus() {
    guarded('labStatus', '演练状态获取失败', async () => {
      try {
        await loadLabStatus();
      } catch (error) {
        state.lab.error =
          error && error.message ? error.message : '获取失败';
        if (state.route === 'lab') updateLabView();
        throw error;
      }
    });
  }

  async function loadLabStatus() {
    const data = await apiGet('/api/lab/status');
    if (!data || typeof data !== 'object') {
      throw new Error('响应格式异常');
    }
    const running = asArray(data.running)
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        name: asString(item.name),
        detail: asString(item.detail),
        since: asNumber(item.since),
      }))
      .filter((item) => item.name);
    const supported = asArray(data.supported).map(asString).filter(Boolean);
    state.lab.status = { running, supported };
    state.lab.error = '';
    if (state.route === 'lab') updateLabView();
  }

  function renderLabShell() {
    const diagnoseButton = el('button', {
      class: 'btn btn-primary btn-sm',
      id: 'labDiagnoseBtn',
      type: 'button',
      onclick: () => {
        void diagnoseLab();
      },
    }, '一键诊断');
    const stopButton = el('button', {
      class: 'btn btn-danger btn-sm',
      id: 'labStopBtn',
      type: 'button',
      text: '全部停止',
      onclick: () => {
        void stopAllLabs();
      },
    });

    const page = el('div', { class: 'tab-page scrollable lab-page' });
    page.appendChild(
      createPageHead(
        '排障演练场',
        '启动受控故障，观察真实 JVM 指标，再用一键诊断把现象收敛成结论。',
        [diagnoseButton, stopButton]
      )
    );
    page.appendChild(
      el('div', { class: 'lab-status-strip', id: 'labSummary' }, '正在读取演练状态…')
    );
    page.appendChild(el('div', { class: 'lab-grid', id: 'labCards' }));
    page.appendChild(
      el(
        'section',
        { class: 'diagnose-panel' },
        el(
          'div',
          { class: 'diagnose-head' },
          el('div', {}, el('div', { class: 'panel-title', text: '诊断结果' }))
        ),
        el('div', { class: 'diagnose-body', id: 'diagnoseBody' })
      )
    );

    dom.view.replaceChildren(page);
    updateLabView();
    renderDiagnosePanel();
  }

  function getLabRunningMap() {
    const map = new Map();
    const status = state.lab.status;
    if (status) {
      for (const item of status.running) map.set(item.name, item);
    }
    return map;
  }

  function getSupportedLabs() {
    const status = state.lab.status;
    if (status && Array.isArray(status.supported)) {
      return new Set(status.supported);
    }
    return new Set(LABS.map((item) => item.name));
  }

  function updateLabView() {
    if (state.route !== 'lab') return;
    const summary = byId('labSummary');
    const cards = byId('labCards');
    const stopButton = byId('labStopBtn');
    const diagnoseButton = byId('labDiagnoseBtn');
    if (!summary || !cards) return;

    const running = getLabRunningMap();
    const supported = getSupportedLabs();

    if (diagnoseButton) {
      diagnoseButton.disabled = state.lab.diagnosing;
      clearNode(diagnoseButton);
      if (state.lab.diagnosing) {
        diagnoseButton.appendChild(
          el('span', { class: 'spinner', 'aria-hidden': 'true' })
        );
        diagnoseButton.appendChild(document.createTextNode('诊断中…'));
      } else {
        diagnoseButton.textContent = '一键诊断';
      }
    }
    if (stopButton) {
      stopButton.disabled = state.lab.stopping || running.size === 0;
      stopButton.textContent = state.lab.stopping ? '停止中…' : '全部停止';
    }

    clearNode(summary);
    if (!state.lab.status && !state.lab.error) {
      summary.appendChild(
        el(
          'div',
          { class: 'lab-status-main' },
          el('span', { class: 'spinner', 'aria-hidden': 'true' }),
          el('span', { text: '正在读取演练状态…' })
        )
      );
      summary.appendChild(
        el('span', { class: 'muted tiny', text: '状态每秒刷新' })
      );
    } else if (state.lab.error && !state.lab.status) {
      summary.appendChild(
        createInlineError(`演练状态获取失败：${state.lab.error}`, () =>
          fetchLabStatus()
        )
      );
    } else {
      const runningList = Array.from(running.values());
      summary.appendChild(
        el(
          'div',
          { class: 'lab-status-main' },
          el('span', {
            class: `live-dot`,
            'aria-hidden': 'true',
          }),
          runningList.length
            ? el(
                'span',
                {},
                '当前运行 ',
                el('strong', { text: fmtInt(runningList.length) }),
                ' 个演练'
              )
            : el('span', { text: '当前没有运行中的演练' }),
          runningList.map((item) =>
            el(
              'span',
              { class: 'badge badge-red' },
              `${item.name} · ${fmtSince(item.since)}`
            )
          )
        )
      );
      summary.appendChild(
        el('span', {
          class: 'muted tiny',
          text: `支持 ${fmtInt(supported.size)} 项 · 状态每秒刷新`,
        })
      );
    }

    clearNode(cards);
    const timestamp = Date.now();
    LABS.forEach((info, index) => {
      cards.appendChild(
        createLabCard(info, index, running.get(info.name), supported, timestamp)
      );
    });
  }

  function createLabCard(info, index, running, supported, now) {
    const isSupported = supported.has(info.name);
    const isRunning = Boolean(running);
    const isStarting = state.lab.starting === info.name;
    const card = el('article', {
      class: `lab-card${isRunning ? ' running' : ''}${
        !isSupported ? ' unsupported' : ''
      }`,
    });

    const statusNode = isRunning
      ? el(
          'span',
          { class: 'status-pill status-failed', title: running.detail || '' },
          '运行中'
        )
      : !isSupported
      ? el('span', { class: 'badge', text: '不支持' })
      : el('span', { class: 'badge', text: '就绪' });

    card.appendChild(
      el(
        'div',
        { class: 'lab-card-head' },
        el(
          'div',
          { style: { minWidth: '0' } },
          el('div', { class: 'lab-index', text: `演练 0${index + 1} · ${info.name}` }),
          el('div', { class: 'lab-title', text: info.title }),
          isRunning
            ? el('div', {
                class: 'lab-running-elapsed',
                text: `运行中 · ${fmtClockSpan(Math.max(0, now - running.since))}`,
              })
            : null
        ),
        statusNode
      )
    );

    card.appendChild(el('div', { class: 'lab-desc', text: info.desc }));

    const fields = el('div', { class: 'lab-detail-grid' });
    fields.appendChild(
      el(
        'div',
        { class: 'lab-field' },
        el('span', { class: 'lab-field-label', text: '现象' }),
        el('span', { text: info.symptom })
      )
    );
    fields.appendChild(
      el(
        'div',
        { class: 'lab-field' },
        el('span', { class: 'lab-field-label', text: '观察' }),
        el('span', { text: info.watch })
      )
    );
    const commandWrap = el('div', { class: 'lab-commands' });
    for (const command of info.commands) {
      commandWrap.appendChild(
        el('span', { class: 'lab-command', text: command })
      );
    }
    fields.appendChild(
      el(
        'div',
        { class: 'lab-field' },
        el('span', { class: 'lab-field-label', text: '命令' }),
        commandWrap
      )
    );
    if (isRunning && running.detail) {
      fields.appendChild(
        el(
          'div',
          { class: 'lab-field' },
          el('span', { class: 'lab-field-label', text: '详情' }),
          el('span', { text: running.detail })
        )
      );
    }
    card.appendChild(fields);

    const startButton = el('button', {
      class: 'btn btn-sm' + (isRunning ? '' : ' btn-primary'),
      type: 'button',
      disabled:
        Boolean(state.lab.starting) ||
        isRunning ||
        !isSupported ||
        state.lab.stopping,
      onclick: () => {
        void startLab(info.name);
      },
    });
    if (isStarting) {
      startButton.appendChild(el('span', { class: 'spinner', 'aria-hidden': 'true' }));
      startButton.appendChild(document.createTextNode('启动中…'));
    } else if (isRunning) {
      startButton.textContent = '运行中';
    } else if (!isSupported) {
      startButton.textContent = '后端未启用';
    } else {
      startButton.textContent = '启动演练';
    }

    card.appendChild(
      el(
        'div',
        { class: 'lab-card-foot' },
        el('span', {
          class: 'lab-start-state',
          text: isRunning
            ? `已运行 ${fmtSince(running.since)} · 使用全部停止结束`
            : isSupported
            ? '受控故障 · 注意观察指标'
            : '当前后端不支持此演练',
        }),
        startButton
      )
    );

    return card;
  }

  async function startLab(name) {
    if (state.lab.starting || state.lab.stopping) return;
    const running = getLabRunningMap();
    if (running.has(name)) return;
    state.lab.starting = name;
    updateLabView();
    let started = false;
    try {
      const response = await apiPost(`/api/lab/${encodeURIComponent(name)}`);
      if (response && response.ok === false) {
        throw new Error(response.detail || '后端未启动该演练');
      }
      started = true;
    } catch (error) {
      showError(
        `启动演练失败：${error && error.message ? error.message : '未知错误'}`,
        true
      );
    } finally {
      state.lab.starting = '';
      updateLabView();
    }
    if (started) {
      try {
        await loadLabStatus();
        renderDiagnosePanel();
      } catch (error) {
        showError(
          `演练已启动，但状态刷新失败：${
            error && error.message ? error.message : '未知错误'
          }`,
          true
        );
      }
    }
  }

  async function stopAllLabs() {
    if (state.lab.stopping) return;
    state.lab.stopping = true;
    updateLabView();
    let stopped = false;
    try {
      await apiPost('/api/lab/stop');
      stopped = true;
    } catch (error) {
      showError(
        `停止演练失败：${error && error.message ? error.message : '未知错误'}`,
        true
      );
    } finally {
      state.lab.stopping = false;
      updateLabView();
    }
    if (stopped) {
      try {
        await loadLabStatus();
      } catch (error) {
        showError(
          `演练已停止，但状态刷新失败：${
            error && error.message ? error.message : '未知错误'
          }`,
          true
        );
      }
    }
  }

  async function diagnoseLab() {
    if (state.lab.diagnosing) return;
    state.lab.diagnosing = true;
    state.lab.diagnoseError = '';
    updateLabView();
    renderDiagnosePanel();
    try {
      const data = await apiPost('/api/diagnose');
      if (!data || typeof data !== 'object') {
        throw new Error('响应格式异常');
      }
      state.lab.diagnose = {
        ts: asNumber(data.ts, Date.now()),
        findings: normalizeFindings(data.findings),
      };
    } catch (error) {
      state.lab.diagnoseError =
        error && error.message ? error.message : '诊断失败';
      showError(`一键诊断失败：${state.lab.diagnoseError}`, true);
    } finally {
      state.lab.diagnosing = false;
      updateLabView();
      renderDiagnosePanel();
    }
  }

  function renderFindings(host, findings) {
    clearNode(host);
    if (!findings.length) {
      host.appendChild(
        el('div', { class: 'findings-empty' }, '✔ 未发现明显异常。')
      );
      return;
    }
    const list = el('div', { class: 'findings-list' });
    for (const finding of findings) {
      const tone = findingLevel(finding.level);
      const content = el(
        'div',
        { class: 'finding-content' },
        el(
          'div',
          { class: 'finding-title-row' },
          el('span', {
            class: 'finding-badge',
            text: finding.level || 'INFO',
          }),
          el('span', { class: 'finding-title', text: finding.title })
        ),
        finding.detail
          ? el('div', { class: 'finding-detail', text: finding.detail })
          : null
      );
      if (finding.evidence.length) {
        const evidence = el('div', { class: 'evidence' });
        for (const item of finding.evidence) {
          evidence.appendChild(el('span', { class: 'evidence-tag', text: item }));
        }
        content.appendChild(evidence);
      }
      list.appendChild(
        el('div', { class: `finding finding-${tone}` }, content)
      );
    }
    host.appendChild(list);
  }

  function nextStepsNode() {
    const commands = [
      ['jstack -l <pid> > thread.txt', '保存线程快照'],
      ['jcmd <pid> GC.heap_info', '确认堆与 GC'],
      ['arthas dashboard', '实时观察线程/内存'],
      ['jstat -gcutil <pid> 1000', '跟踪 GC 变化'],
    ];
    const list = el('div', { class: 'next-steps-list' });
    for (const [command, note] of commands) {
      list.appendChild(
        el(
          'span',
          { class: 'next-step' },
          el('code', { text: command }),
          el('span', { text: note })
        )
      );
    }
    return el(
      'div',
      { class: 'next-steps' },
      el('div', { class: 'next-steps-title', text: '下一步怎么做' }),
      list
    );
  }

  function renderDiagnosePanel() {
    if (state.route !== 'lab') return;
    const host = byId('diagnoseBody');
    if (!host) return;
    clearNode(host);

    if (state.lab.diagnosing) {
      host.appendChild(createLoading('正在采集线程、锁与内存指标…'));
      return;
    }

    if (state.lab.diagnoseError) {
      host.appendChild(
        el(
          'div',
          { class: 'section-gap' },
          createInlineError(`一键诊断失败：${state.lab.diagnoseError}`, () => {
            void diagnoseLab();
          })
        )
      );
      return;
    }

    if (!state.lab.diagnose) {
      host.appendChild(
        el(
          'div',
          { class: 'diagnose-placeholder' },
          el('span', { class: 'pulse-ring', 'aria-hidden': 'true' }),
          '启动演练或制造异常后，点击右上角「一键诊断」，将线程、锁、堆与线程池信号汇总为发现列表。'
        )
      );
      return;
    }

    const findings = state.lab.diagnose.findings;
    host.appendChild(
      el(
        'div',
        { class: 'spread', style: { marginBottom: '9px' } },
        el(
          'div',
          { class: 'finding-title' },
          `共 ${fmtInt(findings.length)} 条发现`
        ),
        el('span', {
          class: 'muted tiny mono',
          text: fmtTime(state.lab.diagnose.ts),
        })
      )
    );
    const findingsHost = el('div');
    renderFindings(findingsHost, findings);
    host.appendChild(findingsHost);
    host.appendChild(nextStepsNode());
  }

  /* =========================================================
   * jstack 分析
   * ======================================================= */

  function normalizeDumpThread(raw) {
    if (!raw || typeof raw !== 'object') return null;
    return {
      name: asString(raw.name) || '(unnamed)',
      tid: asString(raw.tid),
      nid: asString(raw.nid),
      state: asString(raw.state).toUpperCase() || 'UNKNOWN',
      stack: asArray(raw.stack).map(asString),
    };
  }

  function normalizeDumpResult(data) {
    if (!data || typeof data !== 'object') {
      throw new Error('响应格式异常');
    }
    const threads = asArray(data.threads)
      .map(normalizeDumpThread)
      .filter(Boolean);
    const deadlocks = asArray(data.deadlocks)
      .map((group) =>
        asArray(group)
          .filter((item) => item && typeof item === 'object')
          .map((item) => ({
            name: asString(item.name) || '(unnamed)',
            holds: asString(item.holds),
            wants: asString(item.wants),
          }))
      )
      .filter((group) => group.length);
    return {
      total: Math.max(0, asNumber(data.total, threads.length)),
      states: normalizeStates(data.states),
      threads,
      deadlocks,
      findings: normalizeFindings(data.findings),
    };
  }

  function renderDumpShell() {
    const analyzeButton = el('button', {
      class: 'btn btn-primary btn-sm',
      type: 'button',
      id: 'dumpAnalyzeBtn',
      text: '分析',
      onclick: () => {
        void analyzeDump();
      },
    });
    const sampleButton = el('button', {
      class: 'btn btn-sm',
      type: 'button',
      id: 'dumpSampleBtn',
      text: '加载示例',
      onclick: () => {
        loadDumpSample();
      },
    });
    const clearButton = el('button', {
      class: 'btn btn-sm btn-ghost',
      type: 'button',
      id: 'dumpClearBtn',
      text: '清空',
      onclick: () => {
        clearDump();
      },
    });

    const textarea = el('textarea', {
      class: 'dump-textarea',
      id: 'dumpInput',
      spellcheck: 'false',
      placeholder:
        '在此粘贴 jstack -l <pid> 的完整输出…\n\n提示：在服务器执行 jstack -l <pid> > dump.txt，然后把文件内容粘贴到这里。',
      oninput: (event) => {
        state.dump.raw = event.target.value;
      },
    });
    textarea.value = state.dump.raw;

    const page = el('div', { class: 'tab-page scrollable dump-page' });
    page.appendChild(
      createPageHead(
        'jstack 分析',
        '粘贴线程栈，自动识别线程状态、阻塞关系、死锁与可疑模式。',
        [el('span', { class: 'badge', text: 'text/plain' })]
      )
    );
    page.appendChild(
      el(
        'section',
        { class: 'panel dump-editor' },
        el(
          'div',
          { class: 'dump-toolbar' },
          el(
            'div',
            { class: 'dump-toolbar-main' },
            analyzeButton,
            sampleButton,
            clearButton
          ),
          el(
            'span',
            { class: 'dump-hint' },
            '服务端解析 · ',
            el('code', { text: 'jstack -l <pid> > dump.txt' })
          )
        ),
        textarea
      )
    );
    page.appendChild(el('div', { class: 'dump-output', id: 'dumpOutput' }));
    dom.view.replaceChildren(page);
    updateDumpAnalyzeButton();
    renderDumpOutput();
  }

  function updateDumpAnalyzeButton() {
    const button = byId('dumpAnalyzeBtn');
    if (!button) return;
    button.disabled = state.dump.analyzing;
    const sampleButton = byId('dumpSampleBtn');
    const clearButton = byId('dumpClearBtn');
    if (sampleButton) sampleButton.disabled = state.dump.analyzing;
    if (clearButton) clearButton.disabled = state.dump.analyzing;
    clearNode(button);
    if (state.dump.analyzing) {
      button.appendChild(el('span', { class: 'spinner', 'aria-hidden': 'true' }));
      button.appendChild(document.createTextNode('分析中…'));
    } else {
      button.textContent = '分析';
    }
  }

  async function analyzeDump() {
    if (state.dump.analyzing) return;
    const textarea = byId('dumpInput');
    const content = textarea ? textarea.value : state.dump.raw;
    state.dump.raw = content;
    if (!content.trim()) {
      showError('请先粘贴 jstack 输出再分析。', true);
      if (textarea) textarea.focus();
      return;
    }

    state.dump.analyzing = true;
    state.dump.error = '';
    updateDumpAnalyzeButton();
    renderDumpOutput();
    try {
      const data = await apiPost(
        '/api/dump/analyze',
        content,
        'text/plain',
        20000
      );
      state.dump.result = normalizeDumpResult(data);
      state.dump.query = '';
      state.dump.expanded.clear();
      state.dump.analyzedAt = Date.now();
    } catch (error) {
      state.dump.result = null;
      state.dump.error =
        error && error.message ? error.message : '分析失败';
      showError(`jstack 分析失败：${state.dump.error}`, true);
    } finally {
      state.dump.analyzing = false;
      updateDumpAnalyzeButton();
      renderDumpOutput();
    }
  }

  function loadDumpSample() {
    if (state.dump.analyzing) return;
    state.dump.raw = SAMPLE_JSTACK;
    state.dump.result = null;
    state.dump.error = '';
    state.dump.query = '';
    state.dump.expanded.clear();
    const textarea = byId('dumpInput');
    if (textarea) {
      textarea.value = SAMPLE_JSTACK;
      textarea.focus();
      textarea.setSelectionRange(0, 0);
      textarea.scrollTop = 0;
    }
    renderDumpOutput();
  }

  function clearDump() {
    if (state.dump.analyzing) return;
    state.dump.raw = '';
    state.dump.result = null;
    state.dump.error = '';
    state.dump.query = '';
    state.dump.expanded.clear();
    const textarea = byId('dumpInput');
    if (textarea) {
      textarea.value = '';
      textarea.scrollTop = 0;
    }
    renderDumpOutput();
  }

  function renderDumpOutput() {
    const host = byId('dumpOutput');
    if (!host) return;
    clearNode(host);

    if (state.dump.analyzing) {
      host.appendChild(createLoading('正在解析线程栈并生成诊断…'));
      return;
    }

    if (state.dump.error) {
      host.appendChild(
        createInlineError(`jstack 分析失败：${state.dump.error}`, () => {
          void analyzeDump();
        })
      );
      return;
    }

    if (!state.dump.result) {
      host.appendChild(
        createEmpty(
          '粘贴 jstack 输出开始分析',
          '在服务器执行下面的命令导出线程栈，然后把完整文本粘贴到上方编辑框。分析会识别线程状态、死锁关系与可疑堆栈。',
          el(
            'div',
            { class: 'empty-cmd' },
            'jstack -l <pid> > dump.txt'
          )
        )
      );
      return;
    }

    const result = state.dump.result;
    const deadlockCount = result.deadlocks.reduce(
      (acc, group) => acc + group.length,
      0
    );
    const resultMeta = el(
      'div',
      { class: 'result-meta' },
      el(
        'div',
        { class: 'result-meta-main' },
        el('span', { class: 'result-total', text: fmtInt(result.total) }),
        el('span', { class: 'result-meta-label', text: '线程' })
      ),
      el('span', { class: 'result-meta-divider', 'aria-hidden': 'true' }),
      el(
        'span',
        { class: 'result-meta-label' },
        `死锁 ${fmtInt(result.deadlocks.length)} 组 / ${fmtInt(deadlockCount)} 个线程`
      ),
      el('span', { class: 'result-meta-divider', 'aria-hidden': 'true' }),
      el(
        'span',
        { class: 'result-meta-label' },
        `发现 ${fmtInt(result.findings.length)} 条`
      ),
      el('span', {
        class: 'result-meta-label mono',
        style: { marginLeft: 'auto' },
        text: fmtTime(state.dump.analyzedAt),
      })
    );
    host.appendChild(resultMeta);

    const stateBlock = el(
      'section',
      { class: 'dump-block' },
      el(
        'div',
        { class: 'dump-block-head' },
        el('div', { class: 'dump-block-title', text: '线程状态分布' }),
        el('span', {
          class: 'dump-footnote',
          text: `${fmtInt(result.states.reduce((acc, item) => acc + item.count, 0))} 个状态样本`,
        })
      ),
      el(
        'div',
        { class: 'dump-block-body' },
        createStateDistribution(result.states, result.total)
      )
    );
    host.appendChild(stateBlock);

    const findingsBlock = el(
      'section',
      { class: 'dump-block' },
      el(
        'div',
        { class: 'dump-block-head' },
        el('div', { class: 'dump-block-title', text: '诊断发现' }),
        el('span', {
          class: 'badge' + (result.findings.length ? '' : ' badge-green'),
          text: result.findings.length ? `${fmtInt(result.findings.length)} 条` : '无异常',
        })
      ),
      el(
        'div',
        { class: 'dump-block-body' },
        (() => {
          const findingsHost = el('div');
          renderFindings(findingsHost, result.findings);
          return findingsHost;
        })()
      )
    );
    host.appendChild(findingsBlock);

    if (result.deadlocks.length) {
      const relations = el('div', { class: 'deadlock-relations' });
      for (const group of result.deadlocks) {
        for (const item of group) {
          relations.appendChild(
            el(
              'div',
              { class: 'deadlock-relation' },
              el('span', { class: 'who', text: item.name }),
              el('span', { class: 'lbl', text: '持有' }),
              el('span', { class: 'lock', text: item.holds || '—' }),
              el('span', { class: 'deadlock-arrow', text: '→' }),
              el('span', { class: 'lbl', text: '等待' }),
              el('span', { class: 'lock', text: item.wants || '—' })
            )
          );
        }
      }
      host.appendChild(
        el(
          'section',
          { class: 'dump-block deadlock-block' },
          el(
            'div',
            { class: 'dump-block-head' },
            el('div', { class: 'dump-block-title', text: '⚠ 死锁关系' }),
            el('span', {
              class: 'badge badge-red',
              text: `${fmtInt(result.deadlocks.length)} 组 / ${fmtInt(deadlockCount)} 个线程`,
            })
          ),
          el('div', { class: 'dump-block-body' }, relations)
        )
      );
    }

    const searchInput = el('input', {
      class: 'search-input',
      id: 'dumpSearch',
      type: 'search',
      value: state.dump.query,
      placeholder: '按线程名称搜索',
      'aria-label': '搜索解析出的线程',
      autocomplete: 'off',
      oninput: (event) => {
        state.dump.query = event.target.value;
        updateDumpThreadTable();
      },
    });

    const threadBlock = el(
      'section',
      { class: 'dump-block' },
      el(
        'div',
        { class: 'dump-block-head' },
        el(
          'div',
          {},
          el('div', { class: 'dump-block-title', text: '线程列表' }),
          el('div', {
            class: 'panel-sub',
            id: 'dumpThreadMeta',
            text: `共 ${fmtInt(result.threads.length)} 个线程`,
          })
        ),
        el(
          'div',
          { class: 'dump-thread-toolbar' },
          el('div', { class: 'search-box' }, searchInput)
        )
      ),
      el('div', {
        class: 'table-wrap',
        id: 'dumpTableWrap',
      })
    );
    host.appendChild(threadBlock);
    updateDumpThreadTable();
  }

  function updateDumpThreadTable() {
    const wrap = byId('dumpTableWrap');
    if (!wrap || !state.dump.result) return;
    clearNode(wrap);

    const result = state.dump.result;
    const query = state.dump.query.trim().toLowerCase();
    const filtered = result.threads.filter(
      (thread) => !query || thread.name.toLowerCase().includes(query)
    );

    const meta = byId('dumpThreadMeta');
    if (meta) {
      meta.textContent = query
        ? `显示 ${fmtInt(filtered.length)} / ${fmtInt(result.threads.length)} 个线程`
        : `共 ${fmtInt(result.threads.length)} 个线程`;
    }

    if (filtered.length === 0) {
      wrap.appendChild(
        el('div', {
          class: 'table-empty',
          text: query ? '没有匹配的线程名称。' : '没有解析到线程。',
        })
      );
      return;
    }

    const tbody = el('tbody');
    for (let index = 0; index < filtered.length; index += 1) {
      const thread = filtered[index];
      const globalIndex = result.threads.indexOf(thread);
      const key = `${globalIndex}:${thread.tid}:${thread.name}`;
      const expanded = state.dump.expanded.has(key);
      const row = el(
        'tr',
        {
          class: `thread-row${expanded ? ' expanded' : ''}`,
          tabindex: '0',
          role: 'button',
          'aria-expanded': expanded ? 'true' : 'false',
          onclick: () => {
            if (state.dump.expanded.has(key)) {
              state.dump.expanded.delete(key);
            } else {
              state.dump.expanded.add(key);
            }
            updateDumpThreadTable();
          },
          onkeydown: (event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              if (state.dump.expanded.has(key)) {
                state.dump.expanded.delete(key);
              } else {
                state.dump.expanded.add(key);
              }
              updateDumpThreadTable();
            }
          },
        },
        el(
          'td',
          { class: 'thread-name-cell' },
          el(
            'div',
            { class: 'thread-name' },
            el('span', { class: 'caret', text: '›', 'aria-hidden': 'true' }),
            el('span', { class: 'truncate', text: thread.name, title: thread.name })
          )
        ),
        el('td', { class: 'num-cell', text: thread.tid || '—' }),
        el('td', { class: 'num-cell', text: thread.nid || '—' }),
        el(
          'td',
          {},
          el('span', {
            class: `state-pill ${stateClass(thread.state)}`,
            text: thread.state,
            title: stateLabel(thread.state),
          })
        )
      );
      tbody.appendChild(row);
      if (expanded) {
        tbody.appendChild(
          el(
            'tr',
            { class: 'stack-row' },
            el(
              'td',
              { colspan: '4' },
              el(
                'div',
                { class: 'stack-block' },
                el('div', {
                  class: 'stack-block-title',
                  text: `STACK · ${thread.name}`,
                }),
                el('pre', {
                  class: 'stack-pre',
                  text: thread.stack.length
                    ? thread.stack.join('\n')
                    : '无堆栈信息',
                })
              )
            )
          )
        );
      }
    }

    wrap.appendChild(
      el(
        'table',
        { class: 'data-table dump-thread-table' },
        el(
          'thead',
          {},
          el(
            'tr',
            {},
            el('th', { text: '线程名称' }),
            el('th', { text: 'TID' }),
            el('th', { text: 'NID' }),
            el('th', { text: '状态' })
          )
        ),
        tbody
      )
    );
  }

  /* =========================================================
   * 初始化
   * ======================================================= */

  function bindChrome() {
    dom.errorClose = byId('errorClose');
    if (dom.errorClose) {
      dom.errorClose.addEventListener('click', hideError);
    }
    window.addEventListener('hashchange', onHashChange);
    document.addEventListener('visibilitychange', onVisibilityChange);
    let resizeTimer = null;
    window.addEventListener('resize', () => {
      if (resizeTimer) window.clearTimeout(resizeTimer);
      resizeTimer = window.setTimeout(() => {
        if (state.route === 'pools') updatePoolsView();
      }, 160);
    });
  }

  function init() {
    dom.view = byId('view');
    dom.errorBar = byId('errorBar');
    dom.errorText = byId('errorText');
    dom.errorClose = byId('errorClose');
    dom.jvmCpu = byId('jvmCpu');
    dom.jvmHeap = byId('jvmHeap');
    dom.jvmThreads = byId('jvmThreads');
    dom.jvmCapsule = byId('jvmCapsule');
    if (!dom.view) return;

    bindChrome();

    let route = parseRoute();
    if (!route) {
      route = 'demos';
      window.history.replaceState(null, '', '#/demos');
    }
    enterRoute(route);
    startGlobalPoller();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init, { once: true });
  } else {
    init();
  }
})();
