package deal.ui;

import deal.compiler.DealConstruction;
import deal.semantic.ir.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static deal.compiler.CompilerProtocolJson.*;

/** Framework construction calls; no raw source escape hatch. */
public final class CanonicalConstruction extends DealConstruction {
    private final boolean ui;
    public CanonicalConstruction(boolean ui) { this.ui = ui; }
    private static final Set<String> UI_VALUES = Set.of("text", "integer", "boolean", "reference", "path", "field", "binary", "unary");

    @Override protected String value(CanonicalJson.Obj c, String key) {
        var operand = field(c, key);
        if (ui && operand instanceof CanonicalJson.Obj inline && inline.entries().size() == 1
                && inline.entries().getFirst().key().equals("action")) {
            return invoke("action", requireObject(field(inline, "action"), "action")).source();
        }
        return super.value(c, key);
    }

    @Override protected Built invoke(String op, CanonicalJson.Obj c) {
        if (!ui) {
            if (op.equals("declareUpdate")) return new Built(Kind.DECLARATION, "// @ui-update\n" + function(c).source());
            return super.invoke(op, c);
        }
        if (UI_VALUES.contains(op)) return super.invoke(op, c);
        return switch (op) {
            case "action" -> new Built(Kind.VALUE, "action app." + identifier(stringField(c, "name")) + " { " + fields(c, false) + " }");
            case "component" -> {
                String name = identifier(stringField(c, "name"));
                String args = fields(c, false);
                var children = requireArray(field(c, "children"), "children");
                yield new Built(Kind.UI, "ui." + name + "(" + args + ")"
                        + (children.items().isEmpty() ? "" : " {\n" + refs(c, "children", Kind.UI, "\n") + "\n}"));
            }
            case "when" -> new Built(Kind.UI, "When(" + value(c, "condition") + ") {\n"
                    + refs(c, "children", Kind.UI, "\n") + "\n}");
            case "forEach" -> new Built(Kind.UI, "ForEach(" + value(c, "collection") + ", "
                    + identifier(stringField(c, "item")) + ": app." + identifier(stringField(c, "type"))
                    + ", key: " + value(c, "key") + ") {\n" + refs(c, "children", Kind.UI, "\n") + "\n}");
            case "uiBody" -> new Built(Kind.BLOCK, refs(c, "children", Kind.UI, "\n"));
            case "view" -> new Built(Kind.DECLARATION, "// @ui-root\nexport view " + identifier(stringField(c, "name"))
                    + "(state: app." + identifier(stringField(c, "stateType")) + "): View {\n"
                    + get(stringField(c, "body"), Kind.BLOCK).source() + "\n}");
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
            operations.add(callSchema("forEach", Map.of("collection", s, "item", s, "type", s, "key", s, "children", arraySchema(s))));
            operations.add(callSchema("uiBody", Map.of("children", arraySchema(s))));
            operations.add(callSchema("view", Map.of("name", s, "stateType", s, "body", s)));
        }
        return schema(operations);
    }
}
