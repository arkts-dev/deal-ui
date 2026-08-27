package deal.ui;

import java.util.Map;

public final class UiIrDumper {
    public String dump(UiModel.CheckedProgram program) {
        StringBuilder out = new StringBuilder();
        out.append("root ").append(program.title()).append(" state ").append(program.rootStateType()).append('\n');
        append(out, program.rootNodes(), 1);
        for (Map.Entry<String, UiModel.Handler> update : program.updates().entrySet()) {
            out.append("update ").append(update.getKey()).append(" -> ").append(update.getValue().name()).append('\n');
        }
        for (Map.Entry<String, UiModel.Handler> effect : program.effects().entrySet()) {
            out.append("effect ").append(effect.getKey()).append(" -> ").append(effect.getValue().returnType()).append('\n');
        }
        return out.toString();
    }

    private void append(StringBuilder out, Iterable<UiModel.RenderNode> nodes, int depth) {
        for (UiModel.RenderNode node : nodes) {
            out.append("  ".repeat(depth));
            if (node instanceof UiModel.RenderCall call) {
                out.append("call ").append(call.name()).append(" #").append(call.identity()).append('\n');
                append(out, call.children(), depth + 1);
            } else if (node instanceof UiModel.RenderWhen when) {
                out.append("when #").append(when.identity()).append('\n');
                append(out, when.thenNodes(), depth + 1);
                append(out, when.elseNodes(), depth + 1);
            } else if (node instanceof UiModel.RenderScope scope) {
                out.append("scope").append('\n');
                append(out, scope.children(), depth + 1);
            } else {
                UiModel.RenderForEach each = (UiModel.RenderForEach) node;
                out.append("foreach ").append(String.join(".", each.source().parts())).append(" key ")
                    .append(String.join(".", each.key().parts())).append(" #").append(each.identity()).append('\n');
                append(out, each.children(), depth + 1);
            }
        }
    }
}
