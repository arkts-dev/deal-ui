package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiArrayLengthTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-array-length-");
        String logic = "export class State {items: readonly (number | null)[];}export function initialState(): State {return {items: [1, null]};}";
        String pack = "export class Props {value: number;}export component Data(props: Props): View;";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {When(state.items.length > 0) {Helper(value: state.items.length)} Else {ui.Data(value: 0)}}view Helper(value: number): View {ui.Data(value: value)}";
        var packs = Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack));
        var generated = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic, packs, Path.of("."));
        Files.writeString(project.resolve("app.deal"), logic);
        Files.writeString(project.resolve("entry.deal"), generated.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0) throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        var node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=require('node:assert/strict');a.equal(e.view_Root.$f('r',e.bridgeInitialState.$f()).props[0].numberValue,2);", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Array length execution failed");
        for (String expression : new String[]{"state.items.length.length", "state.items.nope"}) {
            try { UiSourceGenerator.generate(project.resolve("view.dealui"), view.replace("state.items.length", expression), project.resolve("app.deal"), logic, packs, Path.of(".")); throw new AssertionError(expression); }
            catch (UiDiagnostic expected) { if (!expected.code().equals("UI2023")) throw expected; }
        }
        try { UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic.replace("items: readonly (number | null)[]", "items: readonly (number | null)[] | null"), packs, Path.of(".")); throw new AssertionError("Nullable array read admitted"); }
        catch (UiDiagnostic expected) { if (!expected.code().equals("UI2022")) throw expected; }
        System.out.println("UiArrayLengthTest: readonly nullable-element array length renders; unsafe/unknown paths reject");
    }
}
