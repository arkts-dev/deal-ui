package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiDeterminismTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            String logic = "export class State { n: number; } export class Go {} export class Back {}\n"
                    + "export function initialState(): State {return {n: 0};}\n"
                    + "// @ui-update\nexport function go(state: State, action: Go): State {return state;}\n"
                    + "// @ui-update\nexport function back(state: State, action: Back): State {return state;}\n";
            String pack = "export class Props {a: Action; b: Action;} export component Button(props: Props): View {event a; event b;}";
            String view = "import * as app from \"./app\"; import * as ui from \"./pack\";\n// @ui-root\n"
                    + "export view Root(state: app.State): View {ui.Button(a: action app.Go {}, b: action app.Back {})}";
            var generated = UiSourceGenerator.generate(Path.of("view.dealui").toAbsolutePath(), view, Path.of("app.deal").toAbsolutePath(), logic,
                    Map.of("./pack", UiParser.parsePack(Path.of("pack"), pack)), Path.of("."));
            System.out.print(generated.source());
            return;
        }
        String first = null;
        for (int i = 0; i < 12; i++) {
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp", System.getProperty("java.class.path"), UiDeterminismTest.class.getName(), "child")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String source = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (child.waitFor() != 0) throw new AssertionError("Generation child failed");
            if (first == null) first = source;
            else if (!first.equals(source)) throw new AssertionError("Fresh JVM generation differs");
        }
        System.out.println("UiDeterminismTest: 12 fresh JVM outputs identical");
    }
}
