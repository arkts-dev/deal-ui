package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiImportedTypesTest {
    private UiImportedTypesTest() {}
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ui-import-types-");
        for (boolean same : new boolean[]{true, false}) {
            String target = same ? "host/a" : "host/b";
            String pack = "import * as x from \"host/a\"; export class Props {click: Action;}export component Button(props: Props): View {event click(payload: x.Point);}";
            String logic = "import * as y from \"" + target + "\";export class State {}export class Go {point: y.Point;}\n// @ui-update\nexport function go(s: State, a: Go): State {return s;}";
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Button(click: action app.Go {point: payload})}";
            Runnable check = () -> UiSourceGenerator.generate(root.resolve("view.dealui"), view, root.resolve("app.deal"), logic,
                    Map.of("./pack", UiParser.parsePack(root.resolve("pack"), pack)), Path.of("."),
                    Map.of("host/a", "export class Point {n: number;}", "host/b", "export class Point {n: number;}"));
            if (same) check.run(); else UiCoreParityTest.expect("UI2031", check);
        }
        Files.writeString(root.resolve("model.deal"), "export class Row {n: number;}");
        String logic = "import * as model from \"./model\";export class State {row: model.Row;}export function initialState(): State {return {row:{n:3}};}";
        var result = UiSourceGenerator.generate(root.resolve("view.dealui"), "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Data(value: state.row.n)}", root.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(root.resolve("pack"), "export class Props {value: number;}export component Data(props: Props): View;")), Path.of("."));
        if (!result.source().contains("state.row.n")) throw new AssertionError("Imported field lost");
        Files.writeString(root.resolve("app.deal"), logic + result.augmentation());
        Files.writeString(root.resolve("entry.deal"), result.source());
        Files.writeString(root.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(root.resolve("entry.deal"), root.resolve("out"), root.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(root.resolve("diagnostics.json")));
        System.out.println("UiImportedTypesTest: import aliases preserve declaration identity and field inventory");
    }
}
