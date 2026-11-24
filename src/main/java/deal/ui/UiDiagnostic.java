package deal.ui;

import java.nio.file.Path;

public final class UiDiagnostic extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final transient Path file;
    private final int line;
    private final int column;
    private final int endLine;
    private final int endColumn;
    private final String expected;
    private final String actual;
    private final String repairArtifact;
    private transient UiModel.Node owningNode;
    private transient deal.diagnostics.CompilerDiagnostic compilerDiagnostic;
    private final transient java.util.List<deal.diagnostics.DiagnosticNote> notes;

    public UiDiagnostic(String code, String message, Path file, int line, int column) {
        this(code, message, file, line, column, "", "");
    }

    public UiDiagnostic(
            String code, String message, Path file, int line, int column,
            String expected, String actual) {
        this(code, message, file, line, column, line, column, expected, actual);
    }

    public UiDiagnostic(String code, String message, Path file, int line, int column,
                        int endLine, int endColumn, String expected, String actual) {
        this(code, message, file, line, column, endLine, endColumn, expected, actual, java.util.List.of());
    }

    public UiDiagnostic(deal.diagnostics.CompilerDiagnostic diagnostic) {
        this(diagnostic.code(), diagnostic.message(), Path.of(diagnostic.range().file()),
                diagnostic.range().startLine(), diagnostic.range().startColumn(),
                diagnostic.range().endLine(), Math.max(1, diagnostic.range().endColumn() - 1),
                diagnostic.facts().expected(), diagnostic.facts().actual(), diagnostic.notes());
        this.compilerDiagnostic = diagnostic;
    }

    private UiDiagnostic(String code, String message, Path file, int line, int column,
                         int endLine, int endColumn, String expected, String actual,
                         java.util.List<deal.diagnostics.DiagnosticNote> notes) {
        this(code, message, file, line, column, endLine, endColumn, expected, actual, notes, "");
    }

    private UiDiagnostic(String code, String message, Path file, int line, int column,
                         int endLine, int endColumn, String expected, String actual,
                         java.util.List<deal.diagnostics.DiagnosticNote> notes, String repairArtifact) {
        super(message);
        this.code = code;
        this.file = file;
        this.line = line;
        this.column = column;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.expected = expected;
        this.actual = actual;
        this.notes = java.util.List.copyOf(notes);
        this.repairArtifact = repairArtifact;
    }

    public UiDiagnostic withRepairArtifact(String artifact) {
        if (!java.util.List.of("deal", "dealui", "pack").contains(artifact)) throw new IllegalArgumentException("Unknown repair artifact");
        var result = new UiDiagnostic(code, getMessage(), file, line, column, endLine, endColumn, expected, actual, notes, artifact);
        result.owningNode = owningNode;
        result.compilerDiagnostic = compilerDiagnostic;
        return result;
    }

    /** The actual node in this check's AST, not an identity reconstructed from source coordinates. */
    public UiModel.Node owningNode() { return owningNode; }

    UiDiagnostic atNode(UiModel.Node node) {
        if (owningNode != null) return this;
        var result = new UiDiagnostic(code, getMessage(), file, line, column, endLine, endColumn, expected, actual, notes, repairArtifact);
        result.owningNode = java.util.Objects.requireNonNull(node);
        result.compilerDiagnostic = compilerDiagnostic;
        return result;
    }

    /** Exact producer diagnostic, including facts/range/revision; null for UI-owned rules. */
    public deal.diagnostics.CompilerDiagnostic compilerDiagnostic() { return compilerDiagnostic; }

    /** The compiler v3 serializer owns snapshot/file/context indexes for both core and UI diagnostics. */
    public String toJson(Path projectRoot, java.util.Map<String, String> sources) {
        var producer = compilerDiagnostic;
        if (producer == null) {
            String path = file == null ? "" : file.toString();
            var range = new deal.diagnostics.DiagnosticRange(path, line, column, endLine, endColumn + 1,
                    -1, -1, -1, deal.diagnostics.RangeOrigin.SOURCE);
            producer = new deal.diagnostics.CompilerDiagnostic(code, "error", getMessage(), range, notes,
                    null, java.util.List.of(), new deal.diagnostics.DiagnosticFacts(code, "ui.check", expected, actual, java.util.List.of()));
            String source = sources.get(path);
            if (source != null) producer = producer.withOccurrence(deal.diagnostics.DiagnosticOccurrence.fromSource(path, source, producer));
        }
        var report = new deal.diagnostics.DiagnosticJson(projectRoot, deal.diagnostics.DiagnosticSourceBatch.observed(sources))
                .compiler(java.util.List.of(producer), java.util.List.of(), null);
        if (compilerDiagnostic == null) {
            // This producer owns UI source rules; never infer responsibility from a diagnostic-code prefix.
            @SuppressWarnings("unchecked") var diagnostics = (java.util.List<java.util.Map<String, Object>>) report.get("diagnostics");
            diagnostics.getFirst().put("responsibility", "SOURCE_CONTRACT");
        }
        return deal.amend.CompilerProtocolJson.encode(report);
    }

    public String repairArtifact() { return repairArtifact; }

    public String code() { return code; }
    public Path file() { return file; }
    public int line() { return line; }
    public int column() { return column; }
    public int endLine() { return endLine; }
    public int endColumn() { return endColumn; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public java.util.List<deal.diagnostics.DiagnosticNote> notes() {
        return notes == null ? java.util.List.of() : notes;
    }
    public String format() { return file + ":" + line + ":" + column + ": error " + code + ": " + getMessage(); }
}
