package deal.ui;

import deal.ast.ArrayType;
import deal.ast.ClassDeclaration;
import deal.ast.FunctionType;
import deal.ast.NamedType;
import deal.ast.NullableType;
import deal.ast.QualifiedType;
import deal.ast.TypeNode;
import deal.checker.NameResolver;
import deal.lexer.Lexer;
import deal.parser.Parser;
import deal.types.Type;
import deal.types.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Lossless source projection; core owns parsing, canonical nullish types and compatibility. */
final class UiTypes {
    private UiTypes() {}
    private static final Map<String, Type> NOMINALS = new java.util.concurrent.ConcurrentHashMap<>();
    static Type core(UiModel.TypeRef ref, UnaryOperator<String> names) {
        Type value = ref.name().contains("=>") ? resolve(parse(ref.name()), names) : leaf(names.apply(ref.name()));
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
            case "Error" -> Types.classType("Error", NameResolver.intrinsicErrorIdentity());
            default -> NOMINALS.computeIfAbsent(name, UiTypes::nominal);
        };
    }
    private static Type nominal(String name) {
        // The frontend supplies its resolved declaration key; core assigns declaration identity.
        var parsed = new Parser(new Lexer("class Value {}", "ui-type").tokenize().tokens(), "ui-type").parse();
        var resolver = new NameResolver("ui-type/" + name, null);
        resolver.resolve(parsed.program());
        return resolver.resolveTypeNode(new NamedType(((ClassDeclaration)parsed.program().statements().getFirst()).span(), "Value"));
    }
    static TypeNode parse(String text) {
        String source = "class Value { field: " + text + "; }";
        var lexed = new Lexer(source, "ui-type").tokenize();
        var parsed = new Parser(lexed.tokens(), "ui-type").parse();
        var errors = new ArrayList<>(lexed.diagnostics()); errors.addAll(parsed.diagnostics());
        if (errors.stream().anyMatch(d -> d.severity().equals("error")))
            throw new IllegalArgumentException(errors.stream().filter(d -> d.severity().equals("error")).findFirst().orElseThrow().message());
        TypeNode node = ((ClassDeclaration)parsed.program().statements().getFirst()).fields().getFirst().type();
        // resolve() invokes core's duplicate checks before unknown absorbs nullish members.
        resolve(node, UnaryOperator.identity());
        return node;
    }
    static void validateNullish(TypeNode node) { resolve(node, UnaryOperator.identity()); }
    private static Type resolve(TypeNode node, UnaryOperator<String> names) {
        return switch (node) {
            case NamedType n -> leaf(names.apply(n.name()));
            case QualifiedType q -> leaf(names.apply(q.moduleName() + "." + q.typeName()));
            case ArrayType a -> new Type.Array(resolve(a.elementType(), names), a.readonly());
            case NullableType n -> {
                for (TypeNode inner = n.innerType(); inner instanceof NullableType member; inner = member.innerType())
                    if (n.undefinedMember() == member.undefinedMember()) throw new IllegalArgumentException("Duplicate nullish member");
                yield Types.withNullish(resolve(n.innerType(), names), n.undefinedMember(), true);
            }
            case FunctionType f -> new Type.Func(f.params().stream().map(p -> resolve(p.type(), names)).toList(), resolve(f.returnType(), names), f.isAsync());
        };
    }
    static UiModel.TypeRef project(TypeNode node) {
        return switch (node) {
            case NamedType n -> new UiModel.TypeRef(n.name(), false, false);
            case QualifiedType q -> new UiModel.TypeRef(q.moduleName() + "." + q.typeName(), false, false);
            case ArrayType a -> project(a.elementType()).arrayOf(a.readonly());
            case NullableType n -> n.undefinedMember() ? project(n.innerType()).withUndefined(true) : project(n.innerType()).withOptional(true);
            case FunctionType f -> new UiModel.TypeRef(functionText(f, UnaryOperator.identity()), false, false);
        };
    }
    static String rename(String name, UnaryOperator<String> names) {
        return name.contains("=>") ? functionText((FunctionType)parse(name), names) : names.apply(name);
    }
    private static String text(TypeNode node, UnaryOperator<String> names) {
        if (node instanceof FunctionType f) return functionText(f, names);
        if (node instanceof NamedType n) return names.apply(n.name());
        if (node instanceof QualifiedType q) return names.apply(q.moduleName() + "." + q.typeName());
        if (node instanceof NullableType n) return "(" + text(n.innerType(), names) + ") | " + (n.undefinedMember() ? "undefined" : "null");
        var a = (ArrayType)node;
        return (a.readonly() ? "readonly " : "") + "(" + text(a.elementType(), names) + ")[]";
    }
    private static String functionText(FunctionType f, UnaryOperator<String> names) {
        var parameters = new ArrayList<String>();
        for (int i = 0; i < f.params().size(); i++) parameters.add("p" + i + ": " + text(f.params().get(i).type(), names));
        String result = text(f.returnType(), names);
        return "(" + String.join(", ", parameters) + ") => " + (f.isAsync() ? "Promise<" + result + ">" : result);
    }
    static UiModel.TypeRef member(UiModel.TypeRef receiver, String field) {
        if (receiver.array() && field.equals("length") || receiver.name().equals("string") && field.equals("length"))
            return new UiModel.TypeRef("number", false, false);
        if (receiver.name().equals("Record<string, unknown>")) return new UiModel.TypeRef("unknown", false, false);
        if (receiver.name().equals("Record<string, string>")) return new UiModel.TypeRef("string", false, false).withUndefined(true);
        if (receiver.name().equals("Error") && (field.equals("name") || field.equals("message")))
            return new UiModel.TypeRef("string", false, false);
        return null;
    }
    static boolean singleton(Type singleton, Type target) {
        if (singleton instanceof Type.Null) return target instanceof Type.Unknown || target instanceof Type.Null
                || target instanceof Type.Nullable n && (n.admitsNull() || n.inner() instanceof Type.Null);
        if (singleton instanceof Type.Undefined) return target instanceof Type.Unknown || target instanceof Type.Undefined
                || target instanceof Type.Nullable n && (n.admitsUndefined() || n.inner() instanceof Type.Undefined);
        return false;
    }
}
