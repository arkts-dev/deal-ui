package deal.ui;

import deal.ast.ArrayLiteralExpr;
import deal.ast.ArrayType;
import deal.ast.AssignmentExpr;
import deal.ast.AwaitExpression;
import deal.ast.BinaryExpr;
import deal.ast.Block;
import deal.ast.CallExpr;
import deal.ast.ClassDeclaration;
import deal.ast.DeleteStatement;
import deal.ast.Either;
import deal.ast.ExpressionNode;
import deal.ast.ExpressionStatement;
import deal.ast.ForInit;
import deal.ast.ForOfStatement;
import deal.ast.ForStatement;
import deal.ast.FunctionDeclaration;
import deal.ast.FunctionExpr;
import deal.ast.FunctionType;
import deal.ast.HasExpr;
import deal.ast.IdentifierExpr;
import deal.ast.IfStatement;
import deal.ast.IndexExpr;
import deal.ast.LiteralExpr;
import deal.ast.MemberAccessExpr;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.ObjectLiteralExpr;
import deal.ast.Parameter;
import deal.ast.QualifiedType;
import deal.ast.ReturnStatement;
import deal.ast.Span;
import deal.ast.StatementNode;
import deal.ast.TemplateLiteralExpr;
import deal.ast.ThrowStatement;
import deal.ast.TryStatement;
import deal.ast.TypeNode;
import deal.ast.UnaryExpr;
import deal.ast.VariableDeclaration;
import deal.ast.WhileStatement;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enforces the immutable-borrow contract at Deal UI handler boundaries. */
final class UiBorrowedValueChecker {
    private record Shape(String name, int dimensions, boolean unknown) {
        boolean reference(Map<String, ClassDeclaration> classes) {
            return unknown || dimensions > 0 || classes.containsKey(simple(name)) || name.equals("table") || name.equals("bytes") || name.equals("function");
        }
    }

    private record Origin(Set<Integer> direct, Set<Integer> nested) {
        private static final Origin NONE = new Origin(Set.of(), Set.of());
        static Origin borrowed(int parameter) { return new Origin(Set.of(parameter), Set.of(parameter)); }
        Set<Integer> all() { Set<Integer> result = new LinkedHashSet<>(direct); result.addAll(nested); return result; }
        Origin merge(Origin other) { return new Origin(union(direct, other.direct), union(nested, other.nested)); }
        Origin member(boolean reference) { return reference ? new Origin(all(), all()) : NONE; }
        Origin element(boolean reference) { return reference ? new Origin(direct.isEmpty() ? nested : direct, nested) : NONE; }
        private static Set<Integer> union(Set<Integer> left, Set<Integer> right) {
            Set<Integer> result = new LinkedHashSet<>(left);
            result.addAll(right);
            return Set.copyOf(result);
        }
    }

    private record Violation(String code, String message, Span span) {}
    private record Summary(Map<Integer, Violation> parameters) {
        static Summary empty() { return new Summary(Map.of()); }
    }

    private final Path file;
    private final Map<String, ClassDeclaration> classes;
    private final Map<String, FunctionDeclaration> functions;

    UiBorrowedValueChecker(Path file, Map<String, ClassDeclaration> classes,
                           Map<String, FunctionDeclaration> functions) {
        this.file = file;
        this.classes = Map.copyOf(classes);
        this.functions = Map.copyOf(functions);
    }

    void check(Set<String> handlerNames) {
        Map<String, Summary> summaries = new LinkedHashMap<>();
        for (FunctionDeclaration function : functions.values()) summaries.put(function.name(), initialSummary(function));
        boolean changed;
        do {
            changed = false;
            for (FunctionDeclaration function : functions.values()) {
                Summary next = analyze(function, summaries);
                Summary previous = summaries.get(function.name());
                if (!previous.parameters().keySet().equals(next.parameters().keySet())) {
                    summaries.put(function.name(), next);
                    changed = true;
                }
            }
        } while (changed);

        for (String name : handlerNames) {
            FunctionDeclaration function = functions.get(name);
            Summary summary = summaries.get(name);
            if (function == null || summary == null) continue;
            for (int parameter : List.of(0, 1)) {
                Violation violation = summary.parameters().get(parameter);
                if (violation == null) continue;
                String parameterName = function.params().get(parameter).name();
                throw new UiDiagnostic(violation.code(), "UI handler parameter '" + parameterName + "' is borrowed immutable: " + violation.message(),
                    file, violation.span().startLine(), violation.span().startColumn());
            }
        }
    }

