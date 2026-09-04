package deal.ui;

import deal.compiler.CompilerProtocol.ChangeResult;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocol.StructuredDiagnostic;
import deal.compiler.DealCompilerWorkspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Stateless cross-artifact facade over the DEAL and Deal UI transpilers. */
public final class CanonicalCompiler {
    private CanonicalCompiler() {}

    public record Inspection(
            deal.compiler.CompilerProtocol.Inspection deal,
            UiCompilerWorkspace.UiInspection dealUi,
            String packVersion,
            String packDigest,
            boolean valid,
            List<StructuredDiagnostic> diagnostics) {
        public Inspection {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record EditContract(
            String code,
            SemanticId ownerId,
            List<RepairScope> allowedOperations,
            String minimumContextQuery,
            String expected,
            String actual) {
        public EditContract {
            allowedOperations = List.copyOf(allowedOperations);
        }
    }

    public static Inspection inspectCanonicalApp(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier) {
        var deal = DealCompilerWorkspace.inspect(
                dealSource, "/generated/app.deal", DealUiDealSource.ADAPTER);
        UiModel.PackModule pack;
        try {
            pack = UiParser.parsePack(Path.of("/generated/platform-ui.dealui-pack"), packSource);
        } catch (UiDiagnostic failure) {
            StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                    failure.code(), "error", failure.getMessage(),
                    new deal.compiler.CompilerProtocol.SourceRange(
                            failure.file().toString(), failure.line(), failure.column(), failure.line(), failure.column()),
                    new SemanticId("dealui:pack:platform"), "valid component pack", "invalid pack",
                    List.of(), List.of(), "inspectComponentPack");
            List<StructuredDiagnostic> diagnostics = new ArrayList<>(deal.diagnostics());
            diagnostics.add(diagnostic);
            return new Inspection(deal, null, "", "", false, diagnostics);
        }
        List<StructuredDiagnostic> diagnostics = new ArrayList<>(deal.diagnostics());
        if (hasErrors(diagnostics)) {
            return new Inspection(deal, null, pack.version(), pack.digest(), false, diagnostics);
        }
        var dealUi = UiCompilerWorkspace.inspect(dealSource, dealUiSource, packSource, packSpecifier);
        diagnostics.addAll(dealUi.diagnostics());
        return new Inspection(
                deal, dealUi, pack.version(), pack.digest(), !hasErrors(diagnostics), diagnostics);
    }

    public static Inspection compileCanonicalApp(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier) {
        return inspectCanonicalApp(dealSource, dealUiSource, packSource, packSpecifier);
    }

    public static ChangeResult applyDealChange(
            String source,
            String baseDigest,
            List<? extends DealCompilerWorkspace.Operation> operations) {
        return DealCompilerWorkspace.apply(
                source, "/generated/app.deal", baseDigest, operations, DealUiDealSource.ADAPTER);
    }

    public static UiCompilerWorkspace.UiChangeResult applyDealUiChange(
            String dealSource,
            String source,
            String packSource,
            String packSpecifier,
            String baseDigest,
            List<? extends UiCompilerWorkspace.Operation> operations) {
        return UiCompilerWorkspace.apply(
                dealSource, source, packSource, packSpecifier, baseDigest, operations);
    }

    public static EditContract explainDiagnostic(StructuredDiagnostic diagnostic) {
        return new EditContract(
                diagnostic.code(), diagnostic.ownerId(), diagnostic.repairScopes(),
                diagnostic.contextQuery(), diagnostic.expected(), diagnostic.actual());
    }

    private static boolean hasErrors(List<StructuredDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity().equals("error"));
    }
}
