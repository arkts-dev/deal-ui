package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiActionForwardingTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-action-forwarding-");
        String logic = "export class State {n: number;}export class Go {n: number;}export function initialState(): State {return {n: 0};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {n: action.n};}";
        String pack = "export class Props {click: Action;}export component Button(props: Props): View {event click(payload: number);}";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {Helper(click: action app.Go {n: payload})}view Helper(click: Action): View {Again(click: click)}view Again(click: Action): View {ui.Button(click: click)}";
        var packs = Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack));
        var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic, packs, Path.of("."));
        Files.writeString(project.resolve("app.deal"), UiSourceGenerator.compilerSource(logic));
        Files.writeString(project.resolve("entry.deal"), result.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),s=e.bridgeInitialState.$f(),p=e.view_Root.$f('r',s).props[0];require('node:assert/strict').equal(e.nextState.$f(s,e['action_'+p.actionSlot].$f(p.actionBindings,7)).n,7);", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Forwarded action failed");
        try { UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack.replace("payload: number", "payload: string"))), Path.of(".")); throw new AssertionError("Forwarded payload mismatch admitted"); }
        catch (UiDiagnostic expected) { if (!expected.code().equals("UI2031")) throw expected; }
        System.out.println("UiActionForwardingTest: multi-hop Action forwarding binds and validates final event payload");
    }
}
