package deal.ui.runtime;

import deal.ui.UiBridge;
import deal.ui.UiRendererBindings;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
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
    @FunctionalInterface public interface Dispatch { void accept(Object action); }

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
            for (UiBridge.Patch patch : patches) if (patch.kind().equals("dispose")) dispose(patch.identity());
            rebuild(next);
        });
    }

    public JComponent componentForTesting(UiBridge.Node next) {
        AtomicReference<JComponent> result = new AtomicReference<>();
        onEdt(() -> {
            result.set(build(next));
            tree = next;
        });
        return result.get();
    }

    public UiBridge.Node tree() { return tree; }
    public boolean lastApplyOnEdt() { return lastApplyOnEdt; }
    public long disposedComponents() { return disposedComponents; }
    public void click(String text) { onEdt(() -> { JButton button = find(root, text); if (button == null) throw new IllegalArgumentException("Button not found: " + text); button.doClick(); }); }
    public void capture(Path path) { onEdt(() -> captureNow(path)); }

    private void ensureFrame() {
        if (frame != null) return;
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(680, 520));
        root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
        root.setBackground(bindings.background());
        frame.setContentPane(root);
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
        JComponent component = compatible(existing, binding.kind()) ? existing : create(binding.kind());
        retained.put(node.identity(), component);
        configure(component, node, binding.kind());
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
    private boolean compatible(JComponent component, UiRendererBindings.Kind kind) { return component != null && component.getName().equals(kind.name()); }
    private JComponent create(UiRendererBindings.Kind kind) {
        return switch (kind) {
            case COLUMN, CARD -> { JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); yield panel; }
            case TEXT, INT_TEXT -> new JLabel();
            case BUTTON -> new JButton();
            case INPUT -> new JTextField();
            case SPINNER -> { JProgressBar progress = new JProgressBar(); progress.setIndeterminate(true); yield progress; }
        };
    }

    private void configure(JComponent component, UiBridge.Node node, UiRendererBindings.Kind kind) {
        component.setName(kind.name());
        if (component instanceof JPanel panel) {
            boolean card = kind == UiRendererBindings.Kind.CARD;
            panel.setOpaque(card);
            panel.setBackground(card ? Color.WHITE : new Color(0, 0, 0, 0));
            panel.setBorder(card ? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xDCE2EA)), BorderFactory.createEmptyBorder(28, 28, 28, 28)) : null);
        } else if (component instanceof JLabel label) {
            label.setText(String.valueOf(value(node, "value")));
            label.setForeground(new Color(0x172033));
            label.setFont(label.getFont().deriveFont(Font.PLAIN, kind == UiRendererBindings.Kind.INT_TEXT ? 36f : 18f));
            label.getAccessibleContext().setAccessibleName(label.getText());
        } else if (component instanceof JTextField input) {
            input.setText(String.valueOf(value(node, "value")));
            input.setColumns(28);
            input.setFont(input.getFont().deriveFont(Font.PLAIN, 16f));
            input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xCAD3E0)), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            input.getAccessibleContext().setAccessibleName(String.valueOf(valueOr(node, "accessibilityLabel", "Input")));
            for (var listener : input.getActionListeners()) input.removeActionListener(listener);
            UiBridge.Prop action = node.props().get("onSubmit");
            if (action != null) input.addActionListener(event -> dispatch.accept(action(action.actionSlot(), input.getText())));
        } else if (component instanceof JButton button) {
            button.setText(String.valueOf(value(node, "text")));
            button.setFont(button.getFont().deriveFont(Font.BOLD, 15f));
            button.setForeground(Color.WHITE);
            button.setBackground(bindings.accent());
            button.setOpaque(true);
            button.setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
            button.getAccessibleContext().setAccessibleName(String.valueOf(valueOr(node, "accessibilityLabel", button.getText())));
            for (var listener : button.getActionListeners()) button.removeActionListener(listener);
            UiBridge.Prop action = node.props().get("onClick");
            if (action != null) button.addActionListener(event -> dispatch.accept(action(action.actionSlot(), "")));
        }
    }

    private Object action(int slot, String payload) { return bridge.action(slot, payload); }
    private Object value(UiBridge.Node node, String name) { UiBridge.Prop prop = node.props().get(name); return prop == null ? "" : prop.value(); }
    private Object valueOr(UiBridge.Node node, String name, Object fallback) { UiBridge.Prop prop = node.props().get(name); return prop == null ? fallback : prop.value(); }
    private void dispose(UiBridge.Identity identity) { JComponent removed = retained.remove(identity); if (removed != null) { disposedComponents++; if (removed instanceof java.awt.Container container) container.removeAll(); } }
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
    @Override public void close() { onEdt(() -> { closed = true; retained.clear(); tree = null; if (frame != null) frame.dispose(); frame = null; root = null; }); }
}
