package deal.ui;

import java.util.Map;

public final class UiIrDumper {
    public String dump(UiModel.CheckedProgram program) {
        StringBuilder result = new StringBuilder();
        UiModel.View view = program.parsed().view();
        result.append("ui-module ").append(view.span().file()).append('\n');
        result.append("root view ").append(view.name()).append('(')
            .append(view.stateParameter()).append(": ").append(view.stateType())
            .append("): View @").append(location(view.span())).append('\n');
        appendNodes(result, view.children(), 1, program);
        result.append("action ").append(program.actionType()).append('\n');
        result.append("update (").append(view.stateType()).append(", ")
            .append(program.actionType()).append(") -> ").append(view.stateType()).append('\n');
        return result.toString();
    }

    private void appendNodes(StringBuilder result, Iterable<UiModel.Node> nodes, int depth,
                             UiModel.CheckedProgram program) {
        for (UiModel.Node node : nodes) {
            indent(result, depth);
            if (node instanceof UiModel.Component component) {
                result.append("component ").append(component.name()).append(" @")
                    .append(location(component.span())).append('\n');
                for (Map.Entry<String, UiModel.Expression> prop : component.props().entrySet()) {
                    indent(result, depth + 1);
                    result.append("prop ").append(prop.getKey()).append(" = ")
                        .append(expression(prop.getValue())).append(" @")
                        .append(location(prop.getValue().span())).append('\n');
                }
                appendNodes(result, component.children(), depth + 1, program);
            } else if (node instanceof UiModel.When when) {
                result.append("when ").append(expression(when.condition())).append(" @")
                    .append(location(when.span())).append('\n');
                appendNodes(result, when.thenChildren(), depth + 1, program);
            }
        }
    }

    private String expression(UiModel.Expression expression) {
        if (expression instanceof UiModel.StringLiteral literal) {
            return "string(" + quote(literal.value()) + ")";
        }
        if (expression instanceof UiModel.BooleanLiteral literal) {
            return "boolean(" + literal.value() + ")";
        }
        if (expression instanceof UiModel.StatePath path) {
            return "state-path(" + path.root() + "." + path.field() + ")";
        }
        if (expression instanceof UiModel.NotExpression not) {
            return "not(" + expression(not.operand()) + ")";
        }
        UiModel.ActionLiteral action = (UiModel.ActionLiteral) expression;
        StringBuilder result = new StringBuilder("action(").append(action.typeName());
        for (Map.Entry<String, UiModel.Expression> field : action.fields().entrySet()) {
            result.append(", ").append(field.getKey()).append('=').append(expression(field.getValue()));
        }
        return result.append(')').toString();
    }

    private void indent(StringBuilder result, int depth) {
        result.append("  ".repeat(depth));
    }

    private String location(UiModel.SourceSpan span) {
        return span.startLine() + ":" + span.startColumn() + "-" + span.endLine() + ":" + span.endColumn();
    }

    private String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
