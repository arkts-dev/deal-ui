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
    record Lifecycle(boolean disposed, boolean draining, long revision, long queued) {}
    record Enqueue(boolean accepted, boolean startDrain, Lifecycle lifecycle) {}
    record Transition(Object state, Node tree, List<Patch> patches, Lifecycle lifecycle, int effectId, Object effectState, Object effectAction) {}

    String title();
    UiRendererBindings rendererBindings();
    Object initialState();
    Lifecycle initialLifecycle();
    Transition initial(Object state, Lifecycle lifecycle);
    Enqueue enqueue(Lifecycle lifecycle);
    Lifecycle finish(Lifecycle lifecycle);
    Lifecycle reject(Lifecycle lifecycle);
    Lifecycle dispose(Lifecycle lifecycle);
    Transition transition(Object state, Node previous, Lifecycle lifecycle, Object action);
    Object action(int slot, Object payload);
    Object runEffect(int effectId, Object state, Object action);
    Map<String, Object> stateSnapshot(Object state);
}
