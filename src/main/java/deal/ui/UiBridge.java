package deal.ui;

import java.util.List;
import java.util.Map;

public interface UiBridge {
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
    record Enqueue(Object store, boolean accepted, boolean startDrain) {}
    record Dequeue(Object store, Object action, boolean present) {}
    record Transition(Object state, Node tree, List<Patch> patches, Object store, int effectId, Object effectState, Object effectAction) {}

    String title();
    UiRendererBindings rendererBindings();
    Object initialState();
    Object initialStore();
    Transition initial(Object state, Object store);
    Enqueue enqueue(Object store, Object action);
    Dequeue dequeue(Object store);
    Object finish(Object store);
    Object reject(Object store);
    Object dispose(Object store);
    Transition transition(Object state, Node previous, Object store, Object action);
    Object action(int slot, Object payload);
    Object runEffect(int effectId, Object state, Object action);
    Map<String, Object> stateSnapshot(Object state);
}
