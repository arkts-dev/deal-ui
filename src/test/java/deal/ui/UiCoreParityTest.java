package deal.ui;

import java.nio.file.Path;
import java.util.Map;

/** Regression contracts absent from the original primitive-only differential matrix. */
public final class UiCoreParityTest {
    private static final Path ROOT = Path.of("/candidate");
    private UiCoreParityTest() {}
    public static void main(String[] args) {
        rejects("null | undefined", "undefined");
        rejects("undefined | null", "null");
        rejects("(null | undefined)[]", "readonly (number | undefined)[]");
        String logic = "export class State {a: null | undefined; b: number | undefined;}";
        expect("UI2020", () -> check(logic, "ui.Data(value: state.a === state.b)", "boolean", ""));
        check("export class State {a: (unknown | null)[];}", "H(v: state.a)", "number", "view H(v: unknown[]): View {ui.Data(value: 0)}");
        check("export class State {a?: unknown; b: unknown;}", "ui.Data(value: state.a === state.b)", "boolean", "");
        for (String bad : new String[]{"number | null | null", "null | number | null", "unknown | undefined | undefined", "null | null"})
            expect("UI1007", () -> UiParser.parseViews(ROOT.resolve("bad.dealui"), "view H(v: " + bad + "): View {}"));
        var functions = new UiChecker().parseDeal(ROOT.resolve("functions.deal"), "export class State {a: () => number; b: () => number;}");
        if (!functions.classes().get("State").fields().get("a").type().equals(functions.classes().get("State").fields().get("b").type()))
            throw new AssertionError("Source locations leaked into function types");
        check("export class State {a: (x: number) => number;}", "H(v: state.a)", "boolean", "view H(v: (other: number) => number): View {ui.Data(value: v === v)}");
        check("export class State {text: string;}", "ui.Data(value: state.text.length)", "number", "");
        check("export class State {table: Record<string, unknown>;}", "ui.Data(value: state.table.entry)", "unknown", "");
        check("export class State {error: Error;}", "ui.Data(value: state.error.message)", "string", "");
        check("export class State {table: Record<string, string>;}", "ui.Data(value: has(state.table.entry))", "boolean", "");
        expect("UI2004", () -> check("class State {}", "ui.Data(value: 0)", "number", ""));
        expect("UI2004", () -> check("class Hidden {} export class State {a: Hidden;}", "H(v: state.a)", "number", "view H(v: app.Hidden): View {ui.Data(value: 0)}"));
        expect("UI2031", () -> check("export class State {} export class Go {}", "H(v: 1, a: action app.Go {})", "number", "view H(v: unknown, a: Action): View {ui.Data(value: v)}"));
        System.out.println("UiCoreParityTest: core compatibility, members and export visibility");
    }
    private static void rejects(String from, String to) {
        expect("UI2031", () -> check("export class State {a: " + from + ";}", "H(v: state.a)", "number",
                "view H(v: " + to + "): View {ui.Data(value: 0)}"));
    }
    static void check(String logic, String body, String prop, String helpers) {
        String view = "import * as app from \"./app\"; import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {" + body + "}" + helpers;
        var checker = new UiChecker();
        checker.check(ROOT.resolve("view.dealui"), UiParser.parseViews(ROOT.resolve("view.dealui"), view),
                ROOT.resolve("app.deal"), checker.parseDeal(ROOT.resolve("app.deal"), logic),
                Map.of("./pack", UiParser.parsePack(ROOT.resolve("pack.dealui-pack"), "export class Props {value: " + prop + ";}export component Data(props: Props): View;")));
    }
    static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (UiDiagnostic failure) { if (!failure.code().equals(code)) throw failure; }
    }
}
