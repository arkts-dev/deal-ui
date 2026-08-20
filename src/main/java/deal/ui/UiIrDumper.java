package deal.ui;

import java.util.Map;

public final class UiIrDumper {
    public String dump(UiModel.CheckedProgram program) {
        StringBuilder result = new StringBuilder();
        UiModel.IrView view = program.ir();
        result.append("ui-module ").append(view.span().file()).append('\n');
        result.append("root view ").append(view.name()).append("(state: ")
            .append(view.stateType()).append("): View @").append(location(view.span())).append('\n');
        appendNodes(result, view.children(), 1);
        result.append("action ").append(program.actionType()).append('\n');
        result.append("update (").append(view.stateType()).append(", ")
            .append(program.actionType()).append(") -> ").append(view.stateType()).append('\n');
        return result.toString();
    }

    private void appendNodes(StringBuilder result, Iterable<UiModel.IrNode> nodes, int depth) {
        for (UiModel.IrNode node : nodes) {
            indent(result, depth);
            if (node instanceof UiModel.IrComponent component) {
                result.append("component ").append(component.name()).append(" @")
                    .append(location(component.span())).append('\n');
                for (Map.Entry<String, UiModel.IrExpression> prop : component.props().entrySet()) {
                    indent(result, depth + 1);
                    result.append("prop ").append(prop.getKey()).append(": ")
                        .append(prop.getValue().type().name().toLowerCase()).append(" = ")
                        .append(expression(prop.getValue())).append(" @")
                        .append(location(prop.getValue().span())).append('\n');
                }
                appendNodes(result, component.children(), depth + 1);
            } else if (node instanceof UiModel.IrWhen when) {
                result.append("when boolean = ").append(expression(when.condition())).append(" @")
                    .append(location(when.span())).append('\n');
                appendNodes(result, when.children(), depth + 1);
            }
        }
    }

    private String expression(UiModel.IrExpression expression) {
        if (expression instanceof UiModel.IrString literal) {
            return "string(" + quote(literal.value()) + ")";
        }
        if (expression instanceof UiModel.IrBoolean literal) {
            return "boolean(" + literal.value() + ")";
        }
        if (expression instanceof UiModel.IrStateField field) {
            return "state-field(" + field.field() + ")";
        }
        if (expression instanceof UiModel.IrNot not) {
            return "not(" + expression(not.operand()) + ")";
        }
        return "action(" + ((UiModel.IrAction) expression).typeName() + ")";
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
