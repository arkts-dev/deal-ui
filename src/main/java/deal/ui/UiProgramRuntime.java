package deal.ui;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UiProgramRuntime implements AutoCloseable {
    private final UiBridge bridge;
    private final ExecutorService transitions;
    private final ExecutorService effects;
    private final deal.ui.runtime.SwingUiRuntime renderer;
    private UiBridge.StateValue state;
    private UiBridge.Node tree;
    private UiBridge.StoreValue store;
    private boolean disposed;
    private long pending;
    private long runningEffects;
    private RuntimeException asynchronousFailure;

    public UiProgramRuntime(UiBridge bridge, String title, UiRendererBindings bindings) {
        this(bridge, title, bindings, Executors.newSingleThreadExecutor(), Executors.newVirtualThreadPerTaskExecutor());
    }

    UiProgramRuntime(UiBridge bridge, String title, UiRendererBindings bindings, ExecutorService transitions, ExecutorService effects) {
        this.bridge = bridge;
        this.transitions = transitions;
        this.effects = effects;
        state = bridge.initialState();
        store = bridge.initialStore();
        renderer = new deal.ui.runtime.SwingUiRuntime(title, bindings, bridge, this::dispatch);
        UiBridge.Transition initial = bridge.initial(state, store);
        state = initial.state();
        tree = initial.tree();
        store = initial.store();
    }

    public void dispatch(UiBridge.ActionValue action) {
        synchronized (this) {
            if (disposed) throw new IllegalStateException("Store is disposed");
            pending++;
        }
        try { transitions.submit(() -> accept(action)); }
        catch (java.util.concurrent.RejectedExecutionException failure) { synchronized (this) { pending--; notifyAll(); } throw failure; }
    }

    private void accept(UiBridge.ActionValue action) {
        UiBridge.Enqueue enqueue;
        synchronized (this) {
            if (disposed) { pending--; notifyAll(); return; }
            try {
                enqueue = bridge.enqueue(store, action);
                store = enqueue.store();
            } catch (RuntimeException failure) {
                pending--;
                asynchronousFailure = failure;
                notifyAll();
                return;
            }
            if (!enqueue.accepted()) { pending--; notifyAll(); return; }
        }
        if (enqueue.startDrain()) drain();
    }

    private void drain() {
        while (true) {
            UiBridge.Dequeue dequeue;
            synchronized (this) {
                if (disposed) return;
                try {
                    dequeue = bridge.dequeue(store);
                    store = dequeue.store();
                    if (!dequeue.present()) {
                        store = bridge.finish(store);
                        notifyAll();
                        return;
                    }
                } catch (RuntimeException failure) {
                    pending = Math.max(0, pending - 1);
                    asynchronousFailure = failure;
                    notifyAll();
                    return;
                }
            }
            apply(dequeue.action());
        }
    }

    private void apply(UiBridge.ActionValue action) {
        UiBridge.Transition transition;
        UiBridge.StateValue priorState;
        UiBridge.Node priorTree;
        UiBridge.StoreValue priorStore;
        synchronized (this) {
            priorState = state;
            priorTree = tree;
            priorStore = store;
        }
        try {
            transition = bridge.transition(priorState, priorTree, priorStore, action);
        } catch (RuntimeException failure) {
            synchronized (this) {
                store = bridge.reject(store);
                pending--;
                asynchronousFailure = failure;
                notifyAll();
            }
            return;
        }
        try {
            renderer.apply(transition.patches(), transition.tree());
        } catch (RuntimeException failure) {
            try { renderer.restore(priorTree); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
            synchronized (this) { store = bridge.reject(store); asynchronousFailure = failure; pending--; notifyAll(); }
            return;
        }
        synchronized (this) {
            if (disposed) { pending--; notifyAll(); return; }
            state = transition.state();
            tree = transition.tree();
            store = transition.store();
            if (transition.effectId() >= 0) runningEffects++;
            pending--;
            notifyAll();
        }
        if (transition.effectId() >= 0) schedule(transition.effectId(), transition.effectState(), transition.effectAction());
    }

    private void submitCompletion(UiBridge.ActionValue action) {
        boolean startDrain;
        synchronized (this) {
            UiBridge.Completion completion = bridge.complete(store, action);
            store = completion.store();
            if (!completion.accepted()) return;
            pending++;
            startDrain = completion.startDrain();
        }
        if (!startDrain) return;
        try { transitions.submit(this::drain); }
        catch (java.util.concurrent.RejectedExecutionException failure) { synchronized (this) { pending--; notifyAll(); } }
    }

    private void schedule(int effectId, UiBridge.StateValue effectState, UiBridge.ActionValue effectAction) {
        try { effects.submit(() -> {
            try {
                UiBridge.ActionValue completion = bridge.runEffect(effectId, effectState, effectAction);
                submitCompletion(completion);
            } catch (RuntimeException failure) {
                synchronized (UiProgramRuntime.this) { asynchronousFailure = failure; }
            } finally {
                synchronized (UiProgramRuntime.this) {
                    runningEffects--;
                    UiProgramRuntime.this.notifyAll();
                }
            }
        }); } catch (java.util.concurrent.RejectedExecutionException failure) {
            synchronized (this) { runningEffects--; asynchronousFailure = failure; notifyAll(); }
        }
    }

    public void show() { UiBridge.Node value; synchronized (this) { value = tree; } renderer.show(value); }
    public void click(String text) { renderer.click(text); }
    public deal.ui.runtime.SwingUiRuntime renderer() { return renderer; }
    public synchronized Map<String, Object> stateSnapshot() { return bridge.stateSnapshot(state); }
    public synchronized UiBridge.Node tree() { return tree; }
    public void awaitActions() throws InterruptedException { synchronized (this) { while (pending > 0) wait(); } }
    public synchronized void awaitIdle() throws InterruptedException {
        while (pending > 0 || runningEffects > 0) wait();
        if (asynchronousFailure != null) { RuntimeException failure = asynchronousFailure; asynchronousFailure = null; throw failure; }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (disposed) return;
            disposed = true;
            store = bridge.dispose(store);
            pending = 0;
            notifyAll();
        }
        transitions.shutdownNow();
        effects.shutdownNow();
        renderer.close();
    }
}
