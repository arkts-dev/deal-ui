package deal.ui;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UiFrameworkTest {
    private static int passed;
    private UiFrameworkTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path outputs = root.resolve("build/test-outputs");
        recreate(outputs);
        UiCompiler compiler = new UiCompiler(fsRoot());
        UiCompiler.Result result = compiler.compile(root.resolve("examples/museum/gallery.dealui"), outputs.resolve("gallery"));
        compiler.build(result, root.resolve("build/classes"));
        String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
        check(generated.contains("function view_Gallery"), ".dealui lowers to DEAL view functions");
        check(generated.contains("function nextState"), "closed transitions are generated in DEAL");
        check(generated.contains("function plan"), "DEAL reconciliation is compiled into the application");
        check(!Files.readString(root.resolve("src/main/java/deal/ui/UiProgramRuntime.java")).contains("java.lang.reflect"), "runtime has no reflection");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            UiBridge bridge = Main.bridge(result, loader);
            try (UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings())) {
                check(runtime.stateSnapshot().get("title").equals("Gallery"), "DEAL supplies initial state");
                check(texts(runtime.tree()).containsAll(List.of("Gallery", "A focused collection for thoughtful discovery", "FEATURED WORK", "0", "Details are hidden", "The Starry Night")), "DEAL-generated tree evaluates composition and control flow");
                check(!runtime.tree().children().get(0).children().get(0).identity().equals(runtime.tree().children().get(0).children().get(1).identity()), "custom view identities include call sites");
                JComponent component = runtime.renderer().componentForTesting(runtime.tree());
                JComponent firstItem = named(component, "ui.Text", "The Starry Night");
                JTextField input = input(component);
                input.setText("Modern Gallery");
                input.postActionEvent();
                runtime.awaitActions();
                check(runtime.stateSnapshot().get("title").equals("Modern Gallery"), "payload action is constructed by generated DEAL");
                button(runtime.renderer().componentForTesting(runtime.tree()), "Toggle details").doClick();
                runtime.awaitActions();
                check(runtime.stateSnapshot().get("expanded").equals(true), "closed DEAL dispatch commits update");
                JComponent second = runtime.renderer().componentForTesting(runtime.tree());
                check(firstItem != null && firstItem == named(second, "ui.Text", "The Starry Night"), "keyed identity is retained");
                check(named(second, "ui.Text", "Details are visible") != null, "DEAL create operations materialize new subtrees");
                button(second, "Increment").doClick();
                button(second, "Increment").doClick();
                runtime.awaitIdle();
                check(((Long) runtime.stateSnapshot().get("count")) >= 1L, "overlapping post-commit effects return through the DEAL queue");
                check(runtime.renderer().lastApplyOnEdt(), "patches apply on EDT");
                long disposed = runtime.renderer().disposedComponents();
                button(runtime.renderer().componentForTesting(runtime.tree()), "Toggle details").doClick();
                runtime.awaitActions();
                check(runtime.renderer().disposedComponents() > disposed, "lost structural identity disposes retained resources");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        expect("UI2037", source(root).replace("view Header(title: string): View {\n  ui.Text(value: title)\n}", "view Header(title: string): View {}"));
        expect("UI2037", source(root).replace("  ui.Card() {", "  ui.Text(value: state.title)\n  ui.Card() {"));
        expect("UI2037", source(root).replace("view Header(title: string): View {\n  ui.Text(value: title)\n}", "view Header(title: string): View { When(true) { ForEach(state.items, item: app.Item, key: item.id) { ui.Text(value: item.title) } } Else { ui.Text(value: title) } }"));
        expect("UI2012", source(root).replace("ui.Card()", "ui.Unknown()"));
        expect("UI2029", source(root).replace("ui.Text(value: title)", "ui.Text(label: title)"));
        expect("UI2031", source(root).replace("ui.IntText(value: state.count)", "ui.IntText(value: state.title)"));
        expect("UI2015", source(root).replace("key: item.id", "key: item"));
        expect("UI2005", source(root), deal(root).replace("// @ui-update\nexport function toggleDetails", "export function toggleDetails"));
        expectPack("UI2026", pack(root).replace("spacing?: Space;", "spacing: Space = missing.token;"));
        expect("UI2013", source(root).replace("spacing: ui.spaceMd", "spacing: null"));
        typedPayloadContracts(root, outputs, compiler);
        compileSemanticExamples(root, outputs, compiler);
        System.out.println("Passed: " + passed);
    }

    private static void compileSemanticExamples(Path root, Path outputs, UiCompiler compiler) throws Exception {
        for (String name : List.of("checkout", "search-mail", "kanban", "dashboard")) {
            UiCompiler.Result result = compiler.compile(root.resolve("examples").resolve(name).resolve(name + ".dealui"), outputs.resolve(name));
            compiler.build(result, root.resolve("build/classes"));
            String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
            check(generated.contains("class NavigationDecision") && generated.contains("class RequestPolicy") && generated.contains("class WindowPolicy") && generated.contains("class OverlayPolicy"), name + " compiles with identical portable interaction policy");
            exerciseSemanticExample(result, name);
        }
    }

    private static void exerciseSemanticExample(UiCompiler.Result result, String name) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            UiBridge bridge = Main.bridge(result, loader);
            try (UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings())) {
                switch (name) {
                    case "checkout" -> { runtime.dispatch(action(runtime.tree(), "Continue to payment", bridge, null)); runtime.awaitActions(); check(runtime.stateSnapshot().get("route").equals("checkout/payment"), "checkout guard permits clean nested navigation"); }
                    case "search-mail" -> { runtime.dispatch(action(runtime.tree(), "onSubmit", bridge, "policy")); runtime.awaitIdle(); check(runtime.stateSnapshot().get("result").equals("Results for policy"), "search completion respects active generation"); }
                    case "kanban" -> { runtime.dispatch(action(runtime.tree(), "Next page", bridge, null)); runtime.awaitActions(); check(runtime.stateSnapshot().get("pageOffset").equals(1L), "kanban pagination uses shared clamped window policy"); }
                    case "dashboard" -> { runtime.dispatch(action(runtime.tree(), "Open settings", bridge, null)); runtime.awaitActions(); check(runtime.stateSnapshot().get("subscribedScope").equals(1L), "dashboard overlay opens scoped subscription and focus lifecycle"); }
                    default -> throw new IllegalArgumentException(name);
                }
            }
        }
    }

    private static UiBridge.ActionValue action(UiBridge.Node node, String textOrProp, UiBridge bridge, Object payload) {
        UiBridge.Prop direct = node.props().get(textOrProp);
        if (direct != null && direct.actionSlot() >= 0) return bridge.action(direct.actionSlot(), payload);
        UiBridge.Prop text = node.props().get("text");
        if (text != null && text.value().equals(textOrProp)) for (UiBridge.Prop prop : node.props().values()) if (prop.actionSlot() >= 0) return bridge.action(prop.actionSlot(), payload);
        for (UiBridge.Node child : node.children()) { try { return action(child, textOrProp, bridge, payload); } catch (IllegalArgumentException ignored) {} }
        throw new IllegalArgumentException("Action not found: " + textOrProp);
    }

    private static void typedPayloadContracts(Path root, Path outputs, UiCompiler compiler) throws Exception {
        Path fixture = outputs.resolve("typed");
        Files.createDirectories(fixture);
        Path logic = fixture.resolve("typed.deal");
        Path pack = fixture.resolve("typed.dealui-pack");
        Path view = fixture.resolve("typed.dealui");
        Files.writeString(logic, "export class State { text: string = \"\"; count: int = 0; amount: number = 0.0; flag: boolean = false; }\nexport class SetText { value: string = \"\"; }\nexport class SetCount { value: int = 0; }\nexport class SetAmount { value: number = 0.0; }\nexport class SetFlag { value: boolean = false; }\nexport class Clear {}\nexport class Completed {}\nexport function initialState(): State { return {}; }\n// @ui-update\nexport function setText(state: State, action: SetText): State { return { text: action.value, count: state.count, amount: state.amount, flag: state.flag }; }\n// @ui-update\nexport function setCount(state: State, action: SetCount): State { return { text: state.text, count: action.value, amount: state.amount, flag: state.flag }; }\n// @ui-update\nexport function setAmount(state: State, action: SetAmount): State { return { text: state.text, count: state.count, amount: action.value, flag: state.flag }; }\n// @ui-update\nexport function setFlag(state: State, action: SetFlag): State { return { text: state.text, count: state.count, amount: state.amount, flag: action.value }; }\n// @ui-update\nexport function clear(state: State, action: Clear): State { return {}; }\n// @ui-effect\nexport async function complete(state: State, action: Clear): Completed { return {}; }\n// @ui-update\nexport function completed(state: State, action: Completed): State { return state; }\nexport function main(): null { return null; }\n");
        Files.writeString(pack, "export class Props { onText?: Action; onInt?: Action; onNumber?: Action; onBool?: Action; onClear?: Action; }\nexport component Host(props: Props): View { event onText(payload: string); event onInt(payload: int); event onNumber(payload: number); event onBool(payload: boolean); event onClear; capability \"renderer.swing.card\"; }\n");
        Files.writeString(view, "import * as app from \"./typed\";\nimport * as ui from \"./typed.dealui-pack\";\n// @ui-root\nexport view Typed(state: app.State): View { ui.Host(onText: action app.SetText { value: payload }, onInt: action app.SetCount { value: payload }, onNumber: action app.SetAmount { value: payload }, onBool: action app.SetFlag { value: payload }, onClear: action app.Clear {}) }\n");
        UiCompiler.Result result = compiler.compile(view, outputs.resolve("typed-output"));
        compiler.build(result, root.resolve("build/classes"));
        String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
        check(generated.contains("payload: string") && generated.contains("payload: int") && generated.contains("payload: number") && generated.contains("payload: boolean") && generated.contains("function action_4(): UiAction"), "generated action factories preserve event payload contracts");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            UiBridge bridge = Main.bridge(result, loader);
            UiBridge.Node tree = bridge.initial(bridge.initialState(), bridge.initialStore()).tree();
            int textSlot = tree.props().get("onText").actionSlot();
            int intSlot = tree.props().get("onInt").actionSlot();
            int numberSlot = tree.props().get("onNumber").actionSlot();
            int boolSlot = tree.props().get("onBool").actionSlot();
            int clearSlot = tree.props().get("onClear").actionSlot();
            check(bridge.action(textSlot, "text") != null && bridge.action(intSlot, 1L) != null && bridge.action(numberSlot, 1.5) != null && bridge.action(boolSlot, true) != null && bridge.action(clearSlot, null) != null, "generated bridge accepts exact typed payloads");
            expectPayloadFailure(() -> bridge.action(textSlot, 1L), "string");
            expectPayloadFailure(() -> bridge.action(intSlot, "1"), "int");
            expectPayloadFailure(() -> bridge.action(numberSlot, 1L), "number");
            expectPayloadFailure(() -> bridge.action(boolSlot, "true"), "boolean");
            expectPayloadFailure(() -> bridge.action(clearSlot, ""), "no");
        }
    }

    private static void expectPayloadFailure(Runnable operation, String expected) {
        try { operation.run(); throw new AssertionError("Expected " + expected + " payload rejection"); }
        catch (IllegalArgumentException failure) { check(failure.getMessage().contains(expected), expected + " payload rejects coercion"); }
    }

    private static void expect(String code, String view) throws Exception { expect(code, view, deal(Path.of("").toAbsolutePath())); }
    private static void expect(String code, String view, String logic) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path directory = Files.createTempDirectory(root.resolve("build"), "invalid-");
        Files.writeString(directory.resolve("gallery.dealui"), view.replace("./gallery", directory.resolve("gallery").toString()).replace("./platform-ui.dealui-pack", root.resolve("examples/museum/platform-ui.dealui-pack").toString()));
        Files.writeString(directory.resolve("gallery.deal"), logic);
        try {
            UiModel.ViewModule views = UiParser.parseViews(directory.resolve("gallery.dealui"), Files.readString(directory.resolve("gallery.dealui")));
            UiChecker checker = new UiChecker();
            UiModel.DealModule module = checker.parseDeal(directory.resolve("gallery.deal"), logic);
            UiModel.PackModule pack = UiParser.parsePack(root.resolve("examples/museum/platform-ui.dealui-pack"), pack(root));
            checker.check(directory.resolve("gallery.dealui"), views, directory.resolve("gallery.deal"), module, java.util.Map.of(root.resolve("examples/museum/platform-ui.dealui-pack").toString(), pack));
            throw new AssertionError("Expected " + code);
        } catch (UiDiagnostic diagnostic) { check(diagnostic.code().equals(code), code + " is reported"); }
    }
    private static void expectPack(String code, String pack) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        UiModel.ViewModule views = UiParser.parseViews(root.resolve("examples/museum/gallery.dealui"), source(root));
        UiChecker checker = new UiChecker();
        UiModel.DealModule deal = checker.parseDeal(root.resolve("examples/museum/gallery.deal"), deal(root));
        try {
            checker.check(root.resolve("examples/museum/gallery.dealui"), views, root.resolve("examples/museum/gallery.deal"), deal, java.util.Map.of("./platform-ui.dealui-pack", UiParser.parsePack(root.resolve("examples/museum/platform-ui.dealui-pack"), pack)));
            throw new AssertionError("Expected " + code);
        } catch (UiDiagnostic diagnostic) { check(diagnostic.code().equals(code), code + " is reported"); }
    }

    private static String source(Path root) throws Exception { return Files.readString(root.resolve("examples/museum/gallery.dealui")); }
    private static String deal(Path root) throws Exception { return Files.readString(root.resolve("examples/museum/gallery.deal")); }
    private static String pack(Path root) throws Exception { return Files.readString(root.resolve("examples/museum/platform-ui.dealui-pack")); }
    private static List<String> texts(UiBridge.Node node) { List<String> values = new ArrayList<>(); collect(node, values); return values; }
    private static void collect(UiBridge.Node node, List<String> values) { UiBridge.Prop prop = node.props().get("value"); if (prop != null) values.add(String.valueOf(prop.value())); node.children().forEach(child -> collect(child, values)); }
    private static AbstractButton button(Component component, String text) { if (component instanceof AbstractButton value && value.getText().equals(text)) return value; if (component instanceof Container container) for (Component child : container.getComponents()) { AbstractButton found = button(child, text); if (found != null) return found; } return null; }
    private static JTextField input(Component component) { if (component instanceof JTextField value) return value; if (component instanceof Container container) for (Component child : container.getComponents()) { JTextField found = input(child); if (found != null) return found; } return null; }
    private static JComponent named(Component component, String name, String text) { if (component instanceof JLabel label && label.getName().equals(name) && label.getText().equals(text)) return label; if (component instanceof Container container) for (Component child : container.getComponents()) { JComponent found = named(child, name, text); if (found != null) return found; } return null; }
    private static Path fsRoot() { String value = System.getenv("DEAL_FS_ROOT"); return value == null ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(value); }
    private static void recreate(Path path) throws Exception { if (Files.exists(path)) try (var files = Files.walk(path)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } Files.createDirectories(path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
}
