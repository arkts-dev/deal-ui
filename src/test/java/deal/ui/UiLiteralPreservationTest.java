package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiLiteralPreservationTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-literal-preservation-");
        String text = "store. core. actions. effects. reconcile. store.finish actions.route export ";
        String logic = "export class State {} export function initialState(): State {return {};}";
        String pack = "export class Props {value: string;} export component Text(props: Props): View;";
        String view = "import * as app from \"./app\"; import * as ui from \"./pack\";\n// @ui-root\n"
                + "export view Root(state: app.State): View {ui.Text(value: \"" + text + "\")}";
        var generated = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack)), Path.of("."));
        if (!generated.source().contains("\"" + text + "\"")) throw new AssertionError("Literal rewritten");
        Files.writeString(project.resolve("app.deal"), logic);
        Files.writeString(project.resolve("entry.deal"), generated.source());
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]);require('node:assert/strict').equal(e.view_Root.$f('root',e.bridgeInitialState.$f()).props[0].stringValue,process.argv[2]);",
                project.resolve("out/entry.js").toString(), text).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Literal execution failed");
        System.out.println("UiLiteralPreservationTest: generated and executed strings unchanged");
    }
}
