package deal.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Platform-neutral DEAL UI store/effect driver for native renderers. */
public final class PortableUiProgramRuntime implements AutoCloseable {
    public interface Renderer extends AutoCloseable {
        void show(UiPortableBridge.Node tree);
        void apply(java.util.List<UiPortableBridge.Patch> patches, UiPortableBridge.Node tree);
        @Override default void close() {}
    }

    private final UiPortableBridge bridge;
    private final Renderer renderer;
    private final ExecutorService transitions;
    private final ScheduledExecutorService effects;
    private final Map<String, Set<ScheduledEffect>> keyedEffects = new HashMap<>();
    private final Set<ScheduledEffect> outstandingEffects = new HashSet<>();
    private UiPortableBridge.StateValue state;
    private UiPortableBridge.StoreValue store;
    private UiPortableBridge.Node tree;
    private boolean disposed;
    private long pending;
    private long runningEffects;
    private RuntimeException asynchronousFailure;

    public PortableUiProgramRuntime(UiPortableBridge bridge, Renderer renderer) {
        this(bridge, renderer, Executors.newSingleThreadExecutor(), Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
    }

    PortableUiProgramRuntime(UiPortableBridge bridge, Renderer renderer, ExecutorService transitions, ScheduledExecutorService effects) {
        this.bridge = java.util.Objects.requireNonNull(bridge);
        this.renderer = java.util.Objects.requireNonNull(renderer);
        this.transitions = java.util.Objects.requireNonNull(transitions);
        this.effects = java.util.Objects.requireNonNull(effects);
        UiPortableBridge.StoreValue createdStore = null;
        try {
            state = bridge.initialState();
            createdStore = bridge.initialStore();
            UiPortableBridge.Transition initial = bridge.initial(state, createdStore);
            state = initial.state();
            store = initial.store();
            tree = initial.tree();
            renderer.show(tree);
        } catch (RuntimeException | Error failure) {
            if (createdStore != null) try { bridge.dispose(createdStore); } catch (RuntimeException disposeFailure) { failure.addSuppressed(disposeFailure); }
            transitions.shutdownNow();
            effects.shutdownNow();
            try { renderer.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public void dispatch(int actionSlot, Object payload) {
        dispatch(bridge.action(actionSlot, payload));
    }

    public void dispatch(UiPortableBridge.ActionValue action) {
        synchronized (this) {
            if (disposed) throw new IllegalStateException("Store is disposed");
            pending++;
        }
        try {
            transitions.submit(() -> accept(action));
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            synchronized (this) { pending--; notifyAll(); }
            throw failure;
        }
    }

    private void accept(UiPortableBridge.ActionValue action) {
        synchronized (this) {
            if (disposed) { retirePending(); return; }
            UiPortableBridge.Enqueue enqueue = bridge.enqueue(store, action);
            store = enqueue.store();
            if (!enqueue.accepted()) { retirePending(); return; }
            if (!enqueue.startDrain()) return;
        }
        drain();
    }

    private void drain() {
        while (true) {
            UiPortableBridge.Dequeue dequeue;
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

    private void apply(UiPortableBridge.ActionValue action) {
        UiPortableBridge.Transition transition;
        synchronized (this) {
            try {
                transition = bridge.transition(state, tree, store, action);
            } catch (RuntimeException failure) {
                store = bridge.reject(store);
                asynchronousFailure = failure;
                retirePending();
                return;
            }
        }
        try {
            renderer.apply(transition.patches(), transition.tree());
        } catch (RuntimeException failure) {
            synchronized (this) {
                store = bridge.reject(store);
                asynchronousFailure = failure;
                retirePending();
            }
            return;
        }
        synchronized (this) {
            if (disposed) { retirePending(); return; }
            state = transition.state();
            tree = transition.tree();
            store = transition.store();
            retirePending();
        }
        applyEffectCommand(transition);
    }

    private void applyEffectCommand(UiPortableBridge.Transition transition) {
        UiPortableBridge.EffectCommand command = transition.effectCommand();
        if (command.operation().equals("cancel")) { cancelKey(command.key(), command.cancellationMode().equals("interrupt")); return; }
        if (command.operation().equals("none") && !command.key().isEmpty()) { cancelKey(command.key(), command.cancellationMode().equals("interrupt")); return; }
        if (transition.effectId() < 0 || command.operation().equals("none")) return;
        ScheduledEffect invocation = new ScheduledEffect(command.key());
        synchronized (this) {
            if (disposed) return;
            outstandingEffects.add(invocation);
            if (!command.key().isEmpty()) {
                Set<ScheduledEffect> prior = Set.copyOf(keyedEffects.getOrDefault(command.key(), Set.of()));
                keyedEffects.computeIfAbsent(command.key(), ignored -> new HashSet<>()).add(invocation);
                if (!command.cancellationMode().equals("none")) for (ScheduledEffect effect : prior) suppress(effect, command.cancellationMode().equals("interrupt"));
            }
            runningEffects++;
        }
        invocation.future = effects.schedule(() -> runEffect(invocation, transition), command.delayMillis(), TimeUnit.MILLISECONDS);
    }

    private void runEffect(ScheduledEffect invocation, UiPortableBridge.Transition transition) {
        synchronized (this) {
            invocation.started = true;
            if (invocation.suppressed || disposed) { finishEffect(invocation); return; }
        }
        try {
            UiPortableBridge.ActionValue completion;
            try {
                completion = bridge.runEffect(transition.effectId(), transition.effectState(), transition.effectAction());
            } catch (RuntimeException failure) {
                completion = bridge.effectFailure(transition.effectId(), transition.effectState(), transition.effectAction(), failure);
                if (completion == null) throw failure;
            }
            synchronized (this) {
                if (!invocation.suppressed && !disposed) {
                    pending++;
                    UiPortableBridge.ActionValue accepted = completion;
                    transitions.submit(() -> acceptCompletion(accepted));
                }
            }
        } catch (RuntimeException failure) {
            synchronized (this) { asynchronousFailure = failure; }
        } finally {
            synchronized (this) { finishEffect(invocation); }
        }
    }

    private void acceptCompletion(UiPortableBridge.ActionValue action) {
        synchronized (this) {
            if (disposed) { retirePending(); return; }
            UiPortableBridge.Completion completion = bridge.complete(store, action);
            store = completion.store();
            if (!completion.accepted()) { retirePending(); return; }
            if (!completion.startDrain()) return;
        }
        drain();
    }

    private void cancelKey(String key, boolean interrupt) {
        synchronized (this) {
            for (ScheduledEffect effect : Set.copyOf(keyedEffects.getOrDefault(key, Set.of()))) suppress(effect, interrupt);
        }
    }

    private void suppress(ScheduledEffect effect, boolean interrupt) {
        effect.suppressed = true;
        if (interrupt) effect.interruptRequested = true;
        if (effect.future != null && effect.future.cancel(interrupt) && !effect.started) finishEffect(effect);
    }

    private void finishEffect(ScheduledEffect effect) {
        if (effect.finished) return;
        effect.finished = true;
        outstandingEffects.remove(effect);
        if (!effect.key.isEmpty()) {
            Set<ScheduledEffect> values = keyedEffects.get(effect.key);
            if (values != null) {
                values.remove(effect);
                if (values.isEmpty()) keyedEffects.remove(effect.key);
            }
        }
        runningEffects--;
        notifyAll();
    }

    private void retirePending() {
        if (pending > 0) pending--;
        notifyAll();
    }

    public synchronized UiPortableBridge.Node tree() { return tree; }
    public synchronized Map<String, Object> stateSnapshot() { return bridge.stateSnapshot(state); }

    public synchronized void awaitIdle() throws InterruptedException {
        while (pending > 0 || runningEffects > 0) wait();
        if (asynchronousFailure != null) throw asynchronousFailure;
    }

    @Override public void close() {
        synchronized (this) {
            if (disposed) return;
            disposed = true;
            store = bridge.dispose(store);
            for (ScheduledEffect effect : Set.copyOf(outstandingEffects)) suppress(effect, true);
            notifyAll();
        }
        transitions.shutdownNow();
        effects.shutdownNow();
        try { renderer.close(); } catch (Exception failure) { throw new IllegalStateException("Cannot close portable renderer", failure); }
    }

    private static final class ScheduledEffect {
        private final String key;
        private ScheduledFuture<?> future;
        private boolean started;
        private boolean suppressed;
        private boolean interruptRequested;
        private boolean finished;

        private ScheduledEffect(String key) { this.key = key; }
    }
}
