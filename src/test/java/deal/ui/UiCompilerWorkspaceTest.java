package deal.ui;

import deal.compiler.CompilerProtocol;

import java.util.List;
import java.util.Map;

/** Compiler-owned UI identities and atomic edit protocol tests. */
public final class UiCompilerWorkspaceTest {
    private static final String PACK = """
            pack version "test-v1";
            export class ColumnProps { onClick?: Action; }
            export class TextProps { value: string; tone?: string; }
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
        absentDeclaredPropertyCanBeInserted();
        childInsertionUsesTheActualChildBlock();
        staleNodeCannotModifyNewRevision();
        canonicalFacadeBlocksCrossArtifactMismatch();
        canonicalDealChangeRequiresAnnotatedUpdateHandlers();
        semanticQueryScopesUiOperations();
        checkedUiChangesRequireQueriedFingerprint();
        documentQueryAddsAndRemovesViewsAtomically();
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

    private static void absentDeclaredPropertyCanBeInserted() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var text = inspection.nodes().stream().filter(value -> value.component().equals("ui.Text")).findFirst().orElseThrow();
        check(text.writableProperties().contains("tone"), "inspection must expose the complete component property contract");
        var result = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.SetProperty(text.id(), "tone", "\"accent\"")));
        check(result.accepted(), "declared property insertion must compile: " + result.diagnostics());
        check(result.source().contains("value: state.title, tone: \"accent\""),
                "new property must be inserted into the existing component call");
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

    private static void canonicalDealChangeRequiresAnnotatedUpdateHandlers() {
        String bootstrap = """
                export class AppState { title: string = ""; }
                export function initialState(): AppState { return {title: ""}; }
                """;
        var inspected = deal.compiler.DealCompilerWorkspace.inspect(
                bootstrap, "/generated/app.deal", DealUiDealSource.ADAPTER);
        var module = CanonicalCompiler.queryDealModule(bootstrap);
        var state = inspected.symbols().stream().filter(value -> value.name().equals("AppState")).findFirst().orElseThrow();
        var initial = inspected.symbols().stream().filter(value -> value.name().equals("initialState")).findFirst().orElseThrow();
        var body = inspected.nodes().stream().filter(value -> value.ownerId().equals(initial.id())).findFirst().orElseThrow();
        var stateSlice = CanonicalCompiler.queryDealSymbol(bootstrap, state.id());
        var bodySlice = CanonicalCompiler.queryDealNode(bootstrap, body.id());
        Map<String, String> fingerprints = Map.of(
                module.ownerId().value(), module.allowedOperations().get(0).targetFingerprint(),
                state.id().value(), stateSlice.allowedOperations().get(0).targetFingerprint(),
                body.id().value(), bodySlice.allowedOperations().get(0).targetFingerprint());
        List<deal.compiler.DealCompilerWorkspace.Operation> missingDirective = List.of(
                new deal.compiler.DealCompilerWorkspace.ReplaceDeclaration(
                        state.id(), "export class AppState { count: int = 0; }"),
                new deal.compiler.DealCompilerWorkspace.ReplaceFunctionBody(
                        body.id(), "return {count: 0};"),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "export class IncrementAction {}"),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "export function update(state: AppState, action: IncrementAction): AppState { return {count: state.count + 1}; }"));
        var rejected = CanonicalCompiler.applyDealChangeChecked(
                bootstrap, new CompilerProtocol.ChangeSetPrecondition(inspected.sourceDigest(), fingerprints), missingDirective);
        check(!rejected.accepted(), "unannotated canonical update must reject");
        check(rejected.source().equals(bootstrap), "framework-contract rejection must roll back the whole ChangeSet");
        check(rejected.diagnostics().stream().anyMatch(value -> value.code().equals("UI2050")),
                "missing update annotation must have a stable diagnostic");

        List<deal.compiler.DealCompilerWorkspace.Operation> misplacedDirective = List.of(
                missingDirective.get(0), missingDirective.get(1), missingDirective.get(2),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "export function update(state: AppState, action: IncrementAction): AppState {\n  // @ui-update\n  return {count: state.count + 1};\n}"));
        var misplacedRejected = CanonicalCompiler.applyDealChangeChecked(
                bootstrap, new CompilerProtocol.ChangeSetPrecondition(inspected.sourceDigest(), fingerprints), misplacedDirective);
        var misplacedDiagnostic = misplacedRejected.diagnostics().stream()
                .filter(value -> value.code().equals("UI2050")).findFirst().orElseThrow();
        check(misplacedDiagnostic.message().contains("inside function"),
                "a misplaced marker must explain the actual location: " + misplacedDiagnostic);
        check(misplacedDiagnostic.expected().contains("immediately before export function update"),
                "a misplaced marker must publish the exact repair contract");
        check(misplacedDiagnostic.actual().equals("marker inside function body"),
                "a misplaced marker must expose structured actual placement");

        List<deal.compiler.DealCompilerWorkspace.Operation> malformedHandler = List.of(
                missingDirective.get(0), missingDirective.get(1), missingDirective.get(2),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "// @ui-update\nexport function update(state: AppState): AppState { return state; }"));
        var malformedRejected = CanonicalCompiler.applyDealChangeChecked(
                bootstrap, new CompilerProtocol.ChangeSetPrecondition(inspected.sourceDigest(), fingerprints), malformedHandler);
        check(!malformedRejected.accepted(), "malformed framework handler must reject during the DEAL transaction");
        check(malformedRejected.source().equals(bootstrap), "malformed framework handler must roll back atomically");
        check(malformedRejected.diagnostics().stream().anyMatch(value -> value.code().equals("UI2034")),
                "malformed framework handler must expose its stable diagnostic before UI generation");

        List<deal.compiler.DealCompilerWorkspace.Operation> annotated = List.of(
                missingDirective.get(0), missingDirective.get(1), missingDirective.get(2),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "// @ui-update\nexport function update(state: AppState, action: IncrementAction): AppState { return {count: state.count + 1}; }"));
        var accepted = CanonicalCompiler.applyDealChangeChecked(
                bootstrap, new CompilerProtocol.ChangeSetPrecondition(inspected.sourceDigest(), fingerprints), annotated);
        check(accepted.accepted(), "annotated canonical update must commit: " + accepted.diagnostics());
    }

    private static void semanticQueryScopesUiOperations() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var button = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Button"))
                .findFirst().orElseThrow();
        var slice = UiCompilerWorkspace.queryNode(DEAL, UI, PACK, "./ui.pack", button.id());
        check(slice.source().startsWith("ui.Button"), "node query must return only the selected subtree");
        check(slice.allowedOperations().stream()
                        .anyMatch(value -> value.operation().equals(UiCompilerWorkspace.SET_PROPERTY)),
                "component query must expose typed property editing");
        check(slice.allowedOperations().stream()
                        .noneMatch(value -> value.operation().equals(UiCompilerWorkspace.REPLACE_VIEW_BODY)),
                "node query must not authorize replacing its complete view");
    }

    private static void checkedUiChangesRequireQueriedFingerprint() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var button = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Button"))
                .findFirst().orElseThrow();
        var slice = UiCompilerWorkspace.queryNode(DEAL, UI, PACK, "./ui.pack", button.id());
        var descriptor = slice.allowedOperations().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.SET_PROPERTY))
                .findFirst().orElseThrow();
        var operation = new UiCompilerWorkspace.SetProperty(button.id(), "text", "\"Increment\"");

        var missing = UiCompilerWorkspace.applyChecked(
                DEAL, UI, PACK, "./ui.pack",
                new CompilerProtocol.ChangeSetPrecondition(inspection.sourceDigest(), Map.of()),
                List.of(operation));
        check(!missing.accepted() && missing.diagnostics().get(0).code().equals("CP1010"),
                "unqueried UI targets must not be writable");

        var accepted = UiCompilerWorkspace.applyChecked(
                DEAL, UI, PACK, "./ui.pack",
                new CompilerProtocol.ChangeSetPrecondition(
                        inspection.sourceDigest(),
                        Map.of(button.id().value(), descriptor.targetFingerprint())),
                List.of(operation));
        check(accepted.accepted(), "queried UI target must be writable: " + accepted.diagnostics());
        check(accepted.source().contains("text: \"Increment\""), "checked UI edit must commit");
    }

    private static void documentQueryAddsAndRemovesViewsAtomically() {
        String imports = """
                import * as app from "./app.deal";
                import * as ui from "./ui.pack";
                """;
        var slice = UiCompilerWorkspace.queryDocument(DEAL, imports, PACK, "./ui.pack");
        var descriptor = slice.allowedOperations().get(0);
        check(slice.source().isEmpty(), "document query must not expose the complete UI source");
        check(descriptor.operation().equals(UiCompilerWorkspace.ADD_VIEW),
                "document query must authorize only view insertion");
        String view = """
                // @ui-root
                export view App(state: app.AppState): View {
                  ui.Column() {
                    ui.Text(value: state.title)
                    ui.Button(text: "Add", onClick: action app.IncrementAction {})
                  }
                }
                """;
        var added = UiCompilerWorkspace.applyChecked(
                DEAL, imports, PACK, "./ui.pack",
                new CompilerProtocol.ChangeSetPrecondition(
                        slice.revision().sourceDigest(),
                        Map.of(slice.ownerId().value(), descriptor.targetFingerprint())),
                List.of(new UiCompilerWorkspace.AddView(slice.ownerId(), view)));
        check(added.accepted(), "a queried document must accept a complete checked view: " + added.diagnostics());
        var app = added.inspection().views().stream()
                .filter(value -> value.name().equals("App")).findFirst().orElseThrow();
        var removed = UiCompilerWorkspace.apply(
                DEAL, added.source(), PACK, "./ui.pack", added.sourceDigest(),
                List.of(new UiCompilerWorkspace.RemoveView(app.id())));
        check(!removed.accepted(), "removing the only root view must fail atomically");
        check(removed.source().equals(added.source()), "failed root removal must preserve the canonical source");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
