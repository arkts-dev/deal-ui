package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiJsFailureActionTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-js-failure-action-");
        String logic = "export class State {n: number;}export class Go {}export class Done {}export class Failed {message: string;}export function initialState(): State {return {n: 0};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {n: 1};}\n// @ui-effect\nexport async function work(state: State, action: Go): Promise<Done> {throw {name: \"Failure\", message: \"injected\"};}\n// @ui-effect-failure\nexport function failure(state: State, action: Go, message: string): Failed {return {message: message};}\n// @ui-update\nexport function done(state: State, action: Done): State {return {n: 10};}\n// @ui-update\nexport function failed(state: State, action: Failed): State {return {n: 99};}";
        String pack = "export class Props {click: Action;}export component Button(props: Props): View {event click;}";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Button(click: action app.Go {})}";
        var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
        Files.writeString(project.resolve("app.deal"), UiSourceGenerator.compilerSource(logic));
        Files.writeString(project.resolve("entry.deal"), result.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0) throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        var node = new ProcessBuilder("node", "src/test/js/failure-action.test.js", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("JS failure action execution failed");
    }
}
