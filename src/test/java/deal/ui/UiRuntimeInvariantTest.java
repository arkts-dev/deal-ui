package deal.ui;

import javax.swing.JLabel;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

public final class UiRuntimeInvariantTest {
    private static int passed;

    private UiRuntimeInvariantTest() {}

    public static void main(String[] args) throws Exception {
        queuedAndNestedActionsAreFifo();
        failedCandidateRetainsState();
        overlappingEffectsCompleteInArrivalOrder();
        disposalRejectsEffectCompletion();
        equivalentRootsAndRendererFailureRetainCommit();
        rejectedSubmissionBalancesPending();
        rejectedEffectSubmissionBalancesIdle();
        queuePolicyFailuresBalanceIdle();
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

    private static void equivalentRootsAndRendererFailureRetainCommit() throws Exception {
        ManualExecutor transitions = new ManualExecutor();
        ProtocolBridge bridge = new ProtocolBridge();
        try (UiProgramRuntime runtime = runtime(bridge, transitions, new ManualExecutor())) {
            runtime.dispatch(action("A"));
            transitions.runAll();
            runtime.awaitIdle();
            check(runtime.tree().props().get("value").value().equals(runtime.stateSnapshot().get("value")), "every commit produces equivalent root output");
            UiBridge.Node prior = runtime.tree();
            runtime.dispatch(action("RENDER_FAIL"));
            transitions.runAll();
            expectFailure(runtime, "No renderer binding");
            check(runtime.stateSnapshot().get("value").equals("A"), "renderer failure retains prior committed state");
            check(runtime.tree().equals(prior), "renderer failure retains prior root tree");
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

    private static UiProgramRuntime runtime(ProtocolBridge bridge, ManualExecutor transitions, ManualExecutor effects) {
        UiRendererBindings bindings = new UiRendererBindings(Map.of("text", new UiRendererBindings.Binding("text", JLabel::new, UiRendererBindings::configure)), Map.of(), Color.WHITE, Color.BLACK);
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

        @Override public String title() { return "Invariant"; }
        @Override public UiRendererBindings rendererBindings() { throw new UnsupportedOperationException(); }
        @Override public StateValue initialState() { return new StateValue(new TestState("")); }
        @Override public StoreValue initialStore() { return new StoreValue(new TestStore(List.of(), false, false)); }
        @Override public Transition initial(StateValue state, StoreValue store) { return transitionValue((TestState) state.abi(), store, null, node("text", ""), -1, null); }
        @Override public Enqueue enqueue(StoreValue store, ActionValue action) { if (failEnqueue) throw new IllegalStateException("enqueue failure"); return admit(store, action); }
        @Override public Dequeue dequeue(StoreValue value) {
            TestStore store = (TestStore) value.abi();
            if (store.queue().isEmpty()) return new Dequeue(value, UiRuntimeInvariantTest.action("NONE"), false);
            List<TestAction> queue = new ArrayList<>(store.queue());
            TestAction action = queue.removeFirst();
            return new Dequeue(new StoreValue(new TestStore(List.copyOf(queue), true, store.disposed())), new ActionValue(action), true);
        }
        @Override public StoreValue finish(StoreValue value) { TestStore store = (TestStore) value.abi(); return new StoreValue(new TestStore(store.queue(), false, store.disposed())); }
        @Override public StoreValue reject(StoreValue value) { return value; }
        @Override public StoreValue dispose(StoreValue value) { TestStore store = (TestStore) value.abi(); return new StoreValue(new TestStore(List.of(), false, true)); }
        @Override public Completion complete(StoreValue store, ActionValue action) { completionAdmissions++; Enqueue enqueue = admit(store, action); if (enqueue.accepted()) acceptedCompletions++; return new Completion(enqueue.store(), enqueue.accepted(), enqueue.startDrain()); }
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
        @Override public ActionValue runEffect(int effectId, StateValue state, ActionValue action) { return UiRuntimeInvariantTest.action("C" + effectId); }
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
            return new Transition(new StateValue(state), next, List.of(patch), store, effect, new StateValue(state), effectAction);
        }
        private Node node(String component, String value) {
            Identity identity = new Identity("root", new Key("none", 0, ""));
            return new Node(component, identity, Map.of("value", new Prop("value", "string", value, -1)), List.of());
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean shutdown;

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable command) { if (shutdown) throw new java.util.concurrent.RejectedExecutionException(); tasks.add(command); }
        void runAll() { while (!tasks.isEmpty()) run(0); }
        void run(int index) { tasks.remove(index).run(); }
        int size() { return tasks.size(); }
    }
}
