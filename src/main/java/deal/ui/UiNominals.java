package deal.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Import aliases are lexical names, never nominal identities or globally reserved names. */
final class UiNominals {
    private UiNominals() {}
    static String alias(String module, java.nio.file.Path importer) {
        if (module.startsWith(".")) {
            var path = importer.toAbsolutePath().getParent().resolve(module).normalize();
            if (!path.toString().endsWith(".deal")) path = java.nio.file.Path.of(path + ".deal");
            module = path.toString();
        }
        return alias(module);
    }
    static String alias(String module) {
        try { return "UiModule_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(module.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    static UiModel.TypeRef rename(UiModel.TypeRef type, UnaryOperator<String> names) { return type.withName(UiTypes.rename(type.name(), names)); }
    static Map<String, UiModel.PackModule> packs(Map<String, UiModel.PackModule> packs) {
        var result = new LinkedHashMap<String, UiModel.PackModule>();
        packs.forEach((key, pack) -> {
            var names = new LinkedHashMap<String, String>();
            java.nio.file.Path file = pack.imports().isEmpty() ? java.nio.file.Path.of("pack") : pack.imports().getFirst().span().file();
            pack.imports().forEach(i -> names.put(i.alias(), alias(i.specifier(), file)));
            UnaryOperator<String> rename = name -> {
                int dot = name.indexOf('.');
                return dot < 0 ? name : names.getOrDefault(name.substring(0, dot), name.substring(0, dot)) + name.substring(dot);
            };
            var classes = new LinkedHashMap<String, UiModel.PackClass>();
            pack.classes().forEach((name, c) -> classes.put(name, new UiModel.PackClass(name, c.fields().stream().map(f -> new UiModel.Field(f.name(), rename(f.type(), rename), f.defaultValue(), f.span(), f.optional())).toList(), c.span())));
            var components = new LinkedHashMap<String, UiModel.Component>();
            pack.components().forEach((name, c) -> components.put(name, new UiModel.Component(name, c.propsType(), c.contracts().stream().map(contract -> contract instanceof UiModel.Event e && e.payload() != null ? new UiModel.Event(e.prop(), rename(e.payload(), rename)) : contract).toList(), c.span())));
            var tokens = new LinkedHashMap<String, UiModel.Token>();
            pack.tokens().forEach((name, t) -> tokens.put(name, new UiModel.Token(name, rename(t.type(), rename), t.value(), t.span())));
            result.put(key, new UiModel.PackModule(pack.version(), pack.digest(), pack.imports().stream().map(i -> new UiModel.Import(alias(i.specifier(), file), i.specifier(), i.span())).toList(), classes, components, tokens, pack.source()));
        });
        return result;
    }
}
