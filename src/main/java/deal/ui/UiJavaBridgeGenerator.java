package deal.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UiJavaBridgeGenerator {
    enum Target { SWING, PORTABLE }

    private final UiModel.CheckedProgram program;
    private final UiDealGenerator.Output generated;
    private final String ui;
    private final String bridge;
    private final Target target;

    UiJavaBridgeGenerator(UiModel.CheckedProgram program, UiDealGenerator.Output generated, String ui, String bridge) {
        this(program, generated, ui, bridge, Target.SWING);
    }

    UiJavaBridgeGenerator(UiModel.CheckedProgram program, UiDealGenerator.Output generated, String ui, String bridge, Target target) {
        this.program = program;
        this.generated = generated;
        this.ui = ui;
        this.bridge = bridge;
        this.target = target;
    }

    String generate() {
        String interfaceName = target == Target.PORTABLE ? "deal.ui.UiPortableBridge" : "deal.ui.UiBridge";
        StringBuilder out = new StringBuilder("public final class ").append(bridge).append(" implements ").append(interfaceName).append(" {\n");
        out.append("  @Override public String title() { return \"").append(program.title()).append("\"; }\n")
            .append("  @Override public CheckedMetadata checkedMetadata() { return new CheckedMetadata(\"").append(escape(program.metadata().rootStateType())).append("\", ")
            .append(stringList(program.metadata().reachableInputActions())).append(", ")
            .append(stringList(program.metadata().effectCompletionActions())).append(", ")
            .append(stringList(program.metadata().usedComponents())).append(", ")
            .append(stringMap(program.metadata().componentCapabilities())).append(", ")
            .append(stringMap(program.metadata().packVersions())).append(", ")
            .append(stringMap(program.metadata().packDigests())).append("); }\n")
            .append("  @Override public java.util.Map<String, String> componentCapabilities() { return java.util.Map.ofEntries(");
        boolean first = true;
        for (Map.Entry<String, UiModel.Component> component : program.components().entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append("java.util.Map.entry(\"").append(component.getKey()).append("\", \"").append(capability(component.getValue())).append("\")");
        }
        out.append("); }\n");
        if (target == Target.SWING) {
            out.append("  @Override public deal.ui.UiRendererBindings rendererBindings() { return new deal.ui.UiRendererBindings(java.util.Map.ofEntries(");
            first = true;
            for (Map.Entry<String, UiModel.Component> component : program.components().entrySet()) {
                if (!first) out.append(", ");
                first = false;
                String factory = factory(component.getValue());
                out.append("java.util.Map.entry(\"").append(component.getKey()).append("\", deal.ui.UiRendererBindings.").append(factory.equals("modal") ? "modalBinding" : "binding").append("(\"").append(component.getKey()).append("\", deal.ui.UiRendererBindings::").append(factory).append(", deal.ui.UiRendererBindings::configure))");
            }
            out.append("), java.util.Map.ofEntries(");
            first = true;
            for (Map.Entry<String, UiModel.Token> token : program.tokens().entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append("java.util.Map.entry(\"").append(token.getKey()).append("\", ").append(tokenValue(token.getValue())).append(")");
            }
            out.append("), new java.awt.Color(0xF3F6FB), new java.awt.Color(0x635BFF)); }\n");
        }
        out.append("  public ").append(bridge).append("() { ").append(ui).append(".dealMain(); }\n")
            .append("  private Object call(String name, Object... args) { return ((deal.codegen.jvm.JvmRuntime.FunctionValue) ").append(ui).append(".exportSurface(\"ui_application\").read(name)).fn.invoke(args); }\n")
            .append("  private record Binding(int slot, Object values) {}\n")
            .append("  private int nextBinding = 1000000; private final java.util.Map<Integer, Binding> bindings = new java.util.HashMap<>(); private final java.util.IdentityHashMap<Object, Integer> handles = new java.util.IdentityHashMap<>(); private Object previousTree;\n")
            .append("  private static Object field(Object value, String name) { return ((deal.codegen.jvm.JvmRuntime.ClassInstance) value).read(name); }\n")
            .append("  private static String text(Object value, String name) { return (String) field(value, name); }\n")
            .append("  private static long integer(Object value, String name) { return ((Number) field(value, name)).longValue(); }\n")
            .append("  private static boolean flag(Object value, String name) { return (Boolean) field(value, name); }\n")
            .append("  private static java.util.List<Object> elements(Object value) { return ((deal.codegen.jvm.JvmRuntime.Array) value).elements; }\n")
            .append("  private static deal.codegen.jvm.JvmRuntime.Array array(java.util.List<Object> values) { var result = new deal.codegen.jvm.JvmRuntime.Array(values.size()); result.elements.addAll(values); return result; }\n")
            .append("  @Override public StateValue initialState() { return new StateValue(call(\"bridgeInitialState\")); }\n")
            .append("  @Override public StoreValue initialStore() { return new StoreValue(call(\"initialStore\")); }\n")
            .append("  @Override public Transition initial(StateValue state, StoreValue store) { return transition(call(\"initial\", state.abi(), store.abi()), state, null); }\n")
            .append("  @Override public Enqueue enqueue(StoreValue store, ActionValue action) { var v = call(\"enqueue\", store.abi(), action.abi()); return new Enqueue(new StoreValue(field(v, \"store\")), flag(v, \"accepted\"), flag(v, \"startDrain\")); }\n")
            .append("  @Override public Dequeue dequeue(StoreValue store) { var v = call(\"dequeue\", store.abi()); return new Dequeue(new StoreValue(field(v, \"store\")), new ActionValue(field(v, \"action\")), flag(v, \"present\")); }\n")
            .append("  @Override public StoreValue finish(StoreValue store) { return new StoreValue(call(\"finish\", store.abi())); }\n")
            .append("  @Override public StoreValue reject(StoreValue store) { return new StoreValue(call(\"reject\", store.abi())); }\n")
            .append("  @Override public StoreValue dispose(StoreValue store) { return new StoreValue(call(\"dispose\", store.abi())); }\n")
            .append("  @Override public Completion complete(StoreValue store, ActionValue action) { var v = call(\"complete\", store.abi(), action.abi()); return new Completion(new StoreValue(field(v, \"store\")), flag(v, \"accepted\"), flag(v, \"startDrain\")); }\n")
            .append("  @Override public Transition transition(StateValue state, Node previous, StoreValue store, ActionValue action) { var next = new StateValue(call(\"nextState\", state.abi(), action.abi())); return transition(call(\"transitionFromCandidate\", next.abi(), previous == null ? null : previousTree, store.abi(), action.abi()), next, action); }\n");
        generateActions(out);
        generateEffects(out);
        generateEffectFailures(out);
        generateSnapshot(out);
        generateConversions(out);
        out.append("}\n");
        return out.toString();
    }

    private void generateActions(StringBuilder out) {
        if (generated.actions().isEmpty()) {
            out.append("  @Override public ActionValue action(int slot, Object payload) { throw new IllegalArgumentException(\"Unknown action slot: \" + slot); }\n");
            return;
        }
        out.append("  @Override public ActionValue action(int slot, Object payload) { Binding binding = bindings.get(slot); if (slot >= 1000000 && binding == null) throw new IllegalArgumentException(\"Invalid or stale action binding\"); Object values = binding == null ? new deal.codegen.jvm.JvmRuntime.Table() : binding.values(); int factory = binding == null ? slot : binding.slot(); return new ActionValue(switch (factory) {\n");
        for (var entry : generated.actions().entrySet()) {
            int slot = entry.getKey();
            var type = entry.getValue().payloadType();
            out.append("    case ").append(slot).append(" -> ");
            if (type == null) out.append("payload == null ? call(\"action_").append(slot).append("\", values) : invalidPayload(slot, \"no\", payload)");
            else out.append("call(\"action_").append(slot).append("\", values, ").append(payloadConversion(slot, type)).append(")");
            out.append(";\n");
        }
        out.append("    default -> throw new IllegalArgumentException(\"Unknown action slot: \" + slot);\n  }); }\n")
            .append("  private static Object invalidPayload(int slot, String expected, Object payload) { throw new IllegalArgumentException(\"Action slot \" + slot + \" requires \" + expected + \" payload: \" + payload); }\n");
    }

    private void generateEffects(StringBuilder out) {
        if (generated.effectIds().isEmpty()) {
            out.append("  @Override public ActionValue runEffect(int effectId, StateValue state, ActionValue action) { throw new IllegalArgumentException(\"Unknown effect: \" + effectId); }\n");
            return;
        }
        out.append("  @Override public ActionValue runEffect(int effectId, StateValue state, ActionValue action) { Object value = switch (effectId) {\n");
        for (var entry : generated.effectIds().entrySet()) out.append("    case ").append(entry.getValue()).append(" -> call(\"bridgeEffect").append(entry.getValue()).append("\", state.abi(), action.abi());\n");
        out.append("    default -> throw new IllegalArgumentException(\"Unknown effect: \" + effectId);\n  };\n")
            .append("    if (value instanceof deal.codegen.jvm.JvmRuntime.AsyncTask task) { deal.codegen.jvm.JvmRuntime.drainTasks(); value = task.future.join(); } return new ActionValue(value); }\n");
    }

    private void generateEffectFailures(StringBuilder out) {
        if (program.effectFailures().isEmpty()) return;
        out.append("  @Override public ActionValue effectFailure(int effectId, StateValue state, ActionValue action, RuntimeException failure) { return switch (effectId) {\n");
        for (var entry : generated.effectIds().entrySet()) if (program.effectFailures().containsKey(entry.getKey())) out.append("    case ").append(entry.getValue()).append(" -> new ActionValue(call(\"bridgeEffectFailure").append(entry.getValue()).append("\", state.abi(), action.abi(), failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage()));\n");
        out.append("    default -> null;\n  }; }\n");
    }

    private void generateSnapshot(StringBuilder out) {
        out.append("  @Override public java.util.Map<String, Object> stateSnapshot(StateValue state) { java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();");
        for (var field : program.deal().classes().get(program.rootStateType()).fields().values()) out.append(" result.put(\"").append(field.name()).append("\", field(state.abi(), \"").append(field.name()).append("\"));");
        out.append(" return java.util.Collections.unmodifiableMap(result); }\n");
    }

    private void generateConversions(StringBuilder out) {
        out.append("  private int actionHandle(Object p) { int slot = (int) integer(p, \"actionSlot\"); if (!text(p, \"kind\").equals(\"action\")) return slot; Object values = field(p, \"actionBindings\"); if (((deal.codegen.jvm.JvmRuntime.Table) values).entries.isEmpty()) return slot; Integer old = handles.get(p); if (old != null) return old; if (nextBinding == Integer.MAX_VALUE) throw new IllegalStateException(\"Action identity exhausted\"); int handle = nextBinding++; handles.put(p, handle); bindings.put(handle, new Binding(slot, values)); return handle; }\n");
        out.append("  private Transition transition(Object v, StateValue state, ActionValue action) { bindings.clear(); handles.clear(); previousTree = field(v, \"tree\"); Node tree = node(previousTree); var active = new java.util.HashMap<>(bindings); var command = action == null ? EffectCommand.immediate() : command(call(\"effectCommand\", state.abi(), action.abi())); var patches = patches(field(field(v, \"plan\"), \"patches\")); bindings.keySet().retainAll(active.keySet()); return new Transition(state, tree, patches, new StoreValue(field(v, \"store\")), (int) integer(field(v, \"effect\"), \"effectId\"), state, action, command); }\n")
            .append("  private EffectCommand command(Object v) { return new EffectCommand(text(v, \"operation\"), text(v, \"key\"), integer(v, \"delayMillis\"), text(v, \"cancellationMode\")); }\n")
            .append("  private Identity identity(Object v) { return new Identity(text(v, \"structural\"), new Key(text(v, \"keyKind\"), integer(v, \"keyInt\"), text(v, \"keyString\"))); }\n")
            .append("  private Node node(Object v) { java.util.Map<String, Prop> props = new java.util.LinkedHashMap<>(); for (Object p : elements(field(v, \"props\"))) { String kind = text(p, \"kind\"); Object value = switch(kind) { case \"int\" -> integer(p, \"intValue\"); case \"number\" -> field(p, \"numberValue\"); case \"boolean\" -> flag(p, \"booleanValue\"); default -> text(p, \"stringValue\"); }; props.put(text(p, \"name\"), new Prop(text(p, \"name\"), kind, value, actionHandle(p))); } java.util.List<Node> children = new java.util.ArrayList<>(); for(Object child : elements(field(v, \"children\"))) children.add(node(child)); return new Node(text(v, \"component\"), identity(v), props, children); }\n")
            .append("  private Object nodeValue(Node v) { java.util.List<Object> props = new java.util.ArrayList<>(); for (Prop p : v.props().values()) props.add(call(\"bridgeProp\", p.name(), p.kind(), p.value() instanceof String s ? s : \"\", p.value() instanceof Long n ? n : 0L, p.value() instanceof Double n ? n : 0.0, p.value() instanceof Boolean b && b, (long) p.actionSlot())); java.util.List<Object> children = new java.util.ArrayList<>(); for(Node n : v.children()) children.add(nodeValue(n)); return call(\"bridgeNode\", v.component(), v.identity().structural(), v.identity().key().kind(), v.identity().key().intValue(), v.identity().key().stringValue(), array(props), array(children)); }\n")
            .append("  private java.util.List<Patch> patches(Object v) { java.util.List<Patch> result = new java.util.ArrayList<>(); for(Object p : elements(v)) result.add(new Patch(text(p, \"kind\"), identity(p), new Identity(text(p, \"parentStructural\"), new Key(text(p, \"parentKeyKind\"), integer(p, \"parentKeyInt\"), text(p, \"parentKeyString\"))), flag(p, \"rootParent\"), (int) integer(p, \"index\"), node(field(p, \"node\")))); return java.util.List.copyOf(result); }\n");
    }

    private String factory(UiModel.Component component) {
        for (UiModel.Contract contract : component.contracts()) if (contract instanceof UiModel.Capability capability && capability.name().startsWith("renderer.swing.")) {
            String factory = capability.name().substring("renderer.swing.".length());
            if (!factory.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalStateException("Invalid Swing renderer factory " + capability.name());
            return factory;
        }
        throw new IllegalStateException("Swing renderer capability required for " + component.name());
    }

    private String capability(UiModel.Component component) {
        for (UiModel.Contract contract : component.contracts()) if (contract instanceof UiModel.Capability capability) return capability.name();
        throw new IllegalStateException("Renderer capability required for " + component.name());
    }
    private String payloadConversion(int slot, UiModel.TypeRef type) {
        if (type == null) throw new IllegalArgumentException("Payload conversion requires an event payload contract");
        String expected = type.name() + "[]".repeat(type.dimensions()) + (type.optional() ? " | null" : "");
        String invalid = "invalidPayload(" + slot + ", \"" + expected + "\", payload)";
        String conversion;
        if (type.array()) conversion = "payload instanceof deal.codegen.jvm.JvmRuntime.Array value ? value : " + invalid;
        else conversion = switch (simple(type.name())) {
            case "string" -> "payload instanceof String text ? text : " + invalid;
            case "int" -> "payload instanceof Long number ? number : " + invalid;
            case "number" -> "payload instanceof Double number ? number : " + invalid;
            case "boolean" -> "payload instanceof Boolean bool ? bool : " + invalid;
            default -> "payload instanceof deal.codegen.jvm.JvmRuntime.ClassInstance value ? value : " + invalid;
        };
        return type.optional() ? "payload == null ? null : (" + conversion + ")" : conversion;
    }
    private String simple(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(dot + 1); }

    private String tokenValue(UiModel.Token token) {
        if (token.value() instanceof UiModel.Literal literal && literal.value() instanceof Map<?, ?> map) {
            UiModel.Expr value = (UiModel.Expr) map.get("value");
            if (value instanceof UiModel.Literal number) return String.valueOf(number.value());
        }
        throw new IllegalStateException("Token host value must contain numeric value");
    }
    private String javaDefault(UiModel.TypeRef type) { if (type.optional()) return "null"; if (type.array()) return "null"; return switch (type.name()) { case "string" -> "\"\""; case "int" -> "0L"; case "number" -> "0.0"; case "boolean" -> "false"; default -> "null"; }; }
    private String stringList(List<String> values) { return "java.util.List.of(" + values.stream().map(value -> "\"" + escape(value) + "\"").collect(java.util.stream.Collectors.joining(", ")) + ")"; }
    private String stringMap(Map<String, String> values) { return "java.util.Map.ofEntries(" + values.entrySet().stream().map(entry -> "java.util.Map.entry(\"" + escape(entry.getKey()) + "\", \"" + escape(entry.getValue()) + "\")").collect(java.util.stream.Collectors.joining(", ")) + ")"; }
    private String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private void appendFields(StringBuilder out, UiModel.DealClass clazz, java.util.function.Function<UiModel.Field, String> value) { boolean first = true; for (UiModel.Field field : clazz.fields().values()) { if (!first) out.append(", "); first = false; out.append(value.apply(field)); } }
}
