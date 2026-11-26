package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiScalarTokensTest {
    private UiScalarTokensTest() {}
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ui-scalar-tokens-");
        String pack = "export token n: number = 7;export token text: string = \"hello\";export token alias: string = text;export token ns: number[] = [n, 8];export class Props {n: number; text: string; ns: number[];}export component Data(props: Props): View;";
        String logic = "export class State {}export function initialState(): State {return {}; }";
        var result = UiSourceGenerator.generate(root.resolve("view.dealui"), "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {ui.Data(n: ui.n, text: ui.alias, ns: ui.ns)}", root.resolve("app.deal"), logic, Map.of("./pack", UiParser.parsePack(root.resolve("pack"), pack)), Path.of("."));
        Files.writeString(root.resolve("app.deal"), logic + result.augmentation());
        Files.writeString(root.resolve("entry.deal"), result.source());
        Files.writeString(root.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(root.resolve("entry.deal"), root.resolve("out"), root.resolve("diagnostics.json")) != 0) throw new AssertionError(Files.readString(root.resolve("diagnostics.json")));
        var node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=require('node:assert/strict'),p=e.view_Root.$f('r',e.bridgeInitialState.$f()).props;a.equal(p[0].numberValue,7);a.equal(p[1].stringValue,'hello');a.deepEqual(JSON.parse(p[2].stringValue),[7,8]);", root.resolve("out/entry.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Token values lost");
        System.out.println("UiScalarTokensTest: actual scalar token and token-reference rendering");
    }
}
