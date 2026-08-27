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
            .append("export class UiAction {\n  slot: int = -1;\n  payload: string = \"\";\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) out.append("  nominal").append(entry.getValue()).append("?: app.").append(entry.getKey()).append(";\n");
        out.append("}\n\nexport class UiStore {\n  lifecycle: store.StoreLifecycle = {};\n  queue: UiAction[] = [];\n}\n\nexport class EnqueueResult {\n  store: UiStore = {};\n  accepted: boolean = false;\n  startDrain: boolean = false;\n}\n\nexport class DequeueResult {\n  store: UiStore = {};\n  action: UiAction = {};\n  present: boolean = false;\n}\n\nexport class Transition {\n  tree: core.ViewNode = {};\n  plan: reconcile.Plan = {};\n  store: UiStore = {};\n  effect: effects.EffectDescriptor = {};\n}\n\n");
        for (UiModel.View view : program.views().values()) generateView(out, view, app);
        generateActionFactories(out);
        generateRouting(out, app);
        out.append("export function main(): null { return null; }\n");
        StringBuilder augmentation = new StringBuilder();
        for (UiModel.View view : program.views().values()) {
            Map<String, UiModel.TypeRef> scope = new LinkedHashMap<>();
            for (UiModel.Parameter parameter : view.parameters()) scope.put(parameter.name(), new UiModel.TypeRef(simple(parameter.type().name()), parameter.type().optional(), parameter.type().array()));
            appendForEachAccessors(augmentation, view.nodes(), scope, new java.util.LinkedHashSet<>());
        }
        String flattened = flattenFramework(out.toString());
        return new Output(flattened, augmentation.toString(), Map.copyOf(actions), Map.copyOf(actionIds), Map.copyOf(effectIds));
    }

    private String flattenFramework(String generatedSource) {
        try {
            StringBuilder source = new StringBuilder("import * as app from \"./").append(moduleName(program.dealSource())).append("\";\n\n");
            for (String module : List.of("core", "store", "actions", "effects", "reconcile")) {
                String value = java.nio.file.Files.readString(java.nio.file.Path.of("").toAbsolutePath().resolve("ui/" + module + ".deal"));
                value = value.replace("import * as core from \"./core\";\n\n", "").replace("core.", "").replace("export ", "");
                if (module.equals("store")) value = value.replace("function finish(", "function lifecycleFinish(").replace("function reject(", "function lifecycleReject(").replace("function dispose(", "function lifecycleDispose(");
                if (module.equals("actions")) value = value.replace("function route(", "function actionRoute(");
                source.append(value).append("\n");
            }
            String body = generatedSource.substring(generatedSource.indexOf('\n') + 1).replace("core.", "").replace("store.finish", "lifecycleFinish").replace("store.reject", "lifecycleReject").replace("store.dispose", "lifecycleDispose").replace("store.", "").replace("actions.route", "actionRoute").replace("actions.", "").replace("effects.", "").replace("reconcile.", "");
            source.append(body);
            return source.toString();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to load DEAL framework modules", failure);
        }
    }

    private void generateView(StringBuilder out, UiModel.View view, String app) {
        out.append("export function view_").append(view.name()).append("(identityPrefix: string");
        for (int i = 0; i < view.parameters().size(); i++) {
            out.append(", ");
            UiModel.Parameter parameter = view.parameters().get(i);
            out.append(parameter.name()).append(": ").append(qualifiedType(parameter.type(), app));
        }
        out.append("): core.ViewNode {\n  let nodes: core.ViewNode[] = [];\n");
        emitNodes(out, view.nodes(), "nodes", "\" + identityPrefix + \"", "core.noKey()", app, 1);
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
                    out.append(pad).append(target).append("[").append(target).append(".length] = view_").append(view.name()).append("(\"").append(structural).append("\"");
                    for (int i = 0; i < view.parameters().size(); i++) {
                        out.append(", ");
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
                out.append(pad).append("for (let ").append(each.item().name()).append(": ").append(qualifiedType(each.item().type(), app)).append(" of app.uiItems").append(each.span().line()).append("(").append(each.source().parts().get(0)).append(")) {\n");
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
            out.append("export function action_").append(entry.getKey()).append("(payload: string): UiAction {\n  return { slot: ").append(entry.getKey()).append(", payload: payload, nominal").append(id).append(": {");
            UiModel.DealClass declaration = program.deal().classes().get(simple(action.name()));
            boolean first = true;
            for (Map.Entry<String, UiModel.Expr> field : action.fields().entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append(field.getKey()).append(": ");
                if (containsPayload(field.getValue())) out.append(payloadExpr(field.getValue()));
                else out.append(expr(field.getValue(), moduleName(program.dealSource())));
            }
            for (String field : declaration.fields().keySet()) if (!action.fields().containsKey(field)) throw new IllegalStateException("Missing action field " + field);
            out.append("} };\n}\n\n");
        }
    }

    private void generateRouting(StringBuilder out, String app) {
        out            .append("export function initialStore(): UiStore { return {}; }\n")
            .append("export function initial(state: app.").append(program.rootStateType()).append(", current: UiStore): Transition { let tree: core.ViewNode = view_").append(program.title()).append("(\"").append(program.title()).append("\", state); return { tree: tree, plan: reconcile.plan(null, tree), store: current, effect: effects.none() }; }\n")
            .append("export function enqueue(current: UiStore, action: UiAction): EnqueueResult { let decision: store.QueueDecision = store.enqueueDecision(current.lifecycle); if (!decision.accepted) { return { store: current }; } let queue: UiAction[] = []; for (let queued: UiAction of current.queue) { queue[queue.length] = queued; } queue[queue.length] = action; return { store: { lifecycle: store.beginDrain(current.lifecycle), queue: queue }, accepted: true, startDrain: decision.startDrain }; }\n")
            .append("export function dequeue(current: UiStore): DequeueResult { if (current.queue.length === 0) { return { store: current }; } let action: UiAction = current.queue[0]; let queue: UiAction[] = []; for (let i: int = 1; i < current.queue.length; i = i + 1) { queue[queue.length] = current.queue[i]; } return { store: { lifecycle: current.lifecycle, queue: queue }, action: action, present: true }; }\n")
            .append("export function complete(current: UiStore, action: UiAction): EnqueueResult { let descriptor: effects.CompletionDescriptor = effects.completion(current.lifecycle.disposed, action.slot); if (!descriptor.accepted) { return { store: current }; } return enqueue(current, action); }\n")
            .append("export function finish(current: UiStore): UiStore { return { lifecycle: store.finish(current.lifecycle), queue: current.queue }; }\n")
            .append("export function reject(current: UiStore): UiStore { return { lifecycle: store.reject(current.lifecycle), queue: current.queue }; }\n")
            .append("export function dispose(current: UiStore): UiStore { let queue: UiAction[] = []; return { lifecycle: store.dispose(current.lifecycle), queue: queue }; }\n\n")
            .append("export function route(action: UiAction): actions.Route {\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            int effect = effectIds.getOrDefault(entry.getKey(), -1);
            int completion = effect < 0 ? -1 : actionIds.get(program.effects().get(entry.getKey()).returnType());
            out.append("  let nominal").append(entry.getValue()).append(": app.").append(entry.getKey()).append(" | null = action.nominal").append(entry.getValue()).append("; if (nominal").append(entry.getValue()).append(" !== null) { return actions.route(").append(entry.getValue()).append(", ").append(entry.getValue()).append(", ").append(effect).append(", ").append(completion).append("); }\n");
        }
        out.append("  return actions.noRoute();\n}\n\n")
            .append("export function nextState(state: app.").append(program.rootStateType()).append(", action: UiAction): app.").append(program.rootStateType()).append(" {\n")
            .append("  let routeValue: actions.Route = route(action);\n  let candidate: app.").append(program.rootStateType()).append(" = state;\n");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            UiModel.Handler handler = program.updates().get(entry.getKey());
            out.append(first ? "  if" : "  else if").append(" (routeValue.updateId === ").append(entry.getValue()).append(") { let value: app.").append(entry.getKey()).append(" | null = action.nominal").append(entry.getValue()).append("; if (value === null) { throw { code: \"UI3002\", message: \"Missing nominal action\" }; } else { candidate = app.").append(handler.name()).append("(state, value); } }\n");
            first = false;
        }
        out.append("  else { throw { code: \"UI3001\", message: \"Unknown closed action\" }; }\n")
            .append("  return candidate;\n}\n\n")
            .append("export function transitionFromCandidate(candidate: app.").append(program.rootStateType()).append(", previous: core.ViewNode | null, current: UiStore, action: UiAction): Transition {\n")
            .append("  let routeValue: actions.Route = route(action);\n")
            .append("  let nextStore: UiStore = { lifecycle: store.commit(current.lifecycle), queue: current.queue };\n")
            .append("  let tree: core.ViewNode = view_").append(program.title()).append("(\"").append(program.title()).append("\", candidate);\n")
            .append("  let planValue: reconcile.Plan = reconcile.plan(previous, tree);\n")
            .append("  let effect: effects.EffectDescriptor = effects.none();\n")
            .append("  if (actions.hasEffect(routeValue)) { effect = effects.start(routeValue.effectId, action.slot, nextStore.lifecycle.revision); }\n")
            .append("  return { tree: tree, plan: planValue, store: nextStore, effect: effect };\n}\n\n");
    }

    private void appendForEachAccessors(StringBuilder out, List<UiModel.Node> nodes, Map<String, UiModel.TypeRef> scope, java.util.Set<Integer> lines) {
        for (UiModel.Node node : nodes) {
            if (node instanceof UiModel.ForEach each) {
                String root = each.source().parts().get(0);
                UiModel.TypeRef rootType = scope.get(root);
                if (rootType == null) throw new IllegalStateException("Unknown iteration scope " + root);
                if (lines.add(each.span().line())) out.append("\nexport function uiItems").append(each.span().line()).append("(").append(root).append(": ").append(dealType(rootType)).append("): ").append(simple(each.item().type().name())).append("[] { return ").append(String.join(".", each.source().parts())).append("; }\n");
                Map<String, UiModel.TypeRef> nested = new LinkedHashMap<>(scope);
                nested.put(each.item().name(), each.item().type());
                appendForEachAccessors(out, each.children(), nested, lines);
            } else if (node instanceof UiModel.Call call) appendForEachAccessors(out, call.children(), scope, lines);
            else if (node instanceof UiModel.When when) { appendForEachAccessors(out, when.thenNodes(), scope, lines); appendForEachAccessors(out, when.elseNodes(), scope, lines); }
        }
    }

    private String actionLiteral(String name, int id, String app) {
        UiModel.DealClass declaration = program.deal().classes().get(name);
        List<String> fields = new ArrayList<>();
        for (UiModel.Field field : declaration.fields().values()) fields.add(field.name() + ": value." + field.name());
        return "action.nominal" + id;
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
