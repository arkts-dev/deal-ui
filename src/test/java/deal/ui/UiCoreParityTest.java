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
        System.out.println("UiCoreParityTest: nullish unions never masquerade as singletons");
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
