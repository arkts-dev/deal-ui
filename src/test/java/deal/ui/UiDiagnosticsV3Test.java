package deal.ui;

import deal.amend.CompilerProtocolJson;
import deal.semantic.ir.CanonicalJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Executable negative inventory and exact shared-v3 transport checks. */
public final class UiDiagnosticsV3Test {
    private static int checks;
    private static final Path FILE = Path.of("/candidate/app.dealui");
    private static final String PREFIX = "export view App(state: app.State): View { ui.Text(value: ";
    private static final String DEAL = "export class Row { id: number; title: string; } export class State { text: string; count: number; flag: boolean; maybe: Row | null; rows: Row[]; }";
    private static final String PACK = "export class Props { value?: string; } export component Text(props: Props): View; export class Empty {} export component Column(props: Empty): View { children optional; }";

    public static void main(String[] args) {
        for (String op : List.of("==", "!=")) expression("state.count " + op + " 1", "UI1026", op);
        expression("state.text ?? \"fallback\"", "UI1027", "??");
        expression("state.maybe?.title", "UI1028", "?.");
        expression("state.rows[0]", "UI1029", "[");
        expression("state.maybe!", "UI1030", "!");
        for (String op : List.of("%", "**", "&", "|", "^", "<<", ">>", ">>>", "as", "instanceof"))
            expression("state.count " + op + " 1", "UI1025", op);
        expression("typeof state.count", "UI1025", "typeof");
        expression("~state.count", "UI1025", "~");
        for (String op : List.of("=", "+=", "-=", "*=", "/=", "%=", "**=", "&&=", "||=", "??=", "&=", "|=", "^=", "<<=", ">>=", ">>>="))
            expression("state.count " + op + " 1", "UI1031", op);
        expression("state.count++", "UI1031", "++");
        expression("state.count--", "UI1031", "--");
        for (String value : List.of("[1]", "{value: 1}", "`value ${state.count}`")) expression(value, "UI1024", value.substring(0, 1));
        expression("<Text />", "UI1021", "<");
        reject("export view App(): View { if (true) {} }", "UI1021", "if");
        reject("export view App(): View { <Text /> }", "UI1021", "<");
        reject("export view App(): View { ui.Text(\"x\") }", "UI1023", "\"x\"");
        reject("export view App(): View { ui.Text({value: \"x\"}) }", "UI1023", "{");
        for (String imported : List.of("import { Text } from \"ui\";", "import ui from \"ui\";"))
            reject(imported + " export view App(): View {}", "UI1020", imported.contains("{") ? "{" : "ui");
        for (String computed : List.of("state.rows.filter()", "state.rows[0]", "[]", "state.rows ?? state.rows"))
            reject("export view App(state: app.State): View { ForEach(" + computed + ", item: app.Row, key: item.id) {} }", "UI1022", "");
        semantic("When(state.count) { ui.Text() }", "UI2032", "boolean", "number", "state.count");
        semantic("When(state.count && true) { ui.Text() }", "UI2032", "boolean", "number", "state.count");
        semantic("ui.Text(value: true * true)", "UI2032", "number", "boolean", "true");
        semantic("ui.Text(value: state.text - state.text)", "UI2032", "number", "string", "state.text");
        semantic("ui.Text(value: state.maybe.title)", "UI2022", "Row", "Row | null", "state.maybe.title");
        semantic("ForEach(state.count, item: app.Row, key: item.id) { ui.Text() }", "UI2014", "app.Row[]", "number", "state.count");
        batch();
        unicodeAndEvidence();
        cli();
        repair();
        for (String literal : List.of("??", "?.", "==", "[", "typeof", "as", "if")) {
            UiParser.parseViews(FILE, PREFIX + "\"" + literal + "\") }"); checks++;
        }
        System.out.println("UiDiagnosticsV3Test: " + checks + " checks passed");
    }

