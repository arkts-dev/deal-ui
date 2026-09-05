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
        typeMismatchPublishesExpectedAndActualTypes();
        absentDeclaredPropertyCanBeInserted();
        childInsertionUsesTheActualChildBlock();
        staleNodeCannotModifyNewRevision();
        canonicalFacadeBlocksCrossArtifactMismatch();
        ancestorSubtreeCanRepairDescendantInterfaceErrors();
        canonicalDealChangeRequiresAnnotatedUpdateHandlers();
        declaredHostCapabilityRequiresPackComponent();
        unreachableActionDiagnosticIsComplete();
        frameworkDiagnosticsUseCompilerOwnedRepairSlots();
        borrowedMutationDiagnosticOwnsHandlerSlot();
        semanticQueryScopesUiOperations();
        checkedUiChangesRequireQueriedFingerprint();
        documentQueryAddsAndRemovesViewsAtomically();
        inspectChangeBuildsUiDependencyCone();
        repairWorkspacePatchesOnlyRejectedUiSlot();
        System.out.println("UiCompilerWorkspaceTest: all tests passed");
    }

    private static void inspectChangeBuildsUiDependencyCone() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var text = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Text")).findFirst().orElseThrow();
        var change = UiCompilerWorkspace.inspectChange(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(), List.of(text.id()),
                List.of(UiCompilerWorkspace.REPLACE_SUBTREE));
        check(change.diagnostics().isEmpty(), "current UI node must be inspectable");
        check(change.dependencyCone().members().stream().anyMatch(value ->
                        value.id().equals(text.id()) && value.exposure().equals("EDIT_BODY")),
                "selected UI node must be editable");
        check(change.dependencyCone().edges().stream().anyMatch(value ->
                        value.to().equals(text.id()) && value.kind().equals("PARENT_CHILD")),
                "UI cone must be owned by compiler parent/child identities");
    }

    private static void repairWorkspacePatchesOnlyRejectedUiSlot() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var text = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Text")).findFirst().orElseThrow();
        var button = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Button")).findFirst().orElseThrow();
        var textDescriptor = UiCompilerWorkspace.queryNode(DEAL, UI, PACK, "./ui.pack", text.id())
                .allowedOperations().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.REPLACE_SUBTREE))
                .findFirst().orElseThrow();
        var buttonDescriptor = UiCompilerWorkspace.queryNode(DEAL, UI, PACK, "./ui.pack", button.id())
                .allowedOperations().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.SET_PROPERTY))
                .findFirst().orElseThrow();
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(
                        text.id().value(), textDescriptor.targetFingerprint(),
                        button.id().value(), buttonDescriptor.targetFingerprint()));
        var changeInspection = UiCompilerWorkspace.inspectChange(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(), List.of(text.id(), button.id()),
                List.of(UiCompilerWorkspace.REPLACE_SUBTREE, UiCompilerWorkspace.SET_PROPERTY));
        var staged = UiCompilerWorkspace.stageChange(
                DEAL, UI, PACK, "./ui.pack", precondition, changeInspection, List.of(
                        new UiCompilerWorkspace.ReplaceSubtree(text.id(), "ui.Unknown(value: state.title)"),
                        new UiCompilerWorkspace.SetProperty(button.id(), "text", "\"Preserved\"")));
        check(!staged.accepted(), "invalid UI subtree must keep the candidate staged");
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .findFirst().orElseThrow();
        check(staged.workspace().slots().stream().anyMatch(value ->
                        value.status() == CompilerProtocol.RepairSlotStatus.STAGED
                                && value.payload().containsValue("\"Preserved\"")),
                "independent valid UI payload must be staged and unavailable to repair");
        var repaired = UiCompilerWorkspace.patchRepairWorkspace(
                DEAL, UI, PACK, "./ui.pack", staged.workspace(), List.of(
                        new CompilerProtocol.SlotPatch(rejected.slotId(),
                                Map.of("source", "ui.Text(value: \"Repaired\")"))));
        check(repaired.accepted(), "narrow UI slot patch must commit: " + repaired.diagnostics());
        check(repaired.source().contains("Repaired") && repaired.source().contains("Preserved"),
                "repaired and sealed UI payloads must both reach canonical source");
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

    private static void typeMismatchPublishesExpectedAndActualTypes() {
        var inspection = UiCompilerWorkspace.inspect(DEAL, UI, PACK, "./ui.pack");
        var text = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Text"))
                .findFirst().orElseThrow();
        var result = UiCompilerWorkspace.apply(
                DEAL, UI, PACK, "./ui.pack", inspection.sourceDigest(),
                List.of(new UiCompilerWorkspace.ReplaceSubtree(
                        text.id(), "ui.Text(value: \"Count \" + state.count)")));
        var diagnostic = result.diagnostics().stream()
                .filter(value -> value.code().equals("UI2020"))
                .findFirst().orElseThrow();
        check(diagnostic.expected().equals("string"),
                "binary mismatch must publish the left operand type");
        check(diagnostic.actual().equals("int"),
                "binary mismatch must publish the right operand type");
        check(diagnostic.message().contains("no implicit coercion"),
                "binary mismatch must explain the Deal UI coercion invariant");
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

    private static void ancestorSubtreeCanRepairDescendantInterfaceErrors() {
        String incompatible = DEAL.replace("title: string = \"Ready\";", "label: string = \"Ready\";")
                .replace("title: \"Ready\"", "label: \"Ready\"")
                .replace("title: state.title", "label: state.label");
        var inspection = UiCompilerWorkspace.inspect(incompatible, UI, PACK, "./ui.pack");
        check(!inspection.diagnostics().isEmpty(), "changed interface must invalidate the old descendant binding");
        var column = inspection.nodes().stream()
                .filter(value -> value.component().equals("ui.Column")).findFirst().orElseThrow();
        var descriptor = UiCompilerWorkspace.queryNode(
                        incompatible, UI, PACK, "./ui.pack", column.id()).allowedOperations().stream()
                .filter(value -> value.operation().equals(UiCompilerWorkspace.REPLACE_SUBTREE))
                .findFirst().orElseThrow();
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspection.sourceDigest(), Map.of(column.id().value(), descriptor.targetFingerprint()));
        var repaired = UiCompilerWorkspace.applyChecked(
                incompatible, UI, PACK, "./ui.pack", precondition,
                List.of(new UiCompilerWorkspace.ReplaceSubtree(column.id(), """
                        ui.Column(onClick: action app.IncrementAction {}) {
                          ui.Text(value: state.label)
                          ui.Button(text: "Add", onClick: action app.IncrementAction {})
                        }
                        """)));
        check(repaired.accepted(),
                "a compiler-inspected parent subtree must be able to repair its invalid descendant: "
                        + repaired.diagnostics());
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

        List<deal.compiler.DealCompilerWorkspace.Operation> orphanAction = List.of(
                missingDirective.get(0), missingDirective.get(1), missingDirective.get(2));
        var orphanRejected = CanonicalCompiler.applyDealChangeChecked(
                bootstrap, new CompilerProtocol.ChangeSetPrecondition(inspected.sourceDigest(), fingerprints), orphanAction);
        check(!orphanRejected.accepted(), "an exported nominal action without a handler must reject");
        check(orphanRejected.source().equals(bootstrap), "an orphan action must roll back the whole ChangeSet");
        check(orphanRejected.diagnostics().stream().anyMatch(value ->
                        value.code().equals("UI2050") && value.message().contains("IncrementAction")),
                "the orphan action diagnostic must identify the missing handler");

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

    private static void frameworkDiagnosticsUseCompilerOwnedRepairSlots() {
        String bootstrap = """
                export class AppState { title: string = ""; }
                export function initialState(): AppState { return {title: ""}; }
                """;
        var inspected = deal.compiler.DealCompilerWorkspace.inspect(
                bootstrap, "/generated/app.deal", DealUiDealSource.ADAPTER);
        var module = CanonicalCompiler.queryDealModule(bootstrap);
        var changeInspection = CanonicalCompiler.inspectDealChange(
                bootstrap, inspected.sourceDigest(), List.of(module.ownerId()),
                List.of(deal.compiler.DealCompilerWorkspace.ADD_DECLARATION));
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspected.sourceDigest(), Map.of(
                        module.ownerId().value(), module.allowedOperations().get(0).targetFingerprint()));
        var operations = List.of(
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "export class IncrementAction {}"),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(),
                        "export function update(state: AppState, action: IncrementAction): AppState { return state; }"));
        var staged = CanonicalCompiler.stageDealChange(
                bootstrap, precondition, changeInspection, operations);
        check(!staged.accepted(), "UI2050 must reject inside the compiler-owned workspace");
        check(staged.diagnostics().stream().anyMatch(value -> value.code().equals("UI2050")),
                "workspace must retain the framework diagnostic");
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .toList();
        check(rejected.size() == 1 && rejected.get(0).payload().get("declaration").contains("function update"),
                "only the unannotated handler slot must remain writable: " + staged.workspace().slots());
        check(rejected.get(0).diagnostics().stream().anyMatch(value -> value.code().equals("UI2050"))
                        && rejected.get(0).diagnostics().stream().noneMatch(value -> value.code().equals("E3004")),
                "the active slot must expose dependency-aware framework diagnostics, not isolated false failures");
        var repaired = CanonicalCompiler.patchDealRepairWorkspace(
                bootstrap, staged.workspace(), List.of(new CompilerProtocol.SlotPatch(
                        rejected.get(0).slotId(), Map.of("declaration",
                                "// @ui-update\nexport function update(state: AppState, action: IncrementAction): AppState { return state; }"))));
        check(repaired.accepted(), "a narrow handler patch must commit the preserved action sibling: "
                + repaired.diagnostics());
        check(repaired.source().contains("class IncrementAction")
                        && repaired.source().contains("// @ui-update"),
                "the repaired candidate must retain both dependent declarations");
    }

    private static void borrowedMutationDiagnosticOwnsHandlerSlot() {
        String bootstrap = """
                export class AppState { count: int = 0; }
                export function initialState(): AppState { return {count: 0}; }
                """;
        var inspected = deal.compiler.DealCompilerWorkspace.inspect(
                bootstrap, "/generated/app.deal", DealUiDealSource.ADAPTER);
        var module = CanonicalCompiler.queryDealModule(bootstrap);
        var changeInspection = CanonicalCompiler.inspectDealChange(
                bootstrap, inspected.sourceDigest(), List.of(module.ownerId()),
                List.of(deal.compiler.DealCompilerWorkspace.ADD_DECLARATION));
        var precondition = new CompilerProtocol.ChangeSetPrecondition(
                inspected.sourceDigest(), Map.of(
                        module.ownerId().value(), module.allowedOperations().get(0).targetFingerprint()));
        var operations = List.of(
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), "export class DoneAction {}"),
                new deal.compiler.DealCompilerWorkspace.AddDeclaration(
                        module.ownerId(), """
                                // @ui-update
                                export function updateDone(state: AppState, action: DoneAction): AppState {
                                  let next: AppState = state;
                                  next.count = state.count + 1;
                                  return next;
                                }
                                """));
        var staged = CanonicalCompiler.stageDealChange(
                bootstrap, precondition, changeInspection, operations);
        var rejected = staged.workspace().slots().stream()
                .filter(value -> value.status() == CompilerProtocol.RepairSlotStatus.REJECTED)
                .toList();
        check(rejected.size() == 1
                        && rejected.get(0).payload().get("declaration").contains("function updateDone")
                        && rejected.get(0).diagnostics().stream().anyMatch(value -> value.code().equals("UI2050")),
                "borrowed mutation must open only its owning handler slot: " + staged.workspace().slots());
        check(staged.workspace().slots().get(0).status() == CompilerProtocol.RepairSlotStatus.STAGED,
                "the handler's action type must remain staged");
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

    private static void unreachableActionDiagnosticIsComplete() {
        String deal = DEAL + """
                export class ResetAction {}
                // @ui-update
                export function reset(state: AppState, action: ResetAction): AppState {
                  return {title: state.title, count: 0};
                }
                """;
        String ui = """
                import * as app from "./app.deal";
                import * as ui from "./ui.pack";
                // @ui-root
                export view App(state: app.AppState): View {
                  ui.Column() { ui.Text(value: state.title) }
                }
                """;
        var diagnostic = UiCompilerWorkspace.inspect(deal, ui, PACK, "./ui.pack")
                .diagnostics().stream().filter(value -> value.code().equals("UI2006"))
                .findFirst().orElseThrow();
        check(diagnostic.message().contains("IncrementAction")
                        && diagnostic.message().contains("ResetAction"),
                "one reachability diagnostic must report every missing action binding: " + diagnostic);
        check(diagnostic.expected().contains("Bind every listed action"),
                "reachability repair must publish an actionable compiler contract");
    }

    private static void declaredHostCapabilityRequiresPackComponent() {
        String pack = """
                pack version "host-v1";
                export class ColumnProps {}
                export class ClockProps { onTick: Action; }
                export component Column(props: ColumnProps): View { children optional; }
                export component FrameClock(props: ClockProps): View {
                  event onTick(payload: int); capability "host.clock.frame";
                }
                """;
        String deal = "// generated-capability: clock.frame\n" + DEAL;
        String missing = """
                import * as app from "./app.deal";
                import * as ui from "./ui.pack";
                // @ui-root
                export view App(state: app.AppState): View {
                  ui.Column() {}
                }
                """;
        var diagnostic = UiCompilerWorkspace.inspect(deal, missing, pack, "./ui.pack")
                .diagnostics().stream().filter(value -> value.code().equals("UI2051"))
                .findFirst().orElseThrow();
        check(diagnostic.expected().contains(
                        "ui.FrameClock(onTick: action app.IncrementAction {})")
                        && diagnostic.actual().equals("clock.frame"),
                "missing host capability must publish an exact compatible binding: " + diagnostic);

        String bound = """
                import * as app from "./app.deal";
                import * as ui from "./ui.pack";
                // @ui-root
                export view App(state: app.AppState): View {
                  ui.Column() {
                    ui.FrameClock(onTick: action app.IncrementAction {})
                  }
                }
                """;
        check(UiCompilerWorkspace.inspect(deal, bound, pack, "./ui.pack").diagnostics().isEmpty(),
                "a declared host capability must pass when its pack component is bound");

        String typedDeal = deal.replace(
                "export class IncrementAction {}",
                "export class IncrementAction { deltaMillis: int; }");
        String ignoredPayload = bound.replace(
                "action app.IncrementAction {}",
                "action app.IncrementAction { deltaMillis: 16 }");
        check(UiCompilerWorkspace.inspect(typedDeal, ignoredPayload, pack, "./ui.pack")
                        .diagnostics().stream().anyMatch(value -> value.code().equals("UI2052")),
                "a payload-bearing host event must reject action fields that ignore its payload");
        String consumedPayload = ignoredPayload.replace("deltaMillis: 16", "deltaMillis: payload");
        check(UiCompilerWorkspace.inspect(typedDeal, consumedPayload, pack, "./ui.pack").diagnostics().isEmpty(),
                "a payload-bearing host event must accept a compatible payload-derived action");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
