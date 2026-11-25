package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Immutable UI boundaries, not a ban on mutable core DEAL or read-only builtins. */
public final class UiBorrowedValuesTest {
    private static int checks;
    private static final String PREFIX = "export class Item { n: number; }\n"
            + "export class State { xs: Item[]; ns: number[]; flag: boolean; raw: unknown; table: Record<string, unknown>; }\n"
            + "export class Go {}\n";
    private static String source(String body) {
        return PREFIX + "// @ui-update\nexport function update(state: State, action: Go): State { " + body + " return state; }\n";
    }
    private static void reject(String body) {
        try { new UiChecker().parseDeal(Path.of("borrow.deal"), source(body)); }
        catch (UiDiagnostic error) {
            if (!List.of("UI2050", "UI2051").contains(error.code())) throw error;
            checks++; return;
        }
        throw new AssertionError("Borrow escape admitted: " + body);
    }
    private static void accept(String body) {
        new UiChecker().parseDeal(Path.of("borrow.deal"), source(body)); checks++;
    }
    public static void main(String[] args) throws Exception {
        for (String body : List.of(
                "state.ns.push(9);", "state.ns.pop();", "state.ns.splice(0, 1);",
                "let xs = state.flag ? state.ns : state.ns; xs[0] = 9;",
                "let n = state.flag ? (state.ns[0] = 9) : 0;",
                "let xs = state.flag ? state.xs : []; xs[0].n = 99;",
                "let get = (): Item[] => state.xs; let xs = get(); xs[0].n = 99;",
                "let get = (): Item[] => state.xs; get()[0].n = 99;",
                "let item = state.xs.find((x: Item): boolean => true); (item as Item).n = 99;",
                "let xs = state.xs.slice(); xs[0].n = 99;",
                "let xs = state.xs.filter((x: Item): boolean => true); xs[0].n = 99;",
                "state.xs.some((x: Item): boolean => { x.n = 99; return true; });",
                "let xs: Item[] = []; xs.push(state.xs[0]); xs[0].n = 99;",
                "state.xs.toSorted((a: Item, b: Item): number => { b.n = 99; return 0; });",
                "state.ns.every((n: number, i: number, xs: number[]): boolean => { xs[0] = 99; return true; });")) reject(body);
        for (String body : List.of(
                "let n = Number.isFinite(state.raw);", "let n = Number.isNaN(state.raw);",
                "let keys = Object.keys(state.table); keys[0] = \"local\";",
                "let json = JSON.stringify(state.table);", "let text = String(state.ns.length);",
                "let xs = state.ns.slice(); xs[0] = 9;",
                "let xs: Item[] = []; xs.push({n: 1}); xs.pop(); xs.splice(0, 1);",
                "let n = state.ns.find((x: number): boolean => true);",
                "let found = state.xs.some((x: Item): boolean => x.n === 1);")) accept(body);
        String helper = PREFIX + "function alias(s: State): Item[] { return s.flag ? s.xs : []; }\n"
                + "// @ui-update\nexport function update(state: State, action: Go): State { let xs = alias(state); xs[0].n = 99; return state; }";
        try { new UiChecker().parseDeal(Path.of("helper.deal"), helper); throw new AssertionError("helper alias escaped"); }
        catch (UiDiagnostic error) { if (!error.code().equals("UI2050")) throw error; checks++; }

        // Compile and execute the safe reads against actual core DEAL, not just the UI analyzer.
        String body = "let finite = Number.isFinite(state.raw); let nan = Number.isNaN(state.raw); "
                + "let keys = Object.keys(state.table); let json = JSON.stringify(state.table); "
                + "let text = String(state.ns.length); let xs = state.ns.slice(); xs[0] = 9;";
        accept(body);
        Path project = Files.createTempDirectory("ui-borrow-values-");
        Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
        Files.writeString(project.resolve("main.deal"), UiSourceGenerator.compilerSource(source(body))
                + "export function proof(): number { let s: State = {xs: [{n: 1}], ns: [1], flag: true, raw: 1, table: {a: 1}}; "
                + "let next = update(s, {}); return next.ns[0]; }\nexport function main(): void { return; }\n");
        if (UiCheckpointCompiler.compile(project.resolve("main.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0)
            throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        Process node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]);require('node:assert/strict').equal(e.proof.$f(),1)",
                project.resolve("out/main.js").toString()).inheritIO().start();
        if (node.waitFor() != 0) throw new AssertionError("Safe borrowed reads execution failed");
        checks++;
        System.out.println("UiBorrowedValuesTest: " + checks + " checks passed");
    }
}
