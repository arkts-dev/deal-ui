package deal.ui;

import java.nio.file.Path;

public final class UiDiagnostic extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient UiModel.SourceSpan span;

    public UiDiagnostic(String code, String message, UiModel.SourceSpan span) {
        super(message);
        this.code = code;
        this.span = span;
    }

    public UiDiagnostic(String code, String message, Path file, int line, int column) {
        this(code, message, new UiModel.SourceSpan(file, line, column, line, column));
    }

    public String code() {
        return code;
    }

    public UiModel.SourceSpan span() {
        return span;
    }

    public String format() {
        return span.file() + ":" + span.startLine() + ":" + span.startColumn()
            + ": error " + code + ": " + getMessage();
    }
}