    private Summary initialSummary(FunctionDeclaration function) {
        if (!function.isExternal()) return Summary.empty();
        Map<Integer, Violation> result = new LinkedHashMap<>();
        for (int index = 0; index < function.params().size(); index++) {
            if (shape(function.params().get(index).type()).reference(classes)) {
                result.put(index, new Violation("UI2051", "value escapes to an external function", function.span()));
            }
        }
        return new Summary(Map.copyOf(result));
    }

    private Summary analyze(FunctionDeclaration function, Map<String, Summary> summaries) {
        if (function.isExternal()) return initialSummary(function);
        Analyzer analyzer = new Analyzer(function, summaries);
        analyzer.block(function.body());
        return new Summary(Map.copyOf(analyzer.violations));
    }

    private final class Analyzer {
        private final Map<String, Summary> summaries;
        private final Map<String, Origin> origins = new LinkedHashMap<>();
        private final Map<String, Shape> shapes = new LinkedHashMap<>();
        private final Map<Integer, Violation> violations = new LinkedHashMap<>();

        Analyzer(FunctionDeclaration function, Map<String, Summary> summaries) {
            this.summaries = summaries;
            for (int index = 0; index < function.params().size(); index++) {
                Parameter parameter = function.params().get(index);
                Shape parameterShape = shape(parameter.type());
                shapes.put(parameter.name(), parameterShape);
                origins.put(parameter.name(), parameterShape.reference(classes) ? Origin.borrowed(index) : Origin.NONE);
            }
        }

        private Analyzer(Analyzer source) {
            summaries = source.summaries;
            origins.putAll(source.origins);
            shapes.putAll(source.shapes);
            violations.putAll(source.violations);
        }

        void block(Block block) {
            for (StatementNode statement : block.statements()) statement(statement);
        }

        private void statement(StatementNode statement) {
            if (statement instanceof VariableDeclaration variable) {
                expression(variable.initializer());
                origins.put(variable.name(), origin(variable.initializer()));
                shapes.put(variable.name(), variable.typeAnnotation().map(UiBorrowedValueChecker.this::shape).orElseGet(() -> expressionShape(variable.initializer())));
            } else if (statement instanceof ExpressionStatement expression) {
                expression(expression.expr());
            } else if (statement instanceof ReturnStatement returned) {
                returned.expr().ifPresent(this::expression);
            } else if (statement instanceof IfStatement conditional) {
                expression(conditional.condition());
                Analyzer yes = new Analyzer(this);
                yes.block(conditional.thenBlock());
                Analyzer no = new Analyzer(this);
                conditional.elseBranch().ifPresent(branch -> {
                    if (branch instanceof Either.Left<IfStatement, Block> left) no.statement(left.value());
                    else no.block(((Either.Right<IfStatement, Block>) branch).value());
                });
                merge(yes);
                merge(no);
            } else if (statement instanceof WhileStatement loop) {
                expression(loop.condition());
                Analyzer body = new Analyzer(this);
                body.block(loop.body());
                merge(body);
            } else if (statement instanceof ForOfStatement loop) {
                expression(loop.iterable());
                Analyzer body = new Analyzer(this);
                Shape itemShape = shape(loop.varType());
                body.shapes.put(loop.varName(), itemShape);
                body.origins.put(loop.varName(), origin(loop.iterable()).element(itemShape.reference(classes)));
                body.block(loop.body());
                merge(body);
            } else if (statement instanceof ForStatement loop) {
                Analyzer body = new Analyzer(this);
                loop.init().ifPresent(init -> {
                    if (init instanceof ForInit.VarDecl variable) body.statement(variable.decl());
                    else body.assignment(((ForInit.AssignExpr) init).expr());
                });
                loop.condition().ifPresent(body::expression);
                body.block(loop.body());
                loop.update().ifPresent(body::expression);
                merge(body);
            } else if (statement instanceof DeleteStatement deleted) {
                mutate(deleted.target(), deleted.span(), "deletes through a borrowed value");
                expression(deleted.target());
            } else if (statement instanceof TryStatement attempt) {
                Analyzer tried = new Analyzer(this);
                tried.block(attempt.tryBlock());
                Analyzer caught = new Analyzer(this);
                caught.origins.put(attempt.catchVar(), Origin.NONE);
                caught.shapes.put(attempt.catchVar(), new Shape("string", 0, false));
                caught.block(attempt.catchBlock());
                merge(tried);
                merge(caught);
            } else if (statement instanceof ThrowStatement thrown) {
                expression(thrown.expr());
            } else if (statement instanceof Block nested) {
                block(nested);
            }
        }

