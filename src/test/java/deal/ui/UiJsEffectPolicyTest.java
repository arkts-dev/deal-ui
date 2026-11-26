package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiJsEffectPolicyTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-js-effect-policy-");
        String logic = "export class State {n: number;}export class Go {operation: string; key: string; delay: number; mode: string;}export class Done {}export class EffectCommand {operation: string; key: string; delayMillis: number; cancellationMode: string;}export function initialState(): State {return {n: 0};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {n: state.n + 1};}\n// @ui-effect\nexport async function work(state: State, action: Go): Promise<Done> {return {};}\n// @ui-effect-policy\nexport function policy(state: State, action: Go): EffectCommand {return {operation: action.operation, key: action.key, delayMillis: action.delay, cancellationMode: action.mode};}\n// @ui-update\nexport function done(state: State, action: Done): State {return {n: 10};}";
        String pack = "export class Props {click: Action;}export class Empty {}export component Button(props: Props): View {event click;}export component Column(props: Empty): View {children;}";
        StringBuilder view = new StringBuilder("import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Column() {");
        for (String fields : new String[]{"operation: \"none\", key: \"\", delay: 0, mode: \"none\"", "operation: \"start\", key: \"x\", delay: 50, mode: \"replace\"", "operation: \"cancel\", key: \"x\", delay: 0, mode: \"interrupt\"", "operation: \"start\", key: \"x\", delay: 0, mode: \"replace\""}) view.append("ui.Button(click: action app.Go {").append(fields).append("})");
        view.append("}}");
        var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view.toString(), project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
        Files.writeString(project.resolve("app.deal"), UiSourceGenerator.compilerSource(logic));
        Files.writeString(project.resolve("entry.deal"), result.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0) throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        var node = new ProcessBuilder("node", "src/test/js/effect-policy.test.js", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("JS policy execution failed");
    }
}
