package deal.ui;

import deal.ast.ClassDeclaration;
import deal.ast.NamedType;
import deal.checker.NameResolver;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.types.Type;
import deal.types.Types;
import java.util.function.UnaryOperator;

/** Projection into core types. Core owns compatibility and canonical nullish semantics. */
final class UiTypes {
    private UiTypes() {}
    static Type core(UiModel.TypeRef ref, UnaryOperator<String> names) {
        Type value = leaf(names.apply(ref.name()));
        for (int i = 0; i < ref.dimensions(); i++) {
            value = nullish(value, ref.nullableElements().get(i), ref.undefinedElements().get(i));
            value = new Type.Array(value, ref.readonlyLayers().get(i));
        }
        return nullish(value, ref.optional(), ref.undefined());
    }
    private static Type nullish(Type type, boolean nullable, boolean undefined) {
        if (nullable) type = Types.withNullish(type, false, false);
        if (undefined) type = Types.withNullish(type, true, false);
        return type;
    }
    private static Type leaf(String name) {
        return switch (name) {
            case "null" -> Type.Null.INSTANCE;
            case "undefined" -> Type.Undefined.INSTANCE;
            case "unknown" -> Type.Unknown.INSTANCE;
            case "number" -> Type.Number.INSTANCE;
            case "string" -> Type.String.INSTANCE;
            case "boolean" -> Type.Boolean.INSTANCE;
            case "void" -> Type.Void.INSTANCE;
            case "never" -> Type.Never.INSTANCE;
            case "Record<string, unknown>" -> Type.Table.INSTANCE;
            case "Record<string, string>" -> Type.Table.STRING;
            default -> nominal(name);
        };
    }
    private static Type nominal(String name) {
        // Declaration names are resolved by core; the frontend supplies its resolved declaration key.
        var parsed = new Parser(new Lexer("class Value {}", "ui-type").tokenize().tokens(), "ui-type").parse();
        var resolver = new NameResolver("ui-type/" + name, null);
        resolver.resolve(parsed.program());
        return resolver.resolveTypeNode(new NamedType(((ClassDeclaration)parsed.program().statements().getFirst()).span(), "Value"));
    }
    static boolean singleton(Type singleton, Type target) {
        if (singleton instanceof Type.Null) return target instanceof Type.Unknown || target instanceof Type.Null
                || target instanceof Type.Nullable n && (n.admitsNull() || n.inner() instanceof Type.Null);
        if (singleton instanceof Type.Undefined) return target instanceof Type.Unknown || target instanceof Type.Undefined
                || target instanceof Type.Nullable n && (n.admitsUndefined() || n.inner() instanceof Type.Undefined);
        return false;
    }
}