        private void expression(ExpressionNode expression) {
            if (expression instanceof AssignmentExpr assignment) {
                assignment(assignment);
            } else if (expression instanceof BinaryExpr binary) {
                expression(binary.left());
                expression(binary.right());
            } else if (expression instanceof UnaryExpr unary) {
                expression(unary.expr());
            } else if (expression instanceof CallExpr call) {
                expression(call.callee());
                for (ExpressionNode argument : call.args()) expression(argument);
                call(call);
            } else if (expression instanceof MemberAccessExpr member) {
                expression(member.object());
            } else if (expression instanceof IndexExpr index) {
                expression(index.array());
                expression(index.index());
            } else if (expression instanceof ArrayLiteralExpr array) {
                array.elements().forEach(this::expression);
            } else if (expression instanceof ObjectLiteralExpr object) {
                object.properties().forEach(property -> expression(property.value()));
            } else if (expression instanceof FunctionExpr function) {
                Analyzer nested = new Analyzer(this);
                for (Parameter parameter : function.params()) {
                    nested.origins.put(parameter.name(), Origin.NONE);
                    nested.shapes.put(parameter.name(), shape(parameter.type()));
                }
                nested.block(function.body());
                merge(nested);
            } else if (expression instanceof HasExpr has) {
                expression(has.object());
            } else if (expression instanceof TemplateLiteralExpr template) {
                template.parts().forEach(this::expression);
            } else if (expression instanceof AwaitExpression awaited) {
                expression(awaited.callee());
            }
        }

        private void assignment(AssignmentExpr assignment) {
            expression(assignment.value());
            if (assignment.target() instanceof IdentifierExpr identifier) {
                origins.put(identifier.name(), origin(assignment.value()));
                shapes.put(identifier.name(), expressionShape(assignment.value()));
                return;
            }
            mutate(assignment.target(), assignment.span(), "writes through a borrowed value");
            expression(assignment.target());
        }

        private void mutate(ExpressionNode target, Span span, String message) {
            ExpressionNode owner = target instanceof MemberAccessExpr member ? member.object()
                : target instanceof IndexExpr index ? index.array() : null;
            if (owner == null) return;
            for (int parameter : origin(owner).direct()) {
                violations.putIfAbsent(parameter, new Violation("UI2050", message, span));
            }
        }

        private void call(CallExpr call) {
            String name = calledFunction(call.callee());
            Summary summary = name == null ? null : summaries.get(name);
            if (summary == null) {
                for (ExpressionNode argument : call.args()) {
                    if (!expressionShape(argument).reference(classes)) continue;
                    for (int parameter : origin(argument).all()) {
                        violations.putIfAbsent(parameter, new Violation("UI2051", "value escapes to an unknown or external callee", call.span()));
                    }
                }
                return;
            }
            for (Map.Entry<Integer, Violation> mutation : summary.parameters().entrySet()) {
                if (mutation.getKey() >= call.args().size()) continue;
                for (int parameter : origin(call.args().get(mutation.getKey())).all()) {
                    violations.putIfAbsent(parameter, new Violation("UI2051", "value is passed to helper '" + name + "' which may mutate it", call.span()));
                }
            }
        }

