package deal.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lexical specialization of forwarded Action parameters at their checked event site. */
final class UiViewBindings {
    private UiViewBindings() {}
    static List<UiModel.Node> bind(List<UiModel.Node> nodes, Map<String, UiModel.Expr> bindings) {
        return nodes.stream().map(node -> {
            if (node instanceof UiModel.Call call) return (UiModel.Node)new UiModel.Call(call.name(), fields(call.arguments(), bindings), bind(call.children(), bindings), call.childBlock(), call.span());
            if (node instanceof UiModel.When when) return new UiModel.When(expr(when.condition(), bindings), bind(when.thenNodes(), bindings), bind(when.elseNodes(), bindings), when.span());
            var each = (UiModel.ForEach)node;
            var nested = new LinkedHashMap<>(bindings); nested.remove(each.item().name());
            return new UiModel.ForEach((UiModel.PathExpr)expr(each.source(), bindings), each.item(), (UiModel.PathExpr)expr(each.key(), nested), bind(each.children(), nested), each.span());
        }).toList();
    }
    private static Map<String, UiModel.Expr> fields(Map<String, UiModel.Expr> fields, Map<String, UiModel.Expr> bindings) {
        var result = new LinkedHashMap<String, UiModel.Expr>(); fields.forEach((key, value) -> result.put(key, expr(value, bindings))); return result;
    }
    private static UiModel.Expr expr(UiModel.Expr value, Map<String, UiModel.Expr> bindings) {
        if (value instanceof UiModel.PathExpr path && bindings.containsKey(path.parts().getFirst())) {
            var replacement = bindings.get(path.parts().getFirst());
            if (path.parts().size() == 1) return replacement;
            if (replacement instanceof UiModel.PathExpr root) {
                var parts = new java.util.ArrayList<>(root.parts()); parts.addAll(path.parts().subList(1, path.parts().size()));
                return new UiModel.PathExpr(parts, path.span());
            }
            throw new UiDiagnostic("UI2031", "A field path requires a nominal path binding", path.span().file(), path.span().line(), path.span().column());
        }
        if (value instanceof UiModel.Action action) return new UiModel.Action(action.name(), fields(action.fields(), bindings), action.span());
        if (value instanceof UiModel.Has has) return new UiModel.Has((UiModel.PathExpr)expr(has.path(), bindings), has.span());
        if (value instanceof UiModel.Unary unary) return new UiModel.Unary(unary.operator(), expr(unary.operand(), bindings), unary.span());
        if (value instanceof UiModel.Binary binary) return new UiModel.Binary(binary.operator(), expr(binary.left(), bindings), expr(binary.right(), bindings), binary.span());
        return value;
    }
}
