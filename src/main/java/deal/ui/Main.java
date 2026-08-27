package deal.ui;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            int exit = run(args);
            if (exit != 0) System.exit(exit);
        } catch (UiDiagnostic diagnostic) {
            System.err.println(diagnostic.format());
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("deal-ui: " + failure.getMessage());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help")) { usage(); return args.length == 0 ? 1 : 0; }
        String command = args[0];
        Path source = null;
        Path fs = defaultFsRoot();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--fs-root")) { if (++i == args.length) throw new IllegalArgumentException("--fs-root requires path"); fs = Path.of(args[i]); }
            else if (args[i].startsWith("-")) throw new IllegalArgumentException("Unknown option: " + args[i]);
            else if (source == null) source = Path.of(args[i]);
            else throw new IllegalArgumentException("Only one .dealui source is accepted");
        }
        if (source == null) throw new IllegalArgumentException("Missing .dealui source");
        Path output = Files.createTempDirectory("deal-ui-").resolve("application");
        UiCompiler compiler = new UiCompiler(fs);
        UiCompiler.Result result = compiler.compile(source, output);
        switch (command) {
            case "check" -> System.out.println("UI validation successful: " + result.viewSource());
            case "dump-ir" -> System.out.print(Files.readString(result.uiIr()));
            case "build" -> { compiler.build(result, runtimeClasses()); System.out.println("UI build successful: " + result.outputDirectory().resolve("classes")); }
            case "run" -> { compiler.build(result, runtimeClasses()); run(result); }
            default -> throw new IllegalArgumentException("Unknown command: " + command);
        }
        return 0;
    }

    static UiBridge bridge(UiCompiler.Result result, ClassLoader loader) throws Exception {
        return (UiBridge) Class.forName(result.bridgeClass(), true, loader).getConstructor().newInstance();
    }

    private static void run(UiCompiler.Result result) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, Main.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Class.forName(result.uiClass(), true, loader).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
    }
    private static Path runtimeClasses() throws Exception { return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
    private static Path defaultFsRoot() { String configured = System.getenv("DEAL_FS_ROOT"); return configured == null || configured.isBlank() ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(configured); }
    private static void usage() { System.err.println("Usage: deal-ui <check|dump-ir|build|run> <root.dealui> [--fs-root PATH]"); }
}
