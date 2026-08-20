package deal.ui;

import java.net.URLClassLoader;
import java.nio.file.Path;

public final class MuseumVisibleSmoke {
    private MuseumVisibleSmoke() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path output = root.resolve("build/visible-smoke");
        UiCompiler compiler = new UiCompiler(fsRoot());
        UiCompiler.Result result = compiler.compile(root.resolve("examples/museum/museum.deal"), output);
        compiler.build(result, root.resolve("build/classes"));
        try (var loader = new URLClassLoader(new java.net.URL[]{output.resolve("classes").toUri().toURL()},
                MuseumVisibleSmoke.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> generated = Class.forName(result.uiClass(), true, loader);
            UiModel.CheckedProgram program = (UiModel.CheckedProgram) generated.getMethod("program").invoke(null);
            try (UiProgramRuntime runtime = new UiProgramRuntime(program, result.moduleClass())) {
                runtime.show();
                Path evidence = output.resolve("visible-evidence");
                runtime.capture(evidence.resolve("museum-collapsed.png"));
                runtime.clickButton("Toggle details");
                if (!Boolean.TRUE.equals(runtime.stateSnapshot().get("expanded"))) {
                    throw new IllegalStateException("DEAL update did not commit expanded state");
                }
                runtime.capture(evidence.resolve("museum-expanded.png"));
            }
        }
        System.out.println("Visible museum scenario: collapsed -> click -> expanded");
    }

    private static Path fsRoot() {
        String configured = System.getenv("DEAL_FS_ROOT");
        return configured == null || configured.isBlank()
            ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(configured);
    }
}
