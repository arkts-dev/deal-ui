package deal.ui;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class SemanticExamplesVisibleSmoke {
    private SemanticExamplesVisibleSmoke() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path outputs = root.resolve("build/visible-outputs");
        Path evidence = root.resolve("build/visible-evidence");
        Files.createDirectories(outputs);
        Files.createDirectories(evidence);
        UiCompiler compiler = new UiCompiler(fsRoot());
        for (String name : List.of("checkout", "search-mail", "kanban", "dashboard")) {
            UiCompiler.Result result = compiler.compile(root.resolve("examples").resolve(name).resolve(name + ".dealui"), outputs.resolve(name + "-" + System.nanoTime()));
            compiler.build(result, root.resolve("build/classes"));
            run(result, name, evidence);
        }
        System.out.println("Visible semantic example scenarios passed");
    }

    private static void run(UiCompiler.Result result, String name, Path evidence) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{result.outputDirectory().resolve("classes").toUri().toURL()}, SemanticExamplesVisibleSmoke.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            UiBridge bridge = Main.bridge(result, loader);
            UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings());
            try (runtime) {
                runtime.show();
                check(!java.awt.GraphicsEnvironment.isHeadless(), name + " requires a visible display");
                switch (name) {
                    case "checkout" -> checkout(runtime, evidence);
                    case "search-mail" -> searchMail(runtime, evidence);
                    case "kanban" -> kanban(runtime, evidence);
                    case "dashboard" -> dashboard(runtime, evidence);
                    default -> throw new IllegalArgumentException(name);
                }
            }
            check(runtime.disposed(), name + " runtime did not close cleanly");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void checkout(UiProgramRuntime runtime, Path evidence) throws Exception {
        click(runtime, "Continue to payment");
        runtime.awaitActions();
        check(state(runtime, "route").equals("checkout/contact") && state(runtime, "touched").equals(true), "checkout accepted an empty contact");
        submit(runtime, "Email address", "invalid");
        runtime.awaitActions();
        click(runtime, "Continue to payment");
        runtime.awaitActions();
        check(state(runtime, "route").equals("checkout/contact") && state(runtime, "valid").equals(false), "checkout accepted an invalid email");
        runtime.renderer().capture(evidence.resolve("checkout-invalid.png"));
        submit(runtime, "Email address", "user@example.com");
        runtime.awaitActions();
        click(runtime, "Continue to payment");
        runtime.awaitActions();
        check(state(runtime, "route").equals("checkout/payment") && state(runtime, "email").equals("user@example.com"), "checkout did not navigate after valid input");
        runtime.renderer().capture(evidence.resolve("checkout-payment.png"));
    }

    private static void searchMail(UiProgramRuntime runtime, Path evidence) throws Exception {
        submit(runtime, "Search messages", "policy");
        runtime.awaitActions();
        long firstGeneration = (Long) state(runtime, "activeGeneration");
        check(state(runtime, "status").equals("debouncing"), "search did not enter debounce");
        submit(runtime, "Search messages", "renderer");
        runtime.awaitIdle();
        check(state(runtime, "generation").equals(firstGeneration + 1) && state(runtime, "activeGeneration").equals(-1L), "search replacement generation did not complete");
        check(state(runtime, "result").equals("1 message matches renderer"), "search effect did not produce renderer results");
        check(texts(runtime.tree()).containsAll(List.of("Renderer release", "release@deal.dev")) && !texts(runtime.tree()).contains("Portable policy review"), "search rendered the wrong mailbox result");
        runtime.renderer().capture(evidence.resolve("search-mail-results.png"));
    }

    private static void kanban(UiProgramRuntime runtime, Path evidence) throws Exception {
        click(runtime, "Move first to doing");
        runtime.awaitActions();
        check(state(runtime, "pendingRevision").equals(1L) && cardColumn(runtime.tree(), "Ship portable policy").equals("doing"), "kanban did not render its optimistic move");
        runtime.awaitIdle();
        check(state(runtime, "revision").equals(1L) && state(runtime, "pendingRevision").equals(0L) && state(runtime, "message").equals("Move accepted"), "kanban valid move did not commit");
        runtime.renderer().capture(evidence.resolve("kanban-committed.png"));
        click(runtime, "Try denied archive move");
        runtime.awaitActions();
        check(state(runtime, "pendingRevision").equals(2L) && cardColumn(runtime.tree(), "Ship portable policy").equals("archive"), "kanban did not expose its denied optimistic move");
        runtime.awaitIdle();
        check(state(runtime, "revision").equals(1L) && state(runtime, "rollbackRevision").equals(1L) && state(runtime, "pendingRevision").equals(0L), "kanban denied move did not roll back");
        check(cardColumn(runtime.tree(), "Ship portable policy").equals("doing") && state(runtime, "message").equals("Destination 'archive' is not part of this board"), "kanban rollback restored the wrong state");
        runtime.renderer().capture(evidence.resolve("kanban-rollback.png"));
    }

    private static void dashboard(UiProgramRuntime runtime, Path evidence) throws Exception {
        check(runtime.renderer().requestedFocusIdForTesting().equals("open-settings"), "dashboard did not request initial focus");
        await(() -> focusId().equals("open-settings"), 1000, "dashboard initial Swing focus was not applied");
        click(runtime, "Open settings");
        runtime.awaitActions();
        check(runtime.renderer().modalVisibleForTesting() && !runtime.renderer().underlyingEnabledForTesting(), "dashboard modal did not block its host");
        check(runtime.renderer().escapeBoundForTesting() && runtime.renderer().scopeTimerRunningForTesting(), "dashboard modal input or timer host behavior is missing");
        check(runtime.renderer().requestedFocusIdForTesting().equals("settings-name") && focusId().equals("settings-name"), "dashboard modal focus was not applied");
        runtime.renderer().capture(evidence.resolve("dashboard-modal.png"));
        await(() -> state(runtime, "announcement").equals("Settings refreshed"), 3000, "dashboard timer did not dispatch its scoped effect");
        long activeScope = (Long) state(runtime, "scopeRevision");
        pressEscape(runtime);
        runtime.awaitActions();
        check(!runtime.renderer().modalVisibleForTesting() && runtime.renderer().underlyingEnabledForTesting() && !runtime.renderer().scopeTimerRunningForTesting(), "dashboard Escape did not restore host behavior");
        check(state(runtime, "command").equals("Escape") && state(runtime, "scopeRevision").equals(activeScope + 1), "dashboard Escape did not commit command state");
        check(runtime.renderer().requestedFocusIdForTesting().equals("open-settings") && focusId().equals("open-settings"), "dashboard Escape did not restore focus");
        runtime.renderer().capture(evidence.resolve("dashboard-closed.png"));
        click(runtime, "Open settings");
        runtime.awaitActions();
        check(runtime.renderer().scopeTimerRunningForTesting(), "dashboard reopened without its scoped timer");
        runtime.renderer().requestCloseForTesting();
        check(runtime.disposed() && !runtime.renderer().scopeTimerRunningForTesting(), "dashboard host close did not dispose its timer and runtime");
    }

    private static void click(UiProgramRuntime runtime, String text) throws Exception {
        onEdt(() -> {
            JButton button = find(component(runtime), JButton.class, value -> value.getText().equals(text));
            if (button == null) throw new IllegalArgumentException("Button not found: " + text);
            button.doClick();
        });
    }

    private static void submit(UiProgramRuntime runtime, String accessibleName, String value) throws Exception {
        onEdt(() -> {
            JTextField field = find(component(runtime), JTextField.class, candidate -> accessibleName.equals(candidate.getAccessibleContext().getAccessibleName()));
            if (field == null) throw new IllegalArgumentException("Input not found: " + accessibleName);
            field.requestFocusInWindow();
            field.setText(value);
            field.postActionEvent();
        });
    }

    private static void pressEscape(UiProgramRuntime runtime) throws Exception {
        onEdt(() -> {
            JComponent modal = runtime.renderer().modalForTesting();
            if (modal == null || !(modal.getParent() instanceof JComponent overlay)) throw new IllegalStateException("Modal overlay not found");
            KeyStroke escape = KeyStroke.getKeyStroke("ESCAPE");
            Object actionKey = overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
            Action action = actionKey == null ? null : overlay.getActionMap().get(actionKey);
            if (action == null) throw new IllegalStateException("Escape InputMap action not found");
            action.actionPerformed(new java.awt.event.ActionEvent(overlay, java.awt.event.ActionEvent.ACTION_PERFORMED, "Escape"));
        });
    }

    private static JComponent component(UiProgramRuntime runtime) {
        return runtime.renderer().componentForTesting(runtime.tree());
    }

    private static String focusId() {
        AtomicReference<String> result = new AtomicReference<>("");
        try {
            onEdt(() -> {
                Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (owner instanceof JComponent component) result.set(String.valueOf(component.getClientProperty("deal.focusId")));
            });
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Swing focus owner", failure);
        }
        return result.get();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), message);
    }

    private static Object state(UiProgramRuntime runtime, String name) {
        Map<String, Object> snapshot = runtime.stateSnapshot();
        if (!snapshot.containsKey(name)) throw new IllegalArgumentException("State not found: " + name);
        return snapshot.get(name);
    }

    private static String cardColumn(UiBridge.Node node, String title) {
        List<String> values = texts(node);
        int index = values.indexOf(title);
        if (index < 0 || index + 1 >= values.size()) throw new IllegalArgumentException("Card not found: " + title);
        return values.get(index + 1);
    }

    private static List<String> texts(UiBridge.Node node) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        UiBridge.Prop text = node.props().get("value");
        if (text != null) result.add(String.valueOf(text.value()));
        for (UiBridge.Node child : node.children()) result.addAll(texts(child));
        return result;
    }

    private static <T extends Component> T find(Component root, Class<T> type, java.util.function.Predicate<T> predicate) {
        if (type.isInstance(root)) {
            T value = type.cast(root);
            if (predicate.test(value)) return value;
        }
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            T value = find(child, type, predicate);
            if (value != null) return value;
        }
        return null;
    }

    private static void onEdt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeAndWait(action);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Path fsRoot() {
        String value = System.getenv("DEAL_FS_ROOT");
        return value == null ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(value);
    }
}
