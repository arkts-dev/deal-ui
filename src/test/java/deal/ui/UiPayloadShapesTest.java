package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiPayloadShapesTest {
    public static void main(String[] args) throws Exception {
        for (String type : new String[]{"number[]", "boolean[]", "(number | null)[]", "number | null", "Record<string, string>", "app.Input", "host.Input"}) {
            Path project = Files.createTempDirectory("ui-payload-shape-");
            boolean nominal = type.endsWith(".Input");
            boolean external = type.equals("host.Input");
            String target = nominal ? "number" : type;
            String initial = nominal ? "0" : type.equals("number | null") ? "null" : type.startsWith("Record") ? "{}" : "[]";
            String logic = "export class State {value: " + target + ";}export class Input {nested: Nested;}export class Nested {n: number;}export class Go {value: " + target + ";}export function initialState(): State {return {value: " + initial + "};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {value: action.value};}";
            String pack = (external ? "import * as host from \"./host\";" : nominal ? "import * as app from \"./app\";" : "") + "export class Props {click: Action;}export component Button(props: Props): View {event click(payload: " + type + ");}";
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Button(click: action app.Go {value: " + (nominal ? "payload.nested.n" : "payload") + "})}";
            var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                    Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."),
                    external ? Map.of("./host", "export class Input {nested: Nested;}export class Nested {n: number;}") : Map.of());
            if (external) Files.writeString(project.resolve("host.deal"), "export class Input {nested: Nested;}export class Nested {n: number;}");
            Files.writeString(project.resolve("app.deal"), UiSourceGenerator.compilerSource(logic));
            Files.writeString(project.resolve("entry.deal"), result.source());
            Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
            if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
                throw new AssertionError(type + " " + Files.readString(project.resolve("diagnostics.json")));
            String json = nominal ? "{\"nested\":{\"n\":7}}" : type.equals("boolean[]") ? "[true,false]" : type.equals("number | null") ? "null" : type.startsWith("Record") ? "{\"label\":\"ok\"}" : "[1," + (type.contains("null") ? "null" : "2") + "]";
            Process node = new ProcessBuilder("node", "-e", "const fs=require('fs'),vm=require('vm'),e=require(process.argv[1]),rt=require(process.argv[1].replace('entry.js','deal/runtime.js'));vm.runInThisContext(fs.readFileSync('runtime-js/checkpoint.js','utf8'));vm.runInThisContext(fs.readFileSync('runtime-js/session.js','utf8'));const schema=require(process.argv[1].replace('entry.js','checkpoint-schema.js')),s=createDealUiSession(e,rt,null,()=>{},schema);s.dispatch(0,process.argv[2]);const a=require('node:assert/strict');a.throws(()=>s.dispatch(0,'{invalid'));a.equal(JSON.parse(s.snapshot()).fault,'');" + (type.startsWith("Record") ? "a.ok(s.checkpoint().includes('label'));a.ok(s.checkpoint().includes('ok'));" : "a.deepEqual(JSON.parse(s.state()).value," + (nominal ? "7" : "JSON.parse(process.argv[2])") + ");") + "", project.resolve("out/entry.js").toString(), json).inheritIO().start();
            if (node.waitFor() != 0) throw new AssertionError("Session payload execution failed: " + type);
        }
        System.out.println("UiPayloadShapesTest: actual session dispatch of arrays, nullable scalar, Record and nominal nested payloads");
    }
}
