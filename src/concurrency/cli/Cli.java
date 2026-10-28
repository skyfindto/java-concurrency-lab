package concurrency.cli;

import concurrency.core.Demo;
import concurrency.core.DemoRegistry;
import concurrency.core.DemoRunner;
import concurrency.core.Level;
import concurrency.core.Log;

import java.util.List;
import java.util.Locale;

/**
 * 命令行入口：不启动页面也能把用例跑一遍。
 *
 * <pre>
 *   java -cp out/production/testCase Main --list
 *   java -cp out/production/testCase Main --run basic-07
 *   java -cp out/production/testCase Main --level basic
 *   java -cp out/production/testCase Main --web 8080
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "--web" : args[0].toLowerCase(Locale.ROOT);
        switch (command) {
            case "--list", "-l" -> list();
            case "--run", "-r" -> {
                String id = args.length > 1 ? args[1] : "";
                runOne(id);
            }
            case "--level" -> {
                String level = args.length > 1 ? args[1] : "basic";
                runLevel(level);
            }
            case "--web", "-w" -> {
                int port = args.length > 1 && !args[1].startsWith("-") ? Integer.parseInt(args[1]) : 8080;
                boolean openBrowser = !java.util.Arrays.asList(args).contains("--no-browser");
                concurrency.web.WebServer.start(port, openBrowser);
            }
            case "--help", "-h" -> help();
            default -> {
                System.out.println("未知参数：" + command);
                help();
            }
        }
    }

    private static void help() {
        System.out.println("""
                Java 并发知识库 · 命令行
                  --web [port]        启动可视化控制台（默认 8080）
                  --list              列出全部用例
                  --run <id前缀>      运行单个用例，如 --run basic-07
                  --level <层级>      运行某个层级：basic|intermediate|advanced|pattern|observe
                """);
    }

    private static void list() {
        Level current = null;
        for (Demo demo : DemoRegistry.all()) {
            if (demo.level() != current) {
                current = demo.level();
                System.out.printf("%n== %s · %s ==%n", current.cn(), current.subtitle());
            }
            System.out.printf("  %-34s %s%n", demo.id(), demo.title());
        }
        System.out.printf("%n共 %d 个用例%n", DemoRegistry.all().size());
    }

    private static void runOne(String idPrefix) {
        Demo demo = DemoRegistry.byId(idPrefix);
        if (demo == null) {
            System.err.println("找不到用例：" + idPrefix + "，可用 --list 查看");
            return;
        }
        System.out.printf("%n===== %s | %s | %s =====%n%n", demo.id(), demo.level().cn(), demo.title());
        execute(demo);
    }

    private static void runLevel(String levelName) {
        Level level = switch (levelName.toLowerCase(Locale.ROOT)) {
            case "basic", "初级" -> Level.BASIC;
            case "intermediate", "juc", "中级" -> Level.INTERMEDIATE;
            case "advanced", "高级" -> Level.ADVANCED;
            case "pattern", "设计模式" -> Level.PATTERN;
            case "observe", "进阶" -> Level.OBSERVE;
            default -> null;
        };
        if (level == null) {
            System.err.println("未知层级：" + levelName);
            return;
        }
        List<Demo> demos = DemoRegistry.byLevel(level);
        int failed = 0;
        for (Demo demo : demos) {
            System.out.printf("%n===== %s | %s =====%n", demo.id(), demo.title());
            if (!execute(demo)) {
                failed++;
            }
        }
        System.out.printf("%n层级 %s 执行完毕：%d 个用例，失败 %d 个%n", level.cn(), demos.size(), failed);
    }

    /** 跑一个用例并把日志按行回放；返回是否成功。 */
    private static boolean execute(Demo demo) {
        try (DemoRunner runner = new DemoRunner()) {
            DemoRunner.Run run = runner.start(demo);
            long seq = 0;
            boolean printedFooter = false;
            while (true) {
                for (Log.Line line : run.log().since(seq)) {
                    System.out.println(line.render());
                    seq = line.seq();
                }
                if (run.status() != DemoRunner.Status.RUNNING) {
                    for (Log.Line line : run.log().since(seq)) {
                        System.out.println(line.render());
                    }
                    printedFooter = true;
                    break;
                }
                if (!printedFooter) {
                    Thread.sleep(15);
                }
            }
            System.out.printf("→ %s（断言 %d 通过 / %d 失败）%n",
                    run.status(), run.okCount(), run.failCount());
            return run.status() == DemoRunner.Status.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
