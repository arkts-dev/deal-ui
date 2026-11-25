package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

/** Nullability belongs to a value at each depth, never to an unrelated enclosing array. */
public final class UiNullableElementsTest {
    private static int checks;
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final String HEADER = "import * as app from \"./app.deal\"; import * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view App(state: app.State): View { ";
    private static final String PACK = "export class Props { values: TYPE; onClick?: Action; } export component Data(props: Props): View { event onClick; capability \"renderer.test.data\"; } export class Empty {} export component Column(props: Empty): View { children optional; capability \"renderer.test.column\"; }";
    private UiNullableElementsTest() {}
    public static void main(String[] args) throws Exception {
        roundtrips();
        contracts();
        execution();
        System.out.println("UiNullableElementsTest: " + checks + " checks passed");
    }
    private static void roundtrips() {
        // Exhaust all nullable/readonly layer combinations through rank three, including outer nullability.
        for (int rank = 0; rank <= 3; rank++) for (int bits = 0; bits < (1 << (rank * 2 + 1)); bits++) {
            var expected = new UiModel.TypeRef("number", false, false);
            for (int depth = 0; depth < rank; depth++) expected = expected.withOptional((bits & (1 << (depth * 2))) != 0).arrayOf((bits & (1 << (depth * 2 + 1))) != 0);
            expected = expected.withOptional((bits & (1 << (rank * 2))) != 0);
            String text = expected.sourceType(expected.name());
            var parsed = UiParser.parseViews(Path.of("roundtrip.dealui"), "view V(values: " + text + "): View {}").views().getFirst().parameters().getFirst().type();
            check(parsed.equals(expected), "view roundtrip " + text);
            var imported = new UiChecker().parseDeal(Path.of("roundtrip.deal"), "export class State { values: " + text + "; }").classes().get("State").fields().get("values").type();
            check(imported.equals(expected), "core import roundtrip " + text);
            var pack = UiParser.parsePack(Path.of("roundtrip.pack"), PACK.replace("TYPE", text));
            check(pack.classes().get("Props").fields().getFirst().type().equals(expected), "pack roundtrip " + text);
        }
        var optional = new UiChecker().parseDeal(Path.of("optional.deal"), "export class State { values?: (number | null)[]; }").classes().get("State").fields().get("values");
        check(optional.optional() && !optional.type().optional() && optional.type().elementType().optional(), "optional presence and nullable element remain independent");
        var eventPack = UiParser.parsePack(Path.of("event.pack"), PACK.replace("TYPE", "(number | null)[]").replace("event onClick;", "event onClick(payload: (number | null)[]);"));
        var event = eventPack.components().get("Data").contracts().stream().filter(UiModel.Event.class::isInstance).map(UiModel.Event.class::cast).findFirst().orElseThrow();
        check(!event.payload().optional() && event.payload().elementType().optional(), "event payload retains nullable elements");
    }
    private static UiModel.CheckedProgram checked(String actual, String expected, boolean helper) {
        String logic = "export class State { values: " + actual + "; }";
        String node = helper ? "Helper(values: state.values)" : "ui.Data(values: state.values)";
        String view = HEADER + node + " }" + (helper ? " view Helper(values: " + expected + "): View { ui.Data(values: values) }" : "");
        var checker = new UiChecker();
        return checker.check(Path.of("/candidate/view.dealui"), UiParser.parseViews(Path.of("/candidate/view.dealui"), view), Path.of("/candidate/app.deal"),
                checker.parseDeal(Path.of("/candidate/app.deal"), logic), Map.of("./pack.dealui-pack", UiParser.parsePack(Path.of("/candidate/pack.dealui-pack"), PACK.replace("TYPE", expected))));
    }
    private static void contracts() {
        var types = List.of("number[]", "number[] | null", "(number | null)[]", "(number | null)[] | null", "(number[] | null)[]", "number[][] | null");
        for (String actual : types) for (String expected : types) for (boolean helper : List.of(false, true)) {
            boolean accepted = actual.equals(expected);
            // Only outer nullability may widen for writable arrays; element descriptors remain exact.
            if ((actual + " | null").equals(expected)) accepted = true;
            try { checked(actual, expected, helper); check(accepted, "unexpected acceptance " + actual + " -> " + expected); }
            catch (UiDiagnostic error) {
                check(!accepted && error.code().equals("UI2031"), "unexpected rejection " + actual + " -> " + expected);
                check(error.actual().equals(actual) && error.expected().equals(expected), "diagnostic retains both exact types");
            }
        }
        checked("number[]", "readonly (number | null)[]", false); checks++;
        try { checked("(number | null)[]", "readonly number[]", false); throw new AssertionError("nullable elements narrowed"); }
        catch (UiDiagnostic error) { check(error.code().equals("UI2031"), "readonly does not remove nullability"); }
        String rows = "export class Row { id: number; } export class State { values: (Row | null)[]; }";
        var checker = new UiChecker();
        try {
            checker.check(Path.of("/candidate/view.dealui"), UiParser.parseViews(Path.of("/candidate/view.dealui"), HEADER + "ui.Column() { ForEach(state.values, row: app.Row, key: row.id) { ui.Data(values: row.id) } } }"),
                    Path.of("/candidate/app.deal"), checker.parseDeal(Path.of("/candidate/app.deal"), rows), Map.of("./pack.dealui-pack", UiParser.parsePack(Path.of("pack"), PACK.replace("TYPE", "number"))));
            throw new AssertionError("ForEach erased item nullability");
        } catch (UiDiagnostic error) { check(error.code().equals("UI2014") && error.actual().equals("(Row | null)[]"), "ForEach reports real element type"); }
    }
    private static void execution() throws Exception {
        Path fixture = Files.createTempDirectory(ROOT.resolve("build"), "nullable-elements-");
        String logic = "export class Row { id: number; values: (number | null)[]; } export class State { values: (number | null)[]; outer: number[] | null; rows: Row[]; }\n"
                + "export class Go { values: (number | null)[]; }\nexport function initialState(): State { return {values: [1, null, 3], outer: null, rows: [{id: 1, values: [null, 2]}]}; }\n"
                + "// @ui-update\nexport function go(state: State, action: Go): State { return {values: action.values, outer: state.outer, rows: state.rows}; }\n";
        String view = HEADER + "ui.Column() { Helper(values: state.values) ForEach(state.rows, row: app.Row, key: row.id) { ui.Data(values: row.values, onClick: action app.Go {values: row.values}) } } }\n"
                + "view Helper(values: (number | null)[]): View { ui.Data(values: values) }";
        String pack = PACK.replace("TYPE", "(number | null)[]");
        Path ui = fixture.resolve("view.dealui"), deal = fixture.resolve("app.deal"), packFile = fixture.resolve("pack.dealui-pack");
        Files.writeString(ui, view); Files.writeString(deal, logic); Files.writeString(packFile, pack);
        var compiler = new UiCompiler(Path.of(System.getenv("DEAL_FS_ROOT")), ROOT);
        var result = compiler.compile(ui, fixture.resolve("jvm"), UiCompiler.Target.PORTABLE);
        compiler.build(result, ROOT.resolve("build/classes"));
        try (var loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiNullableElementsTest.class.getClassLoader())) {
            var previous = Thread.currentThread().getContextClassLoader(); Thread.currentThread().setContextClassLoader(loader);
            try {
                var bridge = (UiPortableBridge)Class.forName(result.bridgeClass(), true, loader).getConstructor().newInstance();
                var initial = bridge.initial(bridge.initialState(), bridge.initialStore());
                check(initial.tree().children().get(0).props().get("values").value().equals("[1,null,3]"), "JVM nullable data prop");
                var row = initial.tree().children().get(1);
                var changed = bridge.transition(initial.state(), initial.tree(), initial.store(), bridge.action(row.props().get("onClick").actionSlot(), null));
                check(changed.tree().children().get(0).props().get("values").value().equals("[null,2]"), "JVM action capture and helper preserve nullable elements");
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
        var generated = UiSourceGenerator.generate(ui, view, deal, logic, Map.of("./pack.dealui-pack", UiParser.parsePack(packFile, pack)), ROOT);
        check(generated.source().contains("values: (number | null)[]"), "generated helper retains element type");
        var field = generated.checked().deal().classes().get("State").fields().get("values").type();
        check(!field.optional() && field.elementType().optional(), "checked metadata retains exact type");
        check(!generated.source().contains("nominal0: {values: null}"), "synthetic action sample does not replace array with null");
        var inspection = UiCompilerWorkspace.inspect(logic, view, pack, "./pack.dealui-pack");
        var dataNode = inspection.nodes().stream().filter(n -> n.component().equals("ui.Data")).findFirst().orElseThrow();
        var surface = UiCompilerWorkspace.queryEditSurface(logic, view, pack, "./pack.dealui-pack", dataNode.id());
        check(surface.componentContracts().stream().flatMap(c -> c.properties().stream()).anyMatch(p -> p.name().equals("values") && p.type().equals("(number | null)[]")), "edit contract retains nullable element type");
        Path js = fixture.resolve("js"); Files.createDirectories(js);
        Files.writeString(js.resolve("app.deal"), UiSourceGenerator.compilerSource(logic) + generated.augmentation());
        Files.writeString(js.resolve("entry.deal"), generated.source());
        Files.writeString(js.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        check(UiCheckpointCompiler.compile(js.resolve("entry.deal"), js.resolve("out"), js.resolve("diagnostics.json")) == 0, "JS generated helper/action samples compile");
        Process node = new ProcessBuilder("node", ROOT.resolve("src/test/js/nullable-elements.test.js").toString(), js.resolve("out").toString(), ROOT.toString()).inheritIO().start();
        check(node.waitFor() == 0, "JS nullable elements execute and checkpoint");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
