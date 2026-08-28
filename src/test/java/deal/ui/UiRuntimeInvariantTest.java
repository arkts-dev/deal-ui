package deal.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UiRuntimeInvariantTest {
    private static int passed;

    private UiRuntimeInvariantTest() {}

    public static void main(String[] args) throws Exception {
        queuedAndNestedActionsAreFifo();
        failedCandidateRetainsState();
        overlappingEffectsCompleteInArrivalOrder();
        sameKeyNoneCompletesInPhysicalOrder();
        keyedReplacementSuppressesPendingInvocation();
        keyedCancellationSuppressesInvocation();
        cancellationBetweenRegistrationAndFuturePublicationRetiresOnce();
        closeBetweenRegistrationAndFuturePublicationRetiresOnce();
        runningInterruptHonoringEffectBalancesIdle();
        runningInterruptDelayingEffectBalancesIdle();
        effectFailureMapsToCompletion();
        unmappedEffectRejectionBalancesIdle();
        disposalRejectsEffectCompletion();
        closeWaitsForOutstandingEffectExit();
        equivalentRootsAndRendererFailureRetainCommit();
        rejectedSubmissionBalancesPending();
        rejectedEffectSubmissionBalancesIdle();
        queuePolicyFailuresBalanceIdle();
        completionPolicyFailuresBalanceIdle();
        dequeueAndFinishFailuresTerminateRuntime();
        rejectedCompletionSubmissionTerminatesRuntime();
        initializationFailureCleansOwnedResources();
        compatibleIdentityIsRetained();
        mutationFailuresRestoreExactLiveGraph();
        sameIdentityReplacementPublishesNewComponent();
        modalFocusTraversalIsConstrainedAndWraps();
        duplicateIdentityLeavesLiveGraphUntouched();
        disposerPreflightProtectsLiveGraph();
        preparedReplacementIsInstalled();
        detachedSnapshotSurvivesCandidateRelease();
        stagedCleanupIsExhaustive();
        cleanupReportingNeverEscapes();
        windowCloseDisposesRuntimeOnce();
        System.out.println("Runtime invariants passed: " + passed);
    }

    private static void queuedAndNestedActionsAreFifo() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            bridge.nested = () -> runtime.dispatch(action("N"));
            runtime.dispatch(action("A"));
            runtime.dispatch(action("B"));
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.stateSnapshot().get("value").equals("ABN"), "multiple and nested actions preserve FIFO order");
        }
    }

    private static void failedCandidateRetainsState() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        try (UiProgramRuntime runtime = runtime(bridge, transitions, new ManualExecutor())) {
            runtime.dispatch(action("A"));
            runtime.dispatch(action("FAIL"));
            runtime.dispatch(action("B"));
            transitions.runAll();
            expectFailure(runtime, "candidate failure");
            check(runtime.stateSnapshot().get("value").equals("AB"), "failed candidate retains committed state and later queue work");
            check(bridge.startedEffects == 0, "failed candidate starts no effect");
        }
    }

    private static void overlappingEffectsCompleteInArrivalOrder() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            runtime.dispatch(action("E2"));
            transitions.runAll();
            check(effects.size() == 2, "effects overlap after commits");
            effects.run(1);
            transitions.runAll();
            effects.run(0);
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.stateSnapshot().get("value").equals("E1E2C2C1"), "effect completions commit in host arrival order");
            check(runtime.renderer().lastApplyOnEdt(), "all invariant patches apply on EDT");
        }
    }

    private static void sameKeyNoneCompletesInPhysicalOrder() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.commands.put("E1", new UiBridge.EffectCommand("start", "search", 0, "none"));
        bridge.commands.put("E2", new UiBridge.EffectCommand("start", "search", 0, "none"));
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            runtime.dispatch(action("E2"));
            transitions.runAll();
            effects.run(1);
            transitions.runAll();
            effects.run(0);
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.stateSnapshot().get("value").equals("E1E2C2C1") && bridge.effectRuns == 2, "same-key none effects overlap and complete in physical order");
        }
    }

    private static void keyedReplacementSuppressesPendingInvocation() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.commands.put("E1", new UiBridge.EffectCommand("start", "search", 10, "replace"));
        bridge.commands.put("E2", new UiBridge.EffectCommand("start", "search", 10, "replace"));
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            runtime.dispatch(action("E2"));
            transitions.runAll();
            check(effects.size() == 2, "replacement retains deterministic scheduler entries");
            effects.run(0);
            effects.run(0);
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.stateSnapshot().get("value").equals("E1E2C2") && bridge.effectRuns == 1, "keyed replacement suppresses pending invocation and completion");
        }
    }

    private static void keyedCancellationSuppressesInvocation() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.commands.put("E1", new UiBridge.EffectCommand("start", "search", 10, "replace"));
        bridge.commands.put("CANCEL", new UiBridge.EffectCommand("cancel", "search", 0, "interrupt"));
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            runtime.dispatch(action("CANCEL"));
            transitions.runAll();
            effects.runAll();
            runtime.awaitIdle();
            check(runtime.stateSnapshot().get("value").equals("E1CANCEL") && bridge.effectRuns == 0, "keyed cancellation retires delayed work without sleeping");
        }
    }

    private static void cancellationBetweenRegistrationAndFuturePublicationRetiresOnce() throws Exception {
        publicationRace(false);
    }

    private static void closeBetweenRegistrationAndFuturePublicationRetiresOnce() throws Exception {
        publicationRace(true);
    }

    private static void publicationRace(boolean close) throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        PublicationBlockingExecutor effects = new PublicationBlockingExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.commands.put("E1", new UiBridge.EffectCommand("start", "search", 10, "replace"));
        bridge.commands.put("CANCEL", new UiBridge.EffectCommand("cancel", "search", 0, "interrupt"));
        UiProgramRuntime runtime = runtime(bridge, transitions, effects);
        runtime.dispatch(action("E1"));
        Thread publisher = Thread.ofPlatform().start(transitions::runAll);
        check(effects.registered.await(2, TimeUnit.SECONDS), "effect is registered before its future is published");
        if (close) {
            runtime.close();
        } else {
            runtime.dispatch(action("CANCEL"));
            transitions.runAll();
        }
        effects.publish.countDown();
        publisher.join();
        effects.runAll();
        transitions.runAll();
        runtime.awaitIdle();
        check(effects.future.cancelCalls == 1 && effects.future.physicalRetirements == 1, "publication race retires scheduled work physically exactly once");
        check(bridge.effectRuns == 0 && bridge.completionAdmissions == 0, "publication race runs no effect and admits no completion");
        runtime.close();
    }

    private static void runningInterruptHonoringEffectBalancesIdle() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.effectBody = effectId -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new IllegalStateException("effect interrupted", failure); }
            return action("C" + effectId);
        };
        bridge.commands.put("E1", new UiBridge.EffectCommand("start", "search", 0, "interrupt"));
        bridge.commands.put("E2", new UiBridge.EffectCommand("start", "search", 0, "interrupt"));
        try (var transitions = Executors.newSingleThreadExecutor(); var effects = Executors.newScheduledThreadPool(2)) {
            UiProgramRuntime runtime = runtime(bridge, transitions, effects);
            runtime.dispatch(action("E1"));
            check(started.await(2, TimeUnit.SECONDS), "interrupt-honoring effect starts");
            runtime.dispatch(action("E2"));
            check(interrupted.await(2, TimeUnit.SECONDS), "interrupt requests interruption");
            runtime.close();
            expectFailure(runtime, "effect interrupted");
        }
    }

    private static void runningInterruptDelayingEffectBalancesIdle() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean exited = new AtomicBoolean();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.effectBody = effectId -> {
            if (effectId != 1) return action("C" + effectId);
            started.countDown();
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) {}
            }
            exited.set(true);
            return action("C1");
        };
        bridge.commands.put("E1", new UiBridge.EffectCommand("start", "search", 0, "interrupt"));
        bridge.commands.put("E2", new UiBridge.EffectCommand("start", "search", 0, "interrupt"));
        try (var transitions = Executors.newSingleThreadExecutor(); var effects = Executors.newScheduledThreadPool(2); UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            check(started.await(2, TimeUnit.SECONDS), "interrupt-delaying effect starts");
            runtime.dispatch(action("E2"));
            CountDownLatch idleReturned = new CountDownLatch(1);
            Thread waiter = Thread.ofPlatform().start(() -> { try { runtime.awaitIdle(); idleReturned.countDown(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } });
            check(!idleReturned.await(100, TimeUnit.MILLISECONDS), "awaitIdle waits for interrupted code to physically exit");
            release.countDown();
            check(idleReturned.await(2, TimeUnit.SECONDS) && exited.get(), "awaitIdle returns after interruption-delaying code exits");
            waiter.join();
        }
    }

    private static void effectFailureMapsToCompletion() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.failEffect = true;
        bridge.mapEffectFailure = true;
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            transitions.runAll();
            effects.runAll();
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.stateSnapshot().get("value").equals("E1F1"), "typed DEAL failure mapping completes through the action queue");
        }
    }

    private static void unmappedEffectRejectionBalancesIdle() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.failEffect = true;
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            transitions.runAll();
            effects.runAll();
            expectFailure(runtime, "effect failure");
        }
    }

    private static void disposalRejectsEffectCompletion() {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        UiProgramRuntime runtime = runtime(bridge, transitions, effects);
        runtime.dispatch(action("E1"));
        transitions.runAll();
        runtime.close();
        effects.runAll();
        transitions.runAll();
        check(bridge.completionAdmissions == 0 && bridge.acceptedCompletions == 0, "disposed stores discard effect completions before admission");
    }

    private static void closeWaitsForOutstandingEffectExit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.effectBody = effectId -> {
            started.countDown();
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException ignored) {}
            }
            return action("C" + effectId);
        };
        try (var transitions = Executors.newSingleThreadExecutor(); var effects = Executors.newSingleThreadScheduledExecutor()) {
            UiProgramRuntime runtime = runtime(bridge, transitions, effects);
            runtime.dispatch(action("E1"));
            check(started.await(2, TimeUnit.SECONDS), "close test effect starts");
            runtime.close();
            CountDownLatch idleReturned = new CountDownLatch(1);
            Thread waiter = Thread.ofPlatform().start(() -> { try { runtime.awaitIdle(); idleReturned.countDown(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } });
            check(!idleReturned.await(100, TimeUnit.MILLISECONDS), "close keeps idle blocked while effect code remains running");
            release.countDown();
            check(idleReturned.await(2, TimeUnit.SECONDS), "close safely drains physical effect exit");
            waiter.join();
        }
    }

    private static void equivalentRootsAndRendererFailureRetainCommit() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        try (UiProgramRuntime runtime = runtime(bridge, transitions, new ManualExecutor())) {
            runtime.dispatch(action("A"));
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.tree().props().get("value").value().equals(runtime.stateSnapshot().get("value")), "every commit produces equivalent root output");
            UiBridge.Node prior = runtime.tree();
            JComponent priorComponent = runtime.renderer().componentForTesting(prior);
            long priorDisposals = runtime.renderer().disposedComponents();
            runtime.dispatch(action("RENDER_FAIL"));
            transitions.runAll();
            expectFailure(runtime, "No renderer binding");
            check(runtime.stateSnapshot().get("value").equals("A"), "renderer failure retains prior committed state");
            check(runtime.tree().equals(prior), "renderer failure retains prior root tree");
            check(runtime.renderer().componentForTesting(prior) == priorComponent, "renderer failure retains native component identity");
            check(runtime.renderer().disposedComponents() == priorDisposals, "renderer failure disposes no retained resources");
        }
    }

    private static void rejectedSubmissionBalancesPending() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        UiProgramRuntime runtime = runtime(bridge, transitions, new ManualExecutor());
        transitions.shutdown();
        try { runtime.dispatch(action("A")); } catch (java.util.concurrent.RejectedExecutionException expected) { passed++; }
        runtime.awaitIdle();
        runtime.close();
    }

    private static void rejectedEffectSubmissionBalancesIdle() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        effects.shutdown();
        try (UiProgramRuntime runtime = runtime(new ProtocolBridge(), transitions, effects)) {
            runtime.dispatch(action("E1"));
            transitions.runAll();
            expectFailure(runtime, "java.util.concurrent.RejectedExecutionException");
        }
    }

    private static void queuePolicyFailuresBalanceIdle() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.failEnqueue = true;
        try (UiProgramRuntime runtime = runtime(bridge, transitions, new ManualExecutor())) {
            runtime.dispatch(action("A"));
            transitions.runAll();
            expectFailure(runtime, "enqueue failure");
        }
    }

    private static void completionPolicyFailuresBalanceIdle() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.failComplete = true;
        try (UiProgramRuntime runtime = runtime(bridge, transitions, effects)) {
            runtime.dispatch(action("E1"));
            transitions.runAll();
            effects.runAll();
            transitions.runAll();
            expectFailure(runtime, "completion failure");
        }
    }

    private static void dequeueAndFinishFailuresTerminateRuntime() throws Exception {
        for (boolean dequeue : List.of(true, false)) {
            ManualExecutor transitions = new ManualExecutor();
            ManualExecutor effects = new ManualExecutor();
            ProtocolBridge bridge = new ProtocolBridge();
            bridge.failDequeue = dequeue;
            bridge.failFinish = !dequeue;
            UiProgramRuntime runtime = runtime(bridge, transitions, effects);
            runtime.dispatch(action("A"));
            transitions.runAll();
            expectFailure(runtime, dequeue ? "dequeue failure" : "finish failure");
            check(runtime.disposed() && bridge.disposeCalls == 1, "queue policy failure terminates and disposes exactly once");
            check(transitions.isShutdown() && effects.isShutdown(), "queue policy failure shuts down executors");
            try { runtime.dispatch(action("B")); throw new AssertionError("Expected disposed runtime"); } catch (IllegalStateException expected) { passed++; }
            runtime.close();
            check(bridge.disposeCalls == 1, "terminal runtime remains idempotent");
        }
    }

    private static void rejectedCompletionSubmissionTerminatesRuntime() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        UiProgramRuntime runtime = runtime(bridge, transitions, effects);
        runtime.dispatch(action("E1"));
        transitions.runAll();
        transitions.shutdown();
        effects.runAll();
        expectFailure(runtime, "RejectedExecutionException");
        check(runtime.disposed() && bridge.disposeCalls == 1, "rejected completion submission terminates runtime");
        check(effects.isShutdown(), "rejected completion submission shuts down effects");
    }

    private static void initializationFailureCleansOwnedResources() {
        ManualExecutor transitions = new ManualExecutor();
        ManualExecutor effects = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        bridge.failInitial = true;
        try { runtime(bridge, transitions, effects); throw new AssertionError("Expected initial failure"); }
        catch (IllegalStateException failure) { check(failure.getMessage().equals("initial failure"), "initial failure is preserved"); }
        check(bridge.disposeCalls == 1, "failed initialization disposes initialized store");
        check(transitions.isShutdown() && effects.isShutdown(), "failed initialization shuts down executors");
    }

    private static void compatibleIdentityIsRetained() {
        UiRendererBindings.Binding panel = UiRendererBindings.binding("panel", JPanel::new, UiRendererBindings::configure);
        UiRendererBindings.Binding text = UiRendererBindings.binding("text", JLabel::new, UiRendererBindings::configure);
        UiRendererBindings bindings = new UiRendererBindings(Map.of("panel", panel, "text", text), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Identity", bindings, bridge, action -> {});
        UiBridge.Identity rootIdentity = new UiBridge.Identity("root", new UiBridge.Key("none", 0, ""));
        UiBridge.Identity childIdentity = new UiBridge.Identity("child", new UiBridge.Key("string", 0, "stable"));
        UiBridge.Node firstChild = new UiBridge.Node("text", childIdentity, Map.of("value", new UiBridge.Prop("value", "string", "before", -1)), List.of());
        UiBridge.Node first = new UiBridge.Node("panel", rootIdentity, Map.of(), List.of(firstChild));
        JPanel root = (JPanel) renderer.componentForTesting(first);
        JComponent child = (JComponent) root.getComponent(0);
        UiBridge.Node nextChild = new UiBridge.Node("text", childIdentity, Map.of("value", new UiBridge.Prop("value", "string", "after", -1)), List.of());
        UiBridge.Node next = new UiBridge.Node("panel", rootIdentity, Map.of(), List.of(nextChild));
        renderer.apply(List.of(new UiBridge.Patch("update", rootIdentity, rootIdentity, true, 0, next), new UiBridge.Patch("update", childIdentity, rootIdentity, false, 0, nextChild)), next);
        check(renderer.componentForTesting(next) == root && root.getComponent(0) == child && ((JLabel) child).getText().equals("after"), "compatible static and keyed identities retain exact component instances");
        check(renderer.disposedComponents() == 0, "retained identities are not disposed");
        renderer.close();
    }

    private static void mutationFailuresRestoreExactLiveGraph() {
        for (String point : List.of("child-install", "configuration-copy", "host-install")) {
            UiRendererBindings.Binding panel = UiRendererBindings.binding("panel", JPanel::new, UiRendererBindings::configure);
            UiRendererBindings.Binding button = UiRendererBindings.binding("button", JButton::new, UiRendererBindings::configure);
            UiRendererBindings.Binding text = UiRendererBindings.binding("text", JLabel::new, UiRendererBindings::configure);
            UiRendererBindings bindings = new UiRendererBindings(Map.of("panel", panel, "button", button, "text", text), Map.of(), Color.WHITE, Color.BLACK);
            ProtocolBridge bridge = new ProtocolBridge();
            deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Rollback", bindings, bridge, action -> {});
            UiBridge.Identity rootIdentity = new UiBridge.Identity("rollback-root", new UiBridge.Key("none", 0, ""));
            UiBridge.Identity firstIdentity = new UiBridge.Identity("rollback-first", new UiBridge.Key("none", 0, ""));
            UiBridge.Identity secondIdentity = new UiBridge.Identity("rollback-second", new UiBridge.Key("none", 0, ""));
            UiBridge.Node first = new UiBridge.Node("button", firstIdentity, Map.of("text", new UiBridge.Prop("text", "string", "before", -1)), List.of());
            UiBridge.Node second = new UiBridge.Node("text", secondIdentity, Map.of("value", new UiBridge.Prop("value", "string", "stable", -1)), List.of());
            UiBridge.Node prior = new UiBridge.Node("panel", rootIdentity, Map.of(), List.of(first, second));
            JPanel root = (JPanel) renderer.componentForTesting(prior);
            JButton firstComponent = (JButton) root.getComponent(0);
            Component[] children = root.getComponents();
            java.awt.event.ActionListener listener = event -> {};
            firstComponent.addActionListener(listener);
            firstComponent.setToolTipText("exact");
            firstComponent.getAccessibleContext().setAccessibleDescription("description");
            UiBridge.Node changedFirst = new UiBridge.Node("button", firstIdentity, Map.of("text", new UiBridge.Prop("text", "string", "after", -1)), List.of());
            UiBridge.Node next = new UiBridge.Node("panel", rootIdentity, Map.of(), List.of(changedFirst, second));
            boolean[] failed = {false};
            renderer.failureInjectorForTesting(candidate -> { if (!failed[0] && candidate.equals(point)) { failed[0] = true; throw new IllegalStateException(point); } });
            try { renderer.apply(List.of(new UiBridge.Patch("update", rootIdentity, rootIdentity, true, 0, next)), next); throw new AssertionError("Expected " + point); }
            catch (IllegalStateException failure) { check(failure.getMessage().equals(point), point + " failure is injected"); }
            check(renderer.tree().equals(prior) && renderer.componentForTesting(prior) == root && root.getComponents().length == children.length && root.getComponent(0) == children[0] && root.getComponent(1) == children[1], point + " restores exact tree, parent order, and identities");
            check(firstComponent.getText().equals("before") && firstComponent.getToolTipText().equals("exact") && firstComponent.getAccessibleContext().getAccessibleDescription().equals("description") && firstComponent.getParent() == root && java.util.Arrays.asList(firstComponent.getActionListeners()).contains(listener), point + " restores exact configuration, listener, and parent");
            renderer.close();
        }
    }

    private static void sameIdentityReplacementPublishesNewComponent() {
        int[] disposals = {0};
        UiRendererBindings.Binding text = new UiRendererBindings.Binding("text", JLabel::new, UiRendererBindings::configure, ignored -> {}, component -> { ((JLabel) component).setText("disposed"); disposals[0]++; });
        UiRendererBindings bindings = new UiRendererBindings(Map.of("text", text), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Same identity", bindings, bridge, action -> {});
        UiBridge.Node prior = bridge.node("text", "before");
        JLabel old = (JLabel) renderer.componentForTesting(prior);
        UiBridge.Node next = bridge.node("text", "after");
        UiBridge.Patch dispose = new UiBridge.Patch("dispose", next.identity(), next.identity(), true, 0, prior);
        UiBridge.Patch create = new UiBridge.Patch("create", next.identity(), next.identity(), true, 0, next);
        renderer.apply(List.of(dispose, create), next);
        JLabel published = (JLabel) renderer.componentForTesting(next);
        check(published != old && published.getText().equals("after") && old.getText().equals("disposed"), "same-identity dispose/create publishes replacement before old disposal");
        check(disposals[0] == 1 && renderer.disposedComponents() == 1, "same-identity replacement disposes only old component after commit");
        renderer.close();
    }

    private static void modalFocusTraversalIsConstrainedAndWraps() {
        UiRendererBindings.Binding panel = UiRendererBindings.binding("panel", JPanel::new, UiRendererBindings::configure);
        UiRendererBindings.Binding modal = new UiRendererBindings.Binding("modal", UiRendererBindings.HostKind.MODAL, JPanel::new, UiRendererBindings::configure, ignored -> {}, ignored -> {});
        UiRendererBindings.Binding input = UiRendererBindings.binding("input", JTextField::new, UiRendererBindings::configure);
        UiRendererBindings.Binding button = UiRendererBindings.binding("button", JButton::new, UiRendererBindings::configure);
        UiRendererBindings bindings = new UiRendererBindings(Map.of("panel", panel, "modal", modal, "input", input, "button", button), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Modal focus", bindings, bridge, action -> {});
        UiBridge.Node first = new UiBridge.Node("input", new UiBridge.Identity("modal-input", new UiBridge.Key("none", 0, "")), Map.of("focusId", new UiBridge.Prop("focusId", "string", "first", -1), "value", new UiBridge.Prop("value", "string", "", -1)), List.of());
        UiBridge.Node last = new UiBridge.Node("button", new UiBridge.Identity("modal-button", new UiBridge.Key("none", 0, "")), Map.of("focusId", new UiBridge.Prop("focusId", "string", "last", -1), "text", new UiBridge.Prop("text", "string", "Done", -1)), List.of());
        UiBridge.Node dialog = new UiBridge.Node("modal", new UiBridge.Identity("modal", new UiBridge.Key("none", 0, "")), Map.of(), List.of(first, last));
        UiBridge.Node root = new UiBridge.Node("panel", new UiBridge.Identity("modal-root", new UiBridge.Key("none", 0, "")), Map.of(), List.of(dialog));
        renderer.componentForTesting(root);
        JComponent modalComponent = renderer.modalForTesting();
        Component firstComponent = ((JPanel) modalComponent).getComponent(0);
        Component lastComponent = ((JPanel) modalComponent).getComponent(1);
        check(renderer.modalFocusAfterForTesting(lastComponent) == firstComponent && renderer.modalFocusBeforeForTesting(firstComponent) == lastComponent, "modal focus traversal wraps forward and backward");
        check(renderer.modalFocusAfterForTesting(new JButton()) == firstComponent && SwingUtilities.isDescendingFrom(firstComponent, modalComponent), "modal traversal rejects outside origin and remains constrained to modal");
        renderer.close();
    }

    private static void duplicateIdentityLeavesLiveGraphUntouched() {
        int[] disposals = {0};
        UiRendererBindings.Binding panel = new UiRendererBindings.Binding("panel", JPanel::new, UiRendererBindings::configure, ignored -> {}, ignored -> disposals[0]++);
        UiRendererBindings bindings = new UiRendererBindings(Map.of("panel", panel), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Duplicate", bindings, bridge, action -> {});
        UiBridge.Node prior = new UiBridge.Node("panel", new UiBridge.Identity("root", new UiBridge.Key("none", 0, "")), Map.of(), List.of());
        JComponent component = renderer.componentForTesting(prior);
        UiBridge.Identity duplicate = new UiBridge.Identity("duplicate", new UiBridge.Key("none", 0, ""));
        UiBridge.Node child = new UiBridge.Node("panel", duplicate, Map.of(), List.of());
        UiBridge.Node next = new UiBridge.Node("panel", prior.identity(), Map.of(), List.of(child, child));
        try { renderer.apply(List.of(), next); throw new AssertionError("Expected duplicate identity"); }
        catch (IllegalStateException failure) { check(failure.getMessage().contains("Duplicate identity"), "duplicate identities are rejected"); }
        check(renderer.componentForTesting(prior) == component && renderer.tree().equals(prior) && disposals[0] == 0, "duplicate identity failure leaves old graph untouched without staged leaks");
        renderer.close();
    }

    private static void disposerPreflightProtectsLiveGraph() {
        int[] prepares = {0};
        int[] disposals = {0};
        boolean[] fail = {true};
        UiRendererBindings.Binding binding = new UiRendererBindings.Binding("text", JLabel::new, UiRendererBindings::configure, component -> { prepares[0]++; if (fail[0]) throw new IllegalStateException("dispose failure"); }, component -> disposals[0]++);
        UiRendererBindings bindings = new UiRendererBindings(Map.of("text", binding), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Disposal", bindings, bridge, action -> {});
        UiBridge.Node prior = bridge.node("text", "prior");
        JComponent component = renderer.componentForTesting(prior);
        UiBridge.Patch disposal = new UiBridge.Patch("dispose", prior.identity(), prior.identity(), true, 0, prior);
        try { renderer.apply(List.of(disposal), bridge.node("text", "next")); throw new AssertionError("Expected dispose failure"); }
        catch (IllegalStateException failure) { check(failure.getMessage().equals("dispose failure"), "throwing disposer is surfaced during preflight"); }
        check(renderer.componentForTesting(prior) == component && renderer.disposedComponents() == 0 && disposals[0] == 1, "throwing disposal preparation leaves live identity and resources unchanged while releasing the candidate");
        fail[0] = false;
        renderer.apply(List.of(disposal), bridge.node("text", "next"));
        check(prepares[0] == 2 && disposals[0] >= 2 && renderer.disposedComponents() == 1, "successful retry releases live and unused staged resources");
        renderer.close();
    }

    private static void preparedReplacementIsInstalled() {
        int[] commits = {0};
        int[] disposals = {0};
        boolean[] fail = {true};
        UiRendererBindings.Binding text = new UiRendererBindings.Binding("text", JLabel::new, (component, node, bridge, dispatch, bindings) -> { component.setName("text"); commits[0]++; }, ignored -> { if (fail[0]) throw new IllegalStateException("replacement disposal failure"); }, ignored -> disposals[0]++);
        UiRendererBindings.Binding panel = new UiRendererBindings.Binding("panel", JPanel::new, (component, node, bridge, dispatch, bindings) -> { component.setName("panel"); commits[0]++; }, ignored -> {}, ignored -> disposals[0]++);
        UiRendererBindings bindings = new UiRendererBindings(Map.of("text", text, "panel", panel), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Replacement", bindings, bridge, action -> {});
        UiBridge.Node prior = bridge.node("text", "prior");
        JComponent old = renderer.componentForTesting(prior);
        UiBridge.Node next = bridge.node("panel", "next");
        UiBridge.Patch update = new UiBridge.Patch("update", next.identity(), next.identity(), true, 0, next);
        try { renderer.apply(List.of(update), next); throw new AssertionError("Expected replacement disposal failure"); }
        catch (IllegalStateException failure) { check(failure.getMessage().equals("replacement disposal failure"), "replacement disposal preparation failure is surfaced"); }
        check(renderer.componentForTesting(prior) == old && commits[0] == 2 && disposals[0] == 1, "failed replacement retains old identity and releases abandoned replacement");
        fail[0] = false;
        renderer.apply(List.of(update), next);
        JComponent replacement = renderer.componentForTesting(next);
        check(replacement instanceof JPanel && replacement != old, "prepared incompatible replacement is installed");
        check(replacement.getName().equals("panel") && commits[0] == 3, "prepared replacement commits configuration once");
        check(disposals[0] == 2 && renderer.disposedComponents() == 1, "displaced component is disposed once after abandoned replacement cleanup");
        renderer.close();
    }

    private static void detachedSnapshotSurvivesCandidateRelease() {
        UiRendererBindings.Binding binding = new UiRendererBindings.Binding("text", JLabel::new, UiRendererBindings::configure, ignored -> {}, component -> { ((JLabel) component).setText("released"); component.setName(null); });
        UiRendererBindings bindings = new UiRendererBindings(Map.of("text", binding), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Snapshot", bindings, bridge, action -> {});
        UiBridge.Node prior = bridge.node("text", "before");
        JLabel retained = (JLabel) renderer.componentForTesting(prior);
        UiBridge.Node next = bridge.node("text", "after");
        renderer.apply(List.of(new UiBridge.Patch("update", next.identity(), next.identity(), true, 0, next)), next);
        check(renderer.componentForTesting(next) == retained && retained.getText().equals("after"), "compatible update retains exact native identity and survives staged candidate release");
        renderer.close();
    }

    private static void stagedCleanupIsExhaustive() {
        int[] releases = {0};
        UiRendererBindings.Binding panel = new UiRendererBindings.Binding("panel", JPanel::new, (component, node, bridge, dispatch, bindings) -> { component.setName("panel"); if (node.props().get("value").value().equals("fail")) throw new IllegalStateException("configuration failure"); }, ignored -> {}, component -> { releases[0]++; if (releases[0] == 1) throw new AssertionError("cleanup error"); });
        UiRendererBindings bindings = new UiRendererBindings(Map.of("panel", panel), Map.of(), Color.WHITE, Color.BLACK);
        ProtocolBridge bridge = new ProtocolBridge();
        deal.ui.runtime.SwingUiRuntime renderer = new deal.ui.runtime.SwingUiRuntime("Cleanup", bindings, bridge, action -> {});
        UiBridge.Node first = bridge.node("panel", "ok");
        UiBridge.Node second = new UiBridge.Node("panel", new UiBridge.Identity("second", new UiBridge.Key("none", 0, "")), Map.of("value", new UiBridge.Prop("value", "string", "fail", -1)), List.of());
        UiBridge.Node root = new UiBridge.Node("panel", new UiBridge.Identity("root-cleanup", new UiBridge.Key("none", 0, "")), Map.of("value", new UiBridge.Prop("value", "string", "root", -1)), List.of(first, second));
        try { renderer.apply(List.of(new UiBridge.Patch("create", root.identity(), root.identity(), true, 0, root)), root); throw new AssertionError("Expected configuration failure"); }
        catch (IllegalStateException failure) { check(failure.getMessage().equals("configuration failure") && failure.getSuppressed().length == 1, "staged cleanup preserves primary failure and suppresses cleanup error"); }
        check(releases[0] == 3, "staged cleanup attempts every owned candidate after individual failure");
        renderer.close();
    }

    private static void cleanupReportingNeverEscapes() {
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler original = thread.getUncaughtExceptionHandler();
        PrintStream originalError = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(error);
            thread.setUncaughtExceptionHandler(null);
            UiRendererBindings.reportCleanupFailure(new IllegalStateException("unhandled cleanup"));
            thread.setUncaughtExceptionHandler((ignored, failure) -> { throw new IllegalStateException("handler failure"); });
            UiRendererBindings.reportCleanupFailure(new IllegalStateException("reported cleanup"));
            check(captured.toString(StandardCharsets.UTF_8).contains("unhandled cleanup"), "cleanup reporting tolerates absent and throwing handlers without leaking test stderr");
        } finally {
            thread.setUncaughtExceptionHandler(original);
            System.setErr(originalError);
        }
    }

    private static void windowCloseDisposesRuntimeOnce() {
        ProtocolBridge bridge = new ProtocolBridge();
        UiProgramRuntime runtime = runtime(bridge, new ManualExecutor(), new ManualExecutor());
        runtime.renderer().requestCloseForTesting();
        runtime.renderer().requestCloseForTesting();
        runtime.close();
        check(runtime.disposed() && bridge.disposeCalls == 1, "window close disposes runtime exactly once");
    }

    private static UiProgramRuntime runtime(ProtocolBridge bridge, java.util.concurrent.ExecutorService transitions, ScheduledExecutorService effects) {
        UiRendererBindings bindings = new UiRendererBindings(Map.of("text", UiRendererBindings.binding("text", JLabel::new, UiRendererBindings::configure)), Map.of(), Color.WHITE, Color.BLACK);
        return new UiProgramRuntime(bridge, "Invariant", bindings, transitions, effects);
    }

    private static UiBridge.ActionValue action(String name) { return new UiBridge.ActionValue(new TestAction(name)); }
    private static void expectFailure(UiProgramRuntime runtime, String text) throws Exception { try { runtime.awaitIdle(); throw new AssertionError("Expected " + text); } catch (RuntimeException failure) { check(failure.toString().contains(text), text + " is surfaced"); } }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }

    private record TestState(String value) {}
    private record TestAction(String name) {}
    private record TestStore(List<TestAction> queue, boolean draining, boolean disposed) {}

    private static final class ProtocolBridge implements UiBridge {
        private Runnable nested;
        private int startedEffects;
        private int completionAdmissions;
        private int acceptedCompletions;
        private boolean failEnqueue;
        private boolean failComplete;
        private boolean failDequeue;
        private boolean failFinish;
        private boolean failInitial;
        private int disposeCalls;
        private int effectRuns;
        private boolean failEffect;
        private boolean mapEffectFailure;
        private java.util.function.IntFunction<UiBridge.ActionValue> effectBody;
        private final Map<String, UiBridge.EffectCommand> commands = new java.util.HashMap<>();

        @Override public String title() { return "Invariant"; }
        @Override public UiRendererBindings rendererBindings() { throw new UnsupportedOperationException(); }
        @Override public StateValue initialState() { return new StateValue(new TestState("")); }
        @Override public StoreValue initialStore() { return new StoreValue(new TestStore(List.of(), false, false)); }
        @Override public Transition initial(StateValue state, StoreValue store) { if (failInitial) throw new IllegalStateException("initial failure"); return transitionValue((TestState) state.abi(), store, null, node("text", ""), -1, null); }
        @Override public Enqueue enqueue(StoreValue store, ActionValue action) { if (failEnqueue) throw new IllegalStateException("enqueue failure"); return admit(store, action); }
        @Override public Dequeue dequeue(StoreValue value) {
            if (failDequeue) throw new IllegalStateException("dequeue failure");
            TestStore store = (TestStore) value.abi();
            if (store.queue().isEmpty()) return new Dequeue(value, UiRuntimeInvariantTest.action("NONE"), false);
            List<TestAction> queue = new ArrayList<>(store.queue());
            TestAction action = queue.removeFirst();
            return new Dequeue(new StoreValue(new TestStore(List.copyOf(queue), true, store.disposed())), new ActionValue(action), true);
        }
        @Override public StoreValue finish(StoreValue value) { if (failFinish) throw new IllegalStateException("finish failure"); TestStore store = (TestStore) value.abi(); return new StoreValue(new TestStore(store.queue(), false, store.disposed())); }
        @Override public StoreValue reject(StoreValue value) { return value; }
        @Override public StoreValue dispose(StoreValue value) { disposeCalls++; TestStore store = (TestStore) value.abi(); return new StoreValue(new TestStore(List.of(), false, true)); }
        @Override public Completion complete(StoreValue store, ActionValue action) { if (failComplete) throw new IllegalStateException("completion failure"); completionAdmissions++; Enqueue enqueue = admit(store, action); if (enqueue.accepted()) acceptedCompletions++; return new Completion(enqueue.store(), enqueue.accepted(), enqueue.startDrain()); }
        @Override public Transition transition(StateValue stateValue, Node previous, StoreValue store, ActionValue actionValue) {
            TestState state = (TestState) stateValue.abi();
            TestAction action = (TestAction) actionValue.abi();
            if (action.name().equals("FAIL")) throw new IllegalStateException("candidate failure");
            if (action.name().equals("A") && nested != null) { Runnable callback = nested; nested = null; callback.run(); }
            String value = state.value() + action.name();
            Node next = action.name().equals("RENDER_FAIL") ? node("missing", value) : node("text", value);
            int effect = action.name().startsWith("E") ? Integer.parseInt(action.name().substring(1)) : -1;
            if (effect >= 0) startedEffects++;
            return transitionValue(new TestState(value), store, actionValue, next, effect, actionValue);
        }
        @Override public ActionValue action(int slot, Object payload) { return UiRuntimeInvariantTest.action(String.valueOf(payload)); }
        @Override public ActionValue runEffect(int effectId, StateValue state, ActionValue action) { effectRuns++; if (failEffect) throw new IllegalStateException("effect failure"); return effectBody == null ? UiRuntimeInvariantTest.action("C" + effectId) : effectBody.apply(effectId); }
        @Override public ActionValue effectFailure(int effectId, StateValue state, ActionValue action, RuntimeException failure) { return mapEffectFailure ? UiRuntimeInvariantTest.action("F" + effectId) : null; }
        @Override public Map<String, Object> stateSnapshot(StateValue state) { return Map.of("value", ((TestState) state.abi()).value()); }

        private Enqueue admit(StoreValue value, ActionValue actionValue) {
            TestStore store = (TestStore) value.abi();
            if (store.disposed()) return new Enqueue(value, false, false);
            List<TestAction> queue = new ArrayList<>(store.queue());
            queue.add((TestAction) actionValue.abi());
            return new Enqueue(new StoreValue(new TestStore(List.copyOf(queue), true, false)), true, !store.draining());
        }
        private Transition transitionValue(TestState state, StoreValue store, ActionValue action, Node next, int effect, ActionValue effectAction) {
            Node previous = node("text", "");
            Patch patch = new Patch("update", next.identity(), previous.identity(), true, 0, next);
            UiBridge.EffectCommand command = effectAction == null ? EffectCommand.none() : commands.getOrDefault(((TestAction) effectAction.abi()).name(), effect < 0 ? EffectCommand.none() : EffectCommand.immediate());
            return new Transition(new StateValue(state), next, List.of(patch), store, effect, new StateValue(state), effectAction, command);
        }
        private Node node(String component, String value) {
            Identity identity = new Identity("root", new Key("none", 0, ""));
            return new Node(component, identity, Map.of("value", new Prop("value", "string", value, -1)), List.of());
        }
    }

    private static final class PublicationBlockingExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        private final CountDownLatch registered = new CountDownLatch(1);
        private final CountDownLatch publish = new CountDownLatch(1);
        private final CountingFuture future = new CountingFuture();
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && future.isDone(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable command) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            future.command = command;
            registered.countDown();
            try { publish.await(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.util.concurrent.RejectedExecutionException(failure); }
            return future;
        }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        private void runAll() { future.run(); }

        private static final class CountingFuture implements ScheduledFuture<Object>, Runnable {
            private Runnable command;
            private boolean cancelled;
            private boolean done;
            private int cancelCalls;
            private int physicalRetirements;
            @Override public synchronized void run() { if (done) return; if (!cancelled) command.run(); done = true; physicalRetirements++; }
            @Override public synchronized boolean cancel(boolean mayInterruptIfRunning) { cancelCalls++; if (done) return false; cancelled = true; done = true; physicalRetirements++; return true; }
            @Override public synchronized boolean isCancelled() { return cancelled; }
            @Override public synchronized boolean isDone() { return done; }
            @Override public Object get() { return null; }
            @Override public Object get(long timeout, TimeUnit unit) { return null; }
            @Override public long getDelay(TimeUnit unit) { return 0; }
            @Override public int compareTo(Delayed other) { return 0; }
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService implements ScheduledExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable command) { if (shutdown) throw new java.util.concurrent.RejectedExecutionException(); tasks.add(command); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) { ManualFuture future = new ManualFuture(command); execute(future); return future; }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        void runAll() { while (!tasks.isEmpty()) run(0); }
        void run(int index) { tasks.remove(index).run(); }
        int size() { return tasks.size(); }

        private static final class ManualFuture implements ScheduledFuture<Object>, Runnable {
            private final Runnable command;
            private boolean cancelled;
            private boolean done;
            private ManualFuture(Runnable command) { this.command = command; }
            @Override public void run() { if (!cancelled) command.run(); done = true; }
            @Override public boolean cancel(boolean mayInterruptIfRunning) { if (done) return false; cancelled = true; done = true; return true; }
            @Override public boolean isCancelled() { return cancelled; }
            @Override public boolean isDone() { return done; }
            @Override public Object get() { return null; }
            @Override public Object get(long timeout, TimeUnit unit) { return null; }
            @Override public long getDelay(TimeUnit unit) { return 0; }
            @Override public int compareTo(Delayed other) { return 0; }
        }
    }
}