    private static void expression(String expression, String code, String token) { reject(PREFIX + expression + ") }", code, token); }
    private static void reject(String source, String code, String token) {
        try { UiParser.parseViews(FILE, source); throw new AssertionError("Accepted " + source); }
        catch (UiDiagnostic d) {
            check(d.code().equals(code), source + ": " + d.code() + " instead of " + code);
            var report = object(CompilerProtocolJson.decode(d.toJson(FILE.getParent())));
            check(CompilerProtocolJson.intField(report, "version") == 3, "v3");
            check(CompilerProtocolJson.stringField(report, "status").equals("rejected"), "rejected");
            var diagnostic = object(array(CompilerProtocolJson.field(report, "diagnostics")).items().getFirst());
            check(CompilerProtocolJson.stringField(diagnostic, "responsibility").equals("SOURCE_CONTRACT"), "UI source responsibility");
            var range = object(CompilerProtocolJson.field(diagnostic, "range"));
            var offsets = array(CompilerProtocolJson.field(range, "offset"));
            int start = ((CanonicalJson.Int)offsets.items().get(0)).value();
            int end = ((CanonicalJson.Int)offsets.items().get(1)).value();
            check(token.isEmpty() || source.substring(start, end).startsWith(token), "offending token: " + source.substring(start, end));
            check(!d.toJson(FILE.getParent()).contains("internal range defect"), "valid source range");
            capture(d.toJson(FILE.getParent()));
        }
    }

    private static void semantic(String node, String code, String expected, String actual, String offending) {
        String source = "import * as app from \"./app\"; import * as ui from \"ui\"; // @ui-root\nexport view App(state: app.State): View { ui.Column() { " + node + " } }";
        var checker = new UiChecker();
        try {
            checker.check(FILE, UiParser.parseViews(FILE, source), Path.of("/candidate/app.deal"),
                checker.parseDeal(Path.of("/candidate/app.deal"), DEAL), Map.of("ui", UiParser.parsePack(Path.of("/candidate/ui.pack"), PACK)));
            throw new AssertionError("Accepted " + node);
        } catch (UiDiagnostic d) {
            check(d.code().equals(code), node + ": " + d.format());
            check(d.expected().equals(expected) && d.actual().equals(actual), d.expected() + " / " + d.actual());
            var producer = d.diagnostics().getFirst();
            check(source.substring(producer.range().startScalarOffset(), producer.range().endScalarOffset()).equals(offending), "operand range: " + producer.range());
            check(d.toJson(FILE.getParent()).contains("\"context\":"), "retained context");
            capture(d.toJson(FILE.getParent()));
        }
    }

