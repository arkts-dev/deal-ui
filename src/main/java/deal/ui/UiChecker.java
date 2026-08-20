package deal.ui;

import deal.ast.ClassDeclaration;
import deal.ast.ClassField;
import deal.ast.ExportDeclaration;
import deal.ast.FunctionDeclaration;
import deal.ast.NamedType;
import deal.ast.Parameter;
import deal.ast.ProgramNode;
import deal.ast.StatementNode;
import deal.ast.TypeNode;
import deal.lexer.Diagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.module.ModuleShapeValidator;
import deal.parser.ParseResult;
import deal.parser.Parser;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiChecker {
    public UiModel.CheckedProgram check(Path sourceFile, UiModel.ParsedSource parsed) {
        ProgramNode program = parseDeal(sourceFile, parsed.dealSource());
        ClassDeclaration stateClass = null;
        Map<String, ClassDeclaration> classes = new LinkedHashMap<>();
        Map<String, FunctionDeclaration> functions = new LinkedHashMap<>();
        java.util.Set<String> exportedFunctions = new java.util.LinkedHashSet<>();
        for (StatementNode statement : program.statements()) {
            if (statement instanceof ExportDeclaration exported
                    && exported.declaration() instanceof FunctionDeclaration function) {
                exportedFunctions.add(function.name());
            }
            StatementNode declaration = statement instanceof ExportDeclaration exported
                ? exported.declaration() : statement;
            if (declaration instanceof ClassDeclaration classDeclaration) {
                classes.put(classDeclaration.name(), classDeclaration);
            } else if (declaration instanceof FunctionDeclaration functionDeclaration) {
                functions.put(functionDeclaration.name(), functionDeclaration);
            }
        }
        stateClass = classes.get(parsed.view().stateType());
        if (stateClass == null) {
            throw diagnostic("UI2001", "Root state type '" + parsed.view().stateType()
                + "' must be a class declared in this module", parsed.view().span());
        }
        Map<String, UiModel.FieldInfo> stateFieldInfo = primitiveFields(stateClass, "root state");
        Map<String, UiModel.ValueType> stateFields = fieldTypes(stateFieldInfo);
        LinkedHashMap<String, ClassDeclaration> referencedActions = new LinkedHashMap<>();
        checkNodes(parsed.view().children(), parsed.view(), stateFields, classes, referencedActions);
        if (referencedActions.size() != 1) {
            throw diagnostic("UI2002", "The preview requires exactly one referenced action type, found "
                + referencedActions.size(), parsed.view().span());
        }
        Map.Entry<String, ClassDeclaration> actionEntry = referencedActions.entrySet().iterator().next();
        Map<String, UiModel.FieldInfo> actionFieldInfo = primitiveFields(actionEntry.getValue(), "action");
        if (!actionFieldInfo.isEmpty()) {
            throw diagnostic("UI2022", "The single-action preview requires an action class with no fields",
                sourceSpan(actionEntry.getValue().span()));
        }
        Map<String, UiModel.ValueType> actionFields = fieldTypes(actionFieldInfo);
        validateActions(parsed.view().children(), parsed.view(), actionEntry.getKey(), actionFields, stateFields);
        if (parsed.view().children().size() != 1
                || !(parsed.view().children().get(0) instanceof UiModel.Component)) {
            throw diagnostic("UI2023", "Root view must contain exactly one root component",
                parsed.view().span());
        }
        validateUpdate(functions.get("update"), exportedFunctions.contains("update"), parsed.view().stateType(),
            actionEntry.getKey(), parsed.view().span());
        return new UiModel.CheckedProgram(parsed, actionEntry.getKey(), stateFieldInfo, actionFieldInfo);
    }

    private ProgramNode parseDeal(Path sourceFile, String source) {
        LexResult lexed = new Lexer(source, sourceFile.toString()).tokenize();
        throwFirstDiagnostic(lexed.diagnostics());
        ParseResult parsed = new Parser(lexed.tokens(), sourceFile.toString()).parse();
        throwFirstDiagnostic(parsed.diagnostics());
        List<Diagnostic> shapeDiagnostics = ModuleShapeValidator.validate(parsed.program(), sourceFile.toString(), false);
        throwFirstDiagnostic(shapeDiagnostics);
        return parsed.program();
    }

    private void checkNodes(List<UiModel.Node> nodes, UiModel.View view,
                            Map<String, UiModel.ValueType> stateFields,
                            Map<String, ClassDeclaration> classes,
                            Map<String, ClassDeclaration> actions) {
        for (UiModel.Node node : nodes) {
            if (node instanceof UiModel.Component component) {
                ComponentSpec spec = ComponentSpec.forName(component.name());
                if (spec == null) {
                    throw diagnostic("UI2003", "Unknown component '" + component.name() + "'", component.span());
                }
                for (String prop : component.props().keySet()) {
                    if (!spec.props.containsKey(prop)) {
                        throw diagnostic("UI2004", "Unknown property '" + prop + "' on " + component.name(),
                            component.props().get(prop).span());
                    }
                }
                for (Map.Entry<String, UiModel.ValueType> required : spec.props.entrySet()) {
                    UiModel.Expression value = component.props().get(required.getKey());
                    if (value == null) {
                        throw diagnostic("UI2005", "Missing required property '" + required.getKey()
                            + "' on " + component.name(), component.span());
                    }
                    UiModel.ValueType actual = expressionType(value, view, stateFields);
                    if (actual != required.getValue()) {
                        throw diagnostic("UI2006", "Property '" + required.getKey() + "' on " + component.name()
                            + " expects " + display(required.getValue()) + ", got " + display(actual), value.span());
                    }
                    if (value instanceof UiModel.ActionLiteral action) {
                        ClassDeclaration actionClass = classes.get(action.typeName());
                        if (actionClass == null) {
                            throw diagnostic("UI2007", "Action type '" + action.typeName()
                                + "' must be a class declared in this module", action.span());
                        }
                        actions.put(action.typeName(), actionClass);
                    }
                }
                if (!spec.children && !component.children().isEmpty()) {
                    throw diagnostic("UI2008", component.name() + " does not accept children", component.span());
                }
                checkNodes(component.children(), view, stateFields, classes, actions);
            } else if (node instanceof UiModel.When when) {
                UiModel.ValueType condition = expressionType(when.condition(), view, stateFields);
                if (condition != UiModel.ValueType.BOOLEAN) {
                    throw diagnostic("UI2009", "When condition must be boolean", when.condition().span());
                }
                checkNodes(when.thenChildren(), view, stateFields, classes, actions);
            }
        }
    }

    private void validateActions(List<UiModel.Node> nodes, UiModel.View view, String actionType,
                                 Map<String, UiModel.ValueType> actionFields,
                                 Map<String, UiModel.ValueType> stateFields) {
        for (UiModel.Node node : nodes) {
            if (node instanceof UiModel.Component component) {
                for (UiModel.Expression expression : component.props().values()) {
                    if (expression instanceof UiModel.ActionLiteral action) {
                        if (!action.typeName().equals(actionType)) {
                            throw diagnostic("UI2010", "Only action type '" + actionType + "' is allowed",
                                action.span());
                        }
                        for (String name : action.fields().keySet()) {
                            if (!actionFields.containsKey(name)) {
                                throw diagnostic("UI2011", "Unknown action field '" + name + "'", action.span());
                            }
                        }
                        for (Map.Entry<String, UiModel.ValueType> field : actionFields.entrySet()) {
                            UiModel.Expression value = action.fields().get(field.getKey());
                            if (value == null) {
                                throw diagnostic("UI2012", "Missing action field '" + field.getKey() + "'",
                                    action.span());
                            }
                            UiModel.ValueType actual = expressionType(value, view, stateFields);
                            if (actual != field.getValue()) {
                                throw diagnostic("UI2013", "Action field '" + field.getKey() + "' expects "
                                    + display(field.getValue()) + ", got " + display(actual), value.span());
                            }
                        }
                    }
                }
                validateActions(component.children(), view, actionType, actionFields, stateFields);
            } else if (node instanceof UiModel.When when) {
                validateActions(when.thenChildren(), view, actionType, actionFields, stateFields);
            }
        }
    }

    private UiModel.ValueType expressionType(UiModel.Expression expression, UiModel.View view,
                                              Map<String, UiModel.ValueType> stateFields) {
        if (expression instanceof UiModel.StringLiteral) {
            return UiModel.ValueType.STRING;
        }
        if (expression instanceof UiModel.BooleanLiteral) {
            return UiModel.ValueType.BOOLEAN;
        }
        if (expression instanceof UiModel.ActionLiteral) {
            return UiModel.ValueType.ACTION;
        }
        if (expression instanceof UiModel.StatePath path) {
            if (!path.root().equals(view.stateParameter())) {
                throw diagnostic("UI2014", "State path must start with root parameter '"
                    + view.stateParameter() + "'", path.span());
            }
            UiModel.ValueType type = stateFields.get(path.field());
            if (type == null) {
                throw diagnostic("UI2015", "Unknown state field '" + path.field() + "'", path.span());
            }
            return type;
        }
        if (expression instanceof UiModel.NotExpression not) {
            UiModel.ValueType operand = expressionType(not.operand(), view, stateFields);
            if (operand != UiModel.ValueType.BOOLEAN) {
                throw diagnostic("UI2016", "Operator ! requires a boolean operand", not.span());
            }
            return UiModel.ValueType.BOOLEAN;
        }
        throw diagnostic("UI2099", "Unsupported UI expression", expression.span());
    }

    private Map<String, UiModel.FieldInfo> primitiveFields(ClassDeclaration declaration, String role) {
        Map<String, UiModel.FieldInfo> result = new LinkedHashMap<>();
        for (ClassField field : declaration.fields()) {
            if (field.optional() || field.nullable()) {
                throw diagnostic("UI2017", "The preview does not support optional or nullable " + role
                    + " field '" + field.name() + "'", sourceSpan(field.span()));
            }
            UiModel.ValueType type = valueType(field.type());
            if (type == null) {
                throw diagnostic("UI2018", "The preview supports only string and boolean " + role
                    + " fields; '" + field.name() + "' has type " + typeName(field.type()), sourceSpan(field.span()));
            }
            Object defaultValue = field.defaultExpr().map(expression -> {
                if (expression instanceof deal.ast.LiteralExpr literal) {
                    if (literal.value() instanceof deal.ast.LiteralValue.StringLiteral string) {
                        return string.value();
                    }
                    if (literal.value() instanceof deal.ast.LiteralValue.BooleanLiteral bool) {
                        return bool.value();
                    }
                }
                throw diagnostic("UI2021", "The preview requires literal defaults for " + role
                    + " field '" + field.name() + "'", sourceSpan(field.span()));
            }).orElseThrow(() -> diagnostic("UI2021", "The preview requires a default for " + role
                + " field '" + field.name() + "'", sourceSpan(field.span())));
            result.put(field.name(), new UiModel.FieldInfo(type, defaultValue));
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private Map<String, UiModel.ValueType> fieldTypes(Map<String, UiModel.FieldInfo> fields) {
        Map<String, UiModel.ValueType> result = new LinkedHashMap<>();
        for (Map.Entry<String, UiModel.FieldInfo> entry : fields.entrySet()) {
            result.put(entry.getKey(), entry.getValue().type());
        }
        return Map.copyOf(result);
    }

    private void validateUpdate(FunctionDeclaration update, boolean exported, String stateType, String actionType,
                                UiModel.SourceSpan span) {
        if (update == null || !exported) {
            throw diagnostic("UI2019", "An exported update(state: " + stateType + ", action: "
                + actionType + "): " + stateType + " function is required", span);
        }
        if (update.isAsync() || update.params().size() != 2
                || !typeName(update.params().get(0).type()).equals(stateType)
                || !typeName(update.params().get(1).type()).equals(actionType)
                || !typeName(update.returnType()).equals(stateType)) {
            throw diagnostic("UI2020", "update must have non-async signature (" + stateType + ", "
                + actionType + ") => " + stateType, sourceSpan(update.span()));
        }
    }

    private UiModel.ValueType valueType(TypeNode type) {
        String name = typeName(type);
        return switch (name) {
            case "string" -> UiModel.ValueType.STRING;
            case "boolean" -> UiModel.ValueType.BOOLEAN;
            default -> null;
        };
    }

    private String typeName(TypeNode type) {
        return type instanceof NamedType named ? named.name() : type.toString();
    }

    private void throwFirstDiagnostic(List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity().equals("error")) {
                throw new UiDiagnostic(diagnostic.code(), diagnostic.message(), Path.of(diagnostic.file()),
                    diagnostic.line(), diagnostic.column());
            }
        }
    }

    private UiDiagnostic diagnostic(String code, String message, UiModel.SourceSpan span) {
        return new UiDiagnostic(code, message, span);
    }

    private UiModel.SourceSpan sourceSpan(deal.ast.Span span) {
        return new UiModel.SourceSpan(Path.of(span.file()), span.startLine(), span.startColumn(),
            span.endLine(), span.endColumn());
    }

    private String display(UiModel.ValueType type) {
        return type.name().toLowerCase();
    }

    private record ComponentSpec(Map<String, UiModel.ValueType> props, boolean children) {
        private static ComponentSpec forName(String name) {
            return switch (name) {
                case "Card", "Column" -> new ComponentSpec(Map.of(), true);
                case "Text" -> new ComponentSpec(Map.of("value", UiModel.ValueType.STRING), false);
                case "Button" -> new ComponentSpec(Map.of(
                    "text", UiModel.ValueType.STRING,
                    "onClick", UiModel.ValueType.ACTION), false);
                default -> null;
            };
        }
    }
}
