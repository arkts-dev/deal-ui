package deal.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class UiWhenNarrowingTest {
    public static void main(String[] args) throws Exception {
        Path project = Files.createTempDirectory("ui-when-narrowing-");
        String logic = "export class Child {name: string;}export class State {child: Child | null; other: Child | null;}export function initialState(): State {return {child: {name: \"yes\"}, other: null};}";
        String pack = "export class Props {value: string;}export component Data(props: Props): View;";
        var packs = Map.of("./pack", UiParser.parsePack(project.resolve("pack"), pack));
        String header = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\nexport view Root(state: app.State): View {";
        for (String body : new String[]{
                "When(state.child !== null) {Helper(child: state.child)} Else {ui.Data(value: \"no\")}",
                "When(null === state.child) {ui.Data(value: \"no\")} Else {ui.Data(value: state.child.name)}",
                "When(!(state.child === null)) {ui.Data(value: state.child.name)} Else {ui.Data(value: \"no\")}",
                "When(state.child !== null && state.child.name === \"yes\") {ui.Data(value: state.child.name)} Else {ui.Data(value: \"no\")}",
                "When(state.child === null || state.child.name !== \"yes\") {ui.Data(value: \"no\")} Else {ui.Data(value: state.child.name)}"}) {
            String view = header + body + "}view Helper(child: app.Child): View {ui.Data(value: child.name)}";
            var generated = UiSourceGenerator.generate(project.resolve("view.dealui"), view, project.resolve("app.deal"), logic, packs, Path.of("."));
            Files.writeString(project.resolve("app.deal"), logic + generated.augmentation());
            Files.writeString(project.resolve("entry.deal"), generated.source());
            Files.writeString(project.resolve("deal.json"), "{\"languageVersion\":\"1.6\",\"moduleRoots\":[\".\"]}");
            if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0) throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
            var node = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),a=require('node:assert/strict'),s=e.bridgeInitialState.$f();a.equal(e.view_Root.$f('r',s).props[0].stringValue,'yes');s.child=null;a.equal(e.view_Root.$f('r',s).props[0].stringValue,'no');", project.resolve("out/entry.js").toString()).inheritIO().start();
            if (node.waitFor() != 0) throw new AssertionError("Branch execution failed");
        }
        for (String body : new String[]{"When(state.child !== null) {ui.Data(value: state.other.name)} Else {ui.Data(value: \"no\")}",
                "When(state.child === null) {ui.Data(value: state.child.name)} Else {ui.Data(value: \"no\")}",
                "When(state.child !== null || state.other !== null) {ui.Data(value: state.child.name)} Else {ui.Data(value: \"no\")}"}) {
            try { UiSourceGenerator.generate(project.resolve("view.dealui"), header + body + "}", project.resolve("app.deal"), logic, packs, Path.of(".")); throw new AssertionError("Unsafe branch admitted"); }
            catch (UiDiagnostic expected) { if (!expected.code().equals("UI2022")) throw expected; }
        }
        String actionLogic = "export class Row {id: number;}export class Child {name: string; rows: Row[];}export class State {child: Child | null;}export class Go {name: string;}export function initialState(): State {return {child: {name: \"yes\", rows: [{id: 1}]}};}\n// @ui-update\nexport function go(state: State, action: Go): State {return {child: null};}";
        String actionPack = "export class Props {value: string; click: Action;}export class Empty {}export component Button(props: Props): View {event click;}export component Column(props: Empty): View {children;}";
        String actionView = header + "When(state.child !== null) {ui.Column() {ForEach(state.child.rows, row: app.Row, key: row.id) {ui.Button(value: state.child.name, click: action app.Go {name: state.child.name})}}} Else {ui.Column()}}";
        var actionGenerated = UiSourceGenerator.generate(project.resolve("view.dealui"), actionView, project.resolve("app.deal"), actionLogic,
                Map.of("./pack", UiParser.parsePack(project.resolve("pack"), actionPack)), Path.of("."));
        Files.writeString(project.resolve("app.deal"), UiSourceGenerator.compilerSource(actionLogic) + actionGenerated.augmentation());
        Files.writeString(project.resolve("entry.deal"), actionGenerated.source());
        if (UiCheckpointCompiler.compile(project.resolve("entry.deal"), project.resolve("out"), project.resolve("diagnostics.json")) != 0) throw new AssertionError(Files.readString(project.resolve("diagnostics.json")));
        var actionNode = new ProcessBuilder("node", "-e", "const e=require(process.argv[1]),s=e.bridgeInitialState.$f(),p=e.view_Root.$f('r',s).children[0].props.find(p=>p.kind==='action');require('node:assert/strict').equal(e.nextState.$f(s,e['action_'+p.actionSlot].$f(p.actionBindings)).child,null);", project.resolve("out/entry.js").toString()).inheritIO().start();
        if (actionNode.waitFor() != 0) throw new AssertionError("Guarded iteration/captured action failed");
        Path jvm = Files.createTempDirectory("ui-when-jvm-");
        Files.writeString(jvm.resolve("app.deal"), actionLogic);
        Files.writeString(jvm.resolve("pack.dealui-pack"), actionPack.replace("event click;", "event click; capability \"renderer.test.button\";").replace("{children;}", "{children; capability \"renderer.test.column\";}"));
        Files.writeString(jvm.resolve("view.dealui"), actionView.replace("./pack\"", "./pack.dealui-pack\""));
        var compiler = new UiCompiler(Path.of(System.getProperty("deal.home")), Path.of(".").toAbsolutePath());
        var compiled = compiler.compile(jvm.resolve("view.dealui"), jvm.resolve("output"), UiCompiler.Target.PORTABLE);
        compiler.build(compiled, Path.of(UiPortableBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{compiled.outputDirectory().resolve("classes").toUri().toURL()}, UiWhenNarrowingTest.class.getClassLoader())) {
            var bridge = (UiPortableBridge)Class.forName(compiled.bridgeClass(), true, loader).getConstructor().newInstance();
            var initial = bridge.initial(bridge.initialState(), bridge.initialStore());
            int slot = initial.tree().children().getFirst().props().get("click").actionSlot();
            var changed = bridge.transition(initial.state(), initial.tree(), initial.store(), bridge.action(slot, null));
            if (!changed.tree().children().isEmpty()) throw new AssertionError("JVM false branch failed");
        }
        // An optional read still admits undefined after excluding null.
        try { UiSourceGenerator.generate(project.resolve("view.dealui"), header + "When(state.child !== null) {ui.Data(value: state.child.name)} Else {ui.Data(value: \"no\")}}", project.resolve("app.deal"), logic.replace("child: Child | null;", "child?: Child | null;"), packs, Path.of(".")); throw new AssertionError("Undefined erased by null guard"); }
        catch (UiDiagnostic expected) { if (!expected.code().equals("UI2022")) throw expected; }
        System.out.println("UiWhenNarrowingTest: true/false/negated/logical guards execute; unrelated/null/unsafe join reject");
    }
}
