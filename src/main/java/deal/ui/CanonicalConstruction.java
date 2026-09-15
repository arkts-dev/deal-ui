package deal.ui;

import deal.compiler.DealConstruction;
import deal.compiler.ConstructionProjection;
import deal.semantic.ir.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static deal.compiler.CompilerProtocolJson.*;

/** Framework construction calls; no raw source escape hatch. */
public final class CanonicalConstruction extends DealConstruction {
    private final boolean ui;
    private AstBuilder astBuilder;
    public CanonicalConstruction(boolean ui) { this.ui = ui; }

    public record AstResult(Object root, Map<Object, String> owners) {
        public AstResult {
            owners = java.util.Collections.unmodifiableMap(new java.util.IdentityHashMap<>(owners));
        }
    }

    public AstResult buildAst(CanonicalJson.Obj batch, Kind expected) {
        if (!ui) throw new IllegalStateException("Deal UI AST construction requires the UI frontend");
        astBuilder = new AstBuilder();
        try {
            buildProjection(batch, expected);
            return new AstResult(astBuilder.nodes.get(stringField(batch, "result")), astBuilder.owners);
        } finally { astBuilder = null; }
    }

    /** Checks a constructed root view with the same semantic checker used for parsed source. */
    public AstResult checkViewAst(CanonicalJson.Obj batch, String dealSource, String packSource, String packSpecifier) {
        AstResult ast = buildAst(batch, Kind.DECLARATION);
        if (!(ast.root() instanceof UiModel.View view)) throw new IllegalArgumentException("A view constructor is required");
        var viewFile = java.nio.file.Path.of("/generated/app.dealui");
        var dealFile = java.nio.file.Path.of("/generated/app.deal");
        var imports = List.of(new UiModel.Import("app", "./app.deal", view.span()),
                new UiModel.Import("ui", packSpecifier, view.span()));
        var module = new UiModel.ViewModule(imports, List.of(view), "");
        var checker = new UiChecker();
        var checkedDeal = checker.parseDeal(dealFile, dealSource);
        var pack = UiParser.parsePack(java.nio.file.Path.of("/generated/platform.dealui-pack"), packSource);
        try {
            checker.check(viewFile, module, dealFile, checkedDeal, Map.of(packSpecifier, pack));
        } catch (UiDiagnostic diagnostic) {
            String owner = ast.owners().get(diagnostic.owningNode());
            if (owner == null) throw diagnostic;
            throw new Failure(diagnostic.code(), owner, diagnostic.getMessage(), Map.of(
                    "expected", diagnostic.expected(), "actual", diagnostic.actual(), "artifact", "dealui", "repairScope", "owner"));
        }
        return ast;
    }

    public AstResult checkRootBodyAst(CanonicalJson.Obj batch, String dealSource, String uiSource,
                                     String packSource, String packSpecifier) {
        AstResult ast = buildAst(batch, Kind.BLOCK);
        var viewFile = java.nio.file.Path.of("/generated/app.dealui");
        var dealFile = java.nio.file.Path.of("/generated/app.deal");
        var original = UiParser.parseViews(viewFile, uiSource);
        @SuppressWarnings("unchecked")
        List<UiModel.Node> body = ast.root() instanceof UiModel.Node node
                ? List.of(node) : (List<UiModel.Node>) ast.root();
        var views = original.views().stream().map(view -> !view.root() ? view :
                new UiModel.View(view.exported(), true, view.name(), view.parameters(), body, view.span())).toList();
        var checker = new UiChecker();
        var diagnostics = checker.diagnose(viewFile, new UiModel.ViewModule(original.imports(), views, ""), dealFile,
                    checker.parseDeal(dealFile, dealSource), Map.of(packSpecifier, UiParser.parsePack(
                            java.nio.file.Path.of("/generated/platform.dealui-pack"), packSource)));
        var owned = diagnostics.stream().filter(d -> ast.owners().containsKey(d.owningNode())).toList();
        if (!owned.isEmpty()) {
            var first = owned.getFirst();
            var owners = owned.stream().map(d -> ast.owners().get(d.owningNode())).distinct().toList();
            throw new Failure(first.code(), owners.getFirst(), first.getMessage(), Map.of(
                    "expected", first.expected(), "actual", first.actual(), "artifact", "dealui", "repairScope", "owners",
                    "repairOwners", encode(owners), "diagnostics", encode(owned.stream().map(d -> Map.of(
                            "owner", ast.owners().get(d.owningNode()), "code", d.code(), "message", d.getMessage(),
                            "expected", d.expected(), "actual", d.actual())).toList())));
        }
        return ast;
    }

    @Override protected void resolved(String id, CanonicalJson.Obj call, Built built) {
        if (astBuilder != null) astBuilder.add(id, call);
    }

