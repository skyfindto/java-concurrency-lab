package concurrency.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import concurrency.core.Demo;
import concurrency.core.DemoRegistry;
import concurrency.core.DemoRunner;
import concurrency.core.Json;
import concurrency.core.Level;
import concurrency.core.PoolMonitor;
import concurrency.observe.Diagnostics;
import concurrency.observe.FaultLab;
import concurrency.observe.SystemMetrics;
import concurrency.observe.ThreadDumpAnalyzer;
import concurrency.observe.ThreadInspector;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 可视化控制台：一个基于 JDK 内置 {@code com.sun.net.httpserver} 的 HTTP 服务。
 *
 * <p>零第三方依赖（不需要 Spring Boot、不需要 Tomcat），
 * 页面本身也在 {@code src/resources/web} 下，克隆下来就能跑。
 *
 * <p>提供 5 组 API：
 * <ul>
 *   <li>用例：列表、运行、轮询日志、取消；</li>
 *   <li>观测：JVM 指标、线程快照、死锁；</li>
 *   <li>线程池：{@link PoolMonitor} 注册的池子实时指标；</li>
 *   <li>排障：一键诊断、故障演练场；</li>
 *   <li>jstack 文本分析。</li>
 * </ul>
 */
public final class WebServer {

    private static DemoRunner runner;
    private static HttpServer server;
    private static int actualPort;

    private WebServer() {
    }

