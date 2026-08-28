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

public final class UiProgramRuntime implements AutoCloseable {
    private final UiBridge bridge;
    private final ExecutorService transitions;
    private final ScheduledExecutorService effects;
    private final Map<String, Set<ScheduledEffect>> keyedEffects = new HashMap<>();
    private final Set<ScheduledEffect> outstandingEffects = new HashSet<>();
    private final deal.ui.runtime.SwingUiRuntime renderer;
    private UiBridge.StateValue state;
    private UiBridge.Node tree;
    private UiBridge.StoreValue store;
    private boolean disposed;
    private long pending;
    private long activeDrains;
    private long runningEffects;
    private RuntimeException asynchronousFailure;
    private java.util.function.Consumer<RuntimeException> errorHandler = failure -> {};

    public UiProgramRuntime(UiBridge bridge, String title, UiRendererBindings bindings) {
        this(bridge, title, bindings, Executors.newSingleThreadExecutor(), Executors.newScheduledThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors())));
    }

    UiProgramRuntime(UiBridge bridge, String title, UiRendererBindings bindings, ExecutorService transitions, ScheduledExecutorService effects) {
        this.bridge = bridge;
        this.transitions = transitions;
        this.effects = effects;
        UiBridge.StoreValue createdStore = null;
        deal.ui.runtime.SwingUiRuntime createdRenderer = null;
        try {
            state = bridge.initialState();
            createdStore = bridge.initialStore();
            store = createdStore;
            createdRenderer = new deal.ui.runtime.SwingUiRuntime(title, bindings, bridge, this::dispatch);
            createdRenderer.onCloseRequest(this::close);
            UiBridge.Transition initial = bridge.initial(state, store);
            state = initial.state();
            tree = initial.tree();
            store = initial.store();
            renderer = createdRenderer;
        } catch (RuntimeException | Error failure) {
            if (createdStore != null) try { bridge.dispose(createdStore); } catch (RuntimeException disposeFailure) { failure.addSuppressed(disposeFailure); }
            try { transitions.shutdownNow(); } catch (RuntimeException shutdownFailure) { failure.addSuppressed(shutdownFailure); }
            try { effects.shutdownNow(); } catch (RuntimeException shutdownFailure) { failure.addSuppressed(shutdownFailure); }
            if (createdRenderer != null) try { createdRenderer.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
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
                reportFailure(failure);
                notifyAll();
                return;
            }
            if (!enqueue.accepted()) { pending--; notifyAll(); return; }
        }
        if (enqueue.startDrain()) drain();
    }

    private void drain() {
        synchronized (this) { activeDrains++; }
        try {
        while (true) {
            UiBridge.Dequeue dequeue;
            RuntimeException terminalFailure = null;
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
                    dequeue = null;
                    terminalFailure = failure;
                }
            }
            if (terminalFailure != null) { terminate(terminalFailure); return; }
            apply(dequeue.action());
        }
        } finally { synchronized (this) { activeDrains--; notifyAll(); } }
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
                try { store = bridge.reject(store); } catch (RuntimeException rejectFailure) { failure.addSuppressed(rejectFailure); }
                retirePending();
                reportFailure(failure);
                notifyAll();
            }
            return;
        }
        try {
            renderer.apply(transition.patches(), transition.tree());
        } catch (RuntimeException failure) {
            synchronized (this) { try { store = bridge.reject(store); } catch (RuntimeException rejectFailure) { failure.addSuppressed(rejectFailure); } reportFailure(failure); retirePending(); }
            return;
        }
        synchronized (this) {
            if (disposed) { pending--; notifyAll(); return; }
            state = transition.state();
            tree = transition.tree();
            store = transition.store();
            pending--;
            notifyAll();
        }
        applyEffectCommand(transition);
    }

    private void submitCompletion(UiBridge.ActionValue action) {
        synchronized (this) {
            if (disposed) return;
            pending++;
        }
        try { transitions.submit(() -> acceptCompletion(action)); }
        catch (java.util.concurrent.RejectedExecutionException failure) { terminate(failure); }
    }

    private void acceptCompletion(UiBridge.ActionValue action) {
        UiBridge.Completion completion;
        synchronized (this) {
            if (disposed) { retirePending(); return; }
            try {
                completion = bridge.complete(store, action);
                store = completion.store();
            } catch (RuntimeException failure) {
                retirePending();
                reportFailure(failure);
                return;
            }
            if (!completion.accepted()) { retirePending(); return; }
        }
        if (completion.startDrain()) drain();
    }

    private void retirePending() { if (pending > 0) pending--; notifyAll(); }

    private void applyEffectCommand(UiBridge.Transition transition) {
        UiBridge.EffectCommand command = transition.effectCommand();
        if (command.operation().equals("cancel")) { cancelKey(command.key(), command.cancellationMode().equals("interrupt")); return; }
        if (command.operation().equals("none") && !command.key().isEmpty()) { cancelKey(command.key(), command.cancellationMode().equals("interrupt")); return; }
        if (transition.effectId() < 0 || command.operation().equals("none")) return;
        ScheduledEffect invocation;
        synchronized (this) {
            if (disposed) return;
            invocation = new ScheduledEffect(command.key());
            outstandingEffects.add(invocation);
            if (!command.key().isEmpty()) {
                Set<ScheduledEffect> prior = Set.copyOf(keyedEffects.getOrDefault(command.key(), Set.of()));
                keyedEffects.computeIfAbsent(command.key(), ignored -> new HashSet<>()).add(invocation);
                if (!command.cancellationMode().equals("none")) for (ScheduledEffect value : prior) suppress(value, command.cancellationMode().equals("interrupt"));
            }
            runningEffects++;
        }
        try {
            ScheduledFuture<?> future = effects.schedule(() -> runEffect(invocation, transition.effectId(), transition.effectState(), transition.effectAction()), command.delayMillis(), TimeUnit.MILLISECONDS);
            synchronized (this) {
                invocation.future = future;
                if (invocation.suppressed || disposed) {
                    boolean cancelled = future.cancel(invocation.interruptRequested || disposed);
                    if (cancelled && !invocation.started) finishEffect(invocation);
                }
            }
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            synchronized (this) { finishEffect(invocation); reportFailure(failure); }
        }
    }

    private void runEffect(ScheduledEffect invocation, int effectId, UiBridge.StateValue effectState, UiBridge.ActionValue effectAction) {
        synchronized (this) {
            if (invocation.finished) return;
            invocation.started = true;
            if (invocation.suppressed || disposed) { finishEffect(invocation); return; }
        }
        try {
            UiBridge.ActionValue completion;
            try { completion = bridge.runEffect(effectId, effectState, effectAction); }
            catch (RuntimeException failure) {
                try { completion = bridge.effectFailure(effectId, effectState, effectAction, failure); }
                catch (RuntimeException mappingFailure) { failure.addSuppressed(mappingFailure); synchronized (this) { reportFailure(failure); } return; }
                if (completion == null) { synchronized (this) { reportFailure(failure); } return; }
            }
            synchronized (this) { if (disposed || invocation.suppressed) return; }
            submitCompletion(completion);
        } finally {
            synchronized (this) { finishEffect(invocation); }
        }
    }

    private void cancelKey(String key, boolean interrupt) { synchronized (this) { for (ScheduledEffect invocation : Set.copyOf(keyedEffects.getOrDefault(key, Set.of()))) suppress(invocation, interrupt); } }
    private void suppress(ScheduledEffect invocation, boolean interrupt) {
        if (invocation.finished) return;
        invocation.suppressed = true;
        invocation.interruptRequested |= interrupt;
        removeKeyed(invocation);
        ScheduledFuture<?> future = invocation.future;
        if (future != null) {
            boolean cancelled = future.cancel(interrupt);
            if (cancelled && !invocation.started) finishEffect(invocation);
        }
    }
    private void finishEffect(ScheduledEffect invocation) {
        if (invocation.finished) return;
        invocation.finished = true;
        removeKeyed(invocation);
        outstandingEffects.remove(invocation);
        runningEffects--;
        notifyAll();
    }
    private void removeKeyed(ScheduledEffect invocation) {
        if (invocation.key.isEmpty()) return;
        Set<ScheduledEffect> values = keyedEffects.get(invocation.key);
        if (values == null) return;
        values.remove(invocation);
        if (values.isEmpty()) keyedEffects.remove(invocation.key);
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

    public synchronized void onError(java.util.function.Consumer<RuntimeException> handler) { errorHandler = java.util.Objects.requireNonNull(handler); }
    private void reportFailure(RuntimeException failure) {
        asynchronousFailure = failure;
        try { errorHandler.accept(failure); } catch (RuntimeException handlerFailure) { failure.addSuppressed(handlerFailure); }
    }
    private void terminate(RuntimeException failure) {
        synchronized (this) {
            if (disposed) { if (asynchronousFailure == null) reportFailure(failure); return; }
            disposed = true;
            pending = 0;
            for (ScheduledEffect invocation : Set.copyOf(outstandingEffects)) suppress(invocation, true);
            try { store = bridge.dispose(store); } catch (RuntimeException disposeFailure) { failure.addSuppressed(disposeFailure); }
            reportFailure(failure);
            notifyAll();
        }
        try { transitions.shutdownNow(); } catch (RuntimeException shutdownFailure) { failure.addSuppressed(shutdownFailure); }
        try { effects.shutdownNow(); } catch (RuntimeException shutdownFailure) { failure.addSuppressed(shutdownFailure); }
        try { renderer.close(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    public void show() { UiBridge.Node value; synchronized (this) { value = tree; } renderer.show(value); }
    public void click(String text) { renderer.click(text); }
    public deal.ui.runtime.SwingUiRuntime renderer() { return renderer; }
    public synchronized boolean disposed() { return disposed; }
    public synchronized Map<String, Object> stateSnapshot() { return bridge.stateSnapshot(state); }
    public synchronized UiBridge.Node tree() { return tree; }
    public void awaitActions() throws InterruptedException { synchronized (this) { while (!disposed && (pending > 0 || activeDrains > 0)) wait(); } }
    public synchronized void awaitIdle() throws InterruptedException {
        while (pending > 0 || activeDrains > 0 || runningEffects > 0) wait();
        if (asynchronousFailure != null) { RuntimeException failure = asynchronousFailure; asynchronousFailure = null; throw failure; }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        synchronized (this) {
            if (disposed) return;
            disposed = true;
            for (ScheduledEffect invocation : Set.copyOf(outstandingEffects)) suppress(invocation, true);
            try { store = bridge.dispose(store); } catch (RuntimeException disposeFailure) { failure = disposeFailure; }
            notifyAll();
        }
        try { transitions.shutdownNow(); } catch (RuntimeException shutdownFailure) { if (failure == null) failure = shutdownFailure; else failure.addSuppressed(shutdownFailure); }
        try { effects.shutdownNow(); } catch (RuntimeException shutdownFailure) { if (failure == null) failure = shutdownFailure; else failure.addSuppressed(shutdownFailure); }
        try { renderer.close(); } catch (RuntimeException closeFailure) { if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure); }
        if (failure != null) throw failure;
    }
}
