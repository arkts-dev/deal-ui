package deal.ui;

import deal.ast.BinaryExpr;
import deal.ast.BinaryOp;
import deal.ast.IdentifierExpr;
import deal.ast.LiteralExpr;
import deal.ast.LiteralValue;
import deal.checker.NullNarrowing;
import deal.checker.Symbol;
import deal.types.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** Pure view guards reuse core singleton refinement, limited to bindings and one declared field. */
final class UiFlowScope {
    private UiFlowScope() {}
    static Map<String, UiModel.TypeRef> branch(UiModel.Expr condition, boolean matching,
            Map<String, UiModel.TypeRef> scope, BiFunction<UiModel.PathExpr, Map<String, UiModel.TypeRef>, UiModel.TypeRef> resolve) {
        if (condition instanceof UiModel.Unary unary && unary.operator().equals("!"))
            return branch(unary.operand(), !matching, scope, resolve);
        if (condition instanceof UiModel.Binary binary && (binary.operator().equals("&&") || binary.operator().equals("||"))) {
            boolean and = binary.operator().equals("&&");
            var selected = branch(binary.left(), and, scope, resolve);
            if (matching == and) return branch(binary.right(), matching, selected, resolve);
            var left = branch(binary.left(), !and, scope, resolve);
            var right = branch(binary.right(), matching, selected, resolve);
            var joined = new LinkedHashMap<>(scope);
            left.forEach((name, type) -> { if (type.equals(right.get(name))) joined.put(name, type); });
            return joined;
        }
        var result = new LinkedHashMap<>(scope);
        if (!(condition instanceof UiModel.Binary binary) || !binary.operator().equals("===") && !binary.operator().equals("!==")) return result;
        UiModel.Expr subject = binary.left(), singleton = binary.right();
        if (isNull(subject)) { subject = binary.right(); singleton = binary.left(); }
        if (!isNull(singleton) || !(subject instanceof UiModel.PathExpr path) || path.parts().size() > 2) return result;
        var declared = scope.containsKey(key(path)) ? scope.get(key(path)) : resolve.apply(path, scope);
        Type base = declared.name().equals("unknown") && !declared.array() ? Type.Unknown.INSTANCE : Type.String.INSTANCE;
        Type current = declared.name().equals("null") && !declared.array() ? Type.Null.INSTANCE
                : declared.name().equals("undefined") && !declared.array() ? Type.Undefined.INSTANCE
                : declared.optional() || declared.undefined() ? new Type.Nullable(base, declared.optional(), declared.undefined()) : base;
        var binding = new Symbol.VariableSymbol(key(path), current, true);
        var identifier = new IdentifierExpr(null, key(path));
        var core = new BinaryExpr(null, identifier, binary.operator().equals("===") ? BinaryOp.EQ : BinaryOp.NEQ,
                new LiteralExpr(null, new LiteralValue.NullLiteral()));
        var flow = new NullNarrowing();
        flow.onCondition(core, matching, ignored -> new NullNarrowing.Subject(binding, null), ignored -> current);
        Type refined = flow.getNarrowedType(binding);
        if (refined instanceof Type.Null) result.put(key(path), new UiModel.TypeRef("null", false, false));
        else if (refined instanceof Type.Nullable nullable) result.put(key(path), declared.withOptional(nullable.admitsNull()).withUndefined(nullable.admitsUndefined()));
        else if (refined != null) result.put(key(path), declared.withOptional(false).withUndefined(false));
        return result;
    }
    static Map<String, UiModel.TypeRef> shadow(Map<String, UiModel.TypeRef> scope, String name, UiModel.TypeRef type) {
        var result = new LinkedHashMap<>(scope);
        result.keySet().removeIf(key -> key.equals(name) || key.startsWith(name + "."));
        result.put(name, type); return result;
    }
    private static boolean isNull(UiModel.Expr value) { return value instanceof UiModel.Literal literal && literal.type().equals("null"); }
    static String key(UiModel.PathExpr path) { return String.join(".", path.parts()); }
}
