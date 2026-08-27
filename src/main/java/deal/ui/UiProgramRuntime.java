package deal.ui;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UiProgramRuntime implements AutoCloseable {
    private final UiBridge bridge;
    private final ExecutorService transitions = Executors.newSingleThreadExecutor();
    private final ExecutorService effects = Executors.newVirtualThreadPerTaskExecutor();
    private final deal.ui.runtime.SwingUiRuntime renderer;
    private Object state;
    private UiBridge.Node tree;
    private Object store;
    private boolean disposed;
    private long pending;
    private long runningEffects;
    private RuntimeException asynchronousFailure;

    public UiProgramRuntime(UiBridge bridge, String title, UiRendererBindings bindings) {
        this.bridge = bridge;
        state = bridge.initialState();
        store = bridge.initialStore();
        renderer = new deal.ui.runtime.SwingUiRuntime(title, bindings, bridge, this::dispatch);
        UiBridge.Transition initial = bridge.initial(state, store);
        state = initial.state();
        tree = initial.tree();
        store = initial.store();
    }

    public void dispatch(Object action) {
        synchronized (this) {
            if (disposed) throw new IllegalStateException("Store is disposed");
            pending++;
        }
        transitions.submit(() -> accept(action));
    }

    private void accept(Object action) {
        UiBridge.Enqueue enqueue;
        synchronized (this) {
            if (disposed) { pending--; notifyAll(); return; }
            enqueue = bridge.enqueue(store, action);
            store = enqueue.store();
            if (!enqueue.accepted()) { pending--; notifyAll(); return; }
        }
        if (enqueue.startDrain()) drain();
    }

    private void drain() {
        while (true) {
            UiBridge.Dequeue dequeue;
            synchronized (this) {
                if (disposed) return;
                dequeue = bridge.dequeue(store);
                store = dequeue.store();
                if (!dequeue.present()) {
                    store = bridge.finish(store);
                    notifyAll();
                    return;
                }
            }
            apply(dequeue.action());
        }
    }

    private void apply(Object action) {
        UiBridge.Transition transition;
        Object priorState;
        UiBridge.Node priorTree;
        Object priorStore;
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
        synchronized (this) {
            if (disposed) {
                pending--;
                notifyAll();
                return;
            }
            state = transition.state();
            tree = transition.tree();
            store = transition.store();
            if (transition.effectId() >= 0) runningEffects++;
        }
        try {
            renderer.apply(transition.patches(), transition.tree());
        } catch (RuntimeException failure) {
            synchronized (this) { asynchronousFailure = failure; }
        } finally {
            synchronized (this) { pending--; notifyAll(); }
        }
        if (transition.effectId() >= 0) schedule(transition.effectId(), transition.effectState(), transition.effectAction());
    }

    private void schedule(int effectId, Object effectState, Object effectAction) {
        effects.submit(() -> {
            try {
                Object completion = bridge.runEffect(effectId, effectState, effectAction);
                synchronized (UiProgramRuntime.this) {
                    if (disposed) return;
                }
                dispatch(completion);
            } catch (RuntimeException failure) {
                synchronized (UiProgramRuntime.this) { asynchronousFailure = failure; }
            } finally {
                synchronized (UiProgramRuntime.this) {
                    runningEffects--;
                    UiProgramRuntime.this.notifyAll();
                }
            }
        });
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
