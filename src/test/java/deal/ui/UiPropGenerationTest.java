package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiPropGenerationTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-prop-generation-");
        String logic = "export class State {value: number | null;}export function initialState(): State {return {value: null};}";
        String pack = "export class Props {value: number | null; text: string;}export component Data(props: Props): View;";
        String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Data(value: state.value, text: \"a\\rb\\t\\u0000\\uD800\")}";
        var packs = Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack));
        var result = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic, packs, Path.of("."));
        Files.writeString(project.resolve("app.deal"), logic);
        Files.writeString(project.resolve("entry.deal"), result.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=require('node:assert/strict'),s=e.bridgeInitialState.$f(),p=e.view_Root.$f('r',s).props;a.equal(p[0].kind,'json');a.equal(p[0].stringValue,'null');a.equal(p[1].stringValue,'a\\rb\\t\\u0000\\uD800');s.value=2;a.equal(e.view_Root.$f('r',s).props[0].stringValue,'2');", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Nullable prop/string execution failed");
        for (String type : new String[]{"number | undefined", "(number | undefined)[]"}) {
            String otherLogic = "export class State {value: " + type + ";}";
            var otherPacks = Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack.replace("number | null", type)));
            try { UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), otherLogic, otherPacks, Path.of(".")); throw new AssertionError("Lossy undefined transport admitted"); }
            catch (UiDiagnostic expected) { if (!expected.code().equals("UI2053")) throw expected; }
        }
        System.out.println("UiPropGenerationTest: nullable scalars/UTF-16 strings render faithfully; unrepresentable undefined rejects");
    }
}
