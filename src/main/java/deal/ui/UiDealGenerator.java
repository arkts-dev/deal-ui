package deal.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UiDealGenerator {
    record GeneratedAction(UiModel.Action action, UiModel.TypeRef payloadType, Map<String, UiModel.TypeRef> captures) {}
    record Output(String source, String appAugmentation, Map<Integer, GeneratedAction> actions, Map<String, Integer> actionIds, Map<String, Integer> effectIds) {}

    private final UiModel.CheckedProgram program;
    private final java.nio.file.Path frameworkRoot;
    private final Map<String, String> nominalImports = new LinkedHashMap<>();
    private String schemaDigest;
    private final Map<String, UiModel.DealClass> nominalClasses = new LinkedHashMap<>();
    private final Map<Integer, GeneratedAction> actions = new LinkedHashMap<>();
    private final Map<String, Integer> actionIds = new LinkedHashMap<>();
    private final Map<String, Integer> effectIds = new LinkedHashMap<>();
    private int actionSlot;
    private int localId;
    private boolean splitViews;
    private final StringBuilder viewHelpers = new StringBuilder();
    private final Map<UiModel.Call, List<UiModel.Node>> forwardedViews = new java.util.IdentityHashMap<>();
    private final Map<UiModel.ForEach, Integer> iterationIds = new java.util.IdentityHashMap<>();

    UiDealGenerator(UiModel.CheckedProgram program, java.nio.file.Path frameworkRoot) {
        this.program = program;
        this.frameworkRoot = frameworkRoot;
        schemaDigest = digest(stateSchema(program));
        for (UiModel.View view : program.views().values()) if (!actionView(view)) { expandForwarding(view.nodes()); indexIterations(view.nodes()); }
        int id = 0;
        for (String action : program.updates().keySet()) actionIds.put(action, id++);
        id = 0;
        for (String action : program.effects().keySet()) effectIds.put(action, id++);
    }

    UiDealGenerator(UiModel.CheckedProgram program, java.nio.file.Path frameworkRoot,
                    Map<String, UiModel.PackModule> packs, Map<String, String> declarations) {
        this(program, frameworkRoot);
        schemaDigest = digest(stateSchema(program) + new java.util.TreeMap<>(declarations) + new java.util.TreeMap<>(program.metadata().packDigests()));
        for (var pack : packs.values()) for (var imported : pack.imports()) {
            String declaration = declarations.get(imported.specifier());
            if (declaration == null && (imported.specifier().equals("./" + moduleName(program.dealSource())) || imported.specifier().equals("./" + moduleName(program.dealSource()) + ".deal"))) continue;
            if (declaration == null) throw new UiDiagnostic("UI2053", "Missing nominal declaration: " + imported.specifier(), program.viewSource(), 1, 1);
            String previous = nominalImports.putIfAbsent(imported.alias(), imported.specifier());
            if (previous != null && !previous.equals(imported.specifier())) throw new IllegalArgumentException("Conflicting nominal alias");
            var parsed = new UiChecker().parseDeal(java.nio.file.Path.of("external.d.deal"), declaration);
            for (var clazz : parsed.classes().values()) nominalClasses.put(imported.alias() + "." + clazz.name(), clazz);
        }
    }

    private static String stateSchema(UiModel.CheckedProgram program) {
        StringBuilder schema=new StringBuilder(program.rootStateType());
        program.deal().classes().forEach((name,clazz)->{
            schema.append('|').append(name);
            clazz.fields().forEach((field,value)->schema.append('|').append(field).append(':').append(value.type()));
        });
        return schema.toString();
    }

    private static String digest(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    Output generate() { return generate(false); }

    Output generateSession() { return generate(true); }

    Output generateJvm() { splitViews = true; return generate(true); }

    private Output generate(boolean sessionExports) {
        String app = moduleName(program.dealSource());
        validateEffectPolicyTypes();
        StringBuilder out = new StringBuilder();
        out.append("import * as app from \"./").append(app).append("\";\n\n")
            .append("export class UiAction {\n  slot: number;\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) out.append("  nominal").append(entry.getValue()).append("?: app.").append(entry.getKey()).append(";\n");
        out.append("}\n\nexport class UiStore {\n  lifecycle: store.StoreLifecycle;\n  queue: UiAction[];\n}\n\nexport class EnqueueResult {\n  store: UiStore;\n  accepted: boolean;\n  startDrain: boolean;\n}\n\nexport class DequeueResult {\n  store: UiStore;\n  action: UiAction;\n  present: boolean;\n}\n\nexport class Transition {\n  tree: core.ViewNode;\n  plan: reconcile.Plan;\n  store: UiStore;\n  effect: effects.EffectDescriptor;\n}\n\n");
        for (var entry : program.packClasses().entrySet()) {
            if (entry.getValue().fields().stream().anyMatch(field -> field.type().name().equals("Action"))) continue;
            out.append("class ").append(packTypeName(entry.getKey())).append(" {\n");
            for (var field : entry.getValue().fields()) out.append("  ").append(field.name()).append(field.optional() ? "?: " : ": ")
                    .append(qualifiedType(field.type(), app)).append(";\n");
            out.append("}\n");
        }
        for (UiModel.View view : program.views().values()) if (!actionView(view)) generateView(out, view, app);
        out.append(viewHelpers);
        generateActionFactories(out);
        generatePayloadTypes(out);
        generateRouting(out, app);
        if (sessionExports) {
        generateCheckActions(out);
        out.append("export function bridgeSchemaDigest(): string { return ").append(quote(schemaDigest)).append("; }\n");
        out.append("export function bridgePayloadNumber(text: string): number { return payloadNumber(text); }\n");
        out.append("export function bridgeFrameVisibility(visible: boolean): FrameClock { return frameVisibility(visible); }\n")
            .append("export function bridgeFrameSample(current: FrameClock, now: number): FrameClock { return frameSample(current,now); }\n");
        out.append("export function bridgeSessionStatus(): SessionStatus { return {inFlight: 0, version: 1, fault: \"\"}; }\n")
            .append("export function bridgeSessionCommitted(current: SessionStatus): SessionStatus { return sessionCommitted(current); }\n")
            .append("export function bridgeSessionStarted(current: SessionStatus): SessionStatus { return sessionStarted(current); }\n")
            .append("export function bridgeSessionExited(current: SessionStatus): SessionStatus { return sessionExited(current); }\n")
            .append("export function bridgeSessionFailed(current: SessionStatus, message: string, effect: boolean): SessionStatus { return sessionFailed(current, message, effect); }\n")
            .append("export function bridgeReplacementReady(current: SessionStatus): boolean { return sessionReplacementReady(current); }\n");
        out.append("export function bridgeInitialState(): app.").append(program.rootStateType()).append(" { return app.initialState(); }\n")
            .append("export function bridgeProp(name: string, kind: string, text: string, numeric: number, flag: boolean, slot: number): Prop { return { name: name, kind: kind, stringValue: text, numberValue: numeric, booleanValue: flag, actionSlot: slot, actionBindings: {} }; }\n")
            .append("export function bridgeNode(component: string, structural: string, keyKind: string, keyNumber: number, keyString: string, props: Prop[], children: ViewNode[]): ViewNode { return { component: component, structural: structural, keyKind: keyKind, keyNumber: keyNumber, keyString: keyString, props: props, children: children }; }\n");
        for (var entry : effectIds.entrySet()) {
            String name = entry.getKey();
            UiModel.Handler handler = program.effects().get(name);
            boolean async = program.deal().functions().get(handler.name()).async();
            out.append("export ").append(async ? "async " : "").append("function bridgeEffect").append(entry.getValue())
                .append("(state: app.").append(program.rootStateType()).append(", action: UiAction): ").append(async ? "Promise<UiAction>" : "UiAction").append(" { let value: app.")
                .append(name).append(" | undefined = action.nominal").append(actionIds.get(name))
                .append("; if (value === undefined) { throw { name: \"UI3002\", message: \"Missing action\" }; } else { let result: app.")
                .append(handler.returnType()).append(" = ").append(async ? "await " : "").append("app.")
                .append(handler.name()).append("(state, value); return { slot: -1, nominal").append(actionIds.get(handler.returnType()))
                .append(": result }; } }\n");
        }
        for (var entry : effectIds.entrySet()) {
            UiModel.Handler handler = program.effectFailures().get(entry.getKey());
            if (handler == null) continue;
            out.append("export function bridgeEffectFailure").append(entry.getValue())
                .append("(state: app.").append(program.rootStateType()).append(", action: UiAction, message: string): UiAction { let value: app.")
                .append(entry.getKey()).append(" | undefined = action.nominal").append(actionIds.get(entry.getKey()))
                .append("; if (value === undefined) { throw { name: \"UI3002\", message: \"Missing action\" }; } else { let result: app.")
                .append(handler.returnType()).append(" = app.").append(handler.name())
                .append("(state, value, message); return { slot: -1, nominal").append(actionIds.get(handler.returnType()))
                .append(": result }; } }\n");
        }
        }
        out.append("export function main(): void { return; }\n");
        StringBuilder augmentation = new StringBuilder();
        for (UiModel.View view : program.views().values()) {
            Map<String, UiModel.TypeRef> scope = new LinkedHashMap<>();
            for (UiModel.Parameter parameter : view.parameters()) scope.put(parameter.name(), parameter.type().withName(simple(parameter.type().name())));
            if (!actionView(view)) appendForEachAccessors(augmentation, view.nodes(), scope);
        }
        String flattened = flattenFramework(out.toString());
        return new Output(flattened, augmentation.toString(), Map.copyOf(actions), Map.copyOf(actionIds), Map.copyOf(effectIds));
    }

    private String flattenFramework(String generatedSource) {
        try {
            StringBuilder source = new StringBuilder("import * as app from \"./").append(moduleName(program.dealSource())).append("\";\n\n");
            nominalImports.forEach((alias, specifier) -> source.append("import * as ").append(alias).append(" from ").append(quote(specifier)).append(";\n"));
            for (String module : List.of("core", "store", "actions", "effects", "interaction", "reconcile", "session")) {
                String value = java.nio.file.Files.readString(frameworkRoot.resolve("ui/" + module + ".deal"));
                value = rewriteFrameworkNames(value.replace("import * as core from \"./core\";\n\n", ""), true);
                if (module.equals("store")) value = value.replace("function finish(", "function lifecycleFinish(").replace("function reject(", "function lifecycleReject(").replace("function dispose(", "function lifecycleDispose(");
                if (module.equals("actions")) value = value.replace("function route(", "function actionRoute(");
                source.append(value).append("\n");
            }
            String body = rewriteFrameworkNames(generatedSource.substring(generatedSource.indexOf('\n') + 1), false);
            source.append(body);
            return source.toString();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to load DEAL framework modules", failure);
        }
    }

    /** Rewrite only lexical names, never text inside strings, templates or comments. */
    private static String rewriteFrameworkNames(String source, boolean framework) {
        var tokens = new deal.lexer.Lexer(source, "generated.deal").tokenize().tokens();
        StringBuilder result = new StringBuilder();
        int offset = 0;
        for (int i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (!token.hasScalarOffsets()) continue;
            int end = token.endScalarOffset();
            String replacement = null;
            if (framework && token.type() == deal.ast.TokenType.EXPORT) replacement = "";
            if (token.type() == deal.ast.TokenType.IDENTIFIER && i + 2 < tokens.size()
                    && tokens.get(i + 1).lexeme().equals(".")
                    && tokens.get(i + 2).type() == deal.ast.TokenType.IDENTIFIER
                    && (token.lexeme().equals("core") || !framework && List.of("store", "actions", "effects", "reconcile").contains(token.lexeme()))) {
                String member = tokens.get(i + 2).lexeme();
                replacement = switch (token.lexeme() + "." + member) {
                    case "store.finish" -> "lifecycleFinish";
                    case "store.reject" -> "lifecycleReject";
                    case "store.dispose" -> "lifecycleDispose";
                    case "actions.route" -> "actionRoute";
                    default -> member;
                };
                end = tokens.get(i + 2).endScalarOffset();
                i += 2;
            }
            if (replacement != null) {
                result.append(source, offset, token.startScalarOffset()).append(replacement);
                offset = end;
            }
        }
        return result.append(source, offset, source.length()).toString();
    }

    private boolean actionView(UiModel.View view) { return view.parameters().stream().anyMatch(parameter -> parameter.type().name().equals("Action")); }

    private void generateView(StringBuilder out, UiModel.View view, String app) {
        out.append("export function view_").append(view.name()).append("(identityPrefix: string");
        for (int i = 0; i < view.parameters().size(); i++) {
            out.append(", ");
            UiModel.Parameter parameter = view.parameters().get(i);
            out.append(parameter.name()).append(": ").append(qualifiedType(parameter.type(), app));
        }
        out.append("): core.ViewNode {\n  let nodes: core.ViewNode[] = [];\n");
        Map<String, UiModel.TypeRef> scope = new LinkedHashMap<>();
        for (UiModel.Parameter parameter : view.parameters()) scope.put(parameter.name(), parameter.type());
        emitNodes(out, view.nodes(), "nodes", "\" + identityPrefix + \"", "core.noKey()", app, 1, scope);
        out.append("  return nodes[0];\n}\n\n");
    }

    private void emitNodes(StringBuilder out, List<UiModel.Node> nodes, String target, String identity, String key, String app, int indent, Map<String, UiModel.TypeRef> scope) {
        int index = 0;
        for (UiModel.Node node : nodes) {
            String structural = identity + "/" + index++;
            String pad = "  ".repeat(indent);
            if (node instanceof UiModel.Call call) {
                UiModel.View view = program.views().get(call.name());
                if (view != null && !program.components().containsKey(call.name())) {
                    if (actionView(view)) {
                        var bound = forwardedViews.get(call);
                        emitNodes(out, bound, target, structural + "/" + view.name(), key, app, indent, scope);
                        continue;
                    }
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
                        UiModel.Event event = event(program.components().get(call.name()), argument.getKey());
                        Map<String, UiModel.TypeRef> captures = new LinkedHashMap<>();
                        for (var binding : scope.entrySet()) {
                            if (action.fields().values().stream().anyMatch(e -> references(e, binding.getKey()))) captures.put(binding.getKey(), binding.getValue());
                        }
                        actions.put(slot, new GeneratedAction(action, event.payload(), Map.copyOf(captures)));
                        String data = captures.keySet().stream().map(name -> name + ": " + name).collect(java.util.stream.Collectors.joining(", "));
                        value = "core.boundActionProp(\"" + argument.getKey() + "\", " + slot + ", {" + data + "})";
                    } else {
                        UiModel.TypeRef type = expressionType(argument.getValue(), scope);
                        if (type.undefined() || type.name().equals("undefined") || type.undefinedElements().contains(true))
                            throw new UiDiagnostic("UI2053", "Renderer props cannot encode undefined; prepare an explicit supported value in DEAL",
                                    argument.getValue().span().file(), argument.getValue().span().line(), argument.getValue().span().column());
                        boolean token = argument.getValue() instanceof UiModel.PathExpr path && program.tokens().containsKey(String.join(".", path.parts()));
                        boolean structured = !token && (type.nullish() || type.array() || !List.of("number", "boolean", "string").contains(simple(type.name())));
                        String factory = structured ? "jsonProp" : switch (simple(type.name())) {
                            case "number" -> "numberProp";
                            case "boolean" -> "booleanProp";
                            case "string" -> "stringProp";
                            default -> "tokenProp";
                        };
                        String expression = argument.getValue() instanceof UiModel.PathExpr path && program.tokens().containsKey(String.join(".", path.parts())) ? "\"" + String.join(".", path.parts()) + "\"" : expr(argument.getValue(), app);
                        if (structured) {
                            String helper = "viewProp" + local + "_" + argument.getKey();
                            viewHelpers.append("function ").append(helper).append("(value: ").append(qualifiedType(type, app)).append("): core.Prop {\n");
                            if (type.name().equals("unknown"))
                                viewHelpers.append("  if (value === undefined) { throw {name: \"UI3005\", message: \"Renderer props cannot encode undefined\"}; }\n");
                            viewHelpers.append("  return core.jsonProp(").append(quote(argument.getKey())).append(", JSON.stringify(value) as string);\n}\n");
                            value = helper + "(" + expression + ")";
                        } else value = "core." + factory + "(\"" + argument.getKey() + "\", " + expression + ")";
                    }
                    out.append(pad).append(props).append("[").append(props).append(".length] = ").append(value).append(";\n");
                }
                if (splitViews && !call.children().isEmpty()) {
                    String helper = "viewChildren" + local;
                    out.append(pad).append("let ").append(children).append(": core.ViewNode[] = ").append(helper)
                        .append("(\"").append(structural).append("\", ").append(key);
                    StringBuilder body = new StringBuilder("function ").append(helper).append("(identityPrefix: string, parentKey: core.Key");
                    for (var binding : scope.entrySet()) {
                        out.append(", ").append(binding.getKey());
                        body.append(", ").append(binding.getKey()).append(": ").append(qualifiedType(binding.getValue(), app));
                    }
                    out.append(");\n");
                    body.append("): core.ViewNode[] {\n  let nodes: core.ViewNode[] = [];\n");
                    emitNodes(body, call.children(), "nodes", "\" + identityPrefix + \"", "parentKey", app, 1, scope);
                    body.append("  return nodes;\n}\n\n");
                    viewHelpers.append(body);
                } else {
                    out.append(pad).append("let ").append(children).append(": core.ViewNode[] = [];\n");
                    emitNodes(out, call.children(), children, structural, key, app, indent, scope);
                }
                out.append(pad).append(target).append("[").append(target).append(".length] = core.node(\"").append(call.name()).append("\", \"").append(structural).append("\", ").append(key).append(", ").append(props).append(", ").append(children).append(");\n");
            } else if (node instanceof UiModel.When when) {
                out.append(pad).append("if (").append(expr(when.condition(), app)).append(") {\n");
                emitNodes(out, when.thenNodes(), target, structural + "/then", key, app, indent + 1, scope);
                out.append(pad).append("} else {\n");
                emitNodes(out, when.elseNodes(), target, structural + "/else", key, app, indent + 1, scope);
                out.append(pad).append("}\n");
            } else {
                UiModel.ForEach each = (UiModel.ForEach) node;
                out.append(pad).append("for (let ").append(each.item().name()).append(" of app.uiItems").append(iterationIds.get(each)).append("(").append(each.source().parts().get(0)).append(")) {\n");
                UiModel.TypeRef keyType = fieldType(each.item().type(), each.key());
                String keyExpr = simple(keyType.name()).equals("number") ? "core.numberKey(" + expr(each.key(), app) + ")" : "core.stringKey(" + expr(each.key(), app) + ")";
                Map<String, UiModel.TypeRef> nested = new LinkedHashMap<>(scope);
                nested.put(each.item().name(), each.item().type());
                emitNodes(out, each.children(), target, structural + "/item", keyExpr, app, indent + 1, nested);
                out.append(pad).append("}\n");
            }
        }
    }

    /** Hosts dispatch typed payloads; the declared type comes from the checked signature. */
    private void generatePayloadTypes(StringBuilder out) {
        for (Map.Entry<Integer, GeneratedAction> entry : actions.entrySet()) {
            UiModel.TypeRef payloadType = entry.getValue().payloadType();
            String kind = payloadType == null ? "none" : payloadType.name();
            if (payloadType != null && (payloadType.array() || payloadType.nullish() || !List.of("number", "boolean", "string").contains(payloadType.name()))) {
                if (payloadType.undefined() || payloadType.name().equals("undefined") || payloadType.undefinedElements().contains(true))
                    throw new UiDiagnostic("UI2053", "JSON event ingress cannot represent undefined", program.viewSource(), 1, 1);
                out.append("export function decodePayload_").append(entry.getKey()).append("(text: string): ").append(qualifiedType(payloadType, moduleName(program.dealSource()))).append(" { return JSON.parse(text); }\n");
                kind = "json";
            }
            out.append("export function payloadType_").append(entry.getKey()).append("(): string { return \"").append(kind).append("\"; }\n");
        }
        out.append("\n");
    }

    /** Compiler-issued handler inventory and nominal synthetic actions, including effect completions. */
    private void generateCheckActions(StringBuilder out) {
        out.append("export function checkHandlerCount(): number { return ").append(actionIds.size()).append("; }\n");
        for (var entry : actionIds.entrySet()) {
            String type = entry.getKey();
            out.append("export function checkAction_").append(entry.getValue()).append("(): UiAction { return {slot: -1, nominal")
                .append(entry.getValue()).append(": ").append(checkSample(new UiModel.TypeRef(type, false, false), new java.util.LinkedHashSet<>())).append("}; }\n");
        }
    }

    private String checkSample(UiModel.TypeRef type, java.util.Set<String> active) {
        if (type.optional()) return "null";
        if (type.undefined()) return "undefined";
        if (type.array()) return "[]";
        return switch (simple(type.name())) {
            case "number" -> "1";
            case "boolean" -> "true";
            case "string" -> "\"12\"";
            case "unknown", "null" -> "null";
            case "undefined" -> "undefined";
            case "Record<string, unknown>", "Record<string, string>" -> "{}";
            default -> {
                if (!active.add(type.name())) throw new UiDiagnostic("UI2053", "Synthetic event has a required recursive type", program.dealSource(), 1, 1);
                UiModel.DealClass clazz = nominalClasses.get(type.name());
                if (clazz == null) clazz = program.deal().classes().get(simple(type.name()));
                if (clazz == null) throw new UiDiagnostic("UI2053", "Missing event nominal declaration", program.dealSource(), 1, 1);
                List<String> fields = new ArrayList<>();
                for (var field : clazz.fields().values()) {
                    if (field.optional()) continue;
                    String value = checkSample(field.type(), active);
                    if (nominalImports.getOrDefault(type.name().contains(".") ? type.name().substring(0,type.name().indexOf('.')) : "", "").equals("embedding/graphics") && simple(type.name()).equals("Pointer")) value = switch (field.name()) { case "phase" -> "\"down\""; case "x", "y" -> "12"; case "id" -> "0"; default -> value; };
                    fields.add(field.name() + ": " + value);
                }
                active.remove(type.name());
                yield "{" + String.join(", ", fields) + "}";
            }
        };
    }

    private void generateActionFactories(StringBuilder out) {
        for (Map.Entry<Integer, GeneratedAction> entry : actions.entrySet()) {
            UiModel.Action action = entry.getValue().action();
            int id = actionIds.get(simple(action.name()));
            UiModel.TypeRef payloadType = entry.getValue().payloadType();
            out.append("export function action_").append(entry.getKey()).append("(bindings: Record<string, unknown>");
            if (payloadType != null) out.append(", payload: ").append(qualifiedType(payloadType, moduleName(program.dealSource())));
            out.append("): UiAction {\n");
            for (var capture : entry.getValue().captures().entrySet()) {
                out.append("  let ").append(capture.getKey()).append(": ").append(qualifiedType(capture.getValue(), moduleName(program.dealSource())))
                    .append(" = bindings.").append(capture.getKey()).append(" as ").append(qualifiedType(capture.getValue(), moduleName(program.dealSource()))).append(";\n");
            }
            out.append("  return { slot: ").append(entry.getKey()).append(", nominal").append(id).append(": {");
            UiModel.DealClass declaration = program.deal().classes().get(simple(action.name()));
            boolean first = true;
            for (Map.Entry<String, UiModel.Expr> field : action.fields().entrySet()) {
                if (!first) out.append(", ");
                first = false;
                out.append(field.getKey()).append(": ");
                if (containsPayload(field.getValue())) out.append(payloadExpr(field.getValue()));
                else out.append(expr(field.getValue(), moduleName(program.dealSource())));
            }
            for (var field : declaration.fields().values()) if (!field.optional() && !action.fields().containsKey(field.name())) throw new IllegalStateException("Missing action field " + field.name());
            out.append("} };\n}\n\n");
        }
    }

    private void generateRouting(StringBuilder out, String app) {
        out            .append("export function initialStore(): UiStore { return {lifecycle: {disposed: false, draining: false, revision: 0}, queue: []}; }\n")
            .append("export function initial(state: app.").append(program.rootStateType()).append(", current: UiStore): Transition { let tree: core.ViewNode = view_").append(program.title()).append("(\"").append(program.title()).append("\", state); return { tree: tree, plan: reconcile.plan(null, tree), store: current, effect: effects.none() }; }\n")
            .append("export function enqueue(current: UiStore, action: UiAction): EnqueueResult { let decision: store.QueueDecision = store.enqueueDecision(current.lifecycle); if (!decision.accepted) { return { store: current, accepted: false, startDrain: false }; } let queue: UiAction[] = []; for (let queued of current.queue) { queue[queue.length] = queued; } queue[queue.length] = action; return { store: { lifecycle: store.beginDrain(current.lifecycle), queue: queue }, accepted: true, startDrain: decision.startDrain }; }\n")
            .append("export function dequeue(current: UiStore): DequeueResult { if (current.queue.length === 0) { return { store: current, action: {slot: -1}, present: false }; } let action: UiAction = current.queue[0]; let queue: UiAction[] = []; let i: number = 1; while (i < current.queue.length) { queue[queue.length] = current.queue[i]; i = i + 1; } return { store: { lifecycle: current.lifecycle, queue: queue }, action: action, present: true }; }\n")
            .append("export function complete(current: UiStore, action: UiAction): EnqueueResult { let descriptor: effects.CompletionDescriptor = effects.completion(current.lifecycle.disposed, action.slot); if (!descriptor.accepted) { return { store: current, accepted: false, startDrain: false }; } return enqueue(current, action); }\n")
            .append("export function finish(current: UiStore): UiStore { return { lifecycle: store.finish(current.lifecycle), queue: current.queue }; }\n")
            .append("export function reject(current: UiStore): UiStore { return { lifecycle: store.reject(current.lifecycle), queue: current.queue }; }\n")
            .append("export function dispose(current: UiStore): UiStore { let queue: UiAction[] = []; return { lifecycle: store.dispose(current.lifecycle), queue: queue }; }\n\n")
            .append("export function route(action: UiAction): actions.Route {\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            int effect = effectIds.getOrDefault(entry.getKey(), -1);
            int completion = effect < 0 ? -1 : actionIds.get(program.effects().get(entry.getKey()).returnType());
            out.append("  let nominal").append(entry.getValue()).append(": app.").append(entry.getKey()).append(" | undefined = action.nominal").append(entry.getValue()).append("; if (nominal").append(entry.getValue()).append(" !== undefined) { return actions.route(").append(entry.getValue()).append(", ").append(entry.getValue()).append(", ").append(effect).append(", ").append(completion).append("); }\n");
        }
        out.append("  return actions.noRoute();\n}\n\n")
            .append("export function validateAction(action: UiAction): actions.Route { let matches: number = 0;\n");
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) out.append("  let valid").append(entry.getValue()).append(": app.").append(entry.getKey()).append(" | undefined = action.nominal").append(entry.getValue()).append("; if (valid").append(entry.getValue()).append(" !== undefined) { matches = matches + 1; }\n");
        out.append("  let routeValue: actions.Route = route(action); if (matches !== 1 || routeValue.actionId < 0) { throw { name: \"UI3004\", message: \"Malformed nominal action\" }; } return routeValue;\n}\n\n")
            .append("export function nextState(state: app.").append(program.rootStateType()).append(", action: UiAction): app.").append(program.rootStateType()).append(" {\n")
            .append("  let routeValue: actions.Route = validateAction(action);\n  let candidate: app.").append(program.rootStateType()).append(" = state;\n");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : actionIds.entrySet()) {
            UiModel.Handler handler = program.updates().get(entry.getKey());
            out.append(first ? "  if" : "  else if").append(" (routeValue.updateId === ").append(entry.getValue()).append(") { let value: app.").append(entry.getKey()).append(" | undefined = action.nominal").append(entry.getValue()).append("; if (value === undefined) { throw { name: \"UI3002\", message: \"Missing nominal action\" }; } else { candidate = app.").append(handler.name()).append("(state, value); } }\n");
            first = false;
        }
        if (first) out.append("  throw { name: \"UI3001\", message: \"Unknown closed action\" };\n");
        else out.append("  else { throw { name: \"UI3001\", message: \"Unknown closed action\" }; }\n");
        out.append("  return candidate;\n}\n\n")
            .append("export function transitionFromCandidate(candidate: app.").append(program.rootStateType()).append(", previous: core.ViewNode | null, current: UiStore, action: UiAction): Transition {\n")
            .append("  let routeValue: actions.Route = validateAction(action);\n")
            .append("  let nextStore: UiStore = { lifecycle: store.commit(current.lifecycle), queue: current.queue };\n")
            .append("  let tree: core.ViewNode = view_").append(program.title()).append("(\"").append(program.title()).append("\", candidate);\n")
            .append("  let planValue: reconcile.Plan = reconcile.plan(previous, tree);\n")
             .append("  let effect: effects.EffectDescriptor = effects.none();\n")
             .append("  if (actions.hasEffect(routeValue)) { effect = effects.start(routeValue.effectId, action.slot, nextStore.lifecycle.revision); }\n")
             .append("  return { tree: tree, plan: planValue, store: nextStore, effect: effect };\n}\n\n")
             .append("export function effectCommand(state: app.").append(program.rootStateType()).append(", action: UiAction): effects.EffectCommand {\n")
             .append("  let routeValue: actions.Route = validateAction(action);\n");
        first = true;
        for (Map.Entry<String, UiModel.Handler> entry : program.effectPolicies().entrySet()) {
            int id = actionIds.get(entry.getKey());
            out.append(first ? "  if" : "  else if").append(" (routeValue.actionId === ").append(id).append(") { let value: app.").append(entry.getKey()).append(" | undefined = action.nominal").append(id).append("; if (value === undefined) { throw { name: \"UI3002\", message: \"Missing nominal action\" }; } else { let command: app.EffectCommand = app.").append(entry.getValue().name()).append("(state, value); return { operation: command.operation, key: command.key, delayMillis: command.delayMillis, cancellationMode: command.cancellationMode }; } }\n");
            first = false;
        }
        out.append("  return effects.startCommand(\"\", 0, \"none\");\n}\n\n");

    }

    private void expandForwarding(List<UiModel.Node> nodes) {
        for (var node : nodes) {
            if (node instanceof UiModel.Call call) {
                var view = program.views().get(call.name());
                if (view != null && actionView(view)) {
                    var bound = UiViewBindings.bind(view.nodes(), call.arguments());
                    forwardedViews.put(call, bound); expandForwarding(bound);
                } else expandForwarding(call.children());
            } else if (node instanceof UiModel.When when) { expandForwarding(when.thenNodes()); expandForwarding(when.elseNodes()); }
            else if (node instanceof UiModel.ForEach each) expandForwarding(each.children());
        }
    }

    private void indexIterations(List<UiModel.Node> nodes) {
        for (UiModel.Node node : nodes) {
            if (node instanceof UiModel.ForEach each) {
                iterationIds.put(each, iterationIds.size());
                indexIterations(each.children());
            } else if (node instanceof UiModel.Call call) indexIterations(forwardedViews.getOrDefault(call, call.children()));
            else if (node instanceof UiModel.When when) { indexIterations(when.thenNodes()); indexIterations(when.elseNodes()); }
        }
    }

    private void appendForEachAccessors(StringBuilder out, List<UiModel.Node> nodes, Map<String, UiModel.TypeRef> scope) {
        for (UiModel.Node node : nodes) {
            if (node instanceof UiModel.ForEach each) {
                String root = each.source().parts().get(0);
                UiModel.TypeRef rootType = scope.get(root);
                if (rootType == null) throw new IllegalStateException("Unknown iteration scope " + root);
                out.append("\nexport function uiItems").append(iterationIds.get(each)).append("(").append(root).append(": ").append(dealType(rootType)).append("): ").append(dealType(expressionType(each.source(), scope))).append(" { return ").append(String.join(".", each.source().parts())).append("; }\n");
                Map<String, UiModel.TypeRef> nested = new LinkedHashMap<>(scope);
                nested.put(each.item().name(), each.item().type());
                appendForEachAccessors(out, each.children(), nested);
            } else if (node instanceof UiModel.Call call) appendForEachAccessors(out, forwardedViews.getOrDefault(call, call.children()), scope);
            else if (node instanceof UiModel.When when) { appendForEachAccessors(out, when.thenNodes(), scope); appendForEachAccessors(out, when.elseNodes(), scope); }
        }
    }

    private String actionLiteral(String name, int id, String app) {
        UiModel.DealClass declaration = program.deal().classes().get(name);
        List<String> fields = new ArrayList<>();
        for (UiModel.Field field : declaration.fields().values()) fields.add(field.name() + ": value." + field.name());
        return "action.nominal" + id;
    }

    private UiModel.TypeRef expressionType(UiModel.Expr expression, Map<String, UiModel.TypeRef> scope) {
        if (expression instanceof UiModel.Literal literal) return new UiModel.TypeRef(literal.type(), false, false);
        if (expression instanceof UiModel.PathExpr path) {
            if (path.parts().get(0).equals("payload")) return new UiModel.TypeRef("string", false, false);
            UiModel.Token token = program.tokens().get(String.join(".", path.parts()));
            if (token != null) return token.type();
            UiModel.TypeRef root = scope.get(path.parts().get(0));
            if (root != null) return pathType(root, path.parts().subList(1, path.parts().size()));
        }
        if (expression instanceof UiModel.Has) return new UiModel.TypeRef("boolean", false, false);
        if (expression instanceof UiModel.Unary unary) {
            if (unary.operator().equals("!")) return new UiModel.TypeRef("boolean", false, false);
            return expressionType(unary.operand(), scope);
        }
        if (expression instanceof UiModel.Binary binary) {
            return switch (binary.operator()) {
                case "&&", "||", "===", "!==", "<", "<=", ">", ">=" -> new UiModel.TypeRef("boolean", false, false);
                case "+", "-", "*", "/" -> expressionType(binary.left(), scope);
                default -> throw new IllegalStateException("Unknown UI expression operator " + binary.operator());
            };
        }
        throw new IllegalStateException("Unknown UI expression type " + expression.getClass().getName());
    }

    private UiModel.TypeRef fieldType(UiModel.TypeRef root, UiModel.PathExpr path) { return pathType(root, path.parts().subList(1, path.parts().size())); }
    private UiModel.TypeRef pathType(UiModel.TypeRef root, List<String> fields) {
        UiModel.TypeRef current = root;
        for (String field : fields) {
            var pack = program.packClasses().get(current.name());
            UiModel.Field declaration = pack == null ? program.deal().classes().get(simple(current.name())).fields().get(field)
                    : pack.fields().stream().filter(value -> value.name().equals(field)).findFirst().orElseThrow();
            current = declaration.optional() ? declaration.type().withUndefined(true) : declaration.type();
        }
        return current;
    }

    private String expr(UiModel.Expr expression, String app) {
        if (expression instanceof UiModel.Literal literal) {
            if (literal.value() == null) return "null";
            if (literal.value() instanceof String string) return quote(string);
            if (literal.value() instanceof Map<?, ?> fields) return "{" + fields.entrySet().stream()
                    .map(field -> field.getKey() + ": " + expr((UiModel.Expr) field.getValue(), app)).collect(java.util.stream.Collectors.joining(", ")) + "}";
            return String.valueOf(literal.value());
        }
        if (expression instanceof UiModel.PathExpr path) {
            var token = program.tokens().get(String.join(".", path.parts()));
            return token == null ? String.join(".", path.parts()) : expr(token.value(), app);
        }
        if (expression instanceof UiModel.Has has) {
            List<String> parts = has.path().parts();
            return "Object.hasOwn(" + String.join(".", parts.subList(0, parts.size() - 1))
                + ", " + quote(parts.get(parts.size() - 1)) + ")";
        }
        if (expression instanceof UiModel.Unary unary) return unary.operator() + "(" + expr(unary.operand(), app) + ")";
        if (expression instanceof UiModel.Binary binary) return "(" + expr(binary.left(), app) + " " + binary.operator() + " " + expr(binary.right(), app) + ")";
        throw new IllegalStateException("Action expression is not a value expression");
    }

    private UiModel.Event event(UiModel.Component component, String prop) {
        for (UiModel.Contract contract : component.contracts()) if (contract instanceof UiModel.Event event && event.prop().equals(prop)) return event;
        throw new IllegalStateException("Missing checked event contract " + component.name() + "." + prop);
    }

    private String payloadExpr(UiModel.Expr expression) {
        if (expression instanceof UiModel.PathExpr path && path.parts().get(0).equals("payload")) return String.join(".", path.parts());
        if (expression instanceof UiModel.Unary unary) return unary.operator() + "(" + payloadExpr(unary.operand()) + ")";
        if (expression instanceof UiModel.Binary binary) return "(" + payloadExpr(binary.left()) + " " + binary.operator() + " " + payloadExpr(binary.right()) + ")";
        return expr(expression, moduleName(program.dealSource()));
    }

    private boolean references(UiModel.Expr expression, String root) {
        if (expression instanceof UiModel.PathExpr path) return path.parts().get(0).equals(root);
        if (expression instanceof UiModel.Unary unary) return references(unary.operand(), root);
        if (expression instanceof UiModel.Binary binary) return references(binary.left(), root) || references(binary.right(), root);
        if (expression instanceof UiModel.Has has) return references(has.path(), root);
        return false;
    }

    private boolean containsPayload(UiModel.Expr expression) {
        if (expression instanceof UiModel.PathExpr path) return path.parts().get(0).equals("payload");
        if (expression instanceof UiModel.Unary unary) return containsPayload(unary.operand());
        if (expression instanceof UiModel.Binary binary) return containsPayload(binary.left()) || containsPayload(binary.right());
        if (expression instanceof UiModel.Has has) return has.path().parts().get(0).equals("payload");
        return false;
    }

    private void validateEffectPolicyTypes() {
        UiModel.DealClass command = program.deal().classes().get("EffectCommand");
        if ((!program.effectPolicies().isEmpty() || !program.effectFailures().isEmpty()) && command == null) throw new UiDiagnostic("UI2042", "EffectCommand must be declared by the application", program.dealSource(), 1, 1);
        if (command == null) return;
        Map<String, String> expected = Map.of("operation", "string", "key", "string", "delayMillis", "number", "cancellationMode", "string");
        for (Map.Entry<String, String> field : expected.entrySet()) {
            UiModel.Field actual = command.fields().get(field.getKey());
            if (actual == null || !actual.type().name().equals(field.getValue()) || actual.type().optional() || actual.type().array()) throw new UiDiagnostic("UI2043", "EffectCommand requires " + field.getKey() + ": " + field.getValue(), program.dealSource(), 1, 1);
        }
    }

    private String qualifiedType(UiModel.TypeRef type, String app) {
        String name = type.name();
        if (program.packClasses().containsKey(name)) name = packTypeName(name);
        if (!name.contains(".") && program.deal().classes().containsKey(name)) name = "app." + name;
        return type.sourceType(name);
    }
    private String packTypeName(String name) { return "UiPack_" + name.replace(".", "_"); }
    private String dealType(UiModel.TypeRef type) { return type.sourceType(type.name()); }
    private String defaultValue(UiModel.TypeRef type) {
        if (type.optional()) return "null";
        if (type.array()) return "[]";
        return switch (simple(type.name())) { case "string" -> "\"\""; case "number" -> "0.0"; case "boolean" -> "false"; default -> "{}"; };
    }
    private String moduleName(java.nio.file.Path path) { String name = path.getFileName().toString(); return name.substring(0, name.length() - ".deal".length()); }
    private String simple(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(dot + 1); }
    private String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (unit == '\\' || unit == '"') result.append('\\').append(unit);
            else if (unit < 0x20 || unit == 0x2028 || unit == 0x2029 || Character.isSurrogate(unit))
                result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) unit));
            else result.append(unit);
        }
        return result.append('"').toString();
    }
}
