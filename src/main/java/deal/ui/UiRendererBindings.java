package deal.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import java.awt.Color;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class UiRendererBindings {
    public enum HostKind { NORMAL, MODAL }
    @FunctionalInterface public interface Configurator { void apply(JComponent component, UiBridge.Node node, UiBridge bridge, Consumer<UiBridge.ActionValue> dispatch, UiRendererBindings bindings); }
    public static Binding binding(String component, Supplier<JComponent> factory, Configurator configurator) { return new Binding(component, HostKind.NORMAL, factory, configurator, ignored -> {}, deal.ui.runtime.SwingUiRuntime::disposeHostComponent); }
    public static Binding modalBinding(String component, Supplier<JComponent> factory, Configurator configurator) { return new Binding(component, HostKind.MODAL, factory, configurator, ignored -> {}, deal.ui.runtime.SwingUiRuntime::disposeHostComponent); }
    public record Binding(String component, HostKind hostKind, Supplier<JComponent> factory, Configurator configurator, Consumer<JComponent> disposalPrepare, Consumer<JComponent> disposer) {
        public Binding(String component, Supplier<JComponent> factory, Configurator configurator, Consumer<JComponent> disposalPrepare, Consumer<JComponent> disposer) { this(component, HostKind.NORMAL, factory, configurator, disposalPrepare, disposer); }
    }

    private final Map<String, Binding> components;
    private final Map<String, Object> tokens;
    private final Color background;
    private final Color accent;

    public UiRendererBindings(Map<String, Binding> components, Map<String, Object> tokens, Color background, Color accent) {
        this.components = Map.copyOf(components);
        this.tokens = Map.copyOf(tokens);
        this.background = background;
        this.accent = accent;
    }

    public Binding require(String component) {
        Binding binding = components.get(component);
        if (binding == null) throw new IllegalStateException("No renderer binding for " + component);
        return binding;
    }
    public int spacing(String token) {
        Object value = tokens.get(token);
        if (value instanceof Number number) return number.intValue();
        throw new IllegalStateException("Spacing token is not numeric: " + token);
    }
    public void prepareDisposal(JComponent component) { String name = component.getName(); if (name == null) return; Binding binding = components.get(name); if (binding != null) binding.disposalPrepare().accept(component); }
    public void dispose(JComponent component) { String name = component.getName(); if (name == null) return; Binding binding = components.get(name); if (binding != null) try { binding.disposer().accept(component); } catch (RuntimeException | Error failure) { reportCleanupFailure(failure); } }
    public static void reportCleanupFailure(Throwable failure) { try { Thread thread = Thread.currentThread(); Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler(); if (handler != null) handler.uncaughtException(thread, failure); } catch (RuntimeException | Error ignored) {} }
    public Color background() { return background; }
    public Color accent() { return accent; }
    public static JComponent column() { JPanel panel = new JPanel(); panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)); return panel; }
    public static JComponent card() { JPanel panel = new JPanel(); panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)); return panel; }
    public static JComponent modal() { ModalPanel panel = new ModalPanel(); panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)); return panel; }
    public static JComponent text() { return new JLabel(); }
    public static JComponent intText() { return new JLabel(); }
    public static JComponent button() { return new JButton(); }
    public static JComponent input() { return new JTextField(); }
    public static JComponent spinner() { JProgressBar progress = new JProgressBar(); progress.setIndeterminate(true); return progress; }
    public static void configure(JComponent component, UiBridge.Node node, UiBridge bridge, Consumer<UiBridge.ActionValue> dispatch, UiRendererBindings bindings) { deal.ui.runtime.SwingUiRuntime.configureHostComponent(component, node, bridge, dispatch, bindings); }

    private static final class ModalPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleModalPanel();
            return accessibleContext;
        }
        private final class AccessibleModalPanel extends AccessibleJPanel {
            private static final long serialVersionUID = 1L;
            @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.DIALOG; }
        }
    }
}
