package deal.ui;

import deal.amend.AmendWorkspace;

/** Length-preserving DEAL parser projection for Deal UI framework directives. */
final class DealUiDealSource {
    static final AmendWorkspace.SourceAdapter ADAPTER = DealUiDealSource::parserSource;

    private DealUiDealSource() {}

    static String parserSource(String source) {
        return source.replaceAll(
                "(?m)^(\\s*)// @(ui-(?:update|effect|effect-policy|effect-failure))(\\s*)$",
                "$1//  $2$3");
    }
}
