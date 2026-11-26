package deal.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiModel {
    private UiModel() {}

    public record Span(Path file, int line, int column, int endLine, int endColumn) {
        public Span(Path file, int line, int column) {
            this(file, line, column, line, column);
        }
    }
    public record Import(String alias, String specifier, Span span) {}
    /** Qualifiers run leaf-outward. optional is the legacy name for outer null admission, not field absence. */
    public record TypeRef(String name, boolean optional, int dimensions, List<Boolean> readonlyLayers,
                          List<Boolean> nullableElements, boolean undefined, List<Boolean> undefinedElements) {
        public TypeRef {
            readonlyLayers = List.copyOf(readonlyLayers);
            nullableElements = List.copyOf(nullableElements);
            undefinedElements = List.copyOf(undefinedElements);
            if (readonlyLayers.size() != dimensions || nullableElements.size() != dimensions || undefinedElements.size() != dimensions)
                throw new IllegalArgumentException("Array qualifier depth mismatch");
            if (name.equals("unknown")) {
                if (dimensions == 0) { optional = false; undefined = false; }
                else {
                    var nulls = new java.util.ArrayList<>(nullableElements); nulls.set(0, false); nullableElements = List.copyOf(nulls);
                    var missing = new java.util.ArrayList<>(undefinedElements); missing.set(0, false); undefinedElements = List.copyOf(missing);
                }
            }
        }
        public TypeRef(String name, boolean optional, int dimensions, List<Boolean> readonlyLayers, List<Boolean> nullableElements) {
            this(name, optional, dimensions, readonlyLayers, nullableElements, false, java.util.Collections.nCopies(dimensions, false));
        }
        public TypeRef(String name, boolean optional, int dimensions, List<Boolean> readonlyLayers) {
            this(name, optional, dimensions, readonlyLayers, java.util.Collections.nCopies(dimensions, false));
        }
        public TypeRef(String name, boolean optional, int dimensions) { this(name, optional, dimensions, java.util.Collections.nCopies(dimensions, false)); }
        public TypeRef(String name, boolean optional, boolean array) { this(name, optional, array ? 1 : 0); }
        public boolean array() { return dimensions > 0; }
        public boolean nullish() { return optional || undefined || !array() && (name.equals("null") || name.equals("undefined")); }
        public TypeRef withOptional(boolean value) { return new TypeRef(name, value, dimensions, readonlyLayers, nullableElements, undefined, undefinedElements); }
        public TypeRef withUndefined(boolean value) { return new TypeRef(name, optional, dimensions, readonlyLayers, nullableElements, value, undefinedElements); }
        public TypeRef withName(String value) { return new TypeRef(value, optional, dimensions, readonlyLayers, nullableElements, undefined, undefinedElements); }
        public TypeRef arrayOf(boolean readonly) {
            var layers = new java.util.ArrayList<>(readonlyLayers); layers.add(readonly);
            var elements = new java.util.ArrayList<>(nullableElements); elements.add(optional);
            var missing = new java.util.ArrayList<>(undefinedElements); missing.add(undefined);
            return new TypeRef(name, false, dimensions + 1, layers, elements, false, missing);
        }
        public TypeRef elementType() {
            if (!array()) throw new IllegalStateException("Not an array type");
            return new TypeRef(name, nullableElements.get(dimensions - 1), dimensions - 1,
                    readonlyLayers.subList(0, dimensions - 1), nullableElements.subList(0, dimensions - 1),
                    undefinedElements.get(dimensions - 1), undefinedElements.subList(0, dimensions - 1));
        }
        public String sourceType(String qualifiedName) {
            String result = qualifiedName;
            if (result.contains("=>") && (dimensions > 0 || optional || undefined)) result = "(" + result + ")";
            for (int i = 0; i < dimensions; i++) {
                boolean nullable = nullableElements.get(i) || undefinedElements.get(i);
                if (nullableElements.get(i)) result += " | null";
                if (undefinedElements.get(i)) result += " | undefined";
                result = readonlyLayers.get(i) ? "readonly (" + result + ")[]"
                        : (nullable || result.startsWith("readonly ") ? "(" + result + ")[]" : result + "[]");
            }
            return result + (optional ? " | null" : "") + (undefined ? " | undefined" : "");
        }
    }
    public record Parameter(String name, TypeRef type) {}

    public sealed interface Expr permits Literal, PathExpr, Unary, Binary, Has, Action {
        Span span();
    }
    public record Literal(Object value, String type, Span span) implements Expr {}
    public record PathExpr(List<String> parts, Span span) implements Expr { public PathExpr { parts = List.copyOf(parts); } }
    public record Unary(String operator, Expr operand, Span span) implements Expr {}
    public record Binary(String operator, Expr left, Expr right, Span span) implements Expr {}
    public record Has(PathExpr path, Span span) implements Expr {}
    public record Action(String name, Map<String, Expr> fields, Span span) implements Expr { public Action { fields = immutable(fields); } }

    public sealed interface Node permits Call, When, ForEach {
        Span span();
    }
    public record Call(String name, Map<String, Expr> arguments, List<Node> children, Span childBlock, Span span) implements Node {
        public Call { arguments = immutable(arguments); children = List.copyOf(children); }
    }
    public record When(Expr condition, List<Node> thenNodes, List<Node> elseNodes, Span span) implements Node {
        public When { thenNodes = List.copyOf(thenNodes); elseNodes = List.copyOf(elseNodes); }
    }
    public record ForEach(PathExpr source, Parameter item, PathExpr key, List<Node> children, Span span) implements Node {
        public ForEach { children = List.copyOf(children); }
    }
    public record View(boolean exported, boolean root, String name, List<Parameter> parameters, List<Node> nodes, Span span) {
        public View { parameters = List.copyOf(parameters); nodes = List.copyOf(nodes); }
    }
    public record ViewModule(List<Import> imports, List<View> views, String source) {
        public ViewModule { imports = List.copyOf(imports); views = List.copyOf(views); }
    }

    public record Field(String name, TypeRef type, Expr defaultValue, Span span, boolean optional) {
        public Field(String name, TypeRef type, Expr defaultValue, Span span) { this(name, type, defaultValue, span, false); }
    }
    public record PackClass(String name, List<Field> fields, Span span) { public PackClass { fields = List.copyOf(fields); } }
    public sealed interface Contract permits Children, Parent, Event, Accessibility, TokenProp, Capability {}
    public record Children(boolean required, String componentType) implements Contract {
        public boolean typed() { return componentType != null; }
    }
    public record Parent(String componentType) implements Contract {}
    public record Event(String prop, TypeRef payload) implements Contract {}
    public record Accessibility(String prop) implements Contract {}
    public record TokenProp(String prop) implements Contract {}
    public record Capability(String name) implements Contract {}
    public record Component(String name, String propsType, List<Contract> contracts, Span span) {
        public Component { contracts = List.copyOf(contracts); }
    }
    public record Token(String name, TypeRef type, Expr value, Span span) {}
    public record PackModule(String version, String digest, List<Import> imports, Map<String, PackClass> classes, Map<String, Component> components,
                             Map<String, Token> tokens, String source) {
        public PackModule { classes = immutable(classes); components = immutable(components); tokens = immutable(tokens); }
    }

    public record DealClass(String name, Map<String, Field> fields, boolean exported) { public DealClass { fields = immutable(fields); } }
    public record Handler(String name, String stateType, String actionType, String returnType, String kind, boolean mayStartEffect, Span span) {
        public boolean effect() { return kind.equals("ui-effect"); }
    }
    public record DealFunction(String name, List<TypeRef> parameters, TypeRef returnType, boolean exported, boolean async, Span span) {
        public DealFunction { parameters = List.copyOf(parameters); }
    }
    public record DealModule(Map<String, DealClass> classes, Map<String, DealFunction> functions, List<Handler> handlers, Path source) {
        public DealModule { classes = immutable(classes); functions = immutable(functions); handlers = List.copyOf(handlers); }
    }

    public record ActionBinding(String typeName, Map<String, Expr> fields, Span span) { public ActionBinding { fields = immutable(fields); } }
    public sealed interface RenderNode permits RenderCall, RenderWhen, RenderForEach, RenderScope {}
    public record RenderCall(String name, Map<String, Expr> arguments, List<RenderNode> children, String identity, Span span) implements RenderNode {
        public RenderCall { arguments = immutable(arguments); children = List.copyOf(children); }
    }
    public record RenderWhen(Expr condition, List<RenderNode> thenNodes, List<RenderNode> elseNodes, String identity, Span span) implements RenderNode {
        public RenderWhen { thenNodes = List.copyOf(thenNodes); elseNodes = List.copyOf(elseNodes); }
    }
    public record RenderForEach(PathExpr source, Parameter item, PathExpr key, List<RenderNode> children, String identity, Span span) implements RenderNode {
        public RenderForEach { children = List.copyOf(children); }
    }
    public record RenderScope(Map<String, Expr> bindings, List<RenderNode> children) implements RenderNode {
        public RenderScope { bindings = immutable(bindings); children = List.copyOf(children); }
    }
    public record CheckedMetadata(String rootStateType, List<String> reachableInputActions,
                                  List<String> effectCompletionActions, List<String> usedComponents,
                                  Map<String, String> componentCapabilities,
                                  Map<String, String> packVersions, Map<String, String> packDigests) {
        public CheckedMetadata {
            reachableInputActions = List.copyOf(reachableInputActions);
            effectCompletionActions = List.copyOf(effectCompletionActions);
            usedComponents = List.copyOf(usedComponents);
            componentCapabilities = immutable(componentCapabilities);
            packVersions = immutable(packVersions);
            packDigests = immutable(packDigests);
        }
    }
    public record CheckedProgram(Path viewSource, Path dealSource, String title, String rootStateType,
                                 Map<String, View> views, Map<String, Component> components,
                                 Map<String, PackClass> packClasses, Map<String, Token> tokens,
                                  DealModule deal, List<RenderNode> rootNodes, Map<String, Handler> updates,
                                  Map<String, Handler> effects, Map<String, Handler> effectPolicies,
                                  Map<String, Handler> effectFailures, CheckedMetadata metadata) {
        public CheckedProgram { views = immutable(views); components = immutable(components); packClasses = immutable(packClasses); tokens = immutable(tokens); rootNodes = List.copyOf(rootNodes); updates = immutable(updates); effects = immutable(effects); effectPolicies = immutable(effectPolicies); effectFailures = immutable(effectFailures); }

    }

    private static <K, V> Map<K, V> immutable(Map<K, V> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
