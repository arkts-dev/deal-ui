package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;

public final class UiPortableIdleTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ui-portable-idle-");
        Files.writeString(root.resolve("app.deal"), "export class State {n: number;}export class Go {}export class Done {}export function initialState(): State {return {n: 0};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {n: 1};}\n// @ui-effect\nexport async function work(state: State, action: Go): Promise<Done> {return {};}\n// @ui-update\nexport function done(state: State, action: Done): State {return {n: 10};}");
        Files.writeString(root.resolve("pack.dealui-pack"), "export class Props {click: Action;}export component Button(props: Props): View {event click; capability \"renderer.test.button\";}");
        Files.writeString(root.resolve("view.dealui"), "import * as app from \"./app\";import * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Button(click: action app.Go {})}");
        var compiler = new UiCompiler(Path.of(System.getProperty("deal.home")), Path.of(".").toAbsolutePath());
        var result = compiler.compile(root.resolve("view.dealui"), root.resolve("output"), UiCompiler.Target.PORTABLE);
        compiler.build(result, Path.of(UiPortableBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        try (var loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiPortableIdleTest.class.getClassLoader())) {
            var bridge = (UiPortableBridge)Class.forName(result.bridgeClass(), true, loader).getConstructor().newInstance();
            var renderer = new PortableUiProgramRuntime.Renderer() {
                public void show(UiPortableBridge.Node tree) {}
                public void apply(java.util.List<UiPortableBridge.Patch> patches, UiPortableBridge.Node tree) {}
            };
            try (var runtime = new PortableUiProgramRuntime(bridge, renderer)) {
                for (int i = 0; i < 2000; i++) {
                    runtime.dispatch(runtime.tree().props().get("click").actionSlot(), null);
                    runtime.awaitIdle();
                    if (!runtime.stateSnapshot().get("n").equals(10.0)) throw new AssertionError("awaitIdle returned before completion at " + i);
                }
            }
            // Scheduling rejection must not leave a phantom outstanding effect.
            var rejected = new java.util.concurrent.ScheduledThreadPoolExecutor(1); rejected.shutdown();
            try (var runtime = new PortableUiProgramRuntime(bridge, renderer, java.util.concurrent.Executors.newSingleThreadExecutor(), rejected)) {
                runtime.dispatch(runtime.tree().props().get("click").actionSlot(), null);
                var failed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var waiter = new Thread(() -> { try { runtime.awaitIdle(); failed.set(new AssertionError("Missing scheduling failure")); }
                    catch (java.util.concurrent.RejectedExecutionException expected) { /* balanced failure */ }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
                waiter.start(); waiter.join(5000);
                if (waiter.isAlive()) { waiter.interrupt(); throw new AssertionError("Rejected scheduling left awaitIdle blocked"); }
                if (failed.get() != null) throw new AssertionError(failed.get());
            }
        }
        System.out.println("UiPortableIdleTest: 2000 generated effect/completion drains and rejected scheduling balance idle");
    }
}
