package deal.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UiJavaBridgeGenerator {
    private final UiModel.CheckedProgram program;
    private final UiDealGenerator.Output generated;
    private final String ui;
    private final String app;
    private final String bridge;

    UiJavaBridgeGenerator(UiModel.CheckedProgram program, UiDealGenerator.Output generated, String ui, String app, String bridge) {
        this.program = program;
        this.generated = generated;
        this.ui = ui;
        this.app = app;
        this.bridge = bridge;
    }

    String generate() {
        StringBuilder out = new StringBuilder("public final class ").append(bridge).append(" implements deal.ui.UiBridge {\n");
        out.append("  @Override public String title() { return \"").append(program.title()).append("\"; }\n")
            .append("  @Override public deal.ui.UiRendererBindings rendererBindings() { return new deal.ui.UiRendererBindings(java.util.Map.ofEntries(");
        boolean first = true;
        for (Map.Entry<String, UiModel.Component> component : program.components().entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append("java.util.Map.entry(\"").append(component.getKey()).append("\", new deal.ui.UiRendererBindings.Binding(\"").append(component.getKey()).append("\", deal.ui.UiRendererBindings::").append(factory(component.getValue())).append(", deal.ui.UiRendererBindings::configure))");
        }
        out.append("), java.util.Map.ofEntries(");
        first = true;
        for (Map.Entry<String, UiModel.Token> token : program.tokens().entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append("java.util.Map.entry(\"").append(token.getKey()).append("\", ").append(tokenValue(token.getValue())).append(")");
        }
        out.append("), new java.awt.Color(0xF3F6FB), new java.awt.Color(0x635BFF)); }\n")
            .append("  @Override public StateValue initialState() { return new StateValue(").append(app).append(".initialState()); }\n")
            .append("  @Override public StoreValue initialStore() { return new StoreValue(").append(ui).append(".initialStore()); }\n")
            .append("  @Override public Transition initial(StateValue state, StoreValue store) { return transition(").append(ui).append(".initial((").append(app).append(".$C_").append(program.rootStateType()).append(") state.abi(), (").append(ui).append(".$C_UiStore) store.abi()), state, null); }\n")
            .append("  @Override public Enqueue enqueue(StoreValue store, ActionValue action) { var value = ").append(ui).append(".enqueue((").append(ui).append(".$C_UiStore) store.abi(), (").append(ui).append(".$C_UiAction) action.abi()); return new Enqueue(new StoreValue(value.store), value.accepted, value.startDrain); }\n")
            .append("  @Override public Dequeue dequeue(StoreValue store) { var value = ").append(ui).append(".dequeue((").append(ui).append(".$C_UiStore) store.abi()); return new Dequeue(new StoreValue(value.store), new ActionValue(value.action), value.present); }\n")
            .append("  @Override public StoreValue finish(StoreValue store) { return new StoreValue(").append(ui).append(".finish((").append(ui).append(".$C_UiStore) store.abi())); }\n")
            .append("  @Override public StoreValue reject(StoreValue store) { return new StoreValue(").append(ui).append(".reject((").append(ui).append(".$C_UiStore) store.abi())); }\n")
            .append("  @Override public StoreValue dispose(StoreValue store) { return new StoreValue(").append(ui).append(".dispose((").append(ui).append(".$C_UiStore) store.abi())); }\n")
            .append("  @Override public Completion complete(StoreValue store, ActionValue action) { var value = ").append(ui).append(".complete((").append(ui).append(".$C_UiStore) store.abi(), (").append(ui).append(".$C_UiAction) action.abi()); return new Completion(new StoreValue(value.store), value.accepted, value.startDrain); }\n")
            .append("  @Override public Transition transition(StateValue state, Node previous, StoreValue store, ActionValue action) { var typedAction = (").append(ui).append(".$C_UiAction) action.abi(); var next = ").append(ui).append(".nextState((").append(app).append(".$C_").append(program.rootStateType()).append(") state.abi(), typedAction); var value = ").append(ui).append(".transitionFromCandidate(next, previous == null ? null : nodeValue(previous), (").append(ui).append(".$C_UiStore) store.abi(), typedAction); return transition(value, new StateValue(next), action); }\n");

        generateActions(out);
        generateEffects(out);
        generateSnapshot(out);
        generateConversions(out);
        out.append("}\n");
        return out.toString();
    }

    private void generateActions(StringBuilder out) {
        out.append("  @Override public ActionValue action(int slot, Object payload) { String text = payload == null ? \"\" : String.valueOf(payload); return new ActionValue(switch (slot) {\n");
        for (Integer slot : generated.actions().keySet()) out.append("    case ").append(slot).append(" -> ").append(ui).append(".action$u").append(slot).append("(text);\n");
        out.append("    default -> throw new IllegalArgumentException(\"Unknown generated action slot: \" + slot);\n  }); }\n");
    }

    private void generateEffects(StringBuilder out) {
        out.append("  @Override public ActionValue runEffect(int effectId, StateValue state, ActionValue action) { var value = (").append(ui).append(".$C_UiAction) action.abi(); return new ActionValue(switch (effectId) {\n");
        for (Map.Entry<String, Integer> entry : generated.effectIds().entrySet()) {
            int id = generated.actionIds().get(entry.getKey());
            UiModel.Handler handler = program.effects().get(entry.getKey());
            UiModel.DealClass action = program.deal().classes().get(entry.getKey());
            out.append("    case ").append(entry.getValue()).append(" -> completion_").append(entry.getValue()).append("(").append(app).append(".").append(handler.name()).append("((").append(app).append(".$C_").append(program.rootStateType()).append(") state.abi(), new ").append(app).append(".$C_").append(entry.getKey()).append("(");
            appendFields(out, action, field -> "value.nominal" + id + "." + field.name());
            out.append(")));\n");
        }
        out.append("    default -> throw new IllegalArgumentException(\"Unknown generated effect: \" + effectId);\n  }); }\n");
        for (Map.Entry<String, Integer> entry : generated.effectIds().entrySet()) {
            UiModel.Handler handler = program.effects().get(entry.getKey());
            int completionId = generated.actionIds().get(handler.returnType());
            UiModel.DealClass completion = program.deal().classes().get(handler.returnType());
            out.append("  private Object completion_").append(entry.getValue()).append("(").append(app).append(".$C_").append(handler.returnType()).append(" value) { return new ").append(ui).append(".$C_UiAction(-1L, \"\"");
            for (Map.Entry<String, Integer> action : generated.actionIds().entrySet()) out.append(", ").append(action.getKey().equals(handler.returnType()) ? "value, true" : "null, false");
            out.append("); }\n");
        }
    }

    private void generateSnapshot(StringBuilder out) {
        out.append("  @Override public java.util.Map<String, Object> stateSnapshot(StateValue state) { var value = (").append(app).append(".$C_").append(program.rootStateType()).append(") state.abi(); return java.util.Map.ofEntries(");
        boolean first = true;
        for (UiModel.Field field : program.deal().classes().get(program.rootStateType()).fields().values()) {
            if (!first) out.append(", ");
            first = false;
            out.append("java.util.Map.entry(\"").append(field.name()).append("\", value.").append(field.name()).append(")");
        }
        out.append("); }\n");
    }

    private void generateConversions(StringBuilder out) {
        out.append("  private Transition transition(").append(ui).append(".$C_Transition value, StateValue state, ActionValue effectAction) { return new Transition(state, node(value.tree), patches(value.plan.patches), new StoreValue(value.store), (int) value.effect.effectId, state, effectAction); }\n")
            .append("  private Identity identity(").append(ui).append(".$C_ViewNode value) { return new Identity(value.structural, new Key(value.keyKind, value.keyInt, value.keyString)); }\n")
            .append("  private Node node(").append(ui).append(".$C_ViewNode value) { java.util.Map<String, Prop> props = new java.util.LinkedHashMap<>(); for (Object raw : value.props.data) { var prop = (").append(ui).append(".$C_Prop) raw; Object converted = switch (prop.kind) { case \"int\" -> prop.intValue; case \"number\" -> prop.numberValue; case \"boolean\" -> prop.booleanValue; default -> prop.stringValue; }; props.put(prop.name, new Prop(prop.name, prop.kind, converted, (int) prop.actionSlot)); } java.util.List<Node> children = new java.util.ArrayList<>(); for (Object raw : value.children.data) children.add(node((").append(ui).append(".$C_ViewNode) raw)); return new Node(value.component, identity(value), props, children); }\n")
            .append("  private ").append(ui).append(".$C_ViewNode nodeValue(Node value) { var props = new ").append(ui).append(".$Array$Prop(new Object[0]); for (Prop prop : value.props().values()) { var raw = new ").append(ui).append(".$C_Prop(prop.name(), prop.kind(), prop.value() instanceof String text ? text : \"\", prop.value() instanceof Long number ? number : 0L, prop.value() instanceof Double number ? number : 0.0, prop.value() instanceof Boolean bool && bool, prop.actionSlot()); props.data = java.util.Arrays.copyOf(props.data, props.data.length + 1); props.data[props.data.length - 1] = raw; } var children = new ").append(ui).append(".$Array$ViewNode(new Object[0]); for (Node child : value.children()) { children.data = java.util.Arrays.copyOf(children.data, children.data.length + 1); children.data[children.data.length - 1] = nodeValue(child); } return new ").append(ui).append(".$C_ViewNode(value.component(), value.identity().structural(), value.identity().key().kind(), value.identity().key().intValue(), value.identity().key().stringValue(), props, children); }\n")
            .append("  private java.util.List<Patch> patches(").append(ui).append(".$Array$Patch values) { java.util.List<Patch> result = new java.util.ArrayList<>(); for (Object raw : values.data) { var value = (").append(ui).append(".$C_Patch) raw; var identity = new Identity(value.structural, new Key(value.keyKind, value.keyInt, value.keyString)); var parent = new Identity(value.parentStructural, new Key(value.parentKeyKind, value.parentKeyInt, value.parentKeyString)); result.add(new Patch(value.kind, identity, parent, value.rootParent, (int) value.index, node(value.node))); } return java.util.List.copyOf(result); }\n");
    }

    private String factory(UiModel.Component component) {
        for (UiModel.Contract contract : component.contracts()) if (contract instanceof UiModel.Capability capability && capability.name().startsWith("renderer.swing.")) {
            String factory = capability.name().substring("renderer.swing.".length());
            if (!factory.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalStateException("Invalid Swing renderer factory " + capability.name());
            return factory;
        }
        throw new IllegalStateException("Swing renderer capability required for " + component.name());
    }
    private String tokenValue(UiModel.Token token) {
        if (token.value() instanceof UiModel.Literal literal && literal.value() instanceof Map<?, ?> map) {
            UiModel.Expr value = (UiModel.Expr) map.get("value");
            if (value instanceof UiModel.Literal number) return String.valueOf(number.value());
        }
        throw new IllegalStateException("Token host value must contain numeric value");
    }
    private String javaDefault(UiModel.TypeRef type) { if (type.optional()) return "null"; if (type.array()) return "null"; return switch (type.name()) { case "string" -> "\"\""; case "int" -> "0L"; case "number" -> "0.0"; case "boolean" -> "false"; default -> "null"; }; }
    private void appendFields(StringBuilder out, UiModel.DealClass clazz, java.util.function.Function<UiModel.Field, String> value) { boolean first = true; for (UiModel.Field field : clazz.fields().values()) { if (!first) out.append(", "); first = false; out.append(value.apply(field)); } }
}
