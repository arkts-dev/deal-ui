package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiIterationIdentityTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-iteration-identity-");
        String logic = "export class Row {id: number;} export class State {a: Row[]; b: Row[];} "
                + "export function initialState(): State {return {a: [{id: 1}], b: [{id: 2}]};}";
        String pack = "export class Empty {} export class Props {value: number;} "
                + "export component Column(props: Empty): View {children;} export component Text(props: Props): View;";
        String view = "import * as app from \"./app\"; import * as ui from \"./pack\";\n// @ui-root\n"
                + "export view Root(state: app.State): View {ui.Column(){ForEach(state.a,row:app.Row,key:row.id){ui.Text(value:row.id)} ForEach(state.b,row:app.Row,key:row.id){ui.Text(value:row.id)}}}";
        var generated = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
        Files.writeString(project.resolve("app.deal"), logic + generated.augmentation());
        Files.writeString(project.resolve("entry.deal"), generated.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=require('node:assert/strict'),t=e.view_Root.$f('root',e.bridgeInitialState.$f());a.deepEqual(t.children.map(n=>n.props[0].numberValue),[1,2]);a.deepEqual(t.children.map(n=>n.keyNumber),[1,2]);",
                project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Collections mixed");
        System.out.println("UiIterationIdentityTest: same-line loops execute distinct collections and keys");
    }
}
