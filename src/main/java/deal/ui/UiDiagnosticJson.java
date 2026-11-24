package deal.ui;

import deal.amend.CompilerProtocol;
import deal.amend.CompilerProtocolJson;
import deal.diagnostics.*;
import deal.semantic.ir.CanonicalJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** UI producer adapter to the shared v3 projection; no independent diagnostic wire format. */
public final class UiDiagnosticJson {
    private UiDiagnosticJson() {}
    private static final Map<String, deal.amend.RepairDiagnosticRegistry.Category> CODES = UiRepairDiagnostics.registry().codes();

    static CompilerDiagnostic producer(UiDiagnostic d) {
        String file = d.file() == null ? "" : d.file().toString();
        String source = d.sources().get(file);
        DiagnosticRange range = range(file, source, d.line(), d.column(), d.endLine(), d.endColumn());
        var facts = d.facts();
        var result = new CompilerDiagnostic(d.code(), "error", d.getMessage(), range, d.notes(), null, List.of(), facts);
        if (source != null) result = result.withOccurrence(DiagnosticOccurrence.fromSource(file, source, result))
                .withExcerpt(DiagnosticExcerpt.fromSource(file, source, range));
        return result;
    }

    static DiagnosticRange range(String file, String source, int line, int column, int endLine, int endColumn) {
        if (source == null) return DiagnosticRange.synthetic(file);
        int start = offset(source, line, column), end = offset(source, endLine, endColumn + 1);
        if (start < 0 || end < start) return DiagnosticRange.synthetic(file);
        return new DiagnosticRange(file, line, column, endLine, endColumn + 1, start, end, end - start, RangeOrigin.SOURCE);
    }

    private static int offset(String source, int line, int column) {
        if (line < 1 || column < 1) return -1;
        int at = 0;
        for (int n = 1; n < line; n++) { int next = source.indexOf('\n', at); if (next < 0) return -1; at = next + 1; }
        int end = source.indexOf('\n', at);
        int result = at + column - 1;
        return result <= (end < 0 ? source.length() : end) ? result : -1;
    }

    public static String encode(Path root, List<CompilerDiagnostic> diagnostics, Map<String, String> sources) {
        return encodeValue(new DiagnosticJson(root, DiagnosticSourceBatch.observed(sources)).compiler(diagnostics, List.of(), null));
    }

    /** Canonical inspection/edit/repair responses share one indexed diagnostic table. */
    public static String encode(Object response) {
        Object value = response;
        if (response instanceof UiCompilerWorkspace.UiInspection inspection) {
            var report = amendment(inspection.diagnostics(), inspection.diagnostics().stream().noneMatch(d -> d.severity().equals("error")));
            report.put("sourceDigest", inspection.sourceDigest()); report.put("documentId", inspection.documentId());
            report.put("appInterfaceFingerprint", inspection.appInterfaceFingerprint());
            report.put("views", inspection.views()); report.put("nodes", inspection.nodes());
            report.put("checkedMetadata", inspection.checkedMetadata()); report.put("allowedOperations", inspection.allowedOperations());
            value = report;
        } else if (response instanceof UiCompilerWorkspace.UiChangeResult change) {
            var report = amendment(change.diagnostics(), change.accepted());
            report.put("source", change.source()); report.put("sourceDigest", change.sourceDigest()); report.put("impact", change.impact());
            value = report;
        } else if (response instanceof CanonicalCompiler.Inspection inspection) {
            var report = amendment(inspection.diagnostics(), inspection.valid());
            report.put("packVersion", inspection.packVersion()); report.put("packDigest", inspection.packDigest());
            report.put("componentPack", inspection.componentPack());
            report.put("deal", metadata(CompilerProtocolJson.toValue(inspection.deal())));
            report.put("dealUi", inspection.dealUi() == null ? null : metadata(CanonicalJson.parse(encode(inspection.dealUi()))));
            value = report;
        }
        return encodeValue(value);
    }

    private static CanonicalJson.Value metadata(CanonicalJson.Value value) {
        if (!(value instanceof CanonicalJson.Obj object)) return value;
        return CanonicalJson.obj(object.entries().stream().filter(e -> !List.of("version", "status", "files", "context", "diagnostics", "failures").contains(e.key())).toList());
    }

    private static Map<String, Object> amendment(List<CompilerProtocol.StructuredDiagnostic> diagnostics, boolean accepted) {
        var report = new DiagnosticJson(null, null).amendment(diagnostics, accepted);
        var failures = diagnostics.stream().filter(d -> d.code().equals("CP2999"))
                .map(d -> DiagnosticJson.object("reason", "ui.analysis.failed", "responsibility", "COMPILER_CONTRACT", "message", d.message())).toList();
        if (!failures.isEmpty()) { report.put("status", "failed"); report.put("failures", failures); }
        return report;
    }

    private static String encodeValue(Object value) {
        return CanonicalJson.serializeText(classify(CompilerProtocolJson.toValue(value)));
    }

    // Explicit UI registrations only. Core producer responsibility is never inferred from a prefix.
    private static CanonicalJson.Value classify(CanonicalJson.Value value) {
        if (value instanceof CanonicalJson.Arr array) return CanonicalJson.arr(array.items().stream().map(UiDiagnosticJson::classify).toList());
        if (!(value instanceof CanonicalJson.Obj object)) return value;
        String code = object.entries().stream().filter(e -> e.key().equals("code") && e.value() instanceof CanonicalJson.Str)
                .map(e -> ((CanonicalJson.Str)e.value()).value()).findFirst().orElse("");
        var category = CODES.get(code);
        String responsibility = category == null ? null : switch (category) {
            case UI_SYNTAX, UI_CONTRACT -> "SOURCE_CONTRACT";
            case RUNTIME -> "RUNTIME_CONTRACT";
            case PROTOCOL -> code.equals("CP2999") ? "COMPILER_CONTRACT" : "PROTOCOL_CONTRACT";
            default -> null;
        };
        return CanonicalJson.obj(object.entries().stream().map(e -> CanonicalJson.e(e.key(),
                responsibility != null && e.key().equals("responsibility") ? CanonicalJson.str(responsibility) : classify(e.value()))).toList());
    }
}
