package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiComposedIdentityTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-composed-identity-");
        String logic = "export class Row {id: string; children: Row[];}export class State {rows: Row[];}export function initialState(): State {return {rows: [{id: \"a:1\", children: [{id: \"same\", children: []}]}, {id: \"a\", children: [{id: \"same\", children: []}]}]};}";
        String pack = "export class Props {value: string;}export class Empty {}export component Text(props: Props): View;export component Column(props: Empty): View {children;}";
        for (String body : new String[]{"RowView(row: row)", "ForEach(row.children, child: app.Row, key: child.id) {RowView(row: child)}"}) {
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Column() {ForEach(state.rows, row: app.Row, key: row.id) {" + body + "}}}view RowView(row: app.Row): View {Again(row: row)}view Again(row: app.Row): View {ui.Text(value: row.id)}";
            var generated = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                    Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
            Files.writeString(project.resolve("app.deal"), logic + generated.augmentation());
            Files.writeString(project.resolve("entry.deal"), generated.source());
            Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
            if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
                throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
            Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=require('node:assert/strict'),s=e.bridgeInitialState.$f(),t=e.initial.$f(s,e.initialStore.$f()).tree;a.equal(t.children.length,2);a.notDeepEqual([t.children[0].structural,t.children[0].keyString],[t.children[1].structural,t.children[1].keyString]);s.rows.reverse();const n=e.initial.$f(s,e.initialStore.$f()).tree;a.deepEqual(n.children.map(x=>[x.structural,x.keyString]),t.children.map(x=>[x.structural,x.keyString]).reverse());", project.resolve("out/entry.js").toString()).inheritIO().start();
            if (node.waitFor() != 0) throw new AssertionError("Composed/nested iteration identity failed");
        }
        System.out.println("UiComposedIdentityTest: composed views and flattened nested loops retain stable repeated identity");
    }
}
