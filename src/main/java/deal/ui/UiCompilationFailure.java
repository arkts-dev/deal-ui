package deal.ui;

/** Complete canonical v3 report from the downstream DEAL compiler, never reconstructed from stderr. */
public final class UiCompilationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String report;
    private final int status;
    public UiCompilationFailure(int status, String report) {
        super("DEAL compilation failed (status=" + status + ")");
        this.status = status;
        this.report = java.util.Objects.requireNonNull(report);
    }
    public int status() { return status; }
    public String toJson() { return report; }
}
