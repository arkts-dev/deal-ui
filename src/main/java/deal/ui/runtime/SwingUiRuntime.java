package deal.ui.runtime;

import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class SwingUiRuntime implements AutoCloseable {
    public sealed interface Node permits CardNode, ColumnNode, TextNode, ButtonNode {}
    public record CardNode(List<Node> children) implements Node {
        public CardNode { children = List.copyOf(children); }
    }
    public record ColumnNode(List<Node> children) implements Node {
        public ColumnNode { children = List.copyOf(children); }
    }
    public record TextNode(String text) implements Node {
        public TextNode { Objects.requireNonNull(text); }
    }
    public record ButtonNode(String text, String actionType) implements Node {
        public ButtonNode { Objects.requireNonNull(text); Objects.requireNonNull(actionType); }
    }

    @FunctionalInterface
    public interface ActionDispatcher {
        void dispatch(String actionType);
    }

    private final ActionDispatcher dispatcher;
    private final String title;
    private JFrame frame;
    private JPanel root;
    private Node currentTree;

    public SwingUiRuntime(String title, ActionDispatcher dispatcher) {
        this.title = Objects.requireNonNull(title);
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    public void show(Node tree) {
        onEdt(() -> {
            if (GraphicsEnvironment.isHeadless()) {
                throw new IllegalStateException("Cannot show Swing UI in a headless environment");
            }
            ensureFrame();
            renderNow(tree);
            frame.setVisible(true);
        });
    }

    public void render(Node tree) {
        onEdt(() -> renderNow(tree));
    }

    public JComponent renderForTesting(Node tree) {
        AtomicReference<JComponent> result = new AtomicReference<>();
        onEdt(() -> result.set(build(tree)));
        return result.get();
    }

    public void capture(Path destination) {
        onEdt(() -> {
            if (frame == null || !frame.isDisplayable()) {
                throw new IllegalStateException("Cannot capture a UI before it is shown");
            }
            BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            try {
                frame.paint(graphics);
            } finally {
                graphics.dispose();
            }
            try {
                Path parent = destination.toAbsolutePath().normalize().getParent();
                if (parent != null) Files.createDirectories(parent);
                if (!ImageIO.write(image, "png", destination.toFile())) {
                    throw new IOException("No PNG writer is available");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to capture Swing UI to " + destination, failure);
            }
        });
    }

    public void clickButton(String text) {
        onEdt(() -> {
            JButton button = findButton(root, text);
            if (button == null) {
                throw new IllegalArgumentException("Button not found: " + text);
            }
            button.doClick();
        });
    }

    public Node currentTree() {
        return currentTree;
    }

    public boolean isDisplayable() {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        onEdt(() -> result.set(frame != null && frame.isDisplayable()));
        return result.get();
    }

    @Override
    public void close() {
        onEdt(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
                root = null;
            }
        });
    }

    private void ensureFrame() {
        if (frame != null) {
            return;
        }
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(480, 360));
        frame.setLocationByPlatform(true);
        root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
        root.setBackground(new Color(0xF3F0E8));
        frame.setContentPane(root);
    }

    private void renderNow(Node tree) {
        Objects.requireNonNull(tree);
        currentTree = tree;
        if (root == null) {
            return;
        }
        root.removeAll();
        root.add(build(tree), BorderLayout.CENTER);
        root.revalidate();
        root.repaint();
        frame.pack();
    }

    private JComponent build(Node node) {
        if (node instanceof CardNode card) {
            JPanel panel = verticalPanel();
            panel.setName("Card");
            panel.setOpaque(true);
            panel.setBackground(Color.WHITE);
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD2CCBD), 1, true),
                BorderFactory.createEmptyBorder(24, 24, 24, 24)));
            addChildren(panel, card.children());
            return panel;
        }
        if (node instanceof ColumnNode column) {
            JPanel panel = verticalPanel();
            panel.setName("Column");
            panel.setOpaque(false);
            addChildren(panel, column.children());
            return panel;
        }
        if (node instanceof TextNode text) {
            JLabel label = new JLabel(text.text());
            label.setName("Text");
            label.setFont(label.getFont().deriveFont(Font.PLAIN, 17.0f));
            label.setForeground(new Color(0x28251F));
            label.getAccessibleContext().setAccessibleName(text.text());
            return label;
        }
        ButtonNode button = (ButtonNode) node;
        JButton component = new JButton(button.text());
        component.setName("Button");
        component.setFont(component.getFont().deriveFont(Font.BOLD, 15.0f));
        component.setFocusPainted(true);
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        component.getAccessibleContext().setAccessibleName(button.text());
        ActionListener listener = event -> dispatcher.dispatch(button.actionType());
        component.addActionListener(listener);
        return component;
    }

    private JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private void addChildren(JPanel parent, List<Node> children) {
        for (int i = 0; i < children.size(); i++) {
            JComponent child = build(children.get(i));
            child.setAlignmentX(Component.LEFT_ALIGNMENT);
            parent.add(child);
            if (i + 1 < children.size()) {
                parent.add(Box.createRigidArea(new Dimension(0, 12)));
            }
        }
    }

    private JButton findButton(Component component, String text) {
        if (component instanceof JButton button && button.getText().equals(text)) {
            return button;
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Swing event dispatch", interrupted);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Swing event dispatch failed", cause);
        }
    }
}
