package deal.ui;

import java.util.List;
import java.util.Map;

public interface UiBridge {
    sealed interface AbiValue permits StateValue, StoreValue, ActionValue { Object abi(); }
    record StateValue(Object abi) implements AbiValue { public StateValue { java.util.Objects.requireNonNull(abi); } }
    record StoreValue(Object abi) implements AbiValue { public StoreValue { java.util.Objects.requireNonNull(abi); } }
    record ActionValue(Object abi) implements AbiValue { public ActionValue { java.util.Objects.requireNonNull(abi); } }
    record Key(String kind, long intValue, String stringValue) {}
    record Identity(String structural, Key key) {}
    record Prop(String name, String kind, Object value, int actionSlot) {}
    record Node(String component, Identity identity, Map<String, Prop> props, List<Node> children) {
        public Node {
            props = Map.copyOf(props);
            children = List.copyOf(children);
        }
    }
    record Patch(String kind, Identity identity, Identity parentIdentity, boolean rootParent, int index, Node node) {}
    record Enqueue(StoreValue store, boolean accepted, boolean startDrain) {}
    record Dequeue(StoreValue store, ActionValue action, boolean present) {}
    record Completion(StoreValue store, boolean accepted, boolean startDrain) {}
    record EffectCommand(String operation, String key, long delayMillis, String cancellationMode) {
        public EffectCommand {
            java.util.Objects.requireNonNull(operation);
            java.util.Objects.requireNonNull(key);
            java.util.Objects.requireNonNull(cancellationMode);
            if (!operation.equals("none") && !operation.equals("start") && !operation.equals("cancel")) throw new IllegalArgumentException("Unknown effect operation: " + operation);
            if (delayMillis < 0) throw new IllegalArgumentException("Effect delay must be non-negative");
            if (!cancellationMode.equals("none") && !cancellationMode.equals("replace") && !cancellationMode.equals("interrupt")) throw new IllegalArgumentException("Unknown effect cancellation mode: " + cancellationMode);
            if ((operation.equals("cancel") || !cancellationMode.equals("none")) && key.isEmpty()) throw new IllegalArgumentException("Effect key is required for cancellation");
        }
        public static EffectCommand none() { return new EffectCommand("none", "", 0, "none"); }
        public static EffectCommand immediate() { return new EffectCommand("start", "", 0, "none"); }
    }
    record Transition(StateValue state, Node tree, List<Patch> patches, StoreValue store, int effectId, StateValue effectState, ActionValue effectAction, EffectCommand effectCommand) {}

    String title();
    UiRendererBindings rendererBindings();
    StateValue initialState();
    StoreValue initialStore();
    Transition initial(StateValue state, StoreValue store);
    Enqueue enqueue(StoreValue store, ActionValue action);
    Dequeue dequeue(StoreValue store);
    StoreValue finish(StoreValue store);
    StoreValue reject(StoreValue store);
    StoreValue dispose(StoreValue store);
    Completion complete(StoreValue store, ActionValue action);
    Transition transition(StateValue state, Node previous, StoreValue store, ActionValue action);
    ActionValue action(int slot, Object payload);
    ActionValue runEffect(int effectId, StateValue state, ActionValue action);
    default ActionValue effectFailure(int effectId, StateValue state, ActionValue action, RuntimeException failure) { return null; }
    Map<String, Object> stateSnapshot(StateValue state);
}