    private static final class AstBuilder {
        private final Map<String, Object> nodes = new java.util.LinkedHashMap<>();
        private final Map<Object, String> owners = new java.util.IdentityHashMap<>();
        // Synthetic source location is presentation-only; identity comes from AST objects.
        private final UiModel.Span span = new UiModel.Span(java.nio.file.Path.of("/constructed/app.dealui"), 1, 1);

        private UiModel.Expr operand(CanonicalJson.Value value) {
            if (value instanceof CanonicalJson.Str handle) return handle.value().isEmpty()
                    ? new UiModel.Literal("", "string", span) : (UiModel.Expr) nodes.get(handle.value());
            if (value instanceof CanonicalJson.Bool bool) return new UiModel.Literal(bool.value(), "boolean", span);
            if (value instanceof CanonicalJson.Int) return new UiModel.Literal(intField(
                    requireObject(toValue(Map.of("value", value)), "integer"), "value"), "int", span);
            var inline = requireObject(value, "operand");
            var entry = inline.entries().getFirst();
            return switch (entry.key()) {
                case "text" -> new UiModel.Literal(((CanonicalJson.Str) entry.value()).value(), "string", span);
                case "path" -> path(entry.value());
                case "action" -> action(requireObject(entry.value(), "action"));
                case "integer", "boolean" -> operand(entry.value());
                default -> throw new IllegalArgumentException("Unsupported UI operand " + entry.key());
            };
        }
        private UiModel.PathExpr path(CanonicalJson.Value parts) {
            return new UiModel.PathExpr(requireArray(parts, "path").items().stream()
                    .map(value -> ((CanonicalJson.Str) value).value()).toList(), span);
        }
        private UiModel.PathExpr pathOperand(CanonicalJson.Value value) {
            UiModel.Expr expression = operand(value);
            if (!(expression instanceof UiModel.PathExpr path))
                throw new IllegalArgumentException("Deal UI requires a state, item or token path here");
            return path;
        }
        private Map<String, UiModel.Expr> fields(CanonicalJson.Obj call) {
            var result = new java.util.LinkedHashMap<String, UiModel.Expr>();
            for (var item : requireArray(field(call, "fields"), "fields").items()) {
                var f = requireObject(item, "field");
                result.put(stringField(f, "name"), operand(field(f, "value")));
            }
            return result;
        }
        private UiModel.Action action(CanonicalJson.Obj call) {
            return new UiModel.Action("app." + stringField(call, "name"), fields(call), span);
        }
        private List<UiModel.Node> children(CanonicalJson.Obj call) {
            return requireArray(field(call, "children"), "children").items().stream()
                    .map(value -> (UiModel.Node) nodes.get(((CanonicalJson.Str) value).value())).toList();
        }
        @SuppressWarnings("unchecked")
        private void add(String id, CanonicalJson.Obj call) {
            Object node = switch (stringField(call, "op")) {
                case "text" -> new UiModel.Literal(stringField(call, "value"), "string", span);
                case "integer" -> new UiModel.Literal(intField(call, "value"), "int", span);
                case "boolean" -> operand(field(call, "value"));
                case "reference" -> new UiModel.PathExpr(List.of(stringField(call, "name")), span);
                case "path" -> path(field(call, "parts"));
                case "field" -> {
                    var parent = pathOperand(field(call, "object"));
                    var parts = new ArrayList<>(parent.parts()); parts.add(stringField(call, "name"));
                    yield new UiModel.PathExpr(parts, span);
                }
                case "binary" -> new UiModel.Binary(stringField(call, "operator"), operand(field(call, "left")), operand(field(call, "right")), span);
                case "unary" -> new UiModel.Unary(stringField(call, "operator"), operand(field(call, "value")), span);
                case "action" -> action(call);
                case "component" -> new UiModel.Call("ui." + stringField(call, "name"), fields(call), children(call),
                        children(call).isEmpty() ? null : span, span);
                case "when" -> new UiModel.When(operand(field(call, "condition")), children(call), List.of(), span);
                case "forEach" -> new UiModel.ForEach(pathOperand(field(call, "collection")),
                        new UiModel.Parameter(stringField(call, "item"), new UiModel.TypeRef("app." + stringField(call, "type"), false, false)),
                        pathOperand(field(call, "key")), children(call), span);
                case "uiBody" -> children(call);
                case "view" -> {
                    Object body = nodes.get(stringField(call, "body"));
                    yield new UiModel.View(true, true, stringField(call, "name"),
                            List.of(new UiModel.Parameter("state", new UiModel.TypeRef("app." + stringField(call, "stateType"), false, false))),
                            body instanceof UiModel.Node single ? List.of(single) : (List<UiModel.Node>) body, span);
                }
                default -> throw new IllegalArgumentException("Unsupported UI AST constructor");
            };
            nodes.put(id, node);
            owners.put(node, id);
        }
    }
    private static final Set<String> UI_VALUES = Set.of("text", "integer", "boolean", "reference", "path", "field", "binary", "unary");

