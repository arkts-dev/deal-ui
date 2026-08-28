package deal.ui.runtime;

import deal.ui.UiBridge;
import deal.ui.UiRendererBindings;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FocusTraversalPolicy;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class SwingUiRuntime implements AutoCloseable {
    @FunctionalInterface public interface Dispatch { void accept(UiBridge.ActionValue action); }

    private final String title;
    private final UiRendererBindings bindings;
    private final UiBridge bridge;
    private final Dispatch dispatch;
    private Map<UiBridge.Identity, JComponent> retained = new LinkedHashMap<>();
    private JFrame frame;
    private HostLayers host;
    private UiBridge.Node tree;
    private Timer scopeTimer;
    private boolean closed;
    private boolean lastApplyOnEdt;
    private long disposedComponents;
    private long focusRevision = Long.MIN_VALUE;
    private String requestedFocusId = "";
    private Runnable closeRequest = () -> {};
    private java.util.function.Consumer<String> failureInjector = ignored -> {};

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
            install(next);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            SwingUtilities.invokeLater(() -> requestFocus(requestedFocusId));
        });
    }

    public void apply(List<UiBridge.Patch> patches, UiBridge.Node next) {
        Objects.requireNonNull(patches);
        onEdt(() -> {
            lastApplyOnEdt = SwingUtilities.isEventDispatchThread();
            install(patches, next, true);
        });
    }

    public JComponent componentForTesting(UiBridge.Node next) {
        AtomicReference<JComponent> result = new AtomicReference<>();
        onEdt(() -> {
            if (!next.equals(tree)) install(next);
            result.set(retained.get(next.identity()));
        });
        return result.get();
    }

    public void onCloseRequest(Runnable action) { closeRequest = Objects.requireNonNull(action); }
    public void requestCloseForTesting() { onEdt(closeRequest); }
    public UiBridge.Node tree() { return tree; }
    public boolean lastApplyOnEdt() { return lastApplyOnEdt; }
    public long disposedComponents() { return disposedComponents; }
    public String requestedFocusIdForTesting() { return requestedFocusId; }
    public boolean modalVisibleForTesting() { AtomicReference<Boolean> result = new AtomicReference<>(); onEdt(() -> result.set(host != null && host.modal() != null)); return result.get(); }
    public JComponent modalForTesting() { AtomicReference<JComponent> result = new AtomicReference<>(); onEdt(() -> result.set(host == null ? null : host.modal())); return result.get(); }
    public boolean escapeBoundForTesting() { AtomicReference<Boolean> result = new AtomicReference<>(); onEdt(() -> result.set(host != null && "deal.modal.escape".equals(host.overlay().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE"))))); return result.get(); }
    public boolean underlyingEnabledForTesting() { AtomicReference<Boolean> result = new AtomicReference<>(); onEdt(() -> result.set(host == null || host.baseEnabled())); return result.get(); }
    public void pressEscapeForTesting() { onEdt(() -> { if (host == null || host.modal() == null) return; javax.swing.Action action = host.overlay().getActionMap().get("deal.modal.escape"); if (action != null) action.actionPerformed(new ActionEvent(host.overlay(), ActionEvent.ACTION_PERFORMED, "Escape")); }); }
    public void fireScopeTimerForTesting() { onEdt(() -> { if (scopeTimer != null) for (var listener : scopeTimer.getActionListeners()) listener.actionPerformed(new ActionEvent(scopeTimer, ActionEvent.ACTION_PERFORMED, "test")); }); }
    public boolean scopeTimerRunningForTesting() { AtomicReference<Boolean> result = new AtomicReference<>(); onEdt(() -> result.set(scopeTimer != null && scopeTimer.isRunning())); return result.get(); }
    public void failureInjectorForTesting(java.util.function.Consumer<String> injector) { failureInjector = Objects.requireNonNull(injector); }
    public Component modalFocusAfterForTesting(Component component) { AtomicReference<Component> result = new AtomicReference<>(); onEdt(() -> result.set(host == null ? null : host.focusAfter(component))); return result.get(); }
    public Component modalFocusBeforeForTesting(Component component) { AtomicReference<Component> result = new AtomicReference<>(); onEdt(() -> result.set(host == null ? null : host.focusBefore(component))); return result.get(); }
    public void click(String text) { onEdt(() -> { JButton button = find(host, text); if (button == null) throw new IllegalArgumentException("Button not found: " + text); button.doClick(); }); }
    public void capture(Path path) { onEdt(() -> captureNow(path)); }

    private void ensureFrame() {
        if (frame != null) return;
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowClosing(java.awt.event.WindowEvent event) { closeRequest.run(); } });
        frame.setMinimumSize(new Dimension(680, 520));
        ensureHost();
        frame.setContentPane(host);
    }

    private void ensureHost() {
        if (host != null) return;
        host = new HostLayers(bindings.background());
    }

    private void install(UiBridge.Node next) { install(List.of(), next, false); }

    private void install(List<UiBridge.Patch> patches, UiBridge.Node next, boolean patchDriven) {
        if (closed) return;
        Objects.requireNonNull(next);
        ensureHost();
        LiveSnapshot snapshot = LiveSnapshot.capture(retained.values(), host, scopeTimer);
        StagedTree staged = null;
        Map<UiBridge.Identity, JComponent> previous = retained;
        Timer previousTimer = scopeTimer;
        try {
            staged = stage(patches, next, patchDriven);
            host.validateInstall(staged.root(), staged.modal());
            for (ComponentPlan plan : staged.plans().values()) installChildren(plan, staged.plans());
            validateFocus(staged.focusTarget(), staged.modal());
            for (ComponentPlan plan : staged.plans().values()) if (plan.staged() != plan.committed()) {
                copyConfiguration(plan.staged(), plan.committed());
                failureInjector.accept("configuration-copy");
            }
            host.install(staged.root(), staged.modal(), staged.escapeAction(), failureInjector);
            if (staged.timer() != null) staged.timer().start();
            retained = staged.components();
            tree = next;
            scopeTimer = staged.timer();
            focusRevision = staged.focusRevision();
            requestedFocusId = staged.focusIntent();
        } catch (RuntimeException | Error failure) {
            snapshot.restore();
            if (staged != null) disposeStaged(staged.newOwned(), failure);
            throw failure;
        }
        if (previousTimer != null) previousTimer.stop();
        if (staged.focusTarget() != null) staged.focusTarget().requestFocusInWindow();
        if (frame != null) frame.pack();
        for (Owned owned : staged.displaced()) {
            JComponent component = owned.component();
            if (component.getParent() != null) component.getParent().remove(component);
            try { owned.binding().disposer().accept(component); } catch (RuntimeException | Error failure) { UiRendererBindings.reportCleanupFailure(failure); }
            disposedComponents++;
        }
        previous.clear();
        disposeOwned(staged.abandoned());
    }

    private StagedTree stage(List<UiBridge.Patch> patches, UiBridge.Node next, boolean patchDriven) {
        Map<UiBridge.Identity, UiBridge.Node> oldNodes = indexTree(tree);
        Map<UiBridge.Identity, UiBridge.Node> nextNodes = indexTree(next);
        Set<UiBridge.Identity> updates = patchDriven ? validatePatches(patches, oldNodes, nextNodes) : new HashSet<>();
        Set<UiBridge.Identity> disposals = patchDriven ? disposalIdentities(patches) : Set.of();
        Set<UiBridge.Identity> replacements = new HashSet<>(disposals);
        Map<UiBridge.Identity, ComponentPlan> plans = new LinkedHashMap<>();
        List<Owned> owned = new ArrayList<>();
        List<Owned> displaced = new ArrayList<>();
        StageContext context = new StageContext();
        try {
            stageNode(next, false, replacements, oldNodes, plans, owned, context);
            Set<JComponent> committed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (ComponentPlan plan : plans.values()) committed.add(plan.committed());
            Set<JComponent> displacedComponents = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map.Entry<UiBridge.Identity, JComponent> entry : retained.entrySet()) {
                JComponent component = entry.getValue();
                if ((!committed.contains(component) || replacements.contains(entry.getKey())) && displacedComponents.add(component)) {
                    UiBridge.Node oldNode = oldNodes.get(entry.getKey());
                    displaced.add(new Owned(component, bindings.require(oldNode.component())));
                }
            }
            for (Owned entry : displaced) entry.binding().disposalPrepare().accept(entry.component());
            long nextFocusRevision = focusRevision;
            String focusIntent = requestedFocusId;
            JComponent focusTarget = null;
            Object revisionValue = valueOr(next, "focusRevision", Long.MIN_VALUE);
            if (revisionValue instanceof Number number && number.longValue() != focusRevision) {
                nextFocusRevision = number.longValue();
                focusIntent = String.valueOf(valueOr(next, "focusIntent", ""));
                focusTarget = findFocus(plans.values().stream().map(ComponentPlan::committed).toList(), focusIntent);
            }
            Timer timer = timer(context.modalNode());
            Runnable escape = escapeAction(context.modalNode());
            Map<UiBridge.Identity, JComponent> components = new LinkedHashMap<>();
            for (Map.Entry<UiBridge.Identity, ComponentPlan> entry : plans.entrySet()) components.put(entry.getKey(), entry.getValue().committed());
            ComponentPlan rootPlan = plans.get(next.identity());
            JComponent root = context.modalNode() != null && context.modalNode().identity().equals(next.identity()) ? null : rootPlan.committed();
            JComponent modal = context.modalNode() == null ? null : plans.get(context.modalNode().identity()).committed();
            Set<JComponent> live = Collections.newSetFromMap(new IdentityHashMap<>());
            live.addAll(components.values());
            List<Owned> abandoned = owned.stream().filter(entry -> !live.contains(entry.component())).toList();
            return new StagedTree(root, modal, components, plans, abandoned, List.copyOf(owned), List.copyOf(displaced), timer, escape, nextFocusRevision, focusIntent, focusTarget);
        } catch (RuntimeException | Error failure) {
            disposeStaged(owned, failure);
            throw failure;
        }
    }

    private void stageNode(UiBridge.Node node, boolean insideModal, Set<UiBridge.Identity> replacements, Map<UiBridge.Identity, UiBridge.Node> oldNodes, Map<UiBridge.Identity, ComponentPlan> plans, List<Owned> owned, StageContext context) {
        UiRendererBindings.Binding binding = bindings.require(node.component());
        boolean modal = binding.hostKind() == UiRendererBindings.HostKind.MODAL;
        if (modal && (insideModal || context.modalNode() != null)) throw new IllegalStateException("Exactly one top-level modal host is supported");
        JComponent staged = Objects.requireNonNull(binding.factory().get(), "Renderer factory returned null for " + node.component());
        owned.add(new Owned(staged, binding));
        binding.configurator().apply(staged, node, bridge, dispatch::accept, bindings);
        UiBridge.Node oldNode = oldNodes.get(node.identity());
        JComponent oldComponent = retained.get(node.identity());
        boolean compatible = oldNode != null && oldNode.component().equals(node.component()) && oldComponent != null;
        boolean retain = compatible && !replacements.contains(node.identity());
        JComponent committed = retain ? oldComponent : staged;
        if (!node.children().isEmpty() && !(committed instanceof JPanel) || !node.children().isEmpty() && !(staged instanceof JPanel)) throw new IllegalStateException("Renderer host cannot contain children: " + node.component());
        int spacing = spacing(node);
        List<JComponent> gaps = new ArrayList<>();
        int normalChildren = 0;
        for (UiBridge.Node child : node.children()) if (bindings.require(child.component()).hostKind() != UiRendererBindings.HostKind.MODAL) normalChildren++;
        for (int index = 1; index < normalChildren && spacing > 0; index++) gaps.add((JComponent) Box.createRigidArea(new Dimension(0, spacing)));
        plans.put(node.identity(), new ComponentPlan(node, staged, committed, List.copyOf(gaps)));
        if (modal) context.setModalNode(node);
        for (UiBridge.Node child : node.children()) stageNode(child, insideModal || modal, replacements, oldNodes, plans, owned, context);
    }

    private static Map<UiBridge.Identity, UiBridge.Node> indexTree(UiBridge.Node root) {
        Map<UiBridge.Identity, UiBridge.Node> nodes = new LinkedHashMap<>();
        if (root != null) indexNode(root, nodes);
        return nodes;
    }

    private static void indexNode(UiBridge.Node node, Map<UiBridge.Identity, UiBridge.Node> nodes) {
        if (nodes.putIfAbsent(node.identity(), node) != null) throw new IllegalStateException("Duplicate identity: " + node.identity());
        for (UiBridge.Node child : node.children()) indexNode(child, nodes);
    }

    private static Set<UiBridge.Identity> validatePatches(List<UiBridge.Patch> patches, Map<UiBridge.Identity, UiBridge.Node> oldNodes, Map<UiBridge.Identity, UiBridge.Node> nextNodes) {
        Set<UiBridge.Identity> updates = new HashSet<>();
        Set<UiBridge.Identity> creates = new HashSet<>();
        Set<UiBridge.Identity> disposals = new HashSet<>();
        for (UiBridge.Patch patch : patches) {
            Objects.requireNonNull(patch);
            switch (patch.kind()) {
                case "update" -> { if (!updates.add(patch.identity())) throw new IllegalStateException("Duplicate update patch identity: " + patch.identity()); }
                case "create" -> { if (!creates.add(patch.identity())) throw new IllegalStateException("Duplicate create patch identity: " + patch.identity()); }
                case "dispose" -> { if (!disposals.add(patch.identity())) throw new IllegalStateException("Duplicate dispose patch identity: " + patch.identity()); }
                default -> throw new IllegalStateException("Unknown renderer patch: " + patch.kind());
            }
        }
        for (UiBridge.Identity identity : updates) if (!nextNodes.containsKey(identity)) throw new IllegalStateException("Invalid update patch identity: " + identity);
        for (UiBridge.Identity identity : creates) if (!nextNodes.containsKey(identity)) throw new IllegalStateException("Invalid create patch identity: " + identity);
        return updates;
    }

    private static Set<UiBridge.Identity> disposalIdentities(List<UiBridge.Patch> patches) {
        Set<UiBridge.Identity> result = new HashSet<>();
        for (UiBridge.Patch patch : patches) if (patch.kind().equals("dispose")) result.add(patch.identity());
        return result;
    }

    private void installChildren(ComponentPlan plan, Map<UiBridge.Identity, ComponentPlan> plans) {
        if (!(plan.committed() instanceof JPanel panel)) return;
        panel.removeAll();
        int gap = 0;
        int child = 0;
        for (UiBridge.Node childNode : plan.node().children()) {
            ComponentPlan childPlan = plans.get(childNode.identity());
            if (childPlan == null || childPlan.committed() == null || childPlan.committed() == plan.committed() || bindings.require(childNode.component()).hostKind() == UiRendererBindings.HostKind.MODAL) continue;
            if (child > 0 && gap < plan.gaps().size()) panel.add(plan.gaps().get(gap++));
            childPlan.committed().setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(childPlan.committed());
            failureInjector.accept("child-install");
            child++;
        }
    }

    private Timer timer(UiBridge.Node modalNode) {
        if (modalNode == null) return null;
        UiBridge.Prop action = modalNode.props().get("onScopeTick");
        UiBridge.Prop scope = modalNode.props().get("scopeRevision");
        if (action == null || action.actionSlot() < 0 || scope == null || !(scope.value() instanceof Number number)) return null;
        Object intervalValue = valueOr(modalNode, "scopeInterval", 1000L);
        int interval = intervalValue instanceof Number value ? Math.max(1, value.intValue()) : 1000;
        long revision = number.longValue();
        Timer timer = new Timer(interval, event -> dispatch.accept(bridge.action(action.actionSlot(), revision)));
        timer.setInitialDelay(interval);
        timer.setRepeats(true);
        return timer;
    }

    private Runnable escapeAction(UiBridge.Node modalNode) {
        if (modalNode == null) return null;
        UiBridge.Prop action = modalNode.props().get("onEscape");
        if (action == null || action.actionSlot() < 0) return null;
        return () -> dispatch.accept(bridge.action(action.actionSlot(), null));
    }

    private static final String INPUT_INGRESS = "deal.inputIngress";

    private static void copyConfiguration(JComponent source, JComponent target) {
        target.setName(source.getName());
        target.setOpaque(source.isOpaque());
        target.setBackground(source.getBackground());
        target.setForeground(source.getForeground());
        target.setFont(source.getFont());
        target.setBorder(source.getBorder());
        target.setFocusable(source.isFocusable());
        target.setCursor(source.getCursor());
        target.setEnabled(source.isEnabled());
        target.setToolTipText(source.getToolTipText());
        target.putClientProperty("deal.focusId", source.getClientProperty("deal.focusId"));
        if (source instanceof JLabel from && target instanceof JLabel to) {
            to.setText(from.getText());
            to.setIcon(from.getIcon());
            to.setHorizontalAlignment(from.getHorizontalAlignment());
        } else if (source instanceof JTextField from && target instanceof JTextField to) {
            InputIngress ingress = inputIngress(to);
            if (ingress != null) ingress.suspend();
            try { to.setText(from.getText()); } finally { if (ingress != null) ingress.resume(); }
            to.setColumns(from.getColumns());
            InputIngress configured = inputIngress(from);
            configureInputIngress(to, configured == null ? null : configured.copyFor(to));
            to.getAccessibleContext().setAccessibleName(from.getAccessibleContext().getAccessibleName());
            to.getAccessibleContext().setAccessibleDescription(from.getAccessibleContext().getAccessibleDescription());
        } else if (source instanceof JButton from && target instanceof JButton to) {
            to.setText(from.getText());
            to.setIcon(from.getIcon());
            to.setFocusPainted(from.isFocusPainted());
            for (var listener : to.getActionListeners()) to.removeActionListener(listener);
            for (var listener : from.getActionListeners()) to.addActionListener(listener);
            to.getAccessibleContext().setAccessibleName(from.getAccessibleContext().getAccessibleName());
            to.getAccessibleContext().setAccessibleDescription(from.getAccessibleContext().getAccessibleDescription());
        } else if (source instanceof JPanel from && target instanceof JPanel to) {
            to.getAccessibleContext().setAccessibleName(from.getAccessibleContext().getAccessibleName());
            to.getAccessibleContext().setAccessibleDescription(from.getAccessibleContext().getAccessibleDescription());
        }
    }

    private void requestFocus(String focusId) {
        JComponent target = findFocus(retained.values(), focusId);
        if (target != null) target.requestFocusInWindow();
    }

    private static JComponent findFocus(Iterable<JComponent> components, String focusId) {
        if (focusId.isEmpty()) return null;
        for (JComponent component : components) if (focusId.equals(component.getClientProperty("deal.focusId"))) return component;
        return null;
    }

    private static void validateFocus(JComponent target, JComponent modal) {
        if (target != null && modal != null && target != modal && !SwingUtilities.isDescendingFrom(target, modal)) throw new IllegalStateException("Modal focus target is outside modal");
    }

    private void disposePrevious(Map<UiBridge.Identity, JComponent> previous) {
        for (JComponent component : unique(previous)) {
            if (component.getParent() != null) component.getParent().remove(component);
            bindings.dispose(component);
            disposedComponents++;
        }
        previous.clear();
    }

    private void disposeStaged(List<Owned> components, Throwable failure) {
        for (Owned owned : uniqueOwned(components)) try { owned.binding().disposer().accept(owned.component()); } catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    private void disposeOwned(List<Owned> components) {
        for (Owned owned : uniqueOwned(components)) try { owned.binding().disposer().accept(owned.component()); } catch (RuntimeException | Error cleanupFailure) { UiRendererBindings.reportCleanupFailure(cleanupFailure); }
    }

    private static List<Owned> uniqueOwned(Iterable<Owned> components) {
        Set<JComponent> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Owned> result = new ArrayList<>();
        for (Owned owned : components) if (seen.add(owned.component())) result.add(owned);
        return List.copyOf(result);
    }

    private static List<JComponent> unique(Map<UiBridge.Identity, JComponent> components) { return unique(components.values()); }

    private static List<JComponent> unique(Iterable<JComponent> components) {
        Set<JComponent> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<JComponent> result = new ArrayList<>();
        for (JComponent component : components) if (seen.add(component)) result.add(component);
        return List.copyOf(result);
    }

    private int spacing(UiBridge.Node node) {
        UiBridge.Prop prop = node.props().get("spacing");
        return prop != null && prop.value() instanceof String token ? bindings.spacing(token) : 0;
    }

    public static void configureHostComponent(JComponent component, UiBridge.Node node, UiBridge bridge, java.util.function.Consumer<UiBridge.ActionValue> dispatch, UiRendererBindings bindings) {
        String binding = node.component();
        component.setName(binding);
        Object focusId = valueOr(node, "focusId", "");
        component.putClientProperty("deal.focusId", String.valueOf(focusId));
        if (!String.valueOf(focusId).isEmpty()) component.setFocusable(true);
        if (component instanceof JPanel panel) {
            boolean card = binding.endsWith("Card") || bindings.require(binding).hostKind() == UiRendererBindings.HostKind.MODAL;
            panel.setOpaque(card);
            panel.setBackground(card ? Color.WHITE : new Color(0, 0, 0, 0));
            panel.setBorder(card ? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xDCE2EA)), BorderFactory.createEmptyBorder(28, 28, 28, 28)) : null);
            if (bindings.require(binding).hostKind() == UiRendererBindings.HostKind.MODAL) {
                panel.getAccessibleContext().setAccessibleName(String.valueOf(valueOr(node, "accessibilityLabel", "Dialog")));
                panel.getAccessibleContext().setAccessibleDescription(String.valueOf(valueOr(node, "accessibilityDescription", "")));
            }
        } else if (component instanceof JLabel label) {
            label.setText(String.valueOf(value(node, "value")));
            label.setForeground(new Color(0x172033));
            String text = label.getText();
            float size = value(node, "value") instanceof Number ? 40f : text.equals(text.toUpperCase(java.util.Locale.ROOT)) && text.length() < 24 ? 13f : text.length() < 28 ? 28f : 17f;
            int style = size >= 28f || size == 13f ? Font.BOLD : Font.PLAIN;
            label.setFont(label.getFont().deriveFont(style, size));
            label.getAccessibleContext().setAccessibleName(label.getText());
        } else if (component instanceof JTextField input) {
            InputIngress ingress = inputIngress(input);
            if (ingress != null) ingress.suspend();
            try { input.setText(String.valueOf(value(node, "value"))); } finally { if (ingress != null) ingress.resume(); }
            input.setColumns(28);
            input.setFont(input.getFont().deriveFont(Font.PLAIN, 16f));
            input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xCAD3E0)), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            input.getAccessibleContext().setAccessibleName(String.valueOf(valueOr(node, "accessibilityLabel", "Input")));
            configureInputIngress(input, new InputIngress(input, actionDispatch(node, "onChange", bridge, dispatch), actionDispatch(node, "onBlur", bridge, dispatch), actionDispatch(node, "onSubmit", bridge, dispatch)));
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
            if (action != null) button.addActionListener(event -> dispatch.accept(bridge.action(action.actionSlot(), valueOr(node, "actionPayload", null))));
        }
    }

    private static java.util.function.Consumer<Object> actionDispatch(UiBridge.Node node, String name, UiBridge bridge, java.util.function.Consumer<UiBridge.ActionValue> dispatch) {
        UiBridge.Prop action = node.props().get(name);
        return action == null || action.actionSlot() < 0 ? null : payload -> dispatch.accept(bridge.action(action.actionSlot(), payload));
    }
    private static InputIngress inputIngress(JTextField input) { Object value = input.getClientProperty(INPUT_INGRESS); return value instanceof InputIngress ingress ? ingress : null; }
    private static void configureInputIngress(JTextField input, InputIngress next) {
        InputIngress previous = inputIngress(input);
        if (previous != null) previous.dispose();
        input.putClientProperty(INPUT_INGRESS, next);
        if (next != null) next.install();
    }
    public static void disposeHostComponent(JComponent component) { if (component instanceof JTextField input) configureInputIngress(input, null); }

    private static Object value(UiBridge.Node node, String name) { UiBridge.Prop prop = node.props().get(name); return prop == null ? "" : prop.value(); }
    private static Object valueOr(UiBridge.Node node, String name, Object fallback) { UiBridge.Prop prop = node.props().get(name); return prop == null ? fallback : prop.value(); }
    private JButton find(Component component, String text) { if (component instanceof JButton button && button.getText().equals(text)) return button; if (component instanceof Container container) for (Component child : container.getComponents()) { JButton result = find(child, text); if (result != null) return result; } return null; }
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
    @Override public void close() { onEdt(() -> { if (closed) return; closed = true; if (scopeTimer != null) scopeTimer.stop(); scopeTimer = null; disposePrevious(retained); retained = new LinkedHashMap<>(); tree = null; if (frame != null) frame.dispose(); frame = null; host = null; }); }

    private static final class InputIngress {
        private final JTextField input;
        private final java.util.function.Consumer<Object> change;
        private final java.util.function.Consumer<Object> blur;
        private final java.util.function.Consumer<Object> submit;
        private final DocumentListener documentListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { changed(event); }
            @Override public void removeUpdate(DocumentEvent event) { changed(event); }
            @Override public void changedUpdate(DocumentEvent event) { changed(event); }
        };
        private final FocusListener focusListener = new FocusAdapter() { @Override public void focusLost(FocusEvent event) { if (!event.isTemporary() && blur != null) blur.accept(null); } };
        private final java.awt.event.ActionListener actionListener;
        private int suspension;
        private boolean installed;
        private InputIngress(JTextField input, java.util.function.Consumer<Object> change, java.util.function.Consumer<Object> blur, java.util.function.Consumer<Object> submit) { this.input = input; this.change = change; this.blur = blur; this.submit = submit; this.actionListener = event -> { if (this.submit != null) this.submit.accept(this.input.getText()); }; }
        private InputIngress copyFor(JTextField target) { return new InputIngress(target, change, blur, submit); }
        private void install() {
            if (installed) return;
            installed = true;
            if (change != null) input.getDocument().addDocumentListener(documentListener);
            if (blur != null) input.addFocusListener(focusListener);
            if (submit != null) input.addActionListener(actionListener);
        }
        private void dispose() {
            if (!installed) return;
            if (change != null) input.getDocument().removeDocumentListener(documentListener);
            if (blur != null) input.removeFocusListener(focusListener);
            if (submit != null) input.removeActionListener(actionListener);
            installed = false;
        }
        private void suspend() { suspension++; }
        private void resume() { suspension--; }
        private void changed(DocumentEvent event) {
            if (suspension != 0 || change == null) return;
            try { change.accept(event.getDocument().getText(0, event.getDocument().getLength())); }
            catch (BadLocationException failure) { throw new IllegalStateException("Cannot snapshot input document", failure); }
        }
    }

    private record Owned(JComponent component, UiRendererBindings.Binding binding) {}
    private record ComponentPlan(UiBridge.Node node, JComponent staged, JComponent committed, List<JComponent> gaps) {}
    private record StagedTree(JComponent root, JComponent modal, Map<UiBridge.Identity, JComponent> components, Map<UiBridge.Identity, ComponentPlan> plans, List<Owned> abandoned, List<Owned> newOwned, List<Owned> displaced, Timer timer, Runnable escapeAction, long focusRevision, String focusIntent, JComponent focusTarget) {}
    private record ComponentState(JComponent component, String name, boolean opaque, Color background, Color foreground, Font font, javax.swing.border.Border border, boolean focusable, java.awt.Cursor cursor, boolean enabled, String tooltip, Object focusId, float alignmentX, String accessibleName, String accessibleDescription, String text, javax.swing.Icon icon, int horizontalAlignment, int columns, boolean focusPainted, java.awt.event.ActionListener[] listeners, InputIngress inputIngress) {
        private static ComponentState capture(JComponent component) {
            String text = component instanceof JLabel value ? value.getText() : component instanceof JTextField value ? value.getText() : component instanceof JButton value ? value.getText() : null;
            javax.swing.Icon icon = component instanceof JLabel value ? value.getIcon() : component instanceof JButton value ? value.getIcon() : null;
            int alignment = component instanceof JLabel value ? value.getHorizontalAlignment() : 0;
            int columns = component instanceof JTextField value ? value.getColumns() : 0;
            boolean focusPainted = component instanceof JButton value && value.isFocusPainted();
            InputIngress ingress = component instanceof JTextField value ? SwingUiRuntime.inputIngress(value) : null;
            java.awt.event.ActionListener[] listeners = component instanceof JTextField value ? java.util.Arrays.stream(value.getActionListeners()).filter(listener -> ingress == null || listener != ingress.actionListener).toArray(java.awt.event.ActionListener[]::new) : component instanceof JButton value ? value.getActionListeners() : new java.awt.event.ActionListener[0];
            return new ComponentState(component, component.getName(), component.isOpaque(), component.getBackground(), component.getForeground(), component.getFont(), component.getBorder(), component.isFocusable(), component.getCursor(), component.isEnabled(), component.getToolTipText(), component.getClientProperty("deal.focusId"), component.getAlignmentX(), component.getAccessibleContext().getAccessibleName(), component.getAccessibleContext().getAccessibleDescription(), text, icon, alignment, columns, focusPainted, listeners, ingress);
        }
        private void restore() {
            component.setName(name); component.setOpaque(opaque); component.setBackground(background); component.setForeground(foreground); component.setFont(font); component.setBorder(border); component.setFocusable(focusable); component.setCursor(cursor); component.setEnabled(enabled); component.setToolTipText(tooltip); component.putClientProperty("deal.focusId", focusId); component.setAlignmentX(alignmentX); component.getAccessibleContext().setAccessibleName(accessibleName); component.getAccessibleContext().setAccessibleDescription(accessibleDescription);
            if (component instanceof JLabel value) { value.setText(text); value.setIcon(icon); value.setHorizontalAlignment(horizontalAlignment); }
            if (component instanceof JTextField value) { InputIngress current = SwingUiRuntime.inputIngress(value); if (current != null) current.suspend(); try { value.setText(text); } finally { if (current != null) current.resume(); } value.setColumns(columns); configureInputIngress(value, inputIngress == null ? null : inputIngress.copyFor(value)); InputIngress restored = SwingUiRuntime.inputIngress(value); for (var listener : value.getActionListeners()) if (restored == null || listener != restored.actionListener) value.removeActionListener(listener); for (var listener : listeners) value.addActionListener(listener); }
            if (component instanceof JButton value) { value.setText(text); value.setIcon(icon); value.setFocusPainted(focusPainted); for (var listener : value.getActionListeners()) value.removeActionListener(listener); for (var listener : listeners) value.addActionListener(listener); }
        }
    }
    private record ContainerState(Container container, Component[] children) {
        private void restore() { container.removeAll(); for (Component child : children) container.add(child); }
    }
    private record LiveSnapshot(List<ComponentState> components, List<ContainerState> containers, HostLayers.HostState hostState, Timer timer, boolean timerRunning) {
        private static LiveSnapshot capture(Iterable<JComponent> retained, HostLayers host, Timer timer) {
            Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            List<ComponentState> components = new ArrayList<>();
            List<ContainerState> containers = new ArrayList<>();
            capture(host, seen, components, containers);
            for (JComponent component : retained) capture(component, seen, components, containers);
            return new LiveSnapshot(List.copyOf(components), List.copyOf(containers), host.snapshot(), timer, timer != null && timer.isRunning());
        }
        private static void capture(Component component, Set<Component> seen, List<ComponentState> components, List<ContainerState> containers) {
            if (component == null || !seen.add(component)) return;
            if (component instanceof JComponent value) components.add(ComponentState.capture(value));
            if (component instanceof Container value) { Component[] children = value.getComponents(); containers.add(new ContainerState(value, children)); for (Component child : children) capture(child, seen, components, containers); }
        }
        private void restore() {
            for (ContainerState state : containers) state.container().removeAll();
            for (ComponentState state : components) state.restore();
            for (ContainerState state : containers.reversed()) state.restore();
            hostState.restore();
            if (timer != null) { if (timerRunning) timer.start(); else timer.stop(); }
        }
    }
    private static final class StageContext {
        private UiBridge.Node modalNode;
        private UiBridge.Node modalNode() { return modalNode; }
        private void setModalNode(UiBridge.Node node) { modalNode = node; }
    }
    private static final class HostLayers extends JLayeredPane {
        private static final long serialVersionUID = 1L;
        private final JPanel base = new JPanel(new BorderLayout());
        private final JPanel overlay = new JPanel(new GridBagLayout());
        private JComponent modal;
        private HostLayers(Color background) {
            base.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));
            base.setBackground(background);
            overlay.setOpaque(true);
            overlay.setBackground(new Color(20, 25, 38, 150));
            overlay.setFocusCycleRoot(true);
            add(base, JLayeredPane.DEFAULT_LAYER);
        }
        private void validateInstall(JComponent root, JComponent nextModal) {
            if (root != null && root == nextModal) throw new IllegalStateException("Root and modal must be distinct");
            if (nextModal != null) new ModalFocusPolicy(nextModal);
        }
        private void install(JComponent root, JComponent nextModal, Runnable escape, java.util.function.Consumer<String> failureInjector) {
            base.removeAll();
            if (root != null) base.add(root, BorderLayout.CENTER);
            setEnabledRecursively(base, nextModal == null);
            if (overlay.getParent() == this) remove(overlay);
            overlay.removeAll();
            modal = nextModal;
            if (modal != null) {
                overlay.setFocusTraversalPolicy(new ModalFocusPolicy(modal));
                overlay.add(modal);
                overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "deal.modal.escape");
                overlay.getActionMap().put("deal.modal.escape", new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { if (escape != null) escape.run(); } });
                add(overlay, JLayeredPane.MODAL_LAYER);
            } else {
                overlay.setFocusTraversalPolicy(null);
                overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(KeyStroke.getKeyStroke("ESCAPE"));
                overlay.getActionMap().remove("deal.modal.escape");
            }
            failureInjector.accept("host-install");
            revalidate();
            repaint();
        }
        private HostState snapshot() { return new HostState(this, modal, overlay.getFocusTraversalPolicy(), overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("ESCAPE")), overlay.getActionMap().get("deal.modal.escape")); }
        private static void setEnabledRecursively(Component component, boolean enabled) { component.setEnabled(enabled); if (component instanceof Container container) for (Component child : container.getComponents()) setEnabledRecursively(child, enabled); }
        private JComponent modal() { return modal; }
        private JPanel overlay() { return overlay; }
        private boolean baseEnabled() { return base.isEnabled(); }
        private Component focusAfter(Component component) { FocusTraversalPolicy policy = overlay.getFocusTraversalPolicy(); return policy == null ? null : policy.getComponentAfter(overlay, component); }
        private Component focusBefore(Component component) { FocusTraversalPolicy policy = overlay.getFocusTraversalPolicy(); return policy == null ? null : policy.getComponentBefore(overlay, component); }
        private record HostState(HostLayers host, JComponent modal, FocusTraversalPolicy policy, Object escapeKey, javax.swing.Action escapeAction) {
            private void restore() {
                host.modal = modal;
                host.overlay.setFocusTraversalPolicy(policy);
                KeyStroke escape = KeyStroke.getKeyStroke("ESCAPE");
                if (escapeKey == null) host.overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).remove(escape); else host.overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escape, escapeKey);
                if (escapeAction == null) host.overlay.getActionMap().remove("deal.modal.escape"); else host.overlay.getActionMap().put("deal.modal.escape", escapeAction);
                host.revalidate(); host.repaint();
            }
        }
        @Override public void doLayout() { base.setBounds(0, 0, getWidth(), getHeight()); overlay.setBounds(0, 0, getWidth(), getHeight()); }
        @Override public Dimension getPreferredSize() { Dimension baseSize = base.getPreferredSize(); Dimension modalSize = modal == null ? new Dimension() : modal.getPreferredSize(); return new Dimension(Math.max(baseSize.width, modalSize.width + 80), Math.max(baseSize.height, modalSize.height + 80)); }
    }
    private static final class ModalFocusPolicy extends FocusTraversalPolicy {
        private final JComponent modal;
        private final List<Component> focusable;
        private ModalFocusPolicy(JComponent modal) {
            this.modal = Objects.requireNonNull(modal);
            List<Component> targets = new ArrayList<>();
            for (Component child : modal.getComponents()) collect(child, targets);
            if (targets.isEmpty()) targets.add(modal);
            focusable = List.copyOf(targets);
        }
        private static void collect(Component component, List<Component> targets) {
            if (component.isFocusable() && component.isEnabled() && component.isVisible()) targets.add(component);
            if (component instanceof Container container) for (Component child : container.getComponents()) collect(child, targets);
        }
        private Component move(Component component, int direction) {
            int index = focusable.indexOf(component);
            if (index < 0) return direction > 0 ? getFirstComponent(null) : getLastComponent(null);
            return focusable.get(Math.floorMod(index + direction, focusable.size()));
        }
        @Override public Component getComponentAfter(Container container, Component component) { return move(component, 1); }
        @Override public Component getComponentBefore(Container container, Component component) { return move(component, -1); }
        @Override public Component getFirstComponent(Container container) { return focusable.getFirst(); }
        @Override public Component getLastComponent(Container container) { return focusable.getLast(); }
        @Override public Component getDefaultComponent(Container container) { return getFirstComponent(container); }
        @Override public Component getInitialComponent(Window window) { return getDefaultComponent(modal); }
    }
}
