package concurrency.core;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 用例注册表：启动时扫描 classpath 下的 {@code concurrency} 包，
 * 把所有 {@link Demo} 实现自动装进来。
 *
 * <p>用反射扫描而不是手写 {@code register(new Xxx())}，是为了「加一个文件就是一个新用例」，
 * 不需要再改任何注册代码。
 */
public final class DemoRegistry {

    private static final List<Demo> DEMOS = loadAll();
    private static final Map<String, Demo> BY_ID = new LinkedHashMap<>();

    static {
        for (Demo demo : DEMOS) {
            BY_ID.put(demo.id(), demo);
        }
    }

    private DemoRegistry() {
    }

    /** 全部用例，已按「层级 → id」排序。 */
    public static List<Demo> all() {
        return DEMOS;
    }

    public static Demo byId(String id) {
        Demo demo = BY_ID.get(id);
        if (demo != null) {
            return demo;
        }
        // 允许模糊匹配：basic-01 能命中 basic-01-thread-create
        for (Demo candidate : DEMOS) {
            if (candidate.id().startsWith(id)) {
                return candidate;
            }
        }
        return null;
    }

    public static List<Demo> byLevel(Level level) {
        List<Demo> list = new ArrayList<>();
        for (Demo demo : DEMOS) {
            if (demo.level() == level) {
                list.add(demo);
            }
        }
        return list;
    }

    private static List<Demo> loadAll() {
        List<Demo> found = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = DemoRegistry.class.getClassLoader();
        }
        try {
            Enumeration<URL> roots = cl.getResources("concurrency");
            while (roots.hasMoreElements()) {
                URL url = roots.nextElement();
                if (!"file".equals(url.getProtocol())) {
                    continue;
                }
                Path root = Path.of(url.toURI());
                try (Stream<Path> files = Files.walk(root)) {
                    List<Path> classFiles = files
                            .filter(p -> p.toString().endsWith(".class"))
                            .filter(p -> !p.getFileName().toString().contains("$")) // 跳过内部类
                            .toList();
                    for (Path file : classFiles) {
                        String relative = root.relativize(file).toString()
                                .replace('\\', '.').replace('/', '.');
                        String className = "concurrency." + relative.substring(0, relative.length() - 6);
                        tryLoad(found, className, cl);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[DemoRegistry] 扫描用例失败：" + e);
        }

        found.sort(Comparator.comparingInt((Demo d) -> d.level().order()).thenComparing(Demo::id));

        // 去重：id 冲突时保留第一个并告警，避免页面出现两个同名用例
        Map<String, Demo> unique = new LinkedHashMap<>();
        for (Demo demo : found) {
            Demo previous = unique.putIfAbsent(demo.id(), demo);
            if (previous != null) {
                System.err.println("[DemoRegistry] 用例 id 重复：" + demo.id() + "，已忽略 " + demo.getClass().getName());
            }
        }
        if (unique.isEmpty()) {
            System.err.println("[DemoRegistry] 没有扫描到任何用例，请确认 classpath 包含 out/production/testCase");
        }
        return List.copyOf(unique.values());
    }

    private static void tryLoad(List<Demo> found, String className, ClassLoader cl) {
        try {
            Class<?> type = Class.forName(className, false, cl);
            if (!Demo.class.isAssignableFrom(type) || type.isInterface()
                    || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                return;
            }
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            found.add((Demo) ctor.newInstance());
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // 不是用例类
        } catch (Throwable t) {
            System.err.println("[DemoRegistry] 加载用例失败：" + className + " -> " + t);
        }
    }

    /** 用于页面统计：每个层级各有多少用例。 */
    public static Map<String, Integer> countByLevel() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Level level : Level.values()) {
            counts.put(level.code(), byLevel(level).size());
        }
        return counts;
    }
}
