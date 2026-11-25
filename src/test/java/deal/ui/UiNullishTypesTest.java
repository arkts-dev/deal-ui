package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiNullishTypesTest {
    private static int checks;
    public static void main(String[] args) {
        for (String type : new String[]{"number | undefined", "undefined | number", "number | null | undefined",
                "null | number | undefined", "(number | undefined)[]", "readonly (number | null | undefined)[]",
                "(number[] | undefined)[]", "(readonly (number | undefined)[] | null)[]"}) {
            var checker = new UiChecker();
            var imported = checker.parseDeal(Path.of("nullish.deal"), "export class State {value: " + type + ";}")
                    .classes().get("State").fields().get("value").type();
            var parsed = UiParser.parseViews(Path.of("nullish.dealui"), "view V(value: " + type + "): View {}")
                    .views().getFirst().parameters().getFirst().type();
            if (!imported.equals(parsed)) throw new AssertionError(type + " import/parse differ: " + imported + " / " + parsed);
            var roundtrip = UiParser.parseViews(Path.of("roundtrip.dealui"), "view V(value: " + parsed.sourceType(parsed.name()) + "): View {}")
                    .views().getFirst().parameters().getFirst().type();
            if (!roundtrip.equals(parsed)) throw new AssertionError(type + " roundtrip lost qualifiers");
            checks += 2;
        }
        var module = new UiChecker().parseDeal(Path.of("presence.deal"), "export class State {a?: number; b: number | null; c: number | undefined;}");
        if (!module.classes().get("State").fields().get("a").optional()
                || module.classes().get("State").fields().get("a").type().nullish()
                || module.classes().get("State").fields().get("b").optional()) throw new AssertionError("Presence conflated");
        String header = "import * as app from \"./app\"; import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {";
        String logic = "export class State {n: number | undefined;}";
        String pack = "export class Props {value: number | null;} export component Data(props: Props): View;";
        for (String invocation : new String[]{"ui.Data()", "ui.Data(value: state.n)"}) {
            var checker = new UiChecker();
            try {
                checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), header + invocation + "}"),
                        Path.of("root/app.deal"), checker.parseDeal(Path.of("root/app.deal"), logic),
                        Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), pack)));
                throw new AssertionError("Invalid nullable contract accepted: " + invocation);
            } catch (UiDiagnostic error) {
                if (!error.code().equals(invocation.equals("ui.Data()") ? "UI2028" : "UI2031")) throw error;
                checks++;
            }
        }
        System.out.println("UiNullishTypesTest: " + checks + " checks passed");
    }
}
