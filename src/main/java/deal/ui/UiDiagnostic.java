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
    private transient java.util.List<deal.diagnostics.CompilerDiagnostic> compilerDiagnostics = java.util.List.of();
    private transient java.util.Map<String, String> sources = java.util.Map.of();
    private transient deal.diagnostics.DiagnosticFacts facts;
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
        this.compilerDiagnostics = java.util.List.of(diagnostic);
    }

    /** One exception transports the entire ordered producer result, including warnings. */
    public static UiDiagnostic compilerBatch(java.util.List<deal.diagnostics.CompilerDiagnostic> diagnostics,
                                              Path file, String source) {
        var first = diagnostics.stream().filter(d -> d.severity().equals("error")).findFirst().orElseThrow();
        var result = new UiDiagnostic(first);
        result.compilerDiagnostics = java.util.List.copyOf(diagnostics);
        return result.withSources(java.util.Map.of(file.toString(), source));
    }

    /** Capture consumed bytes now; serialization never rereads paths or substitutes newer text. */
    public UiDiagnostic withSources(java.util.Map<String, String> observed) {
        var all = new java.util.LinkedHashMap<>(sources);
        observed.forEach(all::putIfAbsent);
        sources = java.util.Map.copyOf(all);
        return this;
    }

    public java.util.List<deal.diagnostics.CompilerDiagnostic> compilerDiagnostics() { return compilerDiagnostics; }
    public java.util.Map<String, String> sources() { return sources; }
    public deal.diagnostics.DiagnosticFacts facts() {
        return facts == null ? new deal.diagnostics.DiagnosticFacts(code, "ui.check", expected, actual, java.util.List.of()) : facts;
    }
    public UiDiagnostic withFacts(deal.diagnostics.DiagnosticFacts value) { facts = java.util.Objects.requireNonNull(value); return this; }

    public java.util.List<deal.diagnostics.CompilerDiagnostic> diagnostics() {
        if (!compilerDiagnostics.isEmpty()) return compilerDiagnostics;
        return java.util.List.of(UiDiagnosticJson.producer(this));
    }

    public String toJson(Path projectRoot) { return UiDiagnosticJson.encode(projectRoot, diagnostics(), sources); }

    public String toJson(Path projectRoot, java.util.Map<String, String> observed) {
        return withSources(observed).toJson(projectRoot);
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
        result.facts = facts;
        result.compilerDiagnostic = compilerDiagnostic;
        result.compilerDiagnostics = compilerDiagnostics;
        result.sources = sources;
        return result;
    }

    /** The actual node in this check's AST, not an identity reconstructed from source coordinates. */
    public UiModel.Node owningNode() { return owningNode; }

    UiDiagnostic atNode(UiModel.Node node) {
        if (owningNode != null) return this;
        var result = new UiDiagnostic(code, getMessage(), file, line, column, endLine, endColumn, expected, actual, notes, repairArtifact);
        result.owningNode = java.util.Objects.requireNonNull(node);
        result.facts = facts;
        result.compilerDiagnostic = compilerDiagnostic;
        result.compilerDiagnostics = compilerDiagnostics;
        result.sources = sources;
        return result;
    }

    /** Exact producer diagnostic, including facts/range/revision; null for UI-owned rules. */
    public deal.diagnostics.CompilerDiagnostic compilerDiagnostic() { return compilerDiagnostic; }

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