    public static void start(int port, boolean openBrowser) throws IOException {
        runner = new DemoRunner();

        HttpServer created = null;
        int candidate = port > 0 ? port : 8080;
        BindException lastError = null;
        for (int i = 0; i < 20 && created == null; i++) {
            try {
                created = HttpServer.create(new InetSocketAddress("127.0.0.1", candidate), 0);
                actualPort = candidate;
            } catch (BindException e) {
                lastError = e;
                System.out.printf("端口 %d 被占用，尝试 %d%n", candidate, candidate + 1);
                candidate++;
            }
        }
        if (created == null) {
            throw new IOException("找不到可用端口", lastError);
        }

        server = created;
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", WebServer::handle);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(0);
                runner.close();
            } catch (RuntimeException ignored) {
                // 进程退出阶段，忽略
            }
        }, "console-shutdown"));

        String url = "http://127.0.0.1:" + actualPort;
        System.out.println();
        System.out.println("  Java 并发 · 知识库与观测台");
        System.out.println("  ----------------------------------------");
        System.out.printf("  用例数量 : %d 个%n", DemoRegistry.all().size());
        System.out.printf("  访问地址 : %s%n", url);
        System.out.println("  按 Ctrl+C 停止");
        System.out.println();

        if (openBrowser) {
            openBrowser(url);
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Throwable ignored) {
            // 无桌面环境（服务器/容器）时忽略
        }
    }

    // ------------------------------------------------------------ 路由

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if (path.startsWith("/api/")) {
                routeApi(exchange, path, method);
                return;
            }
            switch (path) {
                case "/", "/index.html" -> staticFile(exchange, "index.html", "text/html; charset=utf-8");
                case "/app.js" -> staticFile(exchange, "app.js", "application/javascript; charset=utf-8");
                case "/style.css" -> staticFile(exchange, "style.css", "text/css; charset=utf-8");
                case "/favicon.ico" -> send(exchange, 204, "text/plain", new byte[0]);
                default -> sendJson(exchange, 404, Map.of("error", "not found: " + path));
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendJson(exchange, 500, Map.of("error", String.valueOf(e)));
            } catch (IOException ignored) {
                // 响应已发出
            }
        } finally {
            exchange.close();
        }
    }

    private static void routeApi(HttpExchange exchange, String path, String method) throws IOException {
        String[] segments = path.split("/");
        if (segments.length < 3) {
            sendJson(exchange, 404, Map.of("error", "unknown api"));
            return;
        }
        switch (segments[2]) {
            case "demos" -> handleDemos(exchange, segments, method);
            case "runs" -> handleRuns(exchange, segments, method);
            case "pools" -> sendJson(exchange, 200, poolsPayload());
            case "system" -> sendJson(exchange, 200, SystemMetrics.snapshot());
            case "threads" -> sendJson(exchange, 200, ThreadInspector.snapshot());
            case "deadlocks" -> sendJson(exchange, 200, ThreadInspector.deadlocks());
            case "diagnose" -> sendJson(exchange, 200, Diagnostics.diagnose());
            case "lab" -> handleLab(exchange, segments, method);
            case "dump" -> handleDump(exchange, segments, method);
            case "health" -> sendJson(exchange, 200, Map.of(
                    "ok", true,
                    "demos", DemoRegistry.all().size(),
                    "uptimeMs", System.currentTimeMillis()));
            default -> sendJson(exchange, 404, Map.of("error", "unknown api: " + path));
        }
    }

    private static void handleDemos(HttpExchange exchange, String[] segments, String method) throws IOException {
        if (segments.length == 3 && "GET".equals(method)) {
            sendJson(exchange, 200, demosPayload());
            return;
        }
        if (segments.length == 5 && "run".equals(segments[4]) && "POST".equals(method)) {
            String id = URLDecoder.decode(segments[3], StandardCharsets.UTF_8);
            Demo demo = DemoRegistry.byId(id);
            if (demo == null) {
                sendJson(exchange, 404, Map.of("error", "用例不存在: " + id));
                return;
            }
            DemoRunner.Run run = runner.start(demo);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", run.runId());
            payload.put("demoId", demo.id());
            payload.put("timeout", demo.timeoutMillis());
            sendJson(exchange, 200, payload);
            return;
        }
        sendJson(exchange, 404, Map.of("error", "unknown demos api"));
    }

    private static void handleRuns(HttpExchange exchange, String[] segments, String method) throws IOException {
        if (segments.length < 4) {
            sendJson(exchange, 404, Map.of("error", "runId required"));
            return;
        }
        String runId = segments[3];
        DemoRunner.Run run = runner.get(runId);
        if (run == null) {
            sendJson(exchange, 404, Map.of("error", "运行记录不存在或已过期: " + runId));
            return;
        }
        if (segments.length == 5 && "cancel".equals(segments[4]) && "POST".equals(method)) {
            sendJson(exchange, 200, Map.of("cancelled", runner.cancel(runId)));
            return;
        }
        if ("GET".equals(method)) {
            sendJson(exchange, 200, run.snapshot(queryLong(exchange, "from", 0)));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "unknown runs api"));
    }

    private static void handleLab(HttpExchange exchange, String[] segments, String method) throws IOException {
        if (segments.length == 4 && "status".equals(segments[3]) && "GET".equals(method)) {
            sendJson(exchange, 200, FaultLab.status());
            return;
        }
        if (segments.length == 4 && "POST".equals(method)) {
            String name = segments[3];
            if ("stop".equals(name)) {
                sendJson(exchange, 200, FaultLab.stopAll());
            } else {
                sendJson(exchange, 200, FaultLab.start(name));
            }
            return;
        }
        sendJson(exchange, 404, Map.of("error", "unknown lab api"));
    }

    private static void handleDump(HttpExchange exchange, String[] segments, String method) throws IOException {
        if (segments.length == 4 && "analyze".equals(segments[3]) && "POST".equals(method)) {
            String body = readBody(exchange);
            if (body.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "请求体为空，请粘贴 jstack 输出"));
                return;
            }
            sendJson(exchange, 200, ThreadDumpAnalyzer.analyze(body));
            return;
        }
        sendJson(exchange, 404, Map.of("error", "unknown dump api"));
    }

    // ------------------------------------------------------------ payload

    private static Map<String, Object> demosPayload() {
        List<Map<String, Object>> levels = new ArrayList<>();
        for (Level level : Level.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", level.code());
            item.put("cn", level.cn());
            item.put("subtitle", level.subtitle());
            item.put("count", DemoRegistry.byLevel(level).size());
            levels.add(item);
        }

        List<Map<String, Object>> demos = new ArrayList<>();
        for (Demo demo : DemoRegistry.all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", demo.id());
            item.put("level", demo.level().code());
            item.put("levelCn", demo.level().cn());
            item.put("title", demo.title());
            item.put("summary", demo.summary());
            item.put("knowledge", demo.knowledge());
            item.put("bestPractice", demo.bestPractice());
            item.put("timeout", demo.timeoutMillis());
            demos.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("levels", levels);
        payload.put("demos", demos);
        payload.put("total", demos.size());
        return payload;
    }

    private static Map<String, Object> poolsPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", System.currentTimeMillis());
        payload.put("pools", PoolMonitor.snapshots());
        return payload;
    }

    // ------------------------------------------------------------ IO 工具

    private static void staticFile(HttpExchange exchange, String name, String contentType) throws IOException {
        byte[] bytes = loadResource(name);
        if (bytes == null) {
            String fallback = """
                    <!doctype html><meta charset="utf-8">
                    <h1>前端资源缺失</h1>
                    <p>请确认 <code>src/resources/web/%s</code> 存在，并且 <code>src/resources</code>
                    被标记为 Resources Root（testCase.iml 里 type="java-resource"）。</p>
                    """.formatted(name);
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
            contentType = "text/html; charset=utf-8";
        }
        send(exchange, 200, contentType, bytes);
    }

    private static byte[] loadResource(String name) throws IOException {
        ClassLoader loader = WebServer.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream("web/" + name)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        // 兜底：直接在源码目录里找（javac 手动编译时没有资源输出）
        Path fallback = Path.of("src", "resources", "web", name);
        if (Files.isRegularFile(fallback)) {
            return Files.readAllBytes(fallback);
        }
        return null;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long queryLong(HttpExchange exchange, String name, long fallback) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && pair.substring(0, idx).equals(name)) {
                try {
                    return Long.parseLong(pair.substring(idx + 1));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 供 CLI 使用：返回实际监听端口。 */
    public static int port() {
        return actualPort;
    }
}
