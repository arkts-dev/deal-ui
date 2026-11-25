package deal.ui;

import java.nio.file.Path;
import java.util.List;

public final class UiLexicalParityTest {
    public static void main(String[] args) {
        for (String literal : List.of("'single'", "\"\\u0041\\x42\\t\\r\\n\\b\\f\"", "\"\\u{1F600}\"", ".5", "0xFF", "0b101", "0o77", "1_000.25", "1e-3")) {
            String source = "view V(): View {ui.Text(value: " + literal + ")}";
            var module = UiParser.parseViews(Path.of("literal.dealui"), source);
            var call = (UiModel.Call)module.views().getFirst().nodes().getFirst();
            if (!(call.arguments().get("value") instanceof UiModel.Literal)) throw new AssertionError(literal);
        }
        for (String literal : List.of("\"raw\nnewline\"", "'raw\rnewline'", "\"\\q\"", "0x", "1__0")) {
            try { UiParser.parseViews(Path.of("bad.dealui"), "view V(): View {ui.Text(value: " + literal + ")}"); throw new AssertionError("Invalid literal accepted: " + literal); }
            catch (UiDiagnostic expected) { /* Core-invalid literal rejected. */ }
        }
        System.out.println("UiLexicalParityTest: core literal forms accepted; malformed/raw-newline literals rejected");
    }
}
