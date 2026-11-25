package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiRootTypeTest {
    public static void main(String[] args) {
        Path logicFile = Path.of("root/app.deal");
        String logic = "export class State {n: number;}";
        String pack = "export class Props {} export component Empty(props: Props): View;";
        for (String suffix : new String[]{"[]", " | null", "readonly-array"}) {
            String type = suffix.equals("readonly-array") ? "readonly app.State[]" : "app.State" + suffix;
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\n"
                    + "export view Root(state: " + type + "): View {ui.Empty()}";
            var checker = new UiChecker();
            try {
                checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), view),
                        logicFile, checker.parseDeal(logicFile, logic), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), pack)));
                throw new AssertionError("Root qualifier discarded: " + type);
            } catch (UiDiagnostic error) {
                if (!error.code().equals("UI2004") || !error.actual().contains("State")) throw error;
            }
        }
        System.out.println("UiRootTypeTest: nullable/array/readonly-array roots rejected before generation");
    }
}