    @Override protected ConstructionProjection.Fragment value(CanonicalJson.Obj c, String key) {
        var operand = field(c, key);
        if (ui && operand instanceof CanonicalJson.Obj inline && inline.entries().size() == 1
                && inline.entries().getFirst().key().equals("action")) {
            return invoke("action", requireObject(field(inline, "action"), "action")).fragment();
        }
        return super.value(c, key);
    }

    @Override protected Built invoke(String op, CanonicalJson.Obj c) {
        if (!ui) {
            if (op.equals("declareUpdate")) return emit(Kind.DECLARATION, "// @ui-update\n", function(c).fragment());
            return super.invoke(op, c);
        }
        if (UI_VALUES.contains(op)) return super.invoke(op, c);
        return switch (op) {
            case "action" -> emit(Kind.VALUE, "action app." + identifier(stringField(c, "name")) + " { ", fields(c, false), " }");
            case "component" -> {
                String name = identifier(stringField(c, "name"));
                var args = fields(c, false);
                var children = requireArray(field(c, "children"), "children");
                yield emit(Kind.UI, "ui." + name + "(", args, ")",
                        children.items().isEmpty() ? new ConstructionProjection.Text("")
                                : ConstructionProjection.concat(" {\n", refs(c, "children", Kind.UI, "\n"), "\n}"));
            }
            case "when" -> emit(Kind.UI, "When(", value(c, "condition"), ") {\n",
                    refs(c, "children", Kind.UI, "\n"), "\n}");
            case "forEach" -> emit(Kind.UI, "ForEach(", value(c, "collection"), ", "
                    + identifier(stringField(c, "item")) + ": app." + identifier(stringField(c, "type"))
                    + ", key: ", value(c, "key"), ") {\n", refs(c, "children", Kind.UI, "\n"), "\n}");
            case "uiBody" -> new Built(Kind.BLOCK, refs(c, "children", Kind.UI, "\n"));
            case "view" -> emit(Kind.DECLARATION, "// @ui-root\nexport view " + identifier(stringField(c, "name"))
                    + "(state: app." + identifier(stringField(c, "stateType")) + "): View {\n",
                    get(stringField(c, "body"), Kind.BLOCK).fragment(), "\n}");
            default -> throw new IllegalArgumentException("operation unavailable in Deal UI: " + op);
        };
    }

    public static Map<String, Object> contract(boolean ui) {
        var operations = new ArrayList<Map<String, Object>>(operationSchemas().stream().filter(schema -> {
            var props = (Map<?, ?>) schema.get("properties");
            String op = (String) ((Map<?, ?>) props.get("op")).get("const");
            return !ui || UI_VALUES.contains(op);
        }).toList());
        var s = textSchema();
        var fields = arraySchema(objectSchema(Map.of("name", s, "value", operandSchema())));
        if (!ui) operations.add(callSchema("declareUpdate", functionSchema()));
        else {
            var componentOperands = new ArrayList<Map<String, Object>>();
            componentOperands.add(operandSchema());
            componentOperands.add(objectSchema(Map.of("action", objectSchema(Map.of("name", s, "fields", fields)))));
            var componentFields = arraySchema(objectSchema(Map.of("name", s, "value", Map.of("anyOf", componentOperands))));
            operations.add(callSchema("action", Map.of("name", s, "fields", fields)));
            operations.add(callSchema("component", Map.of("name", s, "fields", componentFields, "children", arraySchema(s))));
            operations.add(callSchema("when", Map.of("condition", operandSchema(), "children", arraySchema(s))));
            var pathOperand = Map.of("anyOf", List.of(s, objectSchema(Map.of("path", arraySchema(s)))));
            operations.add(callSchema("forEach", Map.of("collection", pathOperand, "item", s, "type", s, "key", pathOperand, "children", arraySchema(s))));
            operations.add(callSchema("uiBody", Map.of("children", arraySchema(s))));
            operations.add(callSchema("view", Map.of("name", s, "stateType", s, "body", s)));
        }
        return schema(operations);
    }

    /** Body results express scalar leaves inline; the full contract remains available for value edits. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> bodyContract() {
        var full = contract(true);
        var calls = (Map<?, ?>) ((Map<?, ?>) full.get("properties")).get("calls");
        var items = (Map<?, ?>) calls.get("items");
        var kinds = Set.of("binary", "unary", "action", "component", "when", "forEach", "uiBody");
        var operations = ((List<?>) items.get("anyOf")).stream().map(raw -> (Map<String, Object>) raw)
                .filter(operation -> kinds.contains(((Map<?, ?>) ((Map<?, ?>) operation.get("properties")).get("op")).get("const")))
                .toList();
        return schema(operations);
    }
}
