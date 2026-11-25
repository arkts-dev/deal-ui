package deal.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class UiComparisonsTest {
    public static void main(String[] args) {
        String logic = "export class Row {n: number;} export class State {n: number; maybe: number | null; missing: number | undefined; raw: unknown; flag: boolean; xs: number[]; row: Row;}";
        String pack = "export class Props {value: boolean;}export component Data(props: Props): View;";
        for (boolean valid : new boolean[]{true, false}) for (String expr : valid
                ? List.of("null === null", "state.maybe === null", "null !== state.maybe", "state.missing === state.missing", "state.raw === null", "state.n < state.n", "state.flag === state.flag", "state.xs === state.xs")
                : List.of("state.maybe < state.n", "state.flag > state.flag", "state.row < state.row", "state.xs < state.xs", "state.xs === state.n")) {
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Data(value: " + expr + ")}";
            var checker = new UiChecker(); boolean accepted = true;
            try { checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), view), Path.of("root/app.deal"),
                    checker.parseDeal(Path.of("root/app.deal"), logic), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), pack))); }
            catch (UiDiagnostic error) { if (!error.code().equals("UI2020")) throw error; accepted = false; }
            if (accepted != valid) throw new AssertionError(expr + " accepted=" + accepted);
        }
        System.out.println("UiComparisonsTest: valid equality and scalar ordering only");
    }
}
