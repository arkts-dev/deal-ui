package deal.ui;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UiFrameworkTest {
    private static int passed;
    private UiFrameworkTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path outputs = root.resolve("build/test-outputs");
        recreate(outputs);
        UiCompiler compiler = new UiCompiler(fsRoot());
        UiCompiler.Result result = compiler.compile(root.resolve("examples/museum/gallery.dealui"), outputs.resolve("gallery"));
        compiler.build(result, runtimeClasses());
        String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
        check(generated.contains("function view_Gallery"), ".dealui lowers to DEAL view functions");
        check(generated.contains("function nextState"), "closed transitions are generated in DEAL");
        check(generated.contains("function plan"), "DEAL reconciliation is compiled into the application");
        check(!Files.readString(root.resolve("src/main/java/deal/ui/UiProgramRuntime.java")).contains("java.lang.reflect"), "runtime has no reflection");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new ChildFirstLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            UiBridge bridge = Main.bridge(result, loader);
            try (UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings())) {
                check(runtime.stateSnapshot().get("title").equals("Gallery"), "DEAL supplies initial state");
                check(texts(runtime.tree()).containsAll(List.of("Gallery", "A focused collection for thoughtful discovery", "FEATURED WORK", "0", "Details are hidden", "The Starry Night")), "DEAL-generated tree evaluates composition and control flow");
                check(!runtime.tree().children().get(0).children().get(0).identity().equals(runtime.tree().children().get(0).children().get(1).identity()), "custom view identities include call sites");
                JComponent component = runtime.renderer().componentForTesting(runtime.tree());
                JComponent firstItem = named(component, "ui.Text", "The Starry Night");
                JTextField input = input(component);
                input.setText("Modern Gallery");
                input.postActionEvent();
                runtime.awaitActions();
                check(runtime.stateSnapshot().get("title").equals("Modern Gallery"), "payload action is constructed by generated DEAL");
                button(runtime.renderer().componentForTesting(runtime.tree()), "Toggle details").doClick();
                runtime.awaitActions();
                check(runtime.stateSnapshot().get("expanded").equals(true), "closed DEAL dispatch commits update");
                JComponent second = runtime.renderer().componentForTesting(runtime.tree());
                check(firstItem != null && named(second, "ui.Text", "The Starry Night") != null, "keyed content remains equivalent after atomic replacement");
                check(named(second, "ui.Text", "Details are visible") != null, "DEAL create operations materialize new subtrees");
                button(second, "Increment").doClick();
                button(second, "Increment").doClick();
                runtime.awaitIdle();
                check(((Long) runtime.stateSnapshot().get("count")) >= 1L, "overlapping post-commit effects return through the DEAL queue");
                check(runtime.renderer().lastApplyOnEdt(), "patches apply on EDT");
                long disposed = runtime.renderer().disposedComponents();
                button(runtime.renderer().componentForTesting(runtime.tree()), "Toggle details").doClick();
                runtime.awaitActions();
                check(runtime.renderer().disposedComponents() > disposed, "lost structural identity disposes retained resources");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        expect("UI2037", source(root).replace("view Header(title: string): View {\n  ui.Text(value: title)\n}", "view Header(title: string): View {}"));
        expect("UI2037", source(root).replace("  ui.Card() {", "  ui.Text(value: state.title)\n  ui.Card() {"));
        expect("UI2037", source(root).replace("view Header(title: string): View {\n  ui.Text(value: title)\n}", "view Header(title: string): View { When(true) { ForEach(state.items, item: app.Item, key: item.id) { ui.Text(value: item.title) } } Else { ui.Text(value: title) } }"));
        expect("UI2012", source(root).replace("ui.Card()", "ui.Unknown()"));
        expect("UI2029", source(root).replace("ui.Text(value: title)", "ui.Text(label: title)"));
        expect("UI2031", source(root).replace("ui.IntText(value: state.count)", "ui.IntText(value: state.title)"));
        expect("UI2015", source(root).replace("key: item.id", "key: item"));
        expect("UI2005", source(root), deal(root).replace("// @ui-update\nexport function toggleDetails", "export function toggleDetails"));
        borrowedHandlerContracts(root);
        typedChildrenContracts(root);
        compilePolicyOnlyCancellation(root, outputs, compiler);
        expect("UI2040", source(root), deal(root) + "\nexport class EffectCommand { operation: string = \"start\"; key: string = \"test\"; delayMillis: int = 0; cancellationMode: string = \"replace\"; }\n// @ui-effect-policy\nexport function orphanPolicy(state: GalleryState, action: ToggleDetails): EffectCommand { return { operation: \"start\" }; }\n");
        expectPack("UI2026", pack(root).replace("spacing?: Space;", "spacing: Space = missing.token;"));
        expect("UI2013", source(root).replace("spacing: ui.spaceMd", "spacing: null"));
        typedPayloadContracts(root, outputs, compiler);
        dependencyStaging(root, outputs);
        computedPropLowering(outputs, compiler);
        storageHostCompilation(outputs, compiler);
        portableBridge(root, outputs, compiler);
        compileSemanticExamples(root, outputs, compiler);
        System.out.println("Passed: " + passed);
    }

    private static void compilePolicyOnlyCancellation(Path root, Path outputs, UiCompiler compiler) throws Exception {
        Path fixture = outputs.resolve("policy-only");
        Files.createDirectories(fixture);
        Path logic = fixture.resolve("policy-only.deal");
        Path pack = fixture.resolve("policy-only.dealui-pack");
        Path view = fixture.resolve("policy-only.dealui");
        Files.writeString(logic, "export class State { status: string = \"idle\"; }\nexport class Cancel {}\nexport class EffectCommand { operation: string = \"none\"; key: string = \"work\"; delayMillis: int = 0; cancellationMode: string = \"none\"; }\nexport function initialState(): State { return {}; }\n// @ui-update\nexport function cancel(state: State, action: Cancel): State { return { status: \"cancelled\" }; }\n// @ui-effect-policy\nexport function cancelPolicy(state: State, action: Cancel): EffectCommand { return { operation: \"cancel\", cancellationMode: \"interrupt\" }; }\nexport function main(): null { return null; }\n");
        Files.writeString(pack, "export class Props { text: string = \"\"; onClick?: Action; }\nexport component Button(props: Props): View { event onClick; capability \"renderer.swing.button\"; }\n");
        Files.writeString(view, "import * as app from \"./policy-only\";\nimport * as ui from \"./policy-only.dealui-pack\";\n// @ui-root\nexport view PolicyOnly(state: app.State): View { ui.Button(text: \"Cancel\", onClick: action app.Cancel {}) }\n");
        UiCompiler.Result result = compiler.compile(view, outputs.resolve("policy-only-output"));
        compiler.build(result, runtimeClasses());
        String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
        check(generated.contains("app.cancelPolicy") && !generated.contains("app.cancel(state, action"), "policy-only cancellation is generated without an effect body");
    }

    private static void portableBridge(Path root, Path outputs, UiCompiler compiler) throws Exception {
        UiCompiler.Result result = compiler.compile(
            root.resolve("examples/museum/gallery.dealui"),
            outputs.resolve("gallery-portable"),
            UiCompiler.Target.PORTABLE
        );
        compiler.build(result, runtimeClasses());
        String generated = Files.readString(result.generatedDirectory().resolve(result.bridgeClass() + ".java"));
        check(!generated.contains("java.awt") && !generated.contains("UiRendererBindings") && !generated.contains("rendererBindings"), "portable bridge has no Swing or AWT dependency");
        try (URLClassLoader loader = new ChildFirstLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            UiPortableBridge bridge = (UiPortableBridge) Class.forName(result.bridgeClass(), true, loader).getConstructor().newInstance();
            check(bridge.componentCapabilities().get("ui.Button").equals("renderer.swing.button"), "portable bridge exposes checked component capabilities without constructing native bindings");
            UiPortableBridge.CheckedMetadata metadata = bridge.checkedMetadata();
            check(metadata.rootStateType().equals("GalleryState") && metadata.reachableInputActions().contains("ToggleDetails") && metadata.usedComponents().contains("ui.Button"), "portable bridge exposes compiler-owned root, action, and component metadata");
            check(metadata.packDigests().values().stream().allMatch(value -> value.matches("[0-9a-f]{64}")), "portable bridge exposes deterministic component-pack digests");
            UiPortableBridge.StateValue state = bridge.initialState();
            UiPortableBridge.StoreValue store = bridge.initialStore();
            UiPortableBridge.Transition initial = bridge.initial(state, store);
            check(texts(initial.tree()).contains("Gallery"), "portable bridge evaluates the same DEAL-owned view tree");
            UiPortableBridge.Node button = nodeWithText(initial.tree(), "Toggle details");
            UiPortableBridge.Prop onClick = button.props().get("onClick");
            UiPortableBridge.Transition changed = bridge.transition(initial.state(), initial.tree(), initial.store(), bridge.action(onClick.actionSlot(), null));
            check(bridge.stateSnapshot(changed.state()).get("expanded").equals(true), "portable component event enters the generated typed DEAL update");
        }
    }

    private static void computedPropLowering(Path outputs, UiCompiler compiler) throws Exception {
        Path fixture = outputs.resolve("computed-props");
        Files.createDirectories(fixture);
        Path logic = fixture.resolve("computed.deal");
        Path pack = fixture.resolve("computed.dealui-pack");
        Path view = fixture.resolve("computed.dealui");
        Files.writeString(logic, "export class State { value: int = 2; active: boolean = true; }\nexport class Update {}\nexport function initialState(): State { return {}; }\n// @ui-update\nexport function update(state: State, action: Update): State { return state; }\nexport function main(): null { return null; }\n");
        Files.writeString(pack, "export class Props { column: int = 0; visible: boolean = false; scale: number = 1.0; onClick?: Action; }\nexport component Sprite(props: Props): View { event onClick; capability \"renderer.scene.sprite\"; }\n");
        Files.writeString(view, "import * as app from \"./computed\";\nimport * as ui from \"./computed.dealui-pack\";\n// @ui-root\nexport view Computed(state: app.State): View { ui.Sprite(column: state.value - 1, visible: state.active && state.value > 0, scale: 1.0, onClick: action app.Update {}) }\n");
        UiCompiler.Result result = compiler.compile(view, outputs.resolve("computed-props-output"), UiCompiler.Target.PORTABLE);
        compiler.build(result, runtimeClasses());
        String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
        check(generated.contains("intProp(\"column\", (state.value - 1))"), "computed integer props lower with their checked type");
        check(generated.contains("booleanProp(\"visible\", (state.active && (state.value > 0)))"), "computed boolean props lower with their checked type");
    }

    private static void storageHostCompilation(Path outputs, UiCompiler compiler) throws Exception {
        Path fixture = outputs.resolve("storage-host");
        Files.createDirectories(fixture);
        Path logic = fixture.resolve("storage.deal");
        Path pack = fixture.resolve("storage.dealui-pack");
        Path view = fixture.resolve("storage.dealui");
        Files.writeString(logic, "import * as storage from \"host/storage\";\nexport class State { value: string = \"ready\"; }\nexport class Save {}\nexport class Saved { value: string = \"\"; }\nexport class EffectCommand { operation: string = \"start\"; key: string = \"save\"; delayMillis: int = 0; cancellationMode: string = \"replace\"; }\nexport function initialState(): State { return {}; }\n// @ui-update\nexport function request(state: State, action: Save): State { return state; }\n// @ui-effect\nexport async function persist(state: State, action: Save): Saved { let value: string = await storage.save(\"key\", state.value); return { value: value }; }\n// @ui-update\nexport function complete(state: State, action: Saved): State { return { value: action.value }; }\nexport function main(): null { return null; }\n");
        Files.writeString(pack, "export class Props { text: string = \"\"; onClick?: Action; }\nexport component Button(props: Props): View { event onClick; capability \"renderer.swing.button\"; }\n");
        Files.writeString(view, "import * as app from \"./storage\";\nimport * as ui from \"./storage.dealui-pack\";\n// @ui-root\nexport view Storage(state: app.State): View { ui.Button(text: state.value, onClick: action app.Save {}) }\n");
        UiCompiler.Result result = compiler.compile(view, outputs.resolve("storage-host-output"), UiCompiler.Target.PORTABLE);
        compiler.build(result, runtimeClasses());
        String generated = Files.readString(result.generatedDirectory().resolve("ApplicationStorage.java"));
        check(generated.contains("Class.forName(\"HostStorage\")") && generated.contains("__host$storage$save"), "typed host/storage effects compile through the portable Deal UI pipeline");
        check(Files.isRegularFile(result.outputDirectory().resolve("bindings/storage.d.deal")) && Files.readString(result.outputDirectory().resolve("deal.json")).contains("\"host/storage\""), "storage declaration and external binding are staged deterministically");
    }

    private static void compileSemanticExamples(Path root, Path outputs, UiCompiler compiler) throws Exception {
        for (String name : List.of("checkout", "search-mail", "kanban", "dashboard")) {
            UiCompiler.Result result = compiler.compile(root.resolve("examples").resolve(name).resolve(name + ".dealui"), outputs.resolve(name));
            compiler.build(result, runtimeClasses());
            String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
            check(generated.contains("function completionActive") && !generated.contains("class NavigationDecision") && !generated.contains("class RequestPolicy") && !generated.contains("class WindowPolicy") && !generated.contains("class OptimisticPolicy") && !generated.contains("class OverlayPolicy"), name + " compiles with only genuinely shared interaction policy");
            exerciseSemanticExample(result, name);
        }
    }

    private static void exerciseSemanticExample(UiCompiler.Result result, String name) throws Exception {
        try (URLClassLoader loader = new ChildFirstLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            UiBridge bridge = Main.bridge(result, loader);
            try (UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings())) {
                switch (name) {
                    case "checkout" -> exerciseCheckout(runtime, bridge);
                    case "search-mail" -> exerciseSearchMail(runtime, bridge);
                    case "kanban" -> exerciseKanban(runtime, bridge);
                    case "dashboard" -> {
                        runtime.renderer().componentForTesting(runtime.tree());
                        check(runtime.renderer().requestedFocusIdForTesting().equals("open-settings"), "dashboard root applies DEAL focus intent");
                        runtime.dispatch(action(runtime.tree(), "Open settings", bridge, null));
                        runtime.awaitIdle();
                        check(runtime.stateSnapshot().get("subscribedScope").equals(1L), "dashboard overlay opens scoped subscription and focus lifecycle");
                        check(runtime.renderer().modalVisibleForTesting() && !runtime.renderer().underlyingEnabledForTesting(), "dashboard modal uses a blocking Swing layer");
                        check(runtime.renderer().escapeBoundForTesting(), "dashboard modal installs a real Escape InputMap action");
                        JComponent modal = runtime.renderer().modalForTesting();
                        check(modal.getAccessibleContext().getAccessibleRole().equals(javax.accessibility.AccessibleRole.DIALOG) && modal.getAccessibleContext().getAccessibleName().equals("Settings") && modal.getAccessibleContext().getAccessibleDescription().equals("Dashboard settings dialog"), "dashboard modal exposes dialog accessibility metadata");
                        check(runtime.renderer().requestedFocusIdForTesting().equals("settings-name") && runtime.renderer().scopeTimerRunningForTesting(), "dashboard modal applies focus and owns its scoped timer");
                        long activeScope = (Long) runtime.stateSnapshot().get("scopeRevision");
                        runtime.dispatch(action(runtime.tree(), "onScopeTick", bridge, activeScope + 1));
                        runtime.awaitActions();
                        check(runtime.stateSnapshot().get("announcement").equals("Settings dialog opened"), "dashboard rejects stale scoped completion while open");
                        runtime.renderer().fireScopeTimerForTesting();
                        runtime.awaitActions();
                        check(runtime.stateSnapshot().get("announcement").equals("Settings refreshed"), "dashboard accepts matching scoped completion");
                        runtime.renderer().pressEscapeForTesting();
                        runtime.awaitActions();
                        check(!runtime.renderer().modalVisibleForTesting() && runtime.renderer().underlyingEnabledForTesting() && !runtime.renderer().scopeTimerRunningForTesting(), "Escape closes modal and cancels its timer");
                        check(runtime.stateSnapshot().get("command").equals("Escape") && runtime.stateSnapshot().get("scopeRevision").equals(activeScope + 1), "dashboard Escape advances scope and records command semantics");
                        check(runtime.renderer().requestedFocusIdForTesting().equals("open-settings"), "dashboard close restores DEAL focus intent");
                        runtime.dispatch(action(runtime.tree(), "Open settings", bridge, null));
                        runtime.awaitActions();
                        check(runtime.stateSnapshot().get("scopeRevision").equals(activeScope + 2) && runtime.renderer().requestedFocusIdForTesting().equals("settings-name"), "dashboard reopen creates a fresh scope and focus revision");
                        runtime.renderer().close();
                        check(!runtime.renderer().scopeTimerRunningForTesting(), "dashboard host close cancels its scoped timer");
                    }
                    default -> throw new IllegalArgumentException(name);
                }
            }
        }
    }

    private static void exerciseCheckout(UiProgramRuntime runtime, UiBridge bridge) throws Exception {
        JComponent component = runtime.renderer().componentForTesting(runtime.tree());
        JTextField initialEmail = input(component);
        javax.swing.SwingUtilities.invokeAndWait(() -> initialEmail.setText("invalid"));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("dirty").equals(true) && runtime.stateSnapshot().get("touched").equals(false) && runtime.stateSnapshot().get("valid").equals(false), "checkout change marks malformed email dirty without touching it");
        javax.swing.SwingUtilities.invokeAndWait(() -> { for (var listener : initialEmail.getFocusListeners()) listener.focusLost(new java.awt.event.FocusEvent(initialEmail, java.awt.event.FocusEvent.FOCUS_LOST, false)); });
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("touched").equals(true) && texts(runtime.tree()).contains("Enter an email such as name@example.com"), "checkout blur touches and exposes validation");
        runtime.dispatch(action(runtime.tree(), "Continue to payment", bridge, null));
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("route").equals("checkout/contact") && runtime.stateSnapshot().get("guardMessage").equals("Resolve invalid fields before continuing"), "checkout guard blocks invalid continuation");
        runtime.dispatch(action(runtime.tree(), "Leave checkout", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("leavePending").equals(true) && runtime.stateSnapshot().get("route").equals("checkout/contact"), "checkout requests confirmation for dirty leave");
        runtime.dispatch(action(runtime.tree(), "Stay in checkout", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("leavePending").equals(false), "checkout cancellation retains route");
        JTextField corrected = input(runtime.renderer().componentForTesting(runtime.tree()));
        javax.swing.SwingUtilities.invokeAndWait(() -> corrected.setText("user@example.com"));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("email").equals("user@example.com") && runtime.stateSnapshot().get("valid").equals(true) && runtime.stateSnapshot().get("touched").equals(true), "checkout accepts valid correction without Enter and preserves touched state");
        runtime.dispatch(action(runtime.tree(), "Continue to payment", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("route").equals("checkout/payment"), "checkout permits valid nested navigation");
        runtime.dispatch(action(runtime.tree(), "Back", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("route").equals("checkout/contact"), "checkout back returns from payment to contact");
        runtime.dispatch(action(runtime.tree(), "Back", bridge, null));
        runtime.awaitActions();
        runtime.dispatch(action(runtime.tree(), "Discard and leave", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("route").equals("catalog") && runtime.stateSnapshot().get("leavePending").equals(false), "checkout confirmed dirty leave exits to parent destination");
    }

    private static void exerciseSearchMail(UiProgramRuntime runtime, UiBridge bridge) throws Exception {
        runtime.dispatch(action(runtime.tree(), "onSubmit", bridge, "policy"));
        runtime.awaitActions();
        long firstGeneration = (Long) runtime.stateSnapshot().get("activeGeneration");
        check(runtime.stateSnapshot().get("status").equals("debouncing") && runtime.stateSnapshot().get("searchesStarted").equals(0L) && texts(runtime.tree()).contains("Waiting to search"), "search exposes debounce before loading or search execution");
        runtime.dispatch(action(runtime.tree(), "onSubmit", bridge, "renderer"));
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("generation").equals(firstGeneration + 1) && runtime.stateSnapshot().get("activeGeneration").equals(-1L) && runtime.stateSnapshot().get("searchesStarted").equals(1L), "search replacement suppresses stale debounce and completes only the active generation");
        check(runtime.stateSnapshot().get("result").equals("1 message matches renderer") && texts(runtime.tree()).containsAll(List.of("Renderer release", "release@deal.dev")) && !texts(runtime.tree()).contains("Portable policy review"), "search matches actual mailbox records");
        runtime.dispatch(action(runtime.tree(), "onSubmit", bridge, "  ReNdErEr  "));
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("query").equals("renderer") && runtime.stateSnapshot().get("result").equals("1 message matches renderer"), "search normalizes surrounding whitespace and case");
        runtime.dispatch(action(runtime.tree(), "onSubmit", bridge, "policy"));
        runtime.awaitActions();
        long beforeCancel = (Long) runtime.stateSnapshot().get("searchesStarted");
        runtime.dispatch(action(runtime.tree(), "Cancel", bridge, null));
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("status").equals("cancelled") && runtime.stateSnapshot().get("activeGeneration").equals(-1L) && runtime.stateSnapshot().get("searchesStarted").equals(beforeCancel), "policy-only Cancel retires keyed debounce before its effect body executes");
        runtime.dispatch(action(runtime.tree(), "onSubmit", bridge, "fail"));
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("status").equals("error") && runtime.stateSnapshot().get("errorMessage").equals("Local search failed for 'fail'") && texts(runtime.tree()).contains("Local search failed for 'fail'"), "throwing search effect reaches typed error UI");
        long failedGeneration = (Long) runtime.stateSnapshot().get("generation");
        runtime.dispatch(action(runtime.tree(), "Retry", bridge, null));
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("status").equals("ready") && runtime.stateSnapshot().get("generation").equals(failedGeneration + 1) && runtime.stateSnapshot().get("result").equals("No messages match fail"), "Retry starts a fresh generation with defined successful local retry behavior");
    }

    private static void exerciseKanban(UiProgramRuntime runtime, UiBridge bridge) throws Exception {
        check(texts(runtime.tree()).containsAll(List.of("Ship portable policy", "Review renderer")) && !texts(runtime.tree()).contains("Verify rollback"), "kanban initial window contains exactly the first page");
        runtime.renderer().componentForTesting(runtime.tree());
        check(runtime.renderer().requestedFocusIdForTesting().equals("card-1") && focusIds(runtime.tree()).containsAll(List.of("card-1", "card-2", "card-1-doing", "card-2-done")), "kanban root and per-card controls expose the active focus domain: requested=" + runtime.renderer().requestedFocusIdForTesting() + ", ids=" + focusIds(runtime.tree()));
        JComponent retained = runtime.renderer().componentForTesting(nodeWithFocus(runtime.tree(), "card-2"));
        runtime.dispatch(action(runtime.tree(), "Select Review renderer", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("selectedId").equals(2L) && runtime.stateSnapshot().get("focusRevision").equals(1L) && runtime.renderer().requestedFocusIdForTesting().equals("card-2") && texts(runtime.tree()).contains("Selected"), "kanban selects a non-first keyed card and focuses its control");
        check(runtime.renderer().componentForTesting(nodeWithFocus(runtime.tree(), "card-2")) == retained, "kanban selection retains keyed native card-control identity");
        runtime.dispatch(action(runtime.tree(), "Move Review renderer to done", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("revision").equals(1L) && runtime.stateSnapshot().get("pendingRevision").equals(1L) && runtime.stateSnapshot().get("focusRevision").equals(2L), "kanban begins an optimistic revision for the selected non-first card");
        check(cardColumn(runtime.tree(), "Review renderer").equals("done") && runtime.renderer().requestedFocusIdForTesting().equals("card-2-done"), "kanban renders and focuses the selected destination action optimistically");
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("pendingRevision").equals(0L) && runtime.stateSnapshot().get("focusRevision").equals(3L) && runtime.renderer().requestedFocusIdForTesting().equals("card-2") && cardColumn(runtime.tree(), "Review renderer").equals("done"), "kanban commits and returns focus to the selected card");
        runtime.dispatch(action(runtime.tree(), "Next page", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("pageOffset").equals(2L) && runtime.stateSnapshot().get("focusRevision").equals(4L) && runtime.renderer().requestedFocusIdForTesting().equals("card-3") && texts(runtime.tree()).containsAll(List.of("Verify rollback", "Publish completion")), "kanban advances to and focuses the next complete window");
        runtime.dispatch(action(runtime.tree(), "Select Verify rollback", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("selectedId").equals(3L) && runtime.renderer().requestedFocusIdForTesting().equals("card-3"), "kanban selects a card from a later window");
        runtime.dispatch(action(runtime.tree(), "Try denied archive move for Verify rollback", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("pendingRevision").equals(2L) && cardColumn(runtime.tree(), "Verify rollback").equals("archive") && runtime.renderer().requestedFocusIdForTesting().equals("card-3-archive"), "kanban exposes a denied optimistic move for a later card");
        runtime.awaitIdle();
        check(runtime.stateSnapshot().get("revision").equals(1L) && runtime.stateSnapshot().get("pendingRevision").equals(0L) && runtime.stateSnapshot().get("rollbackRevision").equals(1L) && runtime.stateSnapshot().get("focusRevision").equals(7L), "kanban rolls back denied destination completion and advances focus revision");
        check(cardColumn(runtime.tree(), "Verify rollback").equals("todo") && runtime.renderer().requestedFocusIdForTesting().equals("card-3"), "kanban rollback restores the later card and its focus");
        runtime.dispatch(action(runtime.tree(), "Next page", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("pageOffset").equals(2L) && runtime.renderer().requestedFocusIdForTesting().equals("card-3"), "kanban clamps and focuses within the next boundary");
        runtime.dispatch(action(runtime.tree(), "Previous page", bridge, null));
        runtime.awaitActions();
        check(runtime.stateSnapshot().get("pageOffset").equals(0L) && runtime.renderer().requestedFocusIdForTesting().equals("card-1"), "kanban returns focus to an ID present in the first window");
    }

    private static String cardColumn(UiBridge.Node node, String title) {
        List<String> values = texts(node);
        int index = values.indexOf(title);
        if (index < 0 || index + 1 >= values.size()) throw new IllegalArgumentException("Card not found: " + title);
        return values.get(index + 1);
    }

    private static List<String> focusIds(UiBridge.Node node) {
        List<String> result = new ArrayList<>();
        UiBridge.Prop focus = node.props().get("focusId");
        if (focus != null) result.add(String.valueOf(focus.value()));
        for (UiBridge.Node child : node.children()) result.addAll(focusIds(child));
        return result;
    }

    private static UiBridge.Node nodeWithFocus(UiBridge.Node node, String focusId) {
        UiBridge.Prop focus = node.props().get("focusId");
        if (focus != null && focus.value().equals(focusId)) return node;
        for (UiBridge.Node child : node.children()) {
            try { return nodeWithFocus(child, focusId); } catch (IllegalArgumentException ignored) {}
        }
        throw new IllegalArgumentException("Focus ID not found: " + focusId);
    }

    private static UiBridge.ActionValue action(UiBridge.Node node, String textOrProp, UiBridge bridge, Object payload) {
        UiBridge.Prop direct = node.props().get(textOrProp);
        if (direct != null && direct.actionSlot() >= 0) return bridge.action(direct.actionSlot(), payload);
        UiBridge.Prop text = node.props().get("text");
        if (text != null && text.value().equals(textOrProp)) for (UiBridge.Prop prop : node.props().values()) if (prop.actionSlot() >= 0) { UiBridge.Prop actionPayload = node.props().get("actionPayload"); return bridge.action(prop.actionSlot(), payload == null && actionPayload != null ? actionPayload.value() : payload); }
        for (UiBridge.Node child : node.children()) { try { return action(child, textOrProp, bridge, payload); } catch (IllegalArgumentException ignored) {} }
        throw new IllegalArgumentException("Action not found: " + textOrProp);
    }

    private static void typedPayloadContracts(Path root, Path outputs, UiCompiler compiler) throws Exception {
        Path fixture = outputs.resolve("typed");
        Files.createDirectories(fixture);
        Path logic = fixture.resolve("typed.deal");
        Path pack = fixture.resolve("typed.dealui-pack");
        Path view = fixture.resolve("typed.dealui");
        Files.writeString(logic, "export class State { text: string = \"\"; count: int = 0; amount: number = 0.0; flag: boolean = false; }\nexport class SetText { value: string = \"\"; }\nexport class SetCount { value: int = 0; }\nexport class SetAmount { value: number = 0.0; }\nexport class SetFlag { value: boolean = false; }\nexport class Clear {}\nexport class Completed {}\nexport class EffectCommand { operation: string = \"start\"; key: string = \"typed\"; delayMillis: int = 5; cancellationMode: string = \"replace\"; }\nexport function initialState(): State { return {}; }\n// @ui-update\nexport function setText(state: State, action: SetText): State { return { text: action.value, count: state.count, amount: state.amount, flag: state.flag }; }\n// @ui-update\nexport function setCount(state: State, action: SetCount): State { return { text: state.text, count: action.value, amount: state.amount, flag: state.flag }; }\n// @ui-update\nexport function setAmount(state: State, action: SetAmount): State { return { text: state.text, count: state.count, amount: action.value, flag: state.flag }; }\n// @ui-update\nexport function setFlag(state: State, action: SetFlag): State { return { text: state.text, count: state.count, amount: state.amount, flag: action.value }; }\n// @ui-update\nexport function clear(state: State, action: Clear): State { return {}; }\n// @ui-effect-policy\nexport function completePolicy(state: State, action: Clear): EffectCommand { return {}; }\n// @ui-effect\nexport async function complete(state: State, action: Clear): Completed { return {}; }\n// @ui-effect-failure\nexport function completeFailure(state: State, action: Clear, message: string): Completed { return {}; }\n// @ui-update\nexport function completed(state: State, action: Completed): State { return state; }\nexport function main(): null { return null; }\n");
        Files.writeString(pack, "export class Props { onText?: Action; onInt?: Action; onNumber?: Action; onBool?: Action; onClear?: Action; }\nexport component Host(props: Props): View { event onText(payload: string); event onInt(payload: int); event onNumber(payload: number); event onBool(payload: boolean); event onClear; capability \"renderer.swing.card\"; }\n");
        Files.writeString(view, "import * as app from \"./typed\";\nimport * as ui from \"./typed.dealui-pack\";\n// @ui-root\nexport view Typed(state: app.State): View { ui.Host(onText: action app.SetText { value: payload }, onInt: action app.SetCount { value: payload }, onNumber: action app.SetAmount { value: payload }, onBool: action app.SetFlag { value: payload }, onClear: action app.Clear {}) }\n");
        UiCompiler.Result result = compiler.compile(view, outputs.resolve("typed-output"));
        compiler.build(result, runtimeClasses());
        String generated = Files.readString(result.outputDirectory().resolve("deal/ui_application.deal"));
        check(generated.contains("payload: string") && generated.contains("payload: int") && generated.contains("payload: number") && generated.contains("payload: boolean") && generated.contains("function action_4(): UiAction"), "generated action factories preserve event payload contracts");
        check(generated.contains("function effectCommand") && generated.contains("app.completePolicy") && generated.contains("class EffectCommand"), "synchronous DEAL effect policy produces a typed command plan");
        String bridgeSource = Files.readString(result.generatedDirectory().resolve(result.bridgeClass() + ".java"));
        check(bridgeSource.contains("new EffectCommand(command.operation, command.key, command.delayMillis, command.cancellationMode)") && bridgeSource.contains("effectFailure"), "generated bridge exposes typed scheduling and failure completion");
        try (URLClassLoader loader = new ChildFirstLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, UiFrameworkTest.class.getClassLoader())) {
            UiBridge bridge = Main.bridge(result, loader);
            UiBridge.Node tree = bridge.initial(bridge.initialState(), bridge.initialStore()).tree();
            int textSlot = tree.props().get("onText").actionSlot();
            int intSlot = tree.props().get("onInt").actionSlot();
            int numberSlot = tree.props().get("onNumber").actionSlot();
            int boolSlot = tree.props().get("onBool").actionSlot();
            int clearSlot = tree.props().get("onClear").actionSlot();
            check(bridge.action(textSlot, "text") != null && bridge.action(intSlot, 1L) != null && bridge.action(numberSlot, 1.5) != null && bridge.action(boolSlot, true) != null && bridge.action(clearSlot, null) != null, "generated bridge accepts exact typed payloads");
            expectPayloadFailure(() -> bridge.action(textSlot, 1L), "string");
            expectPayloadFailure(() -> bridge.action(intSlot, "1"), "int");
            expectPayloadFailure(() -> bridge.action(numberSlot, 1L), "number");
            expectPayloadFailure(() -> bridge.action(boolSlot, "true"), "boolean");
            expectPayloadFailure(() -> bridge.action(clearSlot, ""), "no");
        }
    }

    private static void borrowedHandlerContracts(Path root) throws Exception {
        String original = deal(root);
        String originalUpdate = "export function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState {\n  return {\n    title: state.title,\n    count: state.count,\n    expanded: !state.expanded,\n    items: state.items\n  };\n}";
        expect("UI2050", source(root), original.replace(originalUpdate,
            "export function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { state.expanded = true; return state; }"));
        expect("UI2050", source(root), original.replace(originalUpdate,
            "export function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { state.items[0].title = \"changed\"; return state; }"));
        expect("UI2050", source(root), original.replace(originalUpdate,
            "export function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { let item: Item = state.items[0]; item.title = \"changed\"; return state; }"));
        expect("UI2051", source(root), original.replace("// @ui-update\n" + originalUpdate,
            "function mutate(item: Item): null { item.title = \"changed\"; return null; }\n" +
            "// @ui-update\nexport function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { mutate(state.items[0]); return state; }"));
        expect("UI2050", source(root), original.replace(originalUpdate,
            "export function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { action = action; state.items = state.items; return state; }"));
        expect("UI2050", source(root), original.replace(
            "export function setTitle(state: GalleryState, action: SetTitle): GalleryState {\n  return {\n    title: action.value,\n    count: state.count,\n    expanded: state.expanded,\n    items: state.items\n  };\n}",
            "export function setTitle(state: GalleryState, action: SetTitle): GalleryState { action.value = \"changed\"; return state; }"));

        String freshLocal = original.replace(originalUpdate,
            "export function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { " +
            "let item: Item = { id: 7, title: \"fresh\" }; let items: Item[] = [item]; items[0] = { id: 8, title: \"replacement\" }; " +
            "return { title: state.title, count: state.count, expanded: true, items: items }; }");
        expectValid(root, source(root), freshLocal, pack(root), "fresh local mutation is allowed in UI handlers");

        String readOnlyHelper = original.replace("// @ui-update\n" + originalUpdate,
            "function titleOf(item: Item): string { return item.title; }\n" +
            "// @ui-update\nexport function toggleDetails(state: GalleryState, action: ToggleDetails): GalleryState { let title: string = titleOf(state.items[0]); return { title: title, count: state.count, expanded: true, items: state.items }; }");
        expectValid(root, source(root), readOnlyHelper, pack(root), "read-only helpers may consume borrowed values");
    }

    private static void typedChildrenContracts(Path root) throws Exception {
        String logic = "export class Item { id: int = 0; title: string = \"\"; }\n" +
            "export class State { items: Item[] = []; active: boolean = true; }\n" +
            "export class Select { id: int = 0; }\n" +
            "export function initialState(): State { return {}; }\n" +
            "// @ui-update\nexport function select(state: State, action: Select): State { return state; }\n" +
            "export function main(): null { return null; }\n";
        String uiPack = "export class Empty {}\n" +
            "export class ItemProps { text: string = \"\"; onClick?: Action; }\n" +
            "export component NavigationBar(props: Empty): View { children required NavigationItem; capability \"renderer.navigation\"; }\n" +
            "export component NavigationItem(props: ItemProps): View { event onClick; capability \"renderer.navigation.item\"; }\n" +
            "export component Text(props: ItemProps): View { capability \"renderer.text\"; }\n";
        String valid = "import * as app from \"./gallery\";\nimport * as ui from \"./platform-ui.dealui-pack\";\n" +
            "// @ui-root\nexport view App(state: app.State): View { ui.NavigationBar() { When(state.active) { ForEach(state.items, item: app.Item, key: item.id) { ui.NavigationItem(text: item.title, onClick: action app.Select { id: item.id }) } } Else { ui.NavigationItem(text: \"Empty\", onClick: action app.Select { id: 0 }) } } }\n";
        expectValid(root, valid, logic, uiPack, "typed children accept direct, conditional, and repeated item components");
        expect("UI2048", valid.replace("ui.NavigationItem(text: item.title", "ui.Text(text: item.title"), logic, uiPack);
        expect("UI2047", valid, logic, uiPack.replace("children required NavigationItem", "children required MissingItem"));
    }

    private static void expectValid(Path root, String view, String logic, String uiPack, String message) throws Exception {
        Path directory = Files.createTempDirectory(root.resolve("build"), "valid-");
        Path viewFile = directory.resolve("gallery.dealui");
        Path logicFile = directory.resolve("gallery.deal");
        Path packFile = directory.resolve("platform-ui.dealui-pack");
        Files.writeString(viewFile, view.replace("./gallery", logicFile.toString()).replace("./platform-ui.dealui-pack", packFile.toString()));
        Files.writeString(logicFile, logic);
        Files.writeString(packFile, uiPack);
        UiChecker checker = new UiChecker();
        UiModel.ViewModule views = UiParser.parseViews(viewFile, Files.readString(viewFile));
        UiModel.DealModule module = checker.parseDeal(logicFile, logic);
        UiModel.PackModule parsedPack = UiParser.parsePack(packFile, uiPack);
        checker.check(viewFile, views, logicFile, module, java.util.Map.of(packFile.toString(), parsedPack));
        check(true, message);
    }

    private static void expectPayloadFailure(Runnable operation, String expected) {
        try { operation.run(); throw new AssertionError("Expected " + expected + " payload rejection"); }
        catch (IllegalArgumentException failure) { check(failure.getMessage().contains(expected), expected + " payload rejects coercion"); }
    }

    private static void dependencyStaging(Path root, Path outputs) throws Exception {
        Path fixture = outputs.resolve("dependencies");
        Path framework = fixture.resolve("framework");
        Path application = fixture.resolve("application");
        copyTree(root.resolve("ui"), framework.resolve("ui"));
        Files.createDirectories(framework.resolve("ui/nested"));
        Files.createDirectories(application.resolve("feature"));
        Files.writeString(framework.resolve("ui/shared.deal"), "export class Shared { value: int = 1; }\n");
        Files.writeString(framework.resolve("ui/nested/policy.deal"), "import * as common from  \"../shared\" ;\nexport function value(): int { let item: common.Shared = {}; return item.value; }\n");
        Files.writeString(application.resolve("feature/helper.deal"), "import * as sharedAlias from \"../../framework/ui/shared\";\nimport * as policyAlias from \"../../framework/ui/nested/policy.deal\";\nexport function helper(): int { return policyAlias.value() + 1; }\n");
        String logic = "import * as helperAlias from \"./feature/helper\" ;\nimport * as duplicateAlias from \"../framework/ui/./shared\";\nimport * as strings from \"std/string\";\n// from \"../framework/ui/shared\"\nexport class State { value: int = 0; }\nexport function untouched(): string { return strings.trim(\"from \\\"../framework/ui/shared\\\"\"); }\nexport function initialState(): State { return {}; }\nexport function main(): null { return null; }\n";
        Files.writeString(application.resolve("app.deal"), logic);
        Files.writeString(application.resolve("pack.dealui-pack"), "export class TextProps { value: string = \"\"; }\nexport component Text(props: TextProps): View { capability \"renderer.swing.text\"; }\n");
        Files.writeString(application.resolve("app.dealui"), "import * as app from \"./app\";\nimport * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view App(state: app.State): View { ui.Text(value: \"ok\") }\n");
        UiCompiler compiler = new UiCompiler(fsRoot(), framework);
        UiCompiler.Result result = compiler.compile(application.resolve("app.dealui"), outputs.resolve("dependencies-output"));
        Path staged = result.outputDirectory().resolve("deal");
        String stagedApp = Files.readString(staged.resolve("application/app.deal"));
        String stagedHelper = Files.readString(staged.resolve("application/feature/helper.deal"));
        check(stagedApp.contains("import * as helperAlias from \"./feature/helper\" ;") && stagedApp.contains("import * as duplicateAlias from \"../framework/ui/shared\";"), "cross-root imports use stable logical prefixes");
        check(stagedApp.contains("strings.trim(\"from \\\"../framework/ui/shared\\\"\")") && stagedApp.contains("// from \"../framework/ui/shared\""), "unrelated strings and comments are preserved");
        check(stagedApp.contains("from \"std/string\"") && !Files.exists(staged.resolve("stdlib/string.d.deal")), "canonical compiler-recognized standard imports remain intact without staged declarations");
        check(stagedHelper.contains("from \"../../framework/ui/nested/policy\"") && Files.isRegularFile(staged.resolve("framework/ui/nested/policy.deal")), "transitive dependencies preserve approved-root layout");
        check(Files.readString(staged.resolve("framework/ui/nested/policy.deal")).contains("from  \"../shared\""), "transitive import token formatting is preserved");
        check(Files.readString(staged.resolve("ui_application.deal")).contains("from \"./application/app\""), "generated application import uses the logical application prefix");
        try (var files = Files.walk(staged)) { check(files.filter(path -> path.getFileName().toString().equals("shared.deal")).count() == 1, "canonical dependency paths are deduplicated"); }
        try (var files = Files.walk(staged)) { check(files.map(staged::relativize).noneMatch(path -> path.toString().contains(root.toString()) || path.toString().contains(fsRoot().toString())), "staged paths contain no absolute host path segments"); }
        UiCompiler.Result repeated = compiler.compile(application.resolve("app.dealui"), outputs.resolve("dependencies-output-repeat"));
        check(stagedSnapshot(staged).equals(stagedSnapshot(repeated.outputDirectory().resolve("deal"))), "staged output paths and contents are stable across output directories");
        Path outside = outputs.resolve("outside.deal");
        Files.writeString(outside, "export function value(): int { return 1; }\n");
        Path escape = application.resolve("escape.deal");
        Files.writeString(escape, "import * as outside from \"../../outside\";\nexport class State { value: int = 0; }\nexport function initialState(): State { return {}; }\nexport function main(): null { return null; }\n");
        Files.writeString(application.resolve("escape.dealui"), "import * as app from \"./escape\";\nimport * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view Escape(state: app.State): View { ui.Text(value: \"escape\") }\n");
        expectDependencyEscape(compiler, application.resolve("escape.dealui"), outputs.resolve("escape-output"), "'../../outside' imported by application/escape.deal");
        Path link = application.resolve("linked.deal");
        try {
            Files.createSymbolicLink(link, outside);
            Path symlink = application.resolve("symlink.deal");
            Files.writeString(symlink, "import * as linked from \"./linked\";\nexport class State { value: int = 0; }\nexport function initialState(): State { return {}; }\nexport function main(): null { return null; }\n");
            Files.writeString(application.resolve("symlink.dealui"), "import * as app from \"./symlink\";\nimport * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view Symlink(state: app.State): View { ui.Text(value: \"symlink\") }\n");
            expectDependencyEscape(compiler, application.resolve("symlink.dealui"), outputs.resolve("symlink-output"), "'./linked' imported by application/symlink.deal");
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {}
        Path missing = application.resolve("missing.deal");
        Files.writeString(missing, "import * as absent from \"./absent\";\nexport class State { value: int = 0; }\nexport function initialState(): State { return {}; }\nexport function main(): null { return null; }\n");
        Files.writeString(application.resolve("missing.dealui"), "import * as app from \"./missing\";\nimport * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view Missing(state: app.State): View { ui.Text(value: \"missing\") }\n");
        try { compiler.compile(application.resolve("missing.dealui"), outputs.resolve("missing-output")); throw new AssertionError("Expected missing dependency failure"); }
        catch (java.io.IOException failure) { check(failure.getMessage().contains("Missing relative DEAL dependency './absent'") && failure.getMessage().contains("missing.deal"), "missing dependencies report importer and specifier"); }
        expectInvalidImport(compiler, application, outputs, "std/../console", "Unsupported DEAL standard library dependency 'std/../console'");
        expectInvalidImport(compiler, application, outputs, "std/coroutine", "Unsupported DEAL standard library dependency 'std/coroutine'");
        expectInvalidImport(compiler, application, outputs, "std/string/", "Unsupported DEAL standard library dependency 'std/string/'");
        expectInvalidImport(compiler, application, outputs, "host/mail", "Unsupported bare DEAL dependency 'host/mail'");
        Path incompleteFs = fixture.resolve("incomplete-fs");
        Files.createDirectories(incompleteFs.resolve("build/deal"));
        Files.createDirectories(incompleteFs.resolve("std"));
        Files.copy(fsRoot().resolve("build/deal/Main.class"), incompleteFs.resolve("build/deal/Main.class"));
        expectInvalidImport(new UiCompiler(incompleteFs, framework), application, outputs, "std/string", "Missing DEAL standard library dependency 'std/string'");
    }

    private static void expectInvalidImport(UiCompiler compiler, Path application, Path outputs, String specifier, String expected) throws Exception {
        String name = "invalid-import-" + Integer.toUnsignedString(specifier.hashCode());
        Path logic = application.resolve(name + ".deal");
        Files.writeString(logic, "import * as dependency from \"" + specifier + "\";\nexport class State { value: int = 0; }\nexport function initialState(): State { return {}; }\nexport function main(): null { return null; }\n");
        Path view = application.resolve(name + ".dealui");
        Files.writeString(view, "import * as app from \"./" + name + "\";\nimport * as ui from \"./pack.dealui-pack\";\n// @ui-root\nexport view Invalid(state: app.State): View { ui.Text(value: \"invalid\") }\n");
        try { compiler.compile(view, outputs.resolve(name)); throw new AssertionError("Expected invalid import failure"); }
        catch (java.io.IOException failure) { check(failure.getMessage().contains(expected) && failure.getMessage().contains(name + ".deal"), "invalid standard and bare imports report importer and specifier"); }
    }

    private static void expectDependencyEscape(UiCompiler compiler, Path view, Path output, String detail) throws Exception {
        try { compiler.compile(view, output); throw new AssertionError("Expected dependency escape failure"); }
        catch (java.io.IOException failure) { check(failure.getMessage().equals("DEAL dependency escape rejected: " + detail), "dependency escapes report deterministic logical diagnostics"); }
    }

    private static java.util.Map<String, String> stagedSnapshot(Path root) throws Exception {
        java.util.Map<String, String> snapshot = new java.util.TreeMap<>();
        try (var files = Files.walk(root)) { for (Path file : files.filter(Files::isRegularFile).toList()) snapshot.put(root.relativize(file).toString().replace(java.io.File.separatorChar, '/'), Files.readString(file)); }
        return snapshot;
    }

    private static void expect(String code, String view) throws Exception { expect(code, view, deal(Path.of("").toAbsolutePath())); }
    private static void expect(String code, String view, String logic) throws Exception {
        expect(code, view, logic, pack(Path.of("").toAbsolutePath()));
    }
    private static void expect(String code, String view, String logic, String uiPack) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path directory = Files.createTempDirectory(root.resolve("build"), "invalid-");
        Files.writeString(directory.resolve("gallery.dealui"), view.replace("./gallery", directory.resolve("gallery").toString()).replace("./platform-ui.dealui-pack", root.resolve("examples/museum/platform-ui.dealui-pack").toString()));
        Files.writeString(directory.resolve("gallery.deal"), logic);
        try {
            UiModel.ViewModule views = UiParser.parseViews(directory.resolve("gallery.dealui"), Files.readString(directory.resolve("gallery.dealui")));
            UiChecker checker = new UiChecker();
            UiModel.DealModule module = checker.parseDeal(directory.resolve("gallery.deal"), logic);
            UiModel.PackModule pack = UiParser.parsePack(root.resolve("examples/museum/platform-ui.dealui-pack"), uiPack);
            checker.check(directory.resolve("gallery.dealui"), views, directory.resolve("gallery.deal"), module, java.util.Map.of(root.resolve("examples/museum/platform-ui.dealui-pack").toString(), pack));
            throw new AssertionError("Expected " + code);
        } catch (UiDiagnostic diagnostic) { check(diagnostic.code().equals(code), code + " is reported"); }
    }
    private static void expectPack(String code, String pack) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        UiModel.ViewModule views = UiParser.parseViews(root.resolve("examples/museum/gallery.dealui"), source(root));
        UiChecker checker = new UiChecker();
        UiModel.DealModule deal = checker.parseDeal(root.resolve("examples/museum/gallery.deal"), deal(root));
        try {
            checker.check(root.resolve("examples/museum/gallery.dealui"), views, root.resolve("examples/museum/gallery.deal"), deal, java.util.Map.of("./platform-ui.dealui-pack", UiParser.parsePack(root.resolve("examples/museum/platform-ui.dealui-pack"), pack)));
            throw new AssertionError("Expected " + code);
        } catch (UiDiagnostic diagnostic) { check(diagnostic.code().equals(code), code + " is reported"); }
    }

    private static String source(Path root) throws Exception { return Files.readString(root.resolve("examples/museum/gallery.dealui")); }
    private static String deal(Path root) throws Exception { return Files.readString(root.resolve("examples/museum/gallery.deal")); }
    private static String pack(Path root) throws Exception { return Files.readString(root.resolve("examples/museum/platform-ui.dealui-pack")); }
    private static List<String> texts(UiBridge.Node node) { List<String> values = new ArrayList<>(); collect(node, values); return values; }
    private static void collect(UiBridge.Node node, List<String> values) { UiBridge.Prop prop = node.props().get("value"); if (prop != null) values.add(String.valueOf(prop.value())); node.children().forEach(child -> collect(child, values)); }
    private static UiPortableBridge.Node nodeWithText(UiPortableBridge.Node node, String text) {
        UiPortableBridge.Prop value = node.props().get("text");
        if (value != null && value.value().equals(text)) return node;
        for (UiPortableBridge.Node child : node.children()) {
            UiPortableBridge.Node found = nodeWithText(child, text);
            if (found != null) return found;
        }
        return null;
    }
    private static AbstractButton button(Component component, String text) { if (component instanceof AbstractButton value && value.getText().equals(text)) return value; if (component instanceof Container container) for (Component child : container.getComponents()) { AbstractButton found = button(child, text); if (found != null) return found; } return null; }
    private static JTextField input(Component component) { if (component instanceof JTextField value) return value; if (component instanceof Container container) for (Component child : container.getComponents()) { JTextField found = input(child); if (found != null) return found; } return null; }
    private static JComponent named(Component component, String name, String text) { if (component instanceof JLabel label && label.getName().equals(name) && label.getText().equals(text)) return label; if (component instanceof Container container) for (Component child : container.getComponents()) { JComponent found = named(child, name, text); if (found != null) return found; } return null; }
    private static Path runtimeClasses() throws Exception { return Path.of(UiBridge.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
    private static Path fsRoot() { String value = System.getenv("DEAL_FS_ROOT"); return value == null ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(value); }
    private static void copyTree(Path source, Path target) throws Exception { try (var files = Files.walk(source)) { for (Path file : files.toList()) { Path destination = target.resolve(source.relativize(file)); if (Files.isDirectory(file)) Files.createDirectories(destination); else Files.copy(file, destination); } } }
    private static void recreate(Path path) throws Exception { if (Files.exists(path)) try (var files = Files.walk(path)) { for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); } Files.createDirectories(path); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }

    private static final class ChildFirstLoader extends URLClassLoader {
        private ChildFirstLoader(java.net.URL[] urls, ClassLoader parent) { super(urls, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("deal.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { loaded = super.loadClass(name, false); }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