        private Origin origin(ExpressionNode expression) {
            Shape resultShape = expressionShape(expression);
            if (expression instanceof IdentifierExpr identifier) return origins.getOrDefault(identifier.name(), Origin.NONE);
            if (expression instanceof MemberAccessExpr member) return origin(member.object()).member(resultShape.reference(classes));
            if (expression instanceof IndexExpr index) return origin(index.array()).element(resultShape.reference(classes));
            if (expression instanceof ArrayLiteralExpr array) {
                Origin result = Origin.NONE;
                for (ExpressionNode element : array.elements()) result = new Origin(Set.of(), result.merge(origin(element)).all());
                return result;
            }
            if (expression instanceof ObjectLiteralExpr object) {
                Origin result = Origin.NONE;
                for (var property : object.properties()) result = new Origin(Set.of(), result.merge(origin(property.value())).all());
                return result;
            }
            if (expression instanceof AssignmentExpr assignment) return origin(assignment.value());
            if (expression instanceof BinaryExpr binary) return origin(binary.left()).merge(origin(binary.right()));
            if (expression instanceof UnaryExpr unary) return origin(unary.expr());
            if (expression instanceof CallExpr call) {
                Origin result = Origin.NONE;
                for (ExpressionNode argument : call.args()) result = result.merge(origin(argument));
                return resultShape.reference(classes) ? result : Origin.NONE;
            }
            if (expression instanceof AwaitExpression awaited) return origin(awaited.callee());
            return Origin.NONE;
        }

        private Shape expressionShape(ExpressionNode expression) {
            if (expression instanceof IdentifierExpr identifier) return shapes.getOrDefault(identifier.name(), unknown());
            if (expression instanceof MemberAccessExpr member) return memberShape(expressionShape(member.object()), member.field());
            if (expression instanceof IndexExpr index) {
                Shape array = expressionShape(index.array());
                return array.dimensions() > 0 ? new Shape(array.name(), array.dimensions() - 1, array.unknown()) : unknown();
            }
            if (expression instanceof ArrayLiteralExpr array) {
                Shape element = array.elements().isEmpty() ? unknown() : expressionShape(array.elements().get(0));
                return new Shape(element.name(), element.dimensions() + 1, element.unknown());
            }
            if (expression instanceof ObjectLiteralExpr || expression instanceof FunctionExpr) return new Shape("function", 0, true);
            if (expression instanceof LiteralExpr literal) return literalShape(literal);
            if (expression instanceof AssignmentExpr assignment) return expressionShape(assignment.value());
            if (expression instanceof AwaitExpression awaited) return expressionShape(awaited.callee());
            if (expression instanceof BinaryExpr || expression instanceof UnaryExpr || expression instanceof HasExpr || expression instanceof TemplateLiteralExpr) return new Shape("string", 0, false);
            return unknown();
        }

        private void merge(Analyzer branch) {
            branch.violations.forEach(violations::putIfAbsent);
            branch.origins.forEach((name, value) -> origins.merge(name, value, Origin::merge));
            branch.shapes.forEach(shapes::putIfAbsent);
        }
    }

    private Shape memberShape(Shape owner, String fieldName) {
        if (owner.dimensions() > 0 || owner.unknown()) return unknown();
        ClassDeclaration declaration = classes.get(simple(owner.name()));
        if (declaration == null) return unknown();
        return declaration.fields().stream()
            .filter(field -> field.name().equals(fieldName))
            .findFirst()
            .map(field -> shape(field.type()))
            .orElseGet(UiBorrowedValueChecker::unknown);
    }

    private Shape literalShape(LiteralExpr literal) {
        String value = literal.value().getClass().getSimpleName();
        if (value.contains("Boolean")) return new Shape("boolean", 0, false);
        if (value.contains("Int")) return new Shape("int", 0, false);
        if (value.contains("Number")) return new Shape("number", 0, false);
        if (value.contains("String")) return new Shape("string", 0, false);
        return new Shape("null", 0, false);
    }

    private Shape shape(TypeNode type) {
        if (type instanceof NullableType nullable) return shape(nullable.innerType());
        if (type instanceof ArrayType array) {
            Shape element = shape(array.elementType());
            return new Shape(element.name(), element.dimensions() + 1, element.unknown());
        }
        if (type instanceof NamedType named) return new Shape(named.name(), 0, false);
        if (type instanceof QualifiedType qualified) return new Shape(qualified.moduleName() + "." + qualified.typeName(), 0, false);
        if (type instanceof FunctionType) return new Shape("function", 0, false);
        return unknown();
    }

    private String calledFunction(ExpressionNode callee) {
        return callee instanceof IdentifierExpr identifier ? identifier.name() : null;
    }

    private static Shape unknown() { return new Shape("unknown", 0, true); }
    private static String simple(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(dot + 1); }
}
