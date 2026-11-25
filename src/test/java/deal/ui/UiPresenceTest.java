package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiPresenceTest {
    public static void main(String[] args) {
        String logic = "export class State {optional?: number; nullable: number | null; missing: number | undefined;}";
        String pack = "export class Props {value: boolean;}export component Data(props: Props): View;";
        for (String expr : new String[]{"has(state.optional)", "has(state.nullable)", "has(state.missing)", "has(value)"}) {
            boolean valid = expr.equals("has(state.optional)");
            String body = expr.equals("has(value)") ? "Helper(value: state.missing)} view Helper(value: number | undefined): View {ui.Data(value: has(value))}" : "ui.Data(value: " + expr + ")}";
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {" + body;
            var checker = new UiChecker(); boolean accepted = true;
            try { checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), view), Path.of("root/app.deal"),
                    checker.parseDeal(Path.of("root/app.deal"), logic), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), pack))); }
            catch (UiDiagnostic error) { if (!error.code().equals("UI2017")) throw error; accepted = false; }
            if (accepted != valid) throw new AssertionError(expr + " accepted=" + accepted);
        }
        System.out.println("UiPresenceTest: has checks optional fields, not nullable/undefined values or parameters");
    }
}
