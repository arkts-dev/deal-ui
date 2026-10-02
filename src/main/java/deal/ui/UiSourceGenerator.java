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
        var views = UiParser.parseViews(viewPath, viewSource);
        var checker = new UiChecker();
        var checked = checker.check(viewPath, views, dealPath, checker.parseDeal(dealPath, dealSource), packs);
        var generated = new UiDealGenerator(checked, frameworkRoot).generateSession();
        return new Result(generated.source(), generated.appAugmentation(), checked);
    }
}
