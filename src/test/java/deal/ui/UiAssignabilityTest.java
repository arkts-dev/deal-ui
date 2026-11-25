package deal.ui;

import java.nio.file.Path;
import java.util.Map;

public final class UiAssignabilityTest {
    public static void main(String[] args) {
        String[] types = {"number", "unknown", "null", "undefined", "number | null", "number | undefined", "number | null | undefined",
                "number[]", "readonly number[]", "readonly unknown[]", "(number | null)[]", "readonly (number | null)[]",
                "(number | undefined)[]", "readonly (number | undefined)[]", "number[][]", "(readonly number[])[]", "readonly (readonly unknown[])[]"};
        int count = 0;
        for (String from : types) for (String to : types) {
            var actual = parse(from); var expected = parse(to);
            boolean core = deal.types.Types.isAssignable(core(actual), core(expected));
            String logic = "export class State {value: " + from + ";}";
            String view = "import * as app from \"./app\";import * as ui from \"./pack\";\n// @ui-root\n"
                    + "export view Root(state: app.State): View {ui.Data(value: state.value)}";
            var checker = new UiChecker(); boolean accepted = true;
            try {
                checker.check(Path.of("root/view.dealui"), UiParser.parseViews(Path.of("root/view.dealui"), view), Path.of("root/app.deal"),
                        checker.parseDeal(Path.of("root/app.deal"), logic), Map.of("./pack", UiParser.parsePack(Path.of("root/pack"),
                                "export class Props {value: " + to + ";}export component Data(props: Props): View;")));
            } catch (UiDiagnostic error) { if (!error.code().equals("UI2031")) throw error; accepted = false; }
            if (accepted != core) throw new AssertionError(from + " -> " + to + ": UI=" + accepted + " core=" + core);
            count++;
        }
        System.out.println("UiAssignabilityTest: " + count + " pairs match core DEAL");
    }
    private static UiModel.TypeRef parse(String type) {
        return UiParser.parseViews(Path.of("type.dealui"), "view V(v: " + type + "): View {}").views().getFirst().parameters().getFirst().type();
    }
    private static deal.types.Type core(UiModel.TypeRef type) {
        deal.types.Type value = switch (type.name()) {
            case "number" -> deal.types.Type.Number.INSTANCE;
            case "unknown" -> deal.types.Type.Unknown.INSTANCE;
            case "null" -> deal.types.Type.Null.INSTANCE;
            case "undefined" -> deal.types.Type.Undefined.INSTANCE;
            default -> throw new AssertionError(type);
        };
        for (int i = 0; i < type.dimensions(); i++) {
            if (type.nullableElements().get(i)) value = deal.types.Types.withNullish(value, false, false);
            if (type.undefinedElements().get(i)) value = deal.types.Types.withNullish(value, true, false);
            value = new deal.types.Type.Array(value, type.readonlyLayers().get(i));
        }
        if (type.optional()) value = deal.types.Types.withNullish(value, false, false);
        if (type.undefined()) value = deal.types.Types.withNullish(value, true, false);
        return value;
    }
}
