package deal.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import java.awt.Color;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class UiRendererBindings {
    @FunctionalInterface public interface Configurator { void apply(JComponent component, UiBridge.Node node, UiBridge bridge, Consumer<UiBridge.ActionValue> dispatch, UiRendererBindings bindings); }
    public static Binding binding(String component, Supplier<JComponent> factory, Configurator configurator) { return new Binding(component, factory, configurator); }
    public record Binding(String component, Supplier<JComponent> factory, Configurator configurator, Consumer<JComponent> disposalPrepare, Consumer<JComponent> disposer) {
        public Binding(String component, Supplier<JComponent> factory, Configurator configurator) { this(component, factory, configurator, ignored -> {}, ignored -> {}); }
        public Binding(String component, Supplier<JComponent> factory, Configurator configurator, Consumer<JComponent> disposer) { this(component, factory, configurator, ignored -> {}, disposer); }
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
    public void dispose(JComponent component) { String name = component.getName(); if (name == null) return; Binding binding = components.get(name); if (binding != null) binding.disposer().accept(component); }
    public Color background() { return background; }
    public Color accent() { return accent; }
    public static JComponent column() { JPanel panel = new JPanel(); panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)); return panel; }
    public static JComponent card() { JPanel panel = new JPanel(); panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)); return panel; }
    public static JComponent text() { return new JLabel(); }
    public static JComponent intText() { return new JLabel(); }
    public static JComponent button() { return new JButton(); }
    public static JComponent input() { return new JTextField(); }
    public static JComponent spinner() { JProgressBar progress = new JProgressBar(); progress.setIndeterminate(true); return progress; }
    public static void configure(JComponent component, UiBridge.Node node, UiBridge bridge, Consumer<UiBridge.ActionValue> dispatch, UiRendererBindings bindings) { deal.ui.runtime.SwingUiRuntime.configureHostComponent(component, node, bridge, dispatch, bindings); }
}
