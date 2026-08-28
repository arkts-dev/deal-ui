package deal.ui;

import deal.ast.ArrayType;
import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.ProgramNode;
import deal.ast.QualifiedType;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UiChecker {
    public UiModel.DealModule parseDeal(Path file, String source) {
        LexResult lexed = new Lexer(source, file.toString()).tokenize();
        first(lexed.diagnostics());
        ParseResult parsed = new Parser(lexed.tokens(), file.toString()).parse();
        first(parsed.diagnostics());
        first(ModuleShapeValidator.validate(parsed.program(), file.toString(), false));
        return dealModule(parsed.program(), file, source);
    }

    public UiModel.CheckedProgram check(Path viewFile, UiModel.ViewModule viewModule, Path dealFile,
                                         UiModel.DealModule deal, Map<String, UiModel.PackModule> packs) {
        Map<String, String> aliases = aliases(viewModule.imports());
        UiModel.View root = null;
        Map<String, UiModel.View> views = new LinkedHashMap<>();
        for (UiModel.View view : viewModule.views()) {
            requireSingleRoot(view.nodes(), view.span());
            if (views.putIfAbsent(view.name(), view) != null) error("UI2001", "Duplicate view '" + view.name() + "'", view.span());
            if (view.root()) {
                if (!view.exported() || root != null) error("UI2002", "Exactly one exported @ui-root view is required", view.span());
                root = view;
            }
        }
        if (root == null) throw new UiDiagnostic("UI2002", "Exactly one exported @ui-root view is required", viewFile, 1, 1);
        if (root.parameters().size() != 1) error("UI2003", "Root view requires one state parameter", root.span());
        String stateType = localType(root.parameters().get(0).type().name(), aliases, dealFile, deal);
        UiModel.DealClass state = deal.classes().get(stateType);
        if (state == null) error("UI2004", "Unknown root state class '" + stateType + "'", root.span());

        Map<String, UiModel.Component> components = new LinkedHashMap<>();
        Map<String, UiModel.PackClass> packClasses = new LinkedHashMap<>();
        Map<String, UiModel.Token> tokens = new LinkedHashMap<>();
        for (UiModel.Import imported : viewModule.imports()) {
            UiModel.PackModule pack = packs.get(imported.specifier());
            if (pack == null) continue;
            pack.components().forEach((name, value) -> components.put(imported.alias() + "." + name, value));
            pack.classes().forEach((name, value) -> packClasses.put(imported.alias() + "." + name, value));
            pack.tokens().forEach((name, value) -> tokens.put(imported.alias() + "." + name, value));
        }
        validatePack(components, packClasses, tokens);

        Map<String, UiModel.Handler> updates = handlers(deal.handlers(), "ui-update");
        Map<String, UiModel.Handler> effects = handlers(deal.handlers(), "ui-effect");
        Map<String, UiModel.Handler> effectPolicies = handlers(deal.handlers(), "ui-effect-policy");
        Map<String, UiModel.Handler> effectFailures = handlers(deal.handlers(), "ui-effect-failure");
        Set<String> reachableActions = new LinkedHashSet<>();
        Map<String, UiModel.TypeRef> scope = new LinkedHashMap<>();
        scope.put(root.parameters().get(0).name(), new UiModel.TypeRef(stateType, false, false));
        List<UiModel.RenderNode> nodes = lower(root.nodes(), root.name(), scope, views, components, packClasses,
            tokens, deal, aliases, reachableActions, new LinkedHashSet<>());
        for (String action : reachableActions) {
            if (!updates.containsKey(action)) error("UI2005", "Reachable action '" + action + "' requires exactly one @ui-update", root.span());
        }
        Set<String> completionActions = effectCompletion(actions(effects));
        completionActions.addAll(effectCompletion(actions(effectFailures)));
        for (String action : updates.keySet()) if (!reachableActions.contains(action) && !completionActions.contains(action)) {
            error("UI2006", "Update action '" + action + "' is unreachable", updates.get(action).span());
        }
        for (Map.Entry<String, UiModel.Handler> effect : effects.entrySet()) {
            if (!updates.containsKey(effect.getKey())) error("UI2007", "Effect action requires an update", effect.getValue().span());
            if (!updates.containsKey(effect.getValue().returnType())) error("UI2008", "Effect completion action requires an update", effect.getValue().span());
            UiModel.Handler policy = effectPolicies.get(effect.getKey());
            if (policy != null && !policy.returnType().equals("EffectCommand")) error("UI2038", "Effect policy must return EffectCommand", policy.span());
            if (policy != null && !policy.stateType().equals(stateType)) error("UI2045", "Effect policy state must match root state", policy.span());
            UiModel.Handler failure = effectFailures.get(effect.getKey());
            if (failure != null && !updates.containsKey(failure.returnType())) error("UI2039", "Effect failure mapper must return an update action", failure.span());
            if (failure != null && !failure.stateType().equals(stateType)) error("UI2046", "Effect failure mapper state must match root state", failure.span());
        }
        for (UiModel.Handler policy : effectPolicies.values()) if (!effects.containsKey(policy.actionType())) error("UI2040", "Effect policy requires an effect", policy.span());
        for (UiModel.Handler failure : effectFailures.values()) if (!effects.containsKey(failure.actionType())) error("UI2041", "Effect failure mapper requires an effect", failure.span());
        return new UiModel.CheckedProgram(viewFile, dealFile, root.name(), stateType, views, components,
            packClasses, tokens, deal, nodes, updates, effects, effectPolicies, effectFailures);
    }

    private Set<String> effectCompletion(Map<String, UiModel.Handler> effects) {
        Set<String> result = new LinkedHashSet<>();
        effects.values().forEach(handler -> result.add(handler.returnType()));
        return result;
    }

    private Map<String, UiModel.Handler> actions(Map<String, UiModel.Handler> handlers) { return handlers; }

    private List<UiModel.RenderNode> lower(List<UiModel.Node> source, String identity,
                                            Map<String, UiModel.TypeRef> scope, Map<String, UiModel.View> views,
                                            Map<String, UiModel.Component> components,
                                            Map<String, UiModel.PackClass> packClasses, Map<String, UiModel.Token> tokens,
                                            UiModel.DealModule deal, Map<String, String> aliases,
                                            Set<String> actions, Set<String> viewStack) {
        List<UiModel.RenderNode> result = new ArrayList<>();
        int index = 0;
        for (UiModel.Node node : source) {
            String nodeIdentity = identity + "/" + index++;
            if (node instanceof UiModel.Call call) {
                UiModel.View view = views.get(simple(call.name()));
                if (view != null && !components.containsKey(call.name())) {
                    if (!call.children().isEmpty()) error("UI2010", "View calls reject children", call.span());
                    exactArguments(call.arguments(), view.parameters().stream().map(UiModel.Parameter::name).toList(), call.span());
                    if (!viewStack.add(view.name())) error("UI2011", "Recursive view call '" + view.name() + "'", call.span());
                    Map<String, UiModel.TypeRef> nested = new LinkedHashMap<>();
                    Map<String, UiModel.Expr> bindings = new LinkedHashMap<>();
                    for (UiModel.Parameter parameter : view.parameters()) {
                        UiModel.Expr argument = call.arguments().get(parameter.name());
                        checkAssignable(type(argument, scope, deal, tokens, aliases, actions, null), parameter.type(), call.span());
                        nested.put(parameter.name(), parameter.type());
                        bindings.put(parameter.name(), argument);
                    }
                    result.add(new UiModel.RenderScope(bindings, lower(view.nodes(), nodeIdentity + "/" + view.name(), nested, views, components,
                        packClasses, tokens, deal, aliases, actions, viewStack)));
                    viewStack.remove(view.name());
                    continue;
                }
                UiModel.Component component = components.get(call.name());
                if (component == null) error("UI2012", "Unknown component or view '" + call.name() + "'", call.span());
                UiModel.PackClass props = findPackClass(component.propsType(), call.name(), packClasses);
                exactProps(call.arguments(), props, call.span());
                Map<String, UiModel.Event> events = new LinkedHashMap<>();
                boolean children = false;
                for (UiModel.Contract contract : component.contracts()) {
                    if (contract instanceof UiModel.Children childPolicy) {
                        children = true;
                        if (childPolicy.required() && call.children().isEmpty()) error("UI2013", "Component requires children", call.span());
                    }
                    if (contract instanceof UiModel.Event event) {
                        events.put(event.prop(), event);
                        UiModel.Field eventField = props.fields().stream().filter(field -> field.name().equals(event.prop())).findFirst().orElseThrow();
                        if (!eventField.type().name().equals("Action")) error("UI2013", "Event prop must have Action type", call.span());
                    }
                    if (contract instanceof UiModel.Accessibility accessibility && call.arguments().containsKey(accessibility.prop())) requireType(type(call.arguments().get(accessibility.prop()), scope, deal, tokens, aliases, actions, null), "string", call.span());
                    if (contract instanceof UiModel.TokenProp tokenProp && call.arguments().containsKey(tokenProp.prop()) && !(call.arguments().get(tokenProp.prop()) instanceof UiModel.PathExpr path && tokens.containsKey(String.join(".", path.parts())))) error("UI2013", "Token prop requires a declared token", call.span());
                }
                if (!children && !call.children().isEmpty()) error("UI2013", "Component rejects children", call.span());
                for (Map.Entry<String, UiModel.Expr> argument : call.arguments().entrySet()) {
                    UiModel.Field field = props.fields().stream().filter(value -> value.name().equals(argument.getKey())).findFirst().orElseThrow();
                    UiModel.Event event = events.get(argument.getKey());
                    checkAssignable(type(argument.getValue(), scope, deal, tokens, aliases, actions, event), field.type(), argument.getValue().span());
                }
                result.add(new UiModel.RenderCall(call.name(), call.arguments(),
                    lower(call.children(), nodeIdentity, scope, views, components, packClasses, tokens, deal, aliases, actions, viewStack),
                    nodeIdentity, call.span()));
            } else if (node instanceof UiModel.When when) {
                requireType(type(when.condition(), scope, deal, tokens, aliases, actions, null), "boolean", when.condition().span());
                result.add(new UiModel.RenderWhen(when.condition(),
                    lower(when.thenNodes(), nodeIdentity + "/then", scope, views, components, packClasses, tokens, deal, aliases, actions, viewStack),
                    lower(when.elseNodes(), nodeIdentity + "/else", scope, views, components, packClasses, tokens, deal, aliases, actions, viewStack),
                    nodeIdentity, when.span()));
            } else {
                UiModel.ForEach each = (UiModel.ForEach) node;
                UiModel.TypeRef array = type(each.source(), scope, deal, tokens, aliases, actions, null);
                if (!array.array() || !sameName(array.name(), each.item().type().name())) error("UI2014", "ForEach source must be exact item array", each.span());
                Map<String, UiModel.TypeRef> nested = new LinkedHashMap<>(scope);
                nested.put(each.item().name(), each.item().type());
                UiModel.TypeRef key = type(each.key(), nested, deal, tokens, aliases, actions, null);
                if (!key.name().equals("int") && !key.name().equals("string")) error("UI2015", "ForEach key must be int or string", each.key().span());
                if (!each.key().parts().get(0).equals(each.item().name())) error("UI2016", "ForEach key must be item-rooted", each.key().span());
                result.add(new UiModel.RenderForEach(each.source(), each.item(), each.key(),
                    lower(each.children(), nodeIdentity + "/item", nested, views, components, packClasses, tokens, deal, aliases, actions, viewStack),
                    nodeIdentity, each.span()));
            }
        }
        return List.copyOf(result);
    }

    private UiModel.TypeRef type(UiModel.Expr expression, Map<String, UiModel.TypeRef> scope,
                                 UiModel.DealModule deal, Map<String, UiModel.Token> tokens,
                                 Map<String, String> aliases, Set<String> actions, UiModel.Event event) {
        if (expression instanceof UiModel.Literal literal) return new UiModel.TypeRef(literal.type(), literal.value() == null, false);
        if (expression instanceof UiModel.PathExpr path) return pathType(path, scope, deal, tokens);
        if (expression instanceof UiModel.Has has) {
            UiModel.TypeRef operand = pathType(has.path(), scope, deal, tokens);
            if (!operand.optional()) error("UI2017", "has requires an optional final field", has.span());
            return primitive("boolean");
        }
        if (expression instanceof UiModel.Action action) {
            String name = simple(action.name());
            UiModel.DealClass declaration = deal.classes().get(name);
            if (declaration == null) error("UI2018", "Unknown nominal action '" + name + "'", action.span());
            exactArguments(action.fields(), declaration.fields().keySet().stream().toList(), action.span());
            Map<String, UiModel.TypeRef> actionScope = new LinkedHashMap<>(scope);
            if (event != null && event.payload() != null) actionScope.put("payload", event.payload());
            for (Map.Entry<String, UiModel.Expr> field : action.fields().entrySet()) {
                checkAssignable(type(field.getValue(), actionScope, deal, tokens, aliases, actions, null), declaration.fields().get(field.getKey()).type(), field.getValue().span());
            }
            actions.add(name);
            return primitive("Action");
        }
        if (expression instanceof UiModel.Unary unary) {
            UiModel.TypeRef operand = type(unary.operand(), scope, deal, tokens, aliases, actions, event);
            requireType(operand, unary.operator().equals("!") ? "boolean" : operand.name(), unary.span());
            if (unary.operator().equals("-") && !operand.name().equals("int") && !operand.name().equals("number")) error("UI2019", "Unary - requires numeric operand", unary.span());
            return operand;
        }
        UiModel.Binary binary = (UiModel.Binary) expression;
        UiModel.TypeRef left = type(binary.left(), scope, deal, tokens, aliases, actions, event);
        UiModel.TypeRef right = type(binary.right(), scope, deal, tokens, aliases, actions, event);
        return switch (binary.operator()) {
            case "&&", "||" -> { requireType(left, "boolean", binary.span()); requireType(right, "boolean", binary.span()); yield primitive("boolean"); }
            case "===", "!==", "<", "<=", ">", ">=" -> { if (!sameName(left.name(), right.name())) error("UI2020", "Operands require matching types", binary.span()); yield primitive("boolean"); }
            case "+", "-", "*", "/", "%" -> { if (!sameName(left.name(), right.name())) error("UI2020", "Operands require matching types", binary.span()); yield left; }
            default -> throw new IllegalStateException(binary.operator());
        };
    }

    private UiModel.TypeRef pathType(UiModel.PathExpr path, Map<String, UiModel.TypeRef> scope,
                                     UiModel.DealModule deal, Map<String, UiModel.Token> tokens) {
        String joined = String.join(".", path.parts());
        UiModel.Token token = tokens.get(joined);
        if (token != null) return token.type();
        UiModel.TypeRef current = scope.get(path.parts().get(0));
        if (current == null) error("UI2021", "Unknown path root '" + path.parts().get(0) + "'", path.span());
        for (int i = 1; i < path.parts().size(); i++) {
            if (current.optional()) error("UI2022", "Nullable intermediate path access", path.span());
            UiModel.DealClass clazz = deal.classes().get(simple(current.name()));
            if (clazz == null) error("UI2023", "Path traverses non-class type", path.span());
            UiModel.Field field = clazz.fields().get(path.parts().get(i));
            if (field == null) error("UI2024", "Unknown field '" + path.parts().get(i) + "'", path.span());
            current = field.type();
        }
        return current;
    }

    private void validatePack(Map<String, UiModel.Component> components, Map<String, UiModel.PackClass> classes,
                              Map<String, UiModel.Token> tokens) {
        for (Map.Entry<String, UiModel.Component> entry : components.entrySet()) {
            UiModel.PackClass props = findPackClass(entry.getValue().propsType(), entry.getKey(), classes);
            Set<String> fields = new LinkedHashSet<>();
            props.fields().forEach(field -> fields.add(field.name()));
            for (UiModel.Contract contract : entry.getValue().contracts()) {
                String prop = contract instanceof UiModel.Event value ? value.prop()
                    : contract instanceof UiModel.Accessibility value ? value.prop()
                    : contract instanceof UiModel.TokenProp value ? value.prop() : null;
                if (prop != null && !fields.contains(prop)) error("UI2025", "Contract references unknown prop '" + prop + "'", entry.getValue().span());
                if (contract instanceof UiModel.Event event) {
                    UiModel.Field field = props.fields().stream().filter(value -> value.name().equals(event.prop())).findFirst().orElseThrow();
                    if (!field.type().name().equals("Action")) error("UI2025", "Event prop must have Action type", entry.getValue().span());
                }
            }
        }
        for (UiModel.PackClass clazz : classes.values()) for (UiModel.Field field : clazz.fields()) {
            if (field.defaultValue() instanceof UiModel.PathExpr path && !tokens.containsKey(String.join(".", path.parts()))) error("UI2026", "Pack-default name must resolve to token", field.span());
        }
        for (UiModel.Token token : tokens.values()) if (token.value() == null) error("UI2026", "Token requires a host value", token.span());
    }

    private UiModel.PackClass findPackClass(String name, String component, Map<String, UiModel.PackClass> classes) {
        UiModel.PackClass result = classes.get(name.contains(".") ? name : prefix(component) + name);
        if (result == null) throw new UiDiagnostic("UI2027", "Unknown prop class '" + name + "'", Path.of("<pack>"), 1, 1);
        return result;
    }

    private void exactProps(Map<String, UiModel.Expr> values, UiModel.PackClass props, UiModel.Span span) {
        Set<String> known = new LinkedHashSet<>();
        for (UiModel.Field field : props.fields()) {
            known.add(field.name());
            if (!field.type().optional() && field.defaultValue() == null && !values.containsKey(field.name())) error("UI2028", "Missing required prop '" + field.name() + "'", span);
        }
        for (String name : values.keySet()) if (!known.contains(name)) error("UI2029", "Unknown prop '" + name + "'", span);
    }

    private void exactArguments(Map<String, ?> values, List<String> names, UiModel.Span span) {
        if (!values.keySet().equals(new LinkedHashSet<>(names))) error("UI2030", "Arguments must exactly match declared names", span);
    }

    private void checkAssignable(UiModel.TypeRef actual, UiModel.TypeRef expected, UiModel.Span span) {
        if (actual.name().equals("null") && expected.optional()) return;
        if (!sameName(actual.name(), expected.name()) || actual.array() != expected.array()) error("UI2031", "Expected " + expected.name() + ", got " + actual.name(), span);
    }
    private void requireType(UiModel.TypeRef actual, String expected, UiModel.Span span) { if (!actual.name().equals(expected)) error("UI2032", "Expected " + expected, span); }
    private UiModel.TypeRef primitive(String name) { return new UiModel.TypeRef(name, false, false); }
    private boolean sameName(String left, String right) { return simple(left).equals(simple(right)); }
    private String prefix(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(0, dot + 1); }
    private String simple(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(dot + 1); }

    private Map<String, UiModel.Handler> handlers(List<UiModel.Handler> source, String kind) {
        Map<String, UiModel.Handler> result = new LinkedHashMap<>();
        for (UiModel.Handler handler : source) if (handler.kind().equals(kind) && result.putIfAbsent(handler.actionType(), handler) != null) error("UI2033", "Duplicate " + kind + " handler for action '" + handler.actionType() + "'", handler.span());
        return Map.copyOf(result);
    }

    private Map<String, String> aliases(List<UiModel.Import> imports) {
        Map<String, String> result = new LinkedHashMap<>();
        for (UiModel.Import value : imports) result.put(value.alias(), value.specifier());
        return result;
    }

    private String localType(String name, Map<String, String> aliases, Path dealFile, UiModel.DealModule deal) {
        if (!name.contains(".")) return name;
        String alias = name.substring(0, name.indexOf('.'));
        String specifier = aliases.get(alias);
        if (specifier == null) return name;
        Path resolved = dealFile.getParent().resolve(specifier).normalize();
        if (!resolved.toString().endsWith(".deal")) resolved = Path.of(resolved + ".deal");
        return resolved.equals(deal.source().normalize()) ? simple(name) : name;
    }

    private UiModel.DealModule dealModule(ProgramNode program, Path file, String source) {
        Map<String, UiModel.DealClass> classes = new LinkedHashMap<>();
        List<FunctionDeclaration> functions = new ArrayList<>();
        Set<String> exported = new LinkedHashSet<>();
        for (StatementNode statement : program.statements()) {
            boolean isExported = statement instanceof ExportDeclaration;
            StatementNode declaration = isExported ? ((ExportDeclaration) statement).declaration() : statement;
            if (declaration instanceof ClassDeclaration clazz) {
                Map<String, UiModel.Field> fields = new LinkedHashMap<>();
                for (ClassField field : clazz.fields()) fields.put(field.name(), new UiModel.Field(field.name(), type(field.type(), field.optional()), null, span(file, field.span().startLine(), field.span().startColumn())));
                classes.put(clazz.name(), new UiModel.DealClass(clazz.name(), fields, isExported));
            } else if (declaration instanceof FunctionDeclaration function) {
                functions.add(function);
                if (isExported) exported.add(function.name());
            }
        }
        Map<String, UiModel.DealFunction> functionInfo = new LinkedHashMap<>();
        for (FunctionDeclaration function : functions) {
            List<UiModel.TypeRef> parameters = function.params().stream().map(parameter -> type(parameter.type(), false)).toList();
            functionInfo.put(function.name(), new UiModel.DealFunction(function.name(), parameters, type(function.returnType(), false), exported.contains(function.name()), function.isAsync(), span(file, function.span().startLine(), function.span().startColumn())));
        }
        List<UiModel.Handler> handlers = new ArrayList<>();
        for (FunctionDeclaration function : functions) {
            String directive = directiveBefore(source, function.span().startLine());
            if (directive == null) continue;
            boolean failure = directive.equals("ui-effect-failure");
            int parameters = failure ? 3 : 2;
            if (!exported.contains(function.name()) || function.params().size() != parameters) throw new UiDiagnostic("UI2034", failure ? "Effect failure mapper must be exported with state, action, and message parameters" : "UI handler must be exported with two parameters", file, function.span().startLine(), function.span().startColumn());
            boolean effect = directive.equals("ui-effect");
            boolean policy = directive.equals("ui-effect-policy") || failure;
            if (function.isAsync() != effect) throw new UiDiagnostic("UI2035", effect ? "Effect must be async" : "Update and effect policy must be synchronous", file, function.span().startLine(), function.span().startColumn());
            String state = typeName(function.params().get(0).type());
            String action = typeName(function.params().get(1).type());
            String returned = typeName(function.returnType());
            if (failure && !typeName(function.params().get(2).type()).equals("string")) throw new UiDiagnostic("UI2044", "Effect failure mapper message parameter must be string", file, function.span().startLine(), function.span().startColumn());
            if (!effect && !policy && !state.equals(returned)) throw new UiDiagnostic("UI2036", "Update must return root state", file, function.span().startLine(), function.span().startColumn());
            handlers.add(new UiModel.Handler(function.name(), state, action, returned, directive, span(file, function.span().startLine(), function.span().startColumn())));
        }
        return new UiModel.DealModule(classes, functionInfo, handlers, file);
    }

    private String directiveBefore(String source, int declarationLine) {
        String[] lines = source.split("\\R", -1);
        for (int line = declarationLine - 2; line >= 0; line--) {
            String value = lines[line].trim();
            if (value.isEmpty()) continue;
            if (value.equals("// @ui-update")) return "ui-update";
            if (value.equals("// @ui-effect")) return "ui-effect";
            if (value.equals("// @ui-effect-policy")) return "ui-effect-policy";
            if (value.equals("// @ui-effect-failure")) return "ui-effect-failure";
            return null;
        }
        return null;
    }

    private UiModel.TypeRef type(TypeNode type, boolean optional) {
        if (type instanceof NullableType nullable) return type(nullable.innerType(), true);
        if (type instanceof ArrayType array) { UiModel.TypeRef element = type(array.elementType(), optional); return new UiModel.TypeRef(element.name(), element.optional(), element.dimensions() + 1); }
        return new UiModel.TypeRef(typeName(type), optional, false);
    }
    private String typeName(TypeNode type) {
        if (type instanceof NamedType named) return named.name();
        if (type instanceof QualifiedType qualified) return qualified.moduleName() + "." + qualified.typeName();
        if (type instanceof ArrayType array) return typeName(array.elementType()) + "[]";
        return type.toString();
    }
    private void first(List<CompilerDiagnostic> diagnostics) { for (CompilerDiagnostic diagnostic : diagnostics) if (diagnostic.severity().equals("error")) throw new UiDiagnostic(diagnostic.code(), diagnostic.message(), Path.of(diagnostic.range().file()), diagnostic.range().startLine(), diagnostic.range().startColumn()); }
    private UiModel.Span span(Path file, int line, int column) { return new UiModel.Span(file, line, column); }
    private void requireSingleRoot(List<UiModel.Node> nodes, UiModel.Span span) {
        if (nodes.size() != 1) error("UI2037", "View must produce exactly one root node", span);
        UiModel.Node root = nodes.get(0);
        if (root instanceof UiModel.ForEach) error("UI2037", "View root cannot be repeated", root.span());
        if (root instanceof UiModel.When when) {
            requireSingleRoot(when.thenNodes(), when.span());
            requireSingleRoot(when.elseNodes(), when.span());
        }
    }

    private void error(String code, String message, UiModel.Span span) { throw new UiDiagnostic(code, message, span.file(), span.line(), span.column()); }
}
