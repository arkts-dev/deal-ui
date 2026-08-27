package deal.ui.runtime;

import deal.ui.UiBridge;
import deal.ui.UiRendererBindings;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SwingUiRuntime implements AutoCloseable {
    @FunctionalInterface public interface Dispatch { void accept(UiBridge.ActionValue action); }

    private final String title;
    private final UiRendererBindings bindings;
    private final UiBridge bridge;
    private final Dispatch dispatch;
    private final Map<UiBridge.Identity, JComponent> retained = new LinkedHashMap<>();
    private JFrame frame;
    private JPanel root;
    private UiBridge.Node tree;
    private boolean closed;
    private boolean lastApplyOnEdt;
    private long disposedComponents;
    private Runnable closeRequest = () -> {};

    public SwingUiRuntime(String title, UiRendererBindings bindings, UiBridge bridge, Dispatch dispatch) {
        this.title = Objects.requireNonNull(title);
        this.bindings = Objects.requireNonNull(bindings);
        this.bridge = Objects.requireNonNull(bridge);
        this.dispatch = Objects.requireNonNull(dispatch);
    }

    public void show(UiBridge.Node next) {
        onEdt(() -> {
            if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Cannot show UI in headless mode");
            ensureFrame();
            rebuild(next);
            frame.setVisible(true);
        });
    }

    public void apply(List<UiBridge.Patch> patches, UiBridge.Node next) {
        onEdt(() -> {
            lastApplyOnEdt = SwingUtilities.isEventDispatchThread();
            stagePatches(patches);
            for (UiBridge.Patch patch : patches) applyPatch(patch);
            for (UiBridge.Patch patch : patches) if (!patch.kind().equals("dispose")) syncChildren(patch.node());
            tree = Objects.requireNonNull(next);
            if (frame != null) frame.pack();
        });
    }

    public void restore(UiBridge.Node previous) { onEdt(() -> { tree = previous; if (root != null) { JComponent component = retained.get(previous.identity()); if (component != null && component.getParent() != root) { root.removeAll(); root.add(component, BorderLayout.CENTER); root.revalidate(); root.repaint(); } } }); }

    public JComponent componentForTesting(UiBridge.Node next) {
        AtomicReference<JComponent> result = new AtomicReference<>();
        onEdt(() -> {
            JComponent component = retained.get(next.identity());
            if (component == null) component = build(next);
            result.set(component);
            tree = next;
        });
        return result.get();
    }

    public void onCloseRequest(Runnable action) { closeRequest = Objects.requireNonNull(action); }
    public void requestCloseForTesting() { onEdt(closeRequest); }
    public UiBridge.Node tree() { return tree; }
    public boolean lastApplyOnEdt() { return lastApplyOnEdt; }
    public long disposedComponents() { return disposedComponents; }
    public void click(String text) { onEdt(() -> { JButton button = find(root, text); if (button == null) throw new IllegalArgumentException("Button not found: " + text); button.doClick(); }); }
    public void capture(Path path) { onEdt(() -> captureNow(path)); }

    private void ensureFrame() {
        if (frame != null) return;
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent event) { closeRequest.run(); } });
        frame.setMinimumSize(new Dimension(680, 520));
        root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
        root.setBackground(bindings.background());
        frame.setContentPane(root);
    }

    private void stagePatches(List<UiBridge.Patch> patches) {
        for (UiBridge.Patch patch : patches) {
            if (patch.kind().equals("dispose")) continue;
            UiRendererBindings.Binding binding = bindings.require(patch.node().component());
            JComponent staged = binding.factory().get();
            binding.configurator().apply(staged, patch.node(), bridge, dispatch::accept, bindings);
        }
    }

    private void applyPatch(UiBridge.Patch patch) {
        if (closed) return;
        if (patch.kind().equals("dispose")) { dispose(patch.identity()); return; }
        UiRendererBindings.Binding binding = bindings.require(patch.node().component());
        JComponent component = retained.get(patch.identity());
        if (component == null) {
            component = patch.kind().equals("create") ? build(patch.node()) : binding.factory().get();
            retained.put(patch.identity(), component);
        }
        binding.configurator().apply(component, patch.node(), bridge, dispatch::accept, bindings);
        java.awt.Container parent = null;
        if (patch.rootParent()) parent = root;
        else {
            JComponent parentComponent = retained.get(patch.parentIdentity());
            if (parentComponent instanceof java.awt.Container container) parent = container;
        }
        if (parent != null) {
            if (component.getParent() != parent || parent.getComponentZOrder(component) != Math.min(patch.index(), parent.getComponentCount() - 1)) {
                if (component.getParent() != null) component.getParent().remove(component);
                parent.add(component, Math.min(patch.index(), parent.getComponentCount()));
            }
            parent.revalidate();
            parent.repaint();
        }
    }

    private void syncChildren(UiBridge.Node node) {
        JComponent component = retained.get(node.identity());
        if (!(component instanceof JPanel panel)) return;
        int spacing = spacing(node);
        panel.removeAll();
        for (int i = 0; i < node.children().size(); i++) {
            JComponent child = retained.get(node.children().get(i).identity());
            if (child == null) continue;
            child.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(child);
            if (i + 1 < node.children().size() && spacing > 0) panel.add(Box.createRigidArea(new Dimension(0, spacing)));
        }
        panel.revalidate();
        panel.repaint();
    }

    private void rebuild(UiBridge.Node next) {
        if (closed) return;
        tree = Objects.requireNonNull(next);
        if (root == null) return;
        JComponent component = build(next);
        root.removeAll();
        root.add(component, BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        frame.pack();
    }

    private JComponent build(UiBridge.Node node) {
        UiRendererBindings.Binding binding = bindings.require(node.component());
        JComponent existing = retained.get(node.identity());
        JComponent component = compatible(existing, binding.component()) ? existing : binding.factory().get();
        retained.put(node.identity(), component);
        binding.configurator().apply(component, node, bridge, dispatch::accept, bindings);
        if (component instanceof JPanel panel) {
            panel.removeAll();
            int spacing = spacing(node);
            for (int i = 0; i < node.children().size(); i++) {
                JComponent child = build(node.children().get(i));
                child.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(child);
                if (i + 1 < node.children().size() && spacing > 0) panel.add(Box.createRigidArea(new Dimension(0, spacing)));
            }
        }
        return component;
    }

    private int spacing(UiBridge.Node node) {
        UiBridge.Prop prop = node.props().get("spacing");
        return prop != null && prop.value() instanceof String token ? bindings.spacing(token) : 0;
    }
    private boolean compatible(JComponent component, String binding) { return component != null && component.getName().equals(binding); }

    public static void configureHostComponent(JComponent component, UiBridge.Node node, UiBridge bridge, java.util.function.Consumer<UiBridge.ActionValue> dispatch, UiRendererBindings bindings) {
        String binding = node.component();
        component.setName(binding);
        if (component instanceof JPanel panel) {
            boolean card = component instanceof JPanel && binding.endsWith("Card");
            panel.setOpaque(card);
            panel.setBackground(card ? Color.WHITE : new Color(0, 0, 0, 0));
            panel.setBorder(card ? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xDCE2EA)), BorderFactory.createEmptyBorder(28, 28, 28, 28)) : null);
        } else if (component instanceof JLabel label) {
            label.setText(String.valueOf(value(node, "value")));
            label.setForeground(new Color(0x172033));
            String text = label.getText();
            float size = value(node, "value") instanceof Number ? 40f : text.equals(text.toUpperCase(java.util.Locale.ROOT)) && text.length() < 24 ? 13f : text.length() < 28 ? 28f : 17f;
            int style = size >= 28f || size == 13f ? Font.BOLD : Font.PLAIN;
            label.setFont(label.getFont().deriveFont(style, size));
            label.getAccessibleContext().setAccessibleName(label.getText());
        } else if (component instanceof JTextField input) {
            input.setText(String.valueOf(value(node, "value")));
            input.setColumns(28);
            input.setFont(input.getFont().deriveFont(Font.PLAIN, 16f));
            input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xCAD3E0)), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            input.getAccessibleContext().setAccessibleName(String.valueOf(valueOr(node, "accessibilityLabel", "Input")));
            for (var listener : input.getActionListeners()) input.removeActionListener(listener);
            UiBridge.Prop action = node.props().get("onSubmit");
            if (action != null) input.addActionListener(event -> dispatch.accept(bridge.action(action.actionSlot(), input.getText())));
        } else if (component instanceof JButton button) {
            button.setText(String.valueOf(value(node, "text")));
            button.setFont(button.getFont().deriveFont(Font.BOLD, 15f));
            button.setForeground(Color.WHITE);
            button.setBackground(bindings.accent());
            button.setOpaque(true);
            button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x5148E5)), BorderFactory.createEmptyBorder(12, 20, 12, 20)));
            button.setFocusPainted(false);
            button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            button.getAccessibleContext().setAccessibleName(String.valueOf(valueOr(node, "accessibilityLabel", button.getText())));
            for (var listener : button.getActionListeners()) button.removeActionListener(listener);
            UiBridge.Prop action = node.props().get("onClick");
            if (action != null) button.addActionListener(event -> dispatch.accept(bridge.action(action.actionSlot(), null)));
        }
    }

    private static Object value(UiBridge.Node node, String name) { UiBridge.Prop prop = node.props().get(name); return prop == null ? "" : prop.value(); }
    private static Object valueOr(UiBridge.Node node, String name, Object fallback) { UiBridge.Prop prop = node.props().get(name); return prop == null ? fallback : prop.value(); }
    private void dispose(UiBridge.Identity identity) {
        JComponent removed = retained.remove(identity);
        if (removed == null) return;
        if (removed instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) if (child instanceof JComponent component) disposeComponent(component);
            container.removeAll();
        }
        if (removed.getParent() != null) removed.getParent().remove(removed);
        bindings.dispose(removed);
        disposedComponents++;
    }

    private void disposeComponent(JComponent component) {
        retained.entrySet().removeIf(entry -> entry.getValue() == component);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) if (child instanceof JComponent nested) disposeComponent(nested);
            container.removeAll();
        }
        bindings.dispose(component);
        disposedComponents++;
    }

    private void disposeRetained() {
        java.util.List<JComponent> roots = retained.values().stream().filter(component -> component.getParent() == null || !retained.containsValue(component.getParent())).toList();
        for (JComponent component : roots) disposeComponent(component);
        retained.clear();
    }
    private JButton find(Component component, String text) { if (component instanceof JButton button && button.getText().equals(text)) return button; if (component instanceof java.awt.Container container) for (Component child : container.getComponents()) { JButton result = find(child, text); if (result != null) return result; } return null; }
    private void captureNow(Path destination) {
        if (frame == null || !frame.isDisplayable()) throw new IllegalStateException("UI is not shown");
        BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { frame.paint(graphics); } finally { graphics.dispose(); }
        try { Path parent = destination.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent); if (!ImageIO.write(image, "png", destination.toFile())) throw new IllegalStateException("PNG writer unavailable"); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Capture failed", failure); }
    }
    private void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) { action.run(); return; }
        try { SwingUtilities.invokeAndWait(action); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted waiting for renderer", failure); }
        catch (InvocationTargetException failure) { Throwable cause = failure.getCause(); if (cause instanceof RuntimeException runtime) throw runtime; if (cause instanceof Error error) throw error; throw new IllegalStateException(cause); }
    }
    @Override public void close() { onEdt(() -> { if (closed) return; closed = true; disposeRetained(); tree = null; if (frame != null) frame.dispose(); frame = null; root = null; }); }
}
