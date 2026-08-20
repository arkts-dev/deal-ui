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
        line(out, "    java.nio.file.Path file = java.nio.file.Path.of(" + quote(program.parsed().view().span().file().toString()) + ");");
        line(out, "    deal.ui.UiModel.View view = " + view(program.parsed().view()) + ";");
        line(out, "    deal.ui.UiModel.ParsedSource parsed = new deal.ui.UiModel.ParsedSource(\"\", \"\", view);");
        line(out, "    return new deal.ui.UiModel.CheckedProgram(parsed, " + quote(program.actionType()) + ", "
            + fields(program.stateFields()) + ", " + fields(program.actionFields()) + ");");
        line(out, "  }");
        line(out, "  public static void main(java.lang.String[] args) {");
        line(out, "    deal.ui.UiProgramRuntime runtime = new deal.ui.UiProgramRuntime(program(), " + quote(moduleClass) + ");");
        line(out, "    runtime.show();");
        line(out, "  }");
        line(out, "  public static void verifyVisible(java.nio.file.Path evidenceDirectory) {");
        line(out, "    deal.ui.UiProgramRuntime runtime = new deal.ui.UiProgramRuntime(program(), " + quote(moduleClass) + ");");
        line(out, "    try {");
        line(out, "      runtime.show();");
        line(out, "      runtime.capture(evidenceDirectory.resolve(\"museum-collapsed.png\"));");
        line(out, "      runtime.clickButton(\"Toggle details\");");
        line(out, "      if (!java.lang.Boolean.TRUE.equals(runtime.stateSnapshot().get(\"expanded\"))) throw new java.lang.IllegalStateException(\"DEAL update did not commit expanded state\");");
        line(out, "      runtime.capture(evidenceDirectory.resolve(\"museum-expanded.png\"));");
        line(out, "    } finally {");
        line(out, "      runtime.close();");
        line(out, "    }");
        line(out, "  }");
        line(out, "}");
        return out.toString();
    }

    private String view(UiModel.View view) {
        return "new deal.ui.UiModel.View(" + quote(view.name()) + ", " + quote(view.stateParameter()) + ", "
            + quote(view.stateType()) + ", " + nodes(view.children()) + ", " + span(view.span()) + ")";
    }

    private String nodes(Iterable<UiModel.Node> nodes) {
        StringBuilder result = new StringBuilder("java.util.List.of(");
        boolean first = true;
        for (UiModel.Node node : nodes) {
            if (!first) result.append(", ");
            first = false;
            result.append(node(node));
        }
        return result.append(')').toString();
    }

    private String node(UiModel.Node node) {
        if (node instanceof UiModel.Component component) {
            return "new deal.ui.UiModel.Component(" + quote(component.name()) + ", " + expressions(component.props())
                + ", " + nodes(component.children()) + ", " + span(component.span()) + ")";
        }
        UiModel.When when = (UiModel.When) node;
        return "new deal.ui.UiModel.When(" + expression(when.condition()) + ", " + nodes(when.thenChildren())
            + ", " + span(when.span()) + ")";
    }

    private String expressions(Map<String, UiModel.Expression> values) {
        StringBuilder result = new StringBuilder("ordered(");
        boolean first = true;
        for (Map.Entry<String, UiModel.Expression> entry : values.entrySet()) {
            if (!first) result.append(", ");
            first = false;
            result.append("java.util.Map.entry(").append(quote(entry.getKey())).append(", ")
                .append(expression(entry.getValue())).append(')');
        }
        return result.append(')').toString();
    }

    private String expression(UiModel.Expression expression) {
        if (expression instanceof UiModel.StringLiteral literal) {
            return "new deal.ui.UiModel.StringLiteral(" + quote(literal.value()) + ", " + span(literal.span()) + ")";
        }
        if (expression instanceof UiModel.BooleanLiteral literal) {
            return "new deal.ui.UiModel.BooleanLiteral(" + literal.value() + ", " + span(literal.span()) + ")";
        }
        if (expression instanceof UiModel.StatePath path) {
            return "new deal.ui.UiModel.StatePath(" + quote(path.root()) + ", " + quote(path.field()) + ", "
                + span(path.span()) + ")";
        }
        if (expression instanceof UiModel.NotExpression not) {
            return "new deal.ui.UiModel.NotExpression(" + expression(not.operand()) + ", " + span(not.span()) + ")";
        }
        UiModel.ActionLiteral action = (UiModel.ActionLiteral) expression;
        return "new deal.ui.UiModel.ActionLiteral(" + quote(action.typeName()) + ", " + expressions(action.fields())
            + ", " + span(action.span()) + ")";
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

    private void line(StringBuilder out, String value) {
        out.append(value).append('\n');
    }
}
