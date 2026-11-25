package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiActionSamplesTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-action-samples-");
        String logic = "export class State {} export class Go {n?: number; raw: unknown; labels: Record<string,string>; table: Record<string,unknown>;}\n"
                + "export function initialState(): State {return {};}\n// @ui-update\nexport function go(state: State, action: Go): State {return state;}";
        String pack = "export class Props {click: Action;}export component Button(props: Props): View {event click;}";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Button(click: action app.Go {raw: null, labels: stateLabels, table: stateTable})}";
        // Bind Record fields through actual state, without view construction syntax.
        logic = logic.replace("export class State {}", "export class State {labels: Record<string,string>; table: Record<string,unknown>;}").replace("return {};", "return {labels: {}, table: {}};");
        view = view.replace("stateLabels", "state.labels").replace("stateTable", "state.table");
        var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
        if (result.source().contains("n: null")) throw new AssertionError("Optional number synthesized as null");
        Files.writeString(project.resolve("app.deal"), UiSourceGenerator.compilerSource(logic));
        Files.writeString(project.resolve("entry.deal"), result.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=e.checkAction_0.$f();require('node:assert/strict').equal(require(process.argv[1].replace('entry.js','deal/runtime.js')).has(a.nominal0,'n'),false);e.nextState.$f(e.bridgeInitialState.$f(),a);",
                project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Synthetic action execution failed");
        System.out.println("UiActionSamplesTest: optional/unknown/Record action compiles and executes");
    }
}
