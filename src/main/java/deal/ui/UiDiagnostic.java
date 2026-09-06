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
    }

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
