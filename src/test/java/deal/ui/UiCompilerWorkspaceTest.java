package deal.ui;

import java.util.List;

/** Compiler-owned UI identities and atomic edit protocol tests. */
public final class UiCompilerWorkspaceTest {
    private static final String PACK = """
            pack version "test-v1";
            export class ColumnProps { onClick?: Action; }
            export class TextProps { value: string; }
            export class ButtonProps { text: string; onClick?: Action; }
            export component Column(props: ColumnProps): View { children optional; event onClick; }
            export component Text(props: TextProps): View;
            export component Button(props: ButtonProps): View { event onClick; accessibility text; }
            """;
    private static final String DEAL = """
            export class AppState { title: string = "Ready"; count: int = 0; }
            export class IncrementAction {}
            export function initialState(): AppState { return {title: "Ready", count: 0}; }
            // @ui-update
            export function update(state: AppState, action: IncrementAction): AppState {
              return {title: state.title, count: state.count + 1};
            }
            """;
    private static final String UI = """
            import * as app from "./app.deal";
            import * as ui from "./ui.pack";
            // @ui-root
            export view App(state: app.AppState): View {
              ui.Column(onClick: action app.IncrementAction {}) {
                ui.Text(value: state.title)
                ui.Button(text: "Add", onClick: action app.IncrementAction {})
              }
            }
            """;

    private UiCompilerWorkspaceTest() {}

    public static void main(String[] args) {
        identitiesAreDeterministicAndCommentIndependent();
        subtreeRepairIsAtomicAndScoped();
        propertyEditPreservesSiblings();
        childInsertionUsesTheActualChildBlock();
        staleNodeCannotModifyNewRevision();
        canonicalFacadeBlocksCrossArtifactMismatch();
        System.out.println("UiCompilerWorkspaceTest: all tests passed");
    }

    private static void identitiesAreDeterministicAndCommentIndependent() {
        var first = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var second = UiCompilerWorkspace.inspect(DEAL, UI.replace("// @ui-root", "// ignored\n// @ui-root"), PACK, "./ui.pack");
        check(first.diagnostics().isEmpty(), "base UI must compile: " + first.diagnostics());
        check(first.views().get(0).id().equals(second.views().get(0).id()), "view identity must not come from comments");
        check(!first.nodes().get(0).id().equals(second.nodes().get(0).id()), "node identity is scoped to source revision");
    }

    private static void subtreeRepairIsAtomicAndScoped() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var text = inspection.nodes().stream().filter(value -> value.component().equals("ui.Text")).findFirst().orElseThrow();
        var rejected = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.ReplaceSubtree(text.id(), "ui.Unknown(value: state.title)")));
        check(!rejected.accepted(), "unknown component must reject");
        check(rejected.source().equals(UI), "rejected UI edit must roll back");
        check(rejected.diagnostics().stream().allMatch(value -> value.ownerId().equals(text.id())),
                "repair must remain scoped to rejected node");

        var accepted = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.ReplaceSubtree(text.id(), "ui.Text(value: \"Updated\")")));
        check(accepted.accepted(), "valid subtree replacement must commit: " + accepted.diagnostics());
        check(accepted.source().contains("Updated"), "replacement must be projected to source");
        check(accepted.source().contains("ui.Button"), "unrelated sibling must be preserved");
    }

    private static void propertyEditPreservesSiblings() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var button = inspection.nodes().stream().filter(value -> value.component().equals("ui.Button")).findFirst().orElseThrow();
        var result = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.SetProperty(button.id(), "text", "\"Increment\"")));
        check(result.accepted(), "property edit must compile: " + result.diagnostics());
        check(result.source().contains("text: \"Increment\""), "property expression must change");
        check(result.source().contains("state.title"), "sibling state binding must remain");
    }

    private static void staleNodeCannotModifyNewRevision() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var text = inspection.nodes().stream().filter(value -> value.component().equals("ui.Text")).findFirst().orElseThrow();
        var first = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.ReplaceSubtree(text.id(), "ui.Text(value: \"One\")")));
        check(first.accepted(), "first revision must compile");
        var stale = UiCompilerWorkspace.apply(
                DEAL, first.source(), PACK, "./ui.pack", first.sourceDigest(),
                List.of(new UiCompilerWorkspace.ReplaceSubtree(text.id(), "ui.Text(value: \"Two\")")));
        check(!stale.accepted(), "old node id must be invalid after revision");
        check(stale.diagnostics().get(0).code().equals("CP1003"), "stale node diagnostic must be stable");
    }

    private static void childInsertionUsesTheActualChildBlock() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var column = inspection.nodes().stream().filter(value -> value.component().equals("ui.Column")).findFirst().orElseThrow();
        var result = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.InsertChild(column.id(), column.children().size(),
                        "ui.Text(value: \"Inserted\")")));
        check(result.accepted(), "child insertion must compile: " + result.diagnostics());
        check(result.source().contains("action app.IncrementAction {}"), "action object must remain intact");
        check(result.source().indexOf("Inserted") > result.source().indexOf("ui.Button"),
                "new child must be inserted in the component child block");
    }

    private static void canonicalFacadeBlocksCrossArtifactMismatch() {
        var accepted = CanonicalCompiler.compileCanonicalApp(DEAL, UI, PACK, "./ui.pack");
        check(accepted.valid(), "canonical pair must compile: " + accepted.diagnostics());
        String incompatible = DEAL.replace("title: string = \"Ready\";", "label: string = \"Ready\";")
                .replace("title: \"Ready\"", "label: \"Ready\"")
                .replace("title: state.title", "label: state.label");
        var rejected = CanonicalCompiler.compileCanonicalApp(incompatible, UI, PACK, "./ui.pack");
        check(!rejected.valid(), "UI binding absent from changed interface must reject");
        check(rejected.diagnostics().stream().anyMatch(value -> value.code().startsWith("UI")),
                "cross-artifact rejection must retain Deal UI diagnostic");
        var scoped = rejected.dealUi().diagnostics().get(0).repairScopes().get(0);
        var repaired = CanonicalCompiler.applyDealUiChange(
                incompatible, UI, PACK, "./ui.pack", rejected.dealUi().sourceDigest(),
                List.of(new UiCompilerWorkspace.ReplaceSubtree(
                        scoped.ownerId(), "ui.Text(value: state.label)")));
        check(repaired.accepted(), "semantic-invalid UI must remain locally repairable: " + repaired.diagnostics());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
