package deal.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UiDealGenerator {
    record Output(String source, String appAugmentation, Map<Integer, UiModel.Action> actions, Map<String, Integer> actionIds, Map<String, Integer> effectIds) {}

    private final UiModel.CheckedProgram program;
    private final Map<Integer, UiModel.Action> actions = new LinkedHashMap<>();
    private final Map<String, Integer> actionIds = new LinkedHashMap<>();
    private final Map<String, Integer> effectIds = new LinkedHashMap<>();
    private int actionSlot;
    private int localId;

    UiDealGenerator(UiModel.CheckedProgram program) {
        this.program = program;
        int id = 0;
        for (String action : program.updates().keySet()) actionIds.put(action, id++);
        id = 0;
        for (String action : program.effects().keySet()) effectIds.put(action, id++);
    }

    Output generate() {
        String app = moduleName(program.dealSource());
        StringBuilder out = new StringBuilder();
        out.append("import * as app from \"./").append(app).append("\";\n\n")
            .append("export class UiAction {\n  kind: int = -1;\n  slot: int = -1;\n  payload: string = \"\";\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            UiModel.DealClass declaration = program.deal().classes().get(entry.getKey());
            for (UiModel.Field field : declaration.fields().values()) out.append("  a").append(entry.getValue()).append("_").append(field.name()).append(": ").append(qualifiedType(field.type(), app)).append(" = ").append(defaultValue(field.type())).append(";\n");
        }
        out.append("}\n\nexport class Transition {\n  tree: core.ViewNode = {};\n  plan: reconcile.Plan = {};\n  lifecycle: store.StoreLifecycle = {};\n  effect: effects.EffectDescriptor = {};\n}\n\n");
        for (UiModel.View view : program.views().values()) generateView(out, view, app);
        generateActionFactories(out);
        generateRouting(out, app);
        out.append("export function main(): null { return null; }\n");
        StringBuilder augmentation = new StringBuilder();
        for (UiModel.View view : program.views().values()) appendForEachAccessors(augmentation, view.nodes(), new java.util.LinkedHashSet<>());
        String flattened = flattenFramework(out.toString());
        return new Output(flattened, augmentation.toString(), Map.copyOf(actions), Map.copyOf(actionIds), Map.copyOf(effectIds));
    }

    private String flattenFramework(String generatedSource) {
        try {
            StringBuilder source = new StringBuilder("import * as app from \"./").append(moduleName(program.dealSource())).append("\";\n\n");
            for (String module : List.of("core", "store", "actions", "effects", "reconcile")) {
                String value = java.nio.file.Files.readString(java.nio.file.Path.of("").toAbsolutePath().resolve("ui/" + module + ".deal"));
                value = value.replace("import * as core from \"./core\";\n\n", "").replace("core.", "").replace("export ", "");
                if (module.equals("store")) value = value.replace("function enqueue(", "function storeEnqueue(").replace("function finish(", "function storeFinish(").replace("function reject(", "function storeReject(").replace("function dispose(", "function storeDispose(");
                if (module.equals("actions")) value = value.replace("function route(", "function actionRoute(");
                source.append(value).append("\n");
            }
            String body = generatedSource.substring(generatedSource.indexOf('\n') + 1).replace("core.", "").replace("store.enqueue", "storeEnqueue").replace("store.finish", "storeFinish").replace("store.reject", "storeReject").replace("store.dispose", "storeDispose").replace("store.", "").replace("actions.route", "actionRoute").replace("actions.", "").replace("effects.", "").replace("reconcile.", "");
            source.append(body);
            return source.toString();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to load DEAL framework modules", failure);
        }
    }

    private void generateView(StringBuilder out, UiModel.View view, String app) {
        out.append("export function view_").append(view.name()).append("(");
        for (int i = 0; i < view.parameters().size(); i++) {
            if (i > 0) out.append(", ");
            UiModel.Parameter parameter = view.parameters().get(i);
            out.append(parameter.name()).append(": ").append(qualifiedType(parameter.type(), app));
        }
        out.append("): core.ViewNode {\n  let nodes: core.ViewNode[] = [];\n");
        emitNodes(out, view.nodes(), "nodes", view.name(), "core.noKey()", app, 1);
        out.append("  return nodes[0];\n}\n\n");
    }

    private void emitNodes(StringBuilder out, List<UiModel.Node> nodes, String target, String identity, String key, String app, int indent) {
        int index = 0;
        for (UiModel.Node node : nodes) {
            String structural = identity + "/" + index++;
            String pad = "  ".repeat(indent);
            if (node instanceof UiModel.Call call) {
                UiModel.View view = program.views().get(simple(call.name()));
                if (view != null && !program.components().containsKey(call.name())) {
                    out.append(pad).append(target).append("[").append(target).append(".length] = view_").append(view.name()).append("(");
                    for (int i = 0; i < view.parameters().size(); i++) {
                        if (i > 0) out.append(", ");
                        out.append(expr(call.arguments().get(view.parameters().get(i).name()), app));
                    }
                    out.append(");\n");
                    continue;
                }
                int local = localId++;
                String props = "props" + local;
                String children = "children" + local;
                out.append(pad).append("let ").append(props).append(": core.Prop[] = [];\n");
                for (Map.Entry<String, UiModel.Expr> argument : call.arguments().entrySet()) {
                    String value;
                    if (argument.getValue() instanceof UiModel.Action action) {
                        int slot = actionSlot++;
                        actions.put(slot, action);
                        value = "core.actionProp(\"" + argument.getKey() + "\", " + slot + ")";
                    } else {
                        UiModel.TypeRef type = expressionType(argument.getValue());
                        String factory = switch (simple(type.name())) {
                            case "int" -> "intProp";
                            case "number" -> "numberProp";
                            case "boolean" -> "booleanProp";
                            case "string" -> "stringProp";
                            default -> "tokenProp";
                        };
                        String expression = argument.getValue() instanceof UiModel.PathExpr path && program.tokens().containsKey(String.join(".", path.parts())) ? "\"" + String.join(".", path.parts()) + "\"" : expr(argument.getValue(), app);
                        value = "core." + factory + "(\"" + argument.getKey() + "\", " + expression + ")";
                    }
                    out.append(pad).append(props).append("[").append(props).append(".length] = ").append(value).append(";\n");
                }
                out.append(pad).append("let ").append(children).append(": core.ViewNode[] = [];\n");
                emitNodes(out, call.children(), children, structural, key, app, indent);
                out.append(pad).append(target).append("[").append(target).append(".length] = core.node(\"").append(call.name()).append("\", \"").append(structural).append("\", ").append(key).append(", ").append(props).append(", ").append(children).append(");\n");
            } else if (node instanceof UiModel.When when) {
                out.append(pad).append("if (").append(expr(when.condition(), app)).append(") {\n");
                emitNodes(out, when.thenNodes(), target, structural + "/then", key, app, indent + 1);
                out.append(pad).append("} else {\n");
                emitNodes(out, when.elseNodes(), target, structural + "/else", key, app, indent + 1);
                out.append(pad).append("}\n");
            } else {
                UiModel.ForEach each = (UiModel.ForEach) node;
                out.append(pad).append("for (let ").append(each.item().name()).append(": ").append(qualifiedType(each.item().type(), app)).append(" of app.uiItems").append(each.span().line()).append("(state)) {\n");
                UiModel.TypeRef keyType = fieldType(each.item().type(), each.key());
                String keyExpr = simple(keyType.name()).equals("int") ? "core.intKey(" + expr(each.key(), app) + ")" : "core.stringKey(" + expr(each.key(), app) + ")";
                emitNodes(out, each.children(), target, structural + "/item", keyExpr, app, indent + 1);
                out.append(pad).append("}\n");
            }
        }
    }

    private void generateActionFactories(StringBuilder out) {
        for (Map.Entry<Integer, UiModel.Action> entry : actions.entrySet()) {
            UiModel.Action action = entry.getValue();
            int id = actionIds.get(simple(action.name()));
            out.append("export function action_").append(entry.getKey()).append("(payload: string): UiAction {\n  return { kind: ").append(id).append(", slot: ").append(entry.getKey()).append(", payload: payload");
            UiModel.DealClass declaration = program.deal().classes().get(simple(action.name()));
            for (Map.Entry<String, UiModel.Expr> field : action.fields().entrySet()) {
                out.append(", a").append(id).append("_").append(field.getKey()).append(": ");
                if (containsPayload(field.getValue())) out.append(payloadExpr(field.getValue()));
                else out.append(expr(field.getValue(), moduleName(program.dealSource())));
            }
            for (String field : declaration.fields().keySet()) if (!action.fields().containsKey(field)) throw new IllegalStateException("Missing action field " + field);
            out.append(" };\n}\n\n");
        }
    }

    private void generateRouting(StringBuilder out, String app) {
        out            .append("export function initialLifecycle(): store.StoreLifecycle { return {}; }\n")
            .append("export function initial(state: app.").append(program.rootStateType()).append(", lifecycle: store.StoreLifecycle): Transition { let tree: core.ViewNode = view_").append(program.title()).append("(state); return { tree: tree, plan: reconcile.plan(null, tree), lifecycle: lifecycle, effect: effects.none() }; }\n")
            .append("export function enqueue(lifecycle: store.StoreLifecycle): store.QueueTransition { return store.enqueue(lifecycle); }\n")

            .append("export function finish(lifecycle: store.StoreLifecycle): store.StoreLifecycle { return store.finish(lifecycle); }\n")
            .append("export function reject(lifecycle: store.StoreLifecycle): store.StoreLifecycle { return store.reject(lifecycle); }\n")
            .append("export function dispose(lifecycle: store.StoreLifecycle): store.StoreLifecycle { return store.dispose(lifecycle); }\n\n")
            .append("export function route(action: UiAction): actions.Route {\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            int effect = effectIds.getOrDefault(entry.getKey(), -1);
            int completion = effect < 0 ? -1 : actionIds.get(program.effects().get(entry.getKey()).returnType());
            out.append("  if (action.kind === ").append(entry.getValue()).append(") { return actions.route(").append(entry.getValue()).append(", ").append(entry.getValue()).append(", ").append(effect).append(", ").append(completion).append("); }\n");
        }
        out.append("  return actions.noRoute();\n}\n\n")
            .append("export function nextState(state: app.").append(program.rootStateType()).append(", action: UiAction): app.").append(program.rootStateType()).append(" {\n")
            .append("  let routeValue: actions.Route = route(action);\n  let candidate: app.").append(program.rootStateType()).append(" = state;\n");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            UiModel.Handler handler = program.updates().get(entry.getKey());
            out.append(first ? "  if" : "  else if").append(" (routeValue.updateId === ").append(entry.getValue()).append(") { candidate = app.").append(handler.name()).append("(state, ").append(actionLiteral(entry.getKey(), entry.getValue(), app)).append("); }\n");
            first = false;
        }
        out.append("  else { throw { code: \"UI3001\", message: \"Unknown closed action\" }; }\n")
            .append("  return candidate;\n}\n\n")
            .append("export function transition(state: app.").append(program.rootStateType()).append(", previous: core.ViewNode | null, lifecycle: store.StoreLifecycle, action: UiAction): Transition {\n")
            .append("  let candidate: app.").append(program.rootStateType()).append(" = nextState(state, action);\n")
            .append("  let routeValue: actions.Route = route(action);\n")
            .append("  let nextLifecycle: store.StoreLifecycle = store.commit(lifecycle);\n")
            .append("  let tree: core.ViewNode = view_").append(program.title()).append("(candidate);\n")
            .append("  let planValue: reconcile.Plan = reconcile.plan(previous, tree);\n")
            .append("  let effect: effects.EffectDescriptor = effects.none();\n")
            .append("  if (actions.hasEffect(routeValue)) { effect = effects.start(routeValue.effectId, action.slot, nextLifecycle.revision); }\n")
            .append("  return { tree: tree, plan: planValue, lifecycle: nextLifecycle, effect: effect };\n}\n\n");
    }

    private void appendForEachAccessors(StringBuilder out, List<UiModel.Node> nodes, java.util.Set<Integer> lines) {
        for (UiModel.Node node : nodes) {
            if (node instanceof UiModel.ForEach each) {
                if (lines.add(each.span().line())) out.append("\nexport function uiItems").append(each.span().line()).append("(state: ").append(program.rootStateType()).append("): ").append(simple(each.item().type().name())).append("[] { return ").append(String.join(".", each.source().parts())).append("; }\n");
                appendForEachAccessors(out, each.children(), lines);
            } else if (node instanceof UiModel.Call call) appendForEachAccessors(out, call.children(), lines);
            else if (node instanceof UiModel.When when) { appendForEachAccessors(out, when.thenNodes(), lines); appendForEachAccessors(out, when.elseNodes(), lines); }
        }
    }

    private String actionLiteral(String name, int id, String app) {
        UiModel.DealClass declaration = program.deal().classes().get(name);
        List<String> fields = new ArrayList<>();
        for (UiModel.Field field : declaration.fields().values()) fields.add(field.name() + ": action.a" + id + "_" + field.name());
        return "{ " + String.join(", ", fields) + " }";
    }

    private UiModel.TypeRef expressionType(UiModel.Expr expression) {
        if (expression instanceof UiModel.Literal literal) return new UiModel.TypeRef(literal.type(), false, false);
        if (expression instanceof UiModel.PathExpr path) {
            UiModel.Token token = program.tokens().get(String.join(".", path.parts()));
            if (token != null) return token.type();
            if (path.parts().get(0).equals("state")) return pathType(new UiModel.TypeRef(program.rootStateType(), false, false), path.parts().subList(1, path.parts().size()));
        }
        return new UiModel.TypeRef("string", false, false);
    }

    private UiModel.TypeRef fieldType(UiModel.TypeRef root, UiModel.PathExpr path) { return pathType(root, path.parts().subList(1, path.parts().size())); }
    private UiModel.TypeRef pathType(UiModel.TypeRef root, List<String> fields) {
        UiModel.TypeRef current = root;
        for (String field : fields) current = program.deal().classes().get(simple(current.name())).fields().get(field).type();
        return current;
    }

    private String expr(UiModel.Expr expression, String app) {
        if (expression instanceof UiModel.Literal literal) {
            if (literal.value() == null) return "null";
            if (literal.value() instanceof String string) return quote(string);
            return String.valueOf(literal.value());
        }
        if (expression instanceof UiModel.PathExpr path) return String.join(".", path.parts());
        if (expression instanceof UiModel.Has has) return "has(" + expr(has.path(), app) + ")";
        if (expression instanceof UiModel.Unary unary) return unary.operator() + "(" + expr(unary.operand(), app) + ")";
        if (expression instanceof UiModel.Binary binary) return "(" + expr(binary.left(), app) + " " + binary.operator() + " " + expr(binary.right(), app) + ")";
        throw new IllegalStateException("Action expression is not a value expression");
    }

    private String payloadExpr(UiModel.Expr expression) {
        if (expression instanceof UiModel.PathExpr path && path.parts().get(0).equals("payload")) return "payload";
        if (expression instanceof UiModel.Unary unary) return unary.operator() + "(" + payloadExpr(unary.operand()) + ")";
        if (expression instanceof UiModel.Binary binary) return "(" + payloadExpr(binary.left()) + " " + binary.operator() + " " + payloadExpr(binary.right()) + ")";
        return expr(expression, moduleName(program.dealSource()));
    }

    private boolean containsPayload(UiModel.Expr expression) {
        if (expression instanceof UiModel.PathExpr path) return path.parts().get(0).equals("payload");
        if (expression instanceof UiModel.Unary unary) return containsPayload(unary.operand());
        if (expression instanceof UiModel.Binary binary) return containsPayload(binary.left()) || containsPayload(binary.right());
        if (expression instanceof UiModel.Has has) return has.path().parts().get(0).equals("payload");
        return false;
    }

    private String qualifiedType(UiModel.TypeRef type, String app) {
        String name = type.name();
        if (!name.contains(".") && program.deal().classes().containsKey(name)) name = "app." + name;
        return name + (type.array() ? "[]" : "") + (type.optional() ? " | null" : "");
    }
    private String dealType(UiModel.TypeRef type) { return type.name() + (type.array() ? "[]" : "") + (type.optional() ? " | null" : ""); }
    private String defaultValue(UiModel.TypeRef type) {
        if (type.optional()) return "null";
        if (type.array()) return "[]";
        return switch (simple(type.name())) { case "string" -> "\"\""; case "int" -> "0"; case "number" -> "0.0"; case "boolean" -> "false"; default -> "{}"; };
    }
    private String moduleName(java.nio.file.Path path) { String name = path.getFileName().toString(); return name.substring(0, name.length() - ".deal".length()); }
    private String simple(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(dot + 1); }
    private String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
}
