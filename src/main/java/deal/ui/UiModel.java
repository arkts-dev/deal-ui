package deal.ui;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiModel {
    private UiModel() {}

    public record SourceSpan(Path file, int startLine, int startColumn, int endLine, int endColumn) {
        @Override
        public String toString() {
            return file + ":" + startLine + ":" + startColumn;
        }
    }

    public sealed interface Expression permits StringLiteral, BooleanLiteral, StatePath, NotExpression, ActionLiteral {
        SourceSpan span();
    }

    public record StringLiteral(String value, SourceSpan span) implements Expression {}
    public record BooleanLiteral(boolean value, SourceSpan span) implements Expression {}
    public record StatePath(String root, String field, SourceSpan span) implements Expression {}
    public record NotExpression(Expression operand, SourceSpan span) implements Expression {}
    public record ActionLiteral(String typeName, Map<String, Expression> fields, SourceSpan span) implements Expression {
        public ActionLiteral {
            fields = immutableMap(fields);
        }
    }

    public sealed interface Node permits Component, When {
        SourceSpan span();
    }

    public record Component(String name, Map<String, Expression> props, List<Node> children, SourceSpan span) implements Node {
        public Component {
            props = immutableMap(props);
            children = List.copyOf(children);
        }
    }

    public record When(Expression condition, List<Node> thenChildren, SourceSpan span) implements Node {
        public When {
            thenChildren = List.copyOf(thenChildren);
        }
    }

    public record View(String name, String stateParameter, String stateType, List<Node> children, SourceSpan span) {
        public View {
            children = List.copyOf(children);
        }
    }

    public record ParsedSource(String originalSource, String dealSource, View view) {}

    public enum ValueType {
        STRING,
        BOOLEAN,
        ACTION
    }

    public record TypedExpression(Expression expression, ValueType type) {}
    public record FieldInfo(ValueType type, Object defaultValue) {}

    public record CheckedProgram(ParsedSource parsed, String actionType, Map<String, FieldInfo> stateFields,
                                 Map<String, FieldInfo> actionFields) {
        public CheckedProgram {
            stateFields = immutableMap(stateFields);
            actionFields = immutableMap(actionFields);
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
