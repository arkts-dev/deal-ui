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
                diagnostic.range().endLine(), diagnostic.range().endColumn(), "", "", diagnostic.notes());
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
        return result;
    }

    /** The actual node in this check's AST, not an identity reconstructed from source coordinates. */
    public UiModel.Node owningNode() { return owningNode; }

    UiDiagnostic atNode(UiModel.Node node) {
        if (owningNode != null) return this;
        var result = new UiDiagnostic(code, getMessage(), file, line, column, endLine, endColumn, expected, actual, notes, repairArtifact);
        result.owningNode = java.util.Objects.requireNonNull(node);
        return result;
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
