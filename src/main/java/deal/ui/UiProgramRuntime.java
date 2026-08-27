package deal.ui;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UiProgramRuntime implements AutoCloseable {
    private final UiBridge bridge;
    private final ArrayDeque<Object> queue = new ArrayDeque<>();
    private final ExecutorService effects = Executors.newVirtualThreadPerTaskExecutor();
    private final deal.ui.runtime.SwingUiRuntime renderer;
    private Object state;
    private UiBridge.Node tree;
    private UiBridge.Lifecycle lifecycle;
    private boolean draining;
    private boolean disposed;
    private long runningEffects;

    public UiProgramRuntime(UiBridge bridge, String title, UiRendererBindings bindings) {
        this.bridge = bridge;
        state = bridge.initialState();
        lifecycle = bridge.initialLifecycle();
        renderer = new deal.ui.runtime.SwingUiRuntime(title, bindings, bridge, this::dispatch);
        UiBridge.Transition initial = bridge.initial(state, lifecycle);
        state = initial.state();
        tree = initial.tree();
        lifecycle = initial.lifecycle();
    }

    public synchronized void dispatch(Object action) {
        if (disposed) throw new IllegalStateException("Store is disposed");
        UiBridge.Enqueue enqueue = bridge.enqueue(lifecycle);
        lifecycle = enqueue.lifecycle();
        if (!enqueue.accepted()) return;
        queue.addLast(action);
        if (draining) return;
        draining = true;
        try {
            while (!queue.isEmpty() && !disposed) {
                Object next = queue.removeFirst();
                try {
                    apply(next);
                } catch (RuntimeException | Error failure) {
                    queue.clear();
                    throw failure;
                }
            }
        } finally {
            lifecycle = bridge.finish(lifecycle);
            draining = false;
            notifyAll();
        }
    }

    private void apply(Object action) {
        try {
            UiBridge.Transition transition = bridge.transition(state, tree, lifecycle, action);
            state = transition.state();
            tree = transition.tree();
            lifecycle = transition.lifecycle();
            renderer.apply(transition.patches(), tree);
            if (transition.effectId() >= 0) schedule(transition.effectId(), transition.effectState(), transition.effectAction());
        } catch (RuntimeException | Error failure) {
            lifecycle = bridge.reject(lifecycle);
            throw failure;
        }
    }

    private void schedule(int effectId, Object effectState, Object effectAction) {
        runningEffects++;
        effects.submit(() -> {
            try {
                Object completion = bridge.runEffect(effectId, effectState, effectAction);
                synchronized (UiProgramRuntime.this) {
                    if (!disposed) dispatch(completion);
                }
            } catch (RuntimeException | Error failure) {
                Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), failure);
            } finally {
                synchronized (UiProgramRuntime.this) {
                    runningEffects--;
                    UiProgramRuntime.this.notifyAll();
                }
            }
        });
    }

    public void show() { renderer.show(tree); }
    public void click(String text) { renderer.click(text); }
    public deal.ui.runtime.SwingUiRuntime renderer() { return renderer; }
    public synchronized Map<String, Object> stateSnapshot() { return bridge.stateSnapshot(state); }
    public synchronized UiBridge.Node tree() { return tree; }
    public synchronized void awaitIdle() throws InterruptedException { while (runningEffects > 0 || draining || !queue.isEmpty()) wait(); }

    @Override
    public synchronized void close() {
        if (disposed) return;
        disposed = true;
        lifecycle = bridge.dispose(lifecycle);
        queue.clear();
        effects.shutdownNow();
        renderer.close();
        notifyAll();
    }
}
