package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiNominalIdentityTest {
    public static void main(String[] args) {
        String logic = "export class State {row: Row;} export class Row {n: number;}";
        String pack = "export class Row {s: string;}export class Props {value: Row;}export component Data(props: Props): View;";
        String header = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {";
        reject(logic, header + "ui.Data(value: state.row)}", pack, "UI2031");
        String scalar = "export class Props {value: number;}export component Data(props: Props): View;";
        reject(logic, header + "bogus.Helper(value: state.row.n)}view Helper(value: number): View {ui.Data(value: value)}", scalar, "UI2012");
        String actions = logic + "export class Go {}\n// @ui-update\nexport function go(state: State, action: Go): State {return state;}";
        reject(actions, header + "ui.Button(click: action bogus.Go {})}", "export class Props {click: Action;}export component Button(props: Props): View {event click;}", "UI2018");
        var checker = new UiChecker();
        checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), header + "Helper(value: state.row)}view Helper(value: app.Row): View {ui.Data(value: value.n)}"),
                Path.of("root/app.deal"), checker.parseDeal(Path.of("root/app.deal"), logic), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), scalar)));
        reject(logic, header.replace("app.State", "app.bogus.State") + "ui.Data(value: state.row.n)}", scalar, "UI2004");
        reject(logic, header + "Helper(value: state.row)}view Helper(value: app.bogus.Row): View {ui.Data(value: value.n)}", scalar, "UI2031");
        System.out.println("UiNominalIdentityTest: distinct declarations and bogus qualifiers reject; application alias resolves");
    }
    private static void reject(String logic, String view, String pack, String code) {
        var checker = new UiChecker();
        try { checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), view), Path.of("root/app.deal"),
                checker.parseDeal(Path.of("root/app.deal"), logic), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"), pack))); throw new AssertionError("Nominal mismatch admitted"); }
        catch (UiDiagnostic error) { if (!error.code().equals(code)) throw error; }
    }
}
