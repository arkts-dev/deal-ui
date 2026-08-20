package deal.ui;

import java.util.Map;

public final class UiJavaGenerator {
    public String generate(UiModel.CheckedProgram program, String moduleClass, String uiClass) {
        StringBuilder out = new StringBuilder();
        line(out, "public final class " + uiClass + " {");
        line(out, "  private " + uiClass + "() {}");
        line(out, "  @SafeVarargs");
        line(out, "  private static <K, V> java.util.Map<K, V> ordered(java.util.Map.Entry<K, V>... entries) {");
        line(out, "    java.util.Map<K, V> result = new java.util.LinkedHashMap<>();");
        line(out, "    for (java.util.Map.Entry<K, V> entry : entries) result.put(entry.getKey(), entry.getValue());");
        line(out, "    return java.util.Collections.unmodifiableMap(result);");
        line(out, "  }");
        line(out, "  public static deal.ui.UiModel.CheckedProgram program() {");
        line(out, "    java.nio.file.Path file = java.nio.file.Path.of(" + quote(program.ir().span().file().toString()) + ");");
        line(out, "    deal.ui.UiModel.IrView ir = " + view(program.ir()) + ";");
        line(out, "    return new deal.ui.UiModel.CheckedProgram(null, ir, " + quote(program.actionType()) + ", "
            + fields(program.stateFields()) + ", " + fields(program.actionFields()) + ");");
        line(out, "  }");
        line(out, "  public static void main(java.lang.String[] args) {");
        line(out, "    deal.ui.UiProgramRuntime runtime = new deal.ui.UiProgramRuntime(program(), " + quote(moduleClass) + ");");
        line(out, "    runtime.show();");
        line(out, "  }");
        line(out, "}");
        return out.toString();
    }

    private String view(UiModel.IrView view) {
        return "new deal.ui.UiModel.IrView(" + quote(view.name()) + ", " + quote(view.stateType()) + ", "
            + nodes(view.children()) + ", " + span(view.span()) + ")";
    }

    private String nodes(Iterable<UiModel.IrNode> nodes) {
        StringBuilder result = new StringBuilder("java.util.List.of(");
        boolean first = true;
        for (UiModel.IrNode node : nodes) {
            if (!first) result.append(", ");
            first = false;
            result.append(node(node));
        }
        return result.append(')').toString();
    }

    private String node(UiModel.IrNode node) {
        if (node instanceof UiModel.IrComponent component) {
            return "new deal.ui.UiModel.IrComponent(" + quote(component.name()) + ", "
                + expressions(component.props()) + ", " + nodes(component.children()) + ", "
                + span(component.span()) + ")";
        }
        UiModel.IrWhen when = (UiModel.IrWhen) node;
        return "new deal.ui.UiModel.IrWhen(" + expression(when.condition()) + ", " + nodes(when.children())
            + ", " + span(when.span()) + ")";
    }

    private String expressions(Map<String, UiModel.IrExpression> values) {
        StringBuilder result = new StringBuilder("ordered(");
        boolean first = true;
        for (Map.Entry<String, UiModel.IrExpression> entry : values.entrySet()) {
            if (!first) result.append(", ");
            first = false;
            result.append("java.util.Map.entry(").append(quote(entry.getKey())).append(", ")
                .append(expression(entry.getValue())).append(')');
        }
        return result.append(')').toString();
    }

    private String expression(UiModel.IrExpression expression) {
        if (expression instanceof UiModel.IrString literal) {
            return "new deal.ui.UiModel.IrString(" + quote(literal.value()) + ", " + span(literal.span()) + ")";
        }
        if (expression instanceof UiModel.IrBoolean literal) {
            return "new deal.ui.UiModel.IrBoolean(" + literal.value() + ", " + span(literal.span()) + ")";
        }
        if (expression instanceof UiModel.IrStateField field) {
            return "new deal.ui.UiModel.IrStateField(" + quote(field.field()) + ", deal.ui.UiModel.ValueType."
                + field.type().name() + ", " + span(field.span()) + ")";
        }
        if (expression instanceof UiModel.IrNot not) {
            return "new deal.ui.UiModel.IrNot(" + expression(not.operand()) + ", " + span(not.span()) + ")";
        }
        UiModel.IrAction action = (UiModel.IrAction) expression;
        return "new deal.ui.UiModel.IrAction(" + quote(action.typeName()) + ", " + span(action.span()) + ")";
    }

    private String fields(Map<String, UiModel.FieldInfo> fields) {
        StringBuilder result = new StringBuilder("ordered(");
        boolean first = true;
        for (Map.Entry<String, UiModel.FieldInfo> field : fields.entrySet()) {
            if (!first) result.append(", ");
            first = false;
            result.append("java.util.Map.entry(").append(quote(field.getKey()))
                .append(", new deal.ui.UiModel.FieldInfo(deal.ui.UiModel.ValueType.")
                .append(field.getValue().type().name()).append(", ")
                .append(value(field.getValue().defaultValue())).append("))");
        }
        return result.append(')').toString();
    }

    private String span(UiModel.SourceSpan span) {
        return "new deal.ui.UiModel.SourceSpan(file, " + span.startLine() + ", " + span.startColumn() + ", "
            + span.endLine() + ", " + span.endColumn() + ")";
    }

    private String value(Object value) {
        if (value instanceof String string) return quote(string);
        if (value instanceof Boolean bool) return bool.toString();
        throw new IllegalArgumentException("Unsupported generated field default: " + value);
    }

    private String quote(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return '"' + escaped + '"';
    }

    private void line(StringBuilder out, String value) { out.append(value).append('\n'); }
}
