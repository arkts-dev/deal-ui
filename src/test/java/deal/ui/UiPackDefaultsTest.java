package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiPackDefaultsTest {
    public static void main(String[] args) {
        String[][] valid = {{"number", "-1.5"}, {"number | null", "null"}, {"number[]", "[1, 2]"}, {"readonly (number | null)[]", "[1, null]"}, {"Size", "{value: 1}"}, {"Size", "small"}, {"Size", "ui.small"}, {"Record<string, string>", "{label: \"ok\"}"}, {"unknown", "{nested: [1, null]}"}};
        String[][] invalid = {{"number", "\"wrong\""}, {"number", "null"}, {"number", "[1]"}, {"number", "{}"}, {"number[]", "[1, \"wrong\"]"}, {"Size", "{value: \"wrong\"}"}, {"Size", "{}"}, {"Size", "{value: 1, extra: 2}"}, {"number", "small"}, {"Size", "missing"}, {"Record<string, string>", "{label: 1}"}, {"number[][]", "[[1], [null]]"}};
        for (var pair : valid) check(pair, true);
        for (var pair : invalid) check(pair, false);
        for (String initializer : new String[]{"\"wrong\"", "missing", "bad"}) {
            String bad = "export token bad: number = " + initializer + ";export class Props {count: number = 0;}export component Data(props: Props): View;";
            var checker = new UiChecker();
            UiCoreParityTest.expect("UI2026", () -> checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Data()}"), Path.of("root/app.deal"), checker.parseDeal(Path.of("root/app.deal"), "export class State {}"), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), bad))));
        }
        System.out.println("UiPackDefaultsTest: typed defaults and token initializers; unknown/cyclic references reject");
    }
    private static void check(String[] pair, boolean valid) {
        String pack = "export class Size {value: number;}export token small: Size = {value: 1};export class Props {count: " + pair[0] + " = " + pair[1] + ";}export component Data(props: Props): View;";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Data()}";
        var checker = new UiChecker(); boolean accepted = true;
        try { checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), view), Path.of("root/app.deal"), checker.parseDeal(Path.of("root/app.deal"), "export class State {}"),
                Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), pack))); }
        catch (UiDiagnostic failure) { if (!failure.code().equals("UI2026")) throw failure; accepted = false; }
        if (accepted != valid) throw new AssertionError(pair[0] + " = " + pair[1] + " accepted=" + accepted);
    }
}
