package deal.ui;

import java.util.List;
import java.util.Map;

public interface UiBridge {
    record StateValue(Object abi) { public StateValue { java.util.Objects.requireNonNull(abi); } }
    record StoreValue(Object abi) { public StoreValue { java.util.Objects.requireNonNull(abi); } }
    record ActionValue(Object abi) { public ActionValue { java.util.Objects.requireNonNull(abi); } }
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
    record Transition(StateValue state, Node tree, List<Patch> patches, StoreValue store, int effectId, StateValue effectState, ActionValue effectAction) {}

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
    Map<String, Object> stateSnapshot(StateValue state);
}
