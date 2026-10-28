/**
 * 项目入口。
 *
 * <p>直接运行这个类（IDEA 里点绿色三角）会启动「Java 并发 · 知识库与观测台」，
 * 浏览器打开 {@code http://127.0.0.1:8080} 即可按难度浏览、逐个运行用例。
 *
 * <p>也可以在命令行里跑单个用例：
 * <pre>
 *   java -cp out/production/testCase Main --list
 *   java -cp out/production/testCase Main --run basic-07
 *   java -cp out/production/testCase Main --web 8081
 * </pre>
 *
 * <p>用例类不单独提供 main 方法：它们统一通过 {@code DemoContext} 输出，
 * 由 {@link concurrency.core.DemoRunner} 负责执行与日志采集，
 * 这样才能同时支持「页面点一下」和「命令行跑一遍」两种用法。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        concurrency.cli.Cli.main(args);
    }
}
