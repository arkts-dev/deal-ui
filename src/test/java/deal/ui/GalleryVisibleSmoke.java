package deal.ui;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GalleryVisibleSmoke {
    private GalleryVisibleSmoke() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path outputs = root.resolve("build/visible-outputs");
        Files.createDirectories(outputs);
        UiCompiler compiler = new UiCompiler(fsRoot());
        UiCompiler.Result result = compiler.compile(root.resolve("examples/museum/gallery.dealui"), outputs.resolve("gallery-" + System.nanoTime()));
        compiler.build(result, root.resolve("build/classes"));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, GalleryVisibleSmoke.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            UiBridge bridge = Main.bridge(result, loader);
            try (UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings())) {
                runtime.show();
                Path evidence = result.outputDirectory().resolve("visible-evidence");
                runtime.renderer().capture(evidence.resolve("gallery-initial.png"));
                runtime.click("Toggle details");
                runtime.renderer().capture(evidence.resolve("gallery-expanded.png"));
                runtime.click("Increment");
                runtime.awaitIdle();
                if (!runtime.stateSnapshot().get("count").equals(1L)) throw new IllegalStateException("Effect completion was not committed");
                runtime.renderer().capture(evidence.resolve("gallery-incremented.png"));
            }
        }
        System.out.println("Visible gallery scenario passed");
    }

    private static Path fsRoot() { String value = System.getenv("DEAL_FS_ROOT"); return value == null ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(value); }
}
