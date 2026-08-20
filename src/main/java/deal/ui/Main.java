package deal.ui;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args);
        } catch (UiDiagnostic diagnostic) {
            System.err.println(diagnostic.format());
            exit = 1;
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause instanceof java.lang.reflect.InvocationTargetException invocation
                    && invocation.getCause() != null) {
                cause = invocation.getCause();
            }
            System.err.println("deal-ui: " + (cause.getMessage() != null
                ? cause.getMessage() : cause.getClass().getName()));
            cause.printStackTrace(System.err);
            exit = 1;
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return args.length == 0 ? 1 : 0;
        }
        String command = args[0];
        Path source = null;
        Path output = Path.of("build/ui");
        Path fsRoot = defaultFsRoot();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--output requires a path");
                    }
                    output = Path.of(args[i]);
                }
                case "--fs-root" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--fs-root requires a path");
                    }
                    fsRoot = Path.of(args[i]);
                }
                default -> {
                    if (args[i].startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                    }
                    if (source != null) {
                        throw new IllegalArgumentException("Only one UI source file may be specified");
                    }
                    source = Path.of(args[i]);
                }
            }
        }
        if (source == null) {
            throw new IllegalArgumentException("Missing UI source file");
        }
        UiCompiler compiler = new UiCompiler(fsRoot);
        UiCompiler.Result result = compiler.compile(source, output);
        switch (command) {
            case "check" -> System.out.println("UI validation successful: " + result.sourceFile());
            case "dump-ir" -> System.out.print(java.nio.file.Files.readString(result.uiIr()));
            case "build" -> {
                compiler.build(result, runtimeClasses());
                System.out.println("UI build successful: " + result.outputDirectory().resolve("classes"));
            }
            case "run" -> {
                compiler.build(result, runtimeClasses());
                runApplication(result);
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
        return 0;
    }

    private static void runApplication(UiCompiler.Result result) throws Exception {
        Class<?> application = loadApplication(result);
        application.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }

    private static Class<?> loadApplication(UiCompiler.Result result) throws Exception {
        URL[] urls = {
            result.outputDirectory().resolve("classes").toUri().toURL(),
            runtimeClasses().toUri().toURL()
        };
        URLClassLoader loader = new URLClassLoader(urls, Main.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        return Class.forName(result.uiClass(), true, loader);
    }

    private static Path runtimeClasses() throws Exception {
        return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Path defaultFsRoot() {
        String configured = System.getenv("DEAL_FS_ROOT");
        return configured == null || configured.isBlank()
            ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(configured);
    }

    private static void usage() {
        System.err.println("Usage: deal-ui <check|dump-ir|build|run> <source.deal> [--output <dir>] [--fs-root <path>]");
    }
}
