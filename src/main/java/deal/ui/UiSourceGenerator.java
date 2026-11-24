package deal.ui;

import java.nio.file.Path;
import java.util.Map;

/** In-process, backend-neutral checked source generation; hosts own staging and backend selection. */
public final class UiSourceGenerator {
    public record Result(String source, String augmentation, UiModel.CheckedProgram checked) {}
    private UiSourceGenerator() {}

    /** Remove framework directives only after checking, before core backend compilation. */
    public static String compilerSource(String source) {
        return source.replaceAll("(?m)^(\\s*)// @(ui-(?:update|effect|effect-policy|effect-failure))(\\s*)$", "$1//  $2$3");
    }

    public static Result generate(Path viewPath, String viewSource, Path dealPath, String dealSource,
                                  Map<String, UiModel.PackModule> packs, Path frameworkRoot) {
        return generate(viewPath, viewSource, dealPath, dealSource, packs, frameworkRoot, Map.of());
    }

    /** Nominal event declarations are supplied by the host's frozen external inventory. */
    public static Result generate(Path viewPath, String viewSource, Path dealPath, String dealSource,
                                  Map<String, UiModel.PackModule> packs, Path frameworkRoot,
                                  Map<String, String> declarations) {
        var views = UiParser.parseViews(viewPath, viewSource);
        var checker = new UiChecker();
        UiModel.CheckedProgram checked;
        try { checked = checker.check(viewPath, views, dealPath, checker.parseDeal(dealPath, dealSource), packs); }
        catch (UiDiagnostic failure) {
            var observed = new java.util.LinkedHashMap<String, String>();
            observed.put(viewPath.toString(), viewSource); observed.put(dealPath.toString(), dealSource);
            packs.values().forEach(pack -> pack.classes().values().forEach(clazz -> observed.put(clazz.span().file().toString(), pack.source())));
            throw failure.withSources(observed);
        }
        UiDealGenerator.Output generated;
        try { generated = new UiDealGenerator(checked, frameworkRoot, packs, declarations).generateSession(); }
        catch (UiDiagnostic failure) { throw failure.withSources(java.util.Map.of(viewPath.toString(), viewSource, dealPath.toString(), dealSource)); }
        return new Result(generated.source(), generated.appAugmentation(), checked);
    }
}
