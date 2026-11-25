package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiPackPathsTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-pack-paths-");
        String logic = "export class State {}export function initialState(): State {return {}; }";
        String pack = "export class Size {value: number;}export token small: Size = {value: 12};export class Props {value: number;}export component Data(props: Props): View;";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {Helper(size: ui.small)}view Helper(size: ui.Size): View {ui.Data(value: size.value)}";
        var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
        Files.writeString(project.resolve("app.deal"), logic);
        Files.writeString(project.resolve("entry.deal"), result.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]);require('node:assert/strict').equal(e.view_Root.$f('r',e.bridgeInitialState.$f()).props[0].numberValue,12);", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Pack path execution failed");
        System.out.println("UiPackPathsTest: pack nominal helper field path compiles and renders");
    }
}
