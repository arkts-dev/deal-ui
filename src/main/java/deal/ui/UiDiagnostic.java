package deal.ui;

import java.nio.file.Path;

public final class UiDiagnostic extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final transient Path file;
    private final int line;
    private final int column;
    private final String expected;
    private final String actual;

    public UiDiagnostic(String code, String message, Path file, int line, int column) {
        this(code, message, file, line, column, "", "");
    }

    public UiDiagnostic(
            String code, String message, Path file, int line, int column,
            String expected, String actual) {
        super(message);
        this.code = code;
        this.file = file;
        this.line = line;
        this.column = column;
        this.expected = expected;
        this.actual = actual;
    }

    public String code() { return code; }
    public Path file() { return file; }
    public int line() { return line; }
    public int column() { return column; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public String format() { return file + ":" + line + ":" + column + ": error " + code + ": " + getMessage(); }
}