    private static void batch() {
        String source = "export function first(): void { let a = ; }\nexport function second(): void { let b = ; }";
        Path file = Path.of("/candidate/broken.deal");
        var lexed = new deal.lexer.Lexer(source, file.toString()).tokenize();
        var parsed = new deal.parser.Parser(lexed.tokens(), file.toString()).parse();
        try { new UiChecker().parseDeal(file, source); throw new AssertionError("Accepted malformed DEAL"); }
        catch (UiDiagnostic d) {
            check(d.compilerDiagnostics().size() == lexed.diagnostics().size() + parsed.diagnostics().size() && d.compilerDiagnostics().size() >= 2, "all producer occurrences");
            var transported = d.withRepairArtifact("deal").atNode(new UiModel.Call("Text", Map.of(), List.of(), null, new UiModel.Span(FILE, 1, 1)));
            check(transported.compilerDiagnostics().equals(d.compilerDiagnostics()), "copy preserves batch");
            String expected = CompilerProtocolJson.encode(new deal.diagnostics.DiagnosticJson(null,
                    deal.diagnostics.DiagnosticSourceBatch.observed(Map.of(file.toString(), source))).compiler(d.compilerDiagnostics(), List.of(), null));
            check(d.toJson(null).equals(expected), "exact core projection unchanged");
            check(transported.toJson(null).equals(expected), "exception transport unchanged");
            capture(expected);
            var inspection = UiCompilerWorkspace.inspect(source, "export view App(): View {}", PACK, "ui");
            check(inspection.diagnostics().size() == d.compilerDiagnostics().size(), "workspace retains batch");
            String json = UiDiagnosticJson.encode(inspection);
            check(json.contains("\"version\":3") && !json.contains("protocolVersion") && !json.contains("\"facts\":"), "workspace v3 only");
            var canonical = CanonicalCompiler.inspectCanonicalApp(source, "export view App(): View {}", PACK, "ui");
            String canonicalJson = canonical.toJson();
            check(canonicalJson.contains("\"deal\":") && canonicalJson.contains("\"dealUi\":") && canonicalJson.contains("appInterface"), "canonical projection keeps editing facts");
            check(canonicalJson.indexOf("\"diagnostics\":") == canonicalJson.lastIndexOf("\"diagnostics\":"), "one shared canonical diagnostic table");
            capture(canonicalJson);
        }
        var ui = new UiDiagnostic("UI2032", "bad operand", FILE, 1, 1).withSources(Map.of(FILE.toString(), "😀x\n"));
        check(ui.toJson(FILE.getParent()).contains("revision"), "snapshot identity");
    }
    private static void unicodeAndEvidence() {
        String source = "// 😀\r\n" + PREFIX + "state.text ?? \"x\") }";
        try { UiParser.parseViews(FILE, source); throw new AssertionError("Expected rejection"); }
        catch (UiDiagnostic d) {
            var diagnostic = d.diagnostics().getFirst();
            check(diagnostic.range().startScalarOffset() == source.indexOf("??"), "UTF-16 offset across CRLF/surrogate pair");
            check(diagnostic.range().startLine() == 2, "one-based line");
            String before = d.toJson(FILE.getParent());
            d.withSources(Map.of(FILE.toString(), "newer source"));
            check(d.toJson(FILE.getParent()).equals(before), "serialization retains consumed snapshot");
            check(before.contains("\"syntax\":") && !before.contains("\"edit\":"), "producer syntax evidence, no invented edit");
            capture(before);
        }
        var range = new deal.diagnostics.DiagnosticRange(FILE.toString(), 1, 1, 1, 2, 0, 1, 1, deal.diagnostics.RangeOrigin.SOURCE);
        var first = new deal.diagnostics.CompilerDiagnostic("E5003", "error", "first", range, List.of(), null)
                .withOccurrence(new deal.diagnostics.DiagnosticOccurrence("revision", "first", null));
        var second = new deal.diagnostics.CompilerDiagnostic("E5003", "error", "second", range,
                List.of(new deal.diagnostics.DiagnosticNote("declared here", range)), null)
                .withOccurrence(new deal.diagnostics.DiagnosticOccurrence("revision", "second", "first"));
        var wrapped = UiDiagnostic.compilerBatch(List.of(first, second), FILE, "x");
        String json = wrapped.withRepairArtifact("deal").toJson(FILE.getParent());
        check(json.contains("\"cause\":0") && json.contains("declared here"), "causal indexes and notes preserved");
        capture(json);
    }

