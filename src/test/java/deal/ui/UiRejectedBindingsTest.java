package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;

public final class UiRejectedBindingsTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ui-rejected-bindings-");
        Files.writeString(root.resolve("app.deal"), "export class State {n: number;}export class Go {n: number;}export function initialState(): State {return {n: 0};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {n: action.n + 1};}");
        Files.writeString(root.resolve("pack.dealui-pack"), "export class Props {click: Action;}export component Button(props: Props): View {event click; capability \"renderer.test.button\";}");
        Files.writeString(root.resolve("view.dealui"), "import * as app from \"./app\";import * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Button(click: action app.Go {n: state.n})}");
        var compiler = new UiCompiler(Path.of(System.getProperty("deal.home")), Path.of(".").toAbsolutePath());
        var result = compiler.compile(root.resolve("view.dealui"), root.resolve("output"), UiCompiler.Target.PORTABLE);
        compiler.build(result, Path.of(UiPortableBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        try (var loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiRejectedBindingsTest.class.getClassLoader())) {
            var bridge = (UiPortableBridge)Class.forName(result.bridgeClass(), true, loader).getConstructor().newInstance();
            class Renderer implements PortableUiProgramRuntime.Renderer {
                UiPortableBridge.Node tree; boolean reject = true;
                public void show(UiPortableBridge.Node value) { tree = value; }
                public void apply(java.util.List<UiPortableBridge.Patch> patches, UiPortableBridge.Node value) { if (reject) throw new IllegalStateException("injected rejection"); tree = value; }
            }
            var renderer = new Renderer();
            try (var runtime = new PortableUiProgramRuntime(bridge, renderer)) {
                var old = runtime.tree(); int handle = old.props().get("click").actionSlot();
                runtime.dispatch(handle, null);
                try { runtime.awaitIdle(); throw new AssertionError("Expected renderer rejection"); } catch (IllegalStateException expected) { if (!expected.getMessage().equals("injected rejection")) throw expected; }
                if (runtime.tree() != old || !runtime.stateSnapshot().get("n").equals(0.0)) throw new AssertionError("Rejected state/tree published");
                renderer.reject = false;
                // The retained action must still construct and execute after rejection.
                var retry = bridge.transition(bridge.initialState(), old, bridge.initialStore(), bridge.action(handle, null));
                if (!bridge.stateSnapshot(retry.state()).get("n").equals(1.0)) throw new AssertionError("Retained capture lost");
                bridge.acceptTransition();
                try { bridge.action(handle, null); throw new AssertionError("Old handle survived accepted replacement"); } catch (IllegalArgumentException expected) { /* stale */ }
            }
        }
        System.out.println("UiRejectedBindingsTest: renderer rejection preserves captures; accepted replacement retires them");
    }
}