    private static void cli() {
        try {
            Path dir = java.nio.file.Files.createTempDirectory("ui-v3-cli-");
            Path input = dir.resolve("app.dealui"), report = dir.resolve("diagnostics.json");
            java.nio.file.Files.writeString(input, PREFIX + "state.text ?? \"x\") }");
            check(Main.run(new String[]{"check", input.toString(), "--fs-root", System.getenv("DEAL_FS_ROOT"), "--diagnostics-json", report.toString()}) == 1, "CLI rejection exit");
            String json = java.nio.file.Files.readString(report);
            check(json.contains("\"version\":3") && json.contains("UI1027"), "CLI v3 rejection");
            java.nio.file.Files.delete(input);
            check(Main.run(new String[]{"check", input.toString(), "--fs-root", System.getenv("DEAL_FS_ROOT"), "--diagnostics-json", report.toString()}) == 2, "CLI operational exit");
            check(java.nio.file.Files.readString(report).contains("\"status\":\"failed\""), "CLI operational envelope");
            capture(java.nio.file.Files.readString(report));
            java.nio.file.Files.writeString(dir.resolve("app.deal"), "export class State { text: string; } export function initialState(): State { return {text: 1}; }");
            java.nio.file.Files.writeString(dir.resolve("ui.dealui-pack"), PACK.replace("component Text(props: Props): View;", "component Text(props: Props): View { capability \"renderer.test.text\"; }").replace("children optional;", "children optional; capability \"renderer.test.column\";"));
            java.nio.file.Files.writeString(input, "import * as app from \"./app.deal\"; import * as ui from \"./ui.dealui-pack\"; // @ui-root\nexport view App(state: app.State): View { ui.Text(value: state.text) }");
            check(Main.run(new String[]{"check", input.toString(), "--renderer", "portable", "--fs-root", System.getenv("DEAL_FS_ROOT"), "--diagnostics-json", report.toString()}) == 1, "downstream semantic rejection");
            String downstream = java.nio.file.Files.readString(report);
            check(downstream.contains("\"expected\":\"string\"") && downstream.contains("\"actual\":\"number\"") && downstream.contains("\"version\":3"), "downstream producer evidence not stderr");
            capture(downstream);
            java.nio.file.Files.writeString(dir.resolve("app.deal"), "export class State { text: string; } export function initialState(): State { return {text: \"ready\"}; }");
            check(Main.run(new String[]{"check", input.toString(), "--renderer", "portable", "--fs-root", System.getenv("DEAL_FS_ROOT"), "--diagnostics-json", report.toString()}) == 0, "CLI accepted");
            capture(java.nio.file.Files.readString(report));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void repair() {
        String logic = "export class State { text: string; } export function initialState(): State { return {text: \"ready\"}; }";
        String ui = "import * as app from \"./app\"; import * as ui from \"ui\"; // @ui-root\nexport view App(state: app.State): View { ui.Text(value: state.text) }";
        var inspection = UiCompilerWorkspace.inspect(logic, ui, PACK, "ui");
        var node = inspection.nodes().getFirst();
        var change = UiCompilerWorkspace.apply(logic, ui, PACK, "ui", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.SetProperty(node.id(), "value", "state.text ?? \"x\"")));
        check(!change.accepted() && change.source().equals(ui), "rejected edit is atomic");
        String json = change.toJson();
        var descriptor = UiCompilerWorkspace.queryNode(logic, ui, PACK, "ui", node.id()).allowedOperations().stream()
                .filter(d -> d.operation().equals(UiCompilerWorkspace.SET_PROPERTY)).findFirst().orElseThrow();
        var precondition = new deal.amend.CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(), Map.of(node.id().value(), descriptor.targetFingerprint()));
        var scope = UiCompilerWorkspace.inspectChange(logic, ui, PACK, "ui", inspection.sourceDigest(), List.of(node.id()), List.of(UiCompilerWorkspace.SET_PROPERTY));
        var staged = UiCompilerWorkspace.stageChange(logic, ui, PACK, "ui", precondition, scope,
                List.of(new UiCompilerWorkspace.SetProperty(node.id(), "value", "state.text ?? \"x\"")));
        String wire = UiDiagnosticJson.encode(staged);
        check(wire.contains("\"workspace\":") && wire.contains("\"diagnostics\":[0]") && !wire.contains("\"facts\":"), "repair slots reference shared diagnostics");
        check(json.contains("\"snapshot\":\"candidate\"") && json.contains("UI1027"), "candidate snapshot retained");
        try {
            Path dir = Path.of("build/diagnostic-reports"); java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("repair.json"), wire);
            java.nio.file.Files.writeString(dir.resolve("rejection.json"), new UiDiagnostic("UI2032", "bad", FILE, 1, 1).withSources(Map.of(FILE.toString(), "x")).toJson(FILE.getParent()));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void capture(String report) {
        try {
            Path dir = Path.of("build/diagnostic-reports"); java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("case-" + checks + ".json"), report);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static CanonicalJson.Obj object(CanonicalJson.Value value) { return CompilerProtocolJson.requireObject(value, "test"); }
    private static CanonicalJson.Arr array(CanonicalJson.Value value) { return CompilerProtocolJson.requireArray(value, "test"); }
    private static void check(boolean valid, String message) { checks++; if (!valid) throw new AssertionError(message); }
}
