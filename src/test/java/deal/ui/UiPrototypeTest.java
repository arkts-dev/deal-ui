package deal.ui;

import deal.ui.runtime.SwingUiRuntime;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UiPrototypeTest {
    private static int passed;

    private UiPrototypeTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve("examples/museum/museum.deal");
        Path outputRoot = root.resolve("build/test-outputs");
        recreateEmpty(outputRoot);
        Path output = outputRoot.resolve("museum");
        UiCompiler compiler = new UiCompiler(fsRoot(), outputRoot);
        UiCompiler.Result result = compiler.compile(source, output);
        compiler.build(result, root.resolve("build/classes"));

        check(Files.readString(result.uiIr()).contains("root view MuseumCard"), "typed UI IR has root");
        check(Files.readString(result.uiIr()).contains("action(ToggleDetails)"), "typed UI IR has action");
        check(Files.readString(result.generatedJava()).contains("public static $C_MuseumState update"),
            "DEAL JVM backend generated exported update");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
            output.resolve("classes").toUri().toURL()
        }, UiPrototypeTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> generatedUi = Class.forName(result.uiClass(), true, loader);
            UiModel.CheckedProgram generatedProgram = (UiModel.CheckedProgram) generatedUi.getMethod("program").invoke(null);
            check(generatedProgram.ir().name().equals("MuseumCard"),
                "generated UI artifact reconstructs the typed root view");
            check(generatedProgram.ir().span().startLine() == 22,
                "generated typed IR preserves root source span");
            try (UiProgramRuntime runtime = new UiProgramRuntime(generatedProgram, result.moduleClass())) {
                check(runtime.stateSnapshot().get("expanded").equals(false), "initial DEAL state is collapsed");
                check(runtime.tree().span().startLine() == 23, "runtime node preserves source span");
                check(texts(runtime.tree()).equals(List.of("The Starry Night", "Vincent van Gogh")),
                    "initial tree uses DEAL state");
                JComponent component = runtime.renderer().renderForTesting(runtime.tree());
                AbstractButton button = findButton(component);
                check(button != null && button.getText().equals("Toggle details"), "Swing button is materialized");
                button.doClick();
                check(runtime.stateSnapshot().get("expanded").equals(true), "button dispatch invokes DEAL update");
                check(texts(runtime.tree()).contains("Painted in 1889"), "recomposition reveals conditional text");
                JComponent expanded = runtime.renderer().renderForTesting(runtime.tree());
                check(findLabel(expanded, "Painted in 1889") != null, "expanded Swing tree is materialized");
                findButton(expanded).doClick();
                check(runtime.stateSnapshot().get("expanded").equals(false), "repeated action returns to collapsed state");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        expectDiagnostic("UI2003", sourceText().replace("Card {", "Canvas {"));
        expectDiagnostic("UI2004", sourceText().replace("Text(value: state.title)", "Text(label: state.title)"));
        expectDiagnostic("UI2006", sourceText().replace("Text(value: state.title)", "Text(value: state.expanded)"));
        expectDiagnostic("UI2008", sourceText().replace("Text(value: state.title)",
            "Text(value: state.title) { Text(value: state.artist) }"));
        expectDiagnostic("UI2009", sourceText().replace("When(state.expanded)", "When(state.title)"));
        expectDiagnostic("UI2015", sourceText().replace("Text(value: state.title)", "Text(value: state.missing)"));
        expectDiagnostic("UI2020", sourceText().replace("action: ToggleDetails", "action: MuseumState"));
        expectDiagnostic("UI2023", sourceText().replace("  Card {", "  Text(value: state.title)\n  Card {"));
        expectDiagnostic("UI1001", sourceText().replace("Text(value: state.title)", "let value: string"));
        expectDiagnostic("UI1001", sourceText().replace("Text(value: state.title)", "Text(state.title)"));
        expectDiagnostic("UI1007", sourceText().replace("// @ui-root\n", ""));
        expectCompilerFailure(sourceText().replace("expanded: boolean = false", "expanded: string = false"));
        expectUnsafeOutput(source, root, outputRoot);
        expectUnsafeOutput(source, source.getParent(), outputRoot);
        expectUnsafeOutput(source, fsRoot(), outputRoot);
        Path occupied = outputRoot.resolve("occupied");
        Files.createDirectory(occupied);
        Path userFile = occupied.resolve("important.txt");
        Files.writeString(userFile, "retain");
        expectUnsafeOutput(source, occupied, outputRoot);
        check(Files.readString(userFile).equals("retain"), "existing output contents are retained");
        Path symlink = outputRoot.resolve("output-link");
        Files.createSymbolicLink(symlink, root.resolve("build"));
        expectUnsafeOutput(source, symlink, outputRoot);
        expectRootSubstitution(source, root, "before-output-creation");
        expectRootSubstitution(source, root, "after-output-creation");

        System.out.println("Passed: " + passed);
    }

    private static void expectRootSubstitution(Path source, Path root, String transition) throws Exception {
        Path parent = root.resolve("build/substitution-" + transition);
        recreateEmpty(parent);
        Path owned = parent.resolve("owned");
        Path moved = parent.resolve("moved");
        Path attacker = parent.resolve("attacker");
        Files.createDirectory(owned);
        Files.createDirectory(attacker);
        Path sentinel = attacker.resolve("sentinel.txt");
        Files.writeString(sentinel, "retain");
        UiCompiler.OutputHook hook = current -> {
            if (current.equals(transition)) {
                Files.move(owned, moved);
                Files.createSymbolicLink(owned, attacker);
            }
        };
        try {
            new UiCompiler(fsRoot(), owned, hook).compile(source, owned.resolve("result"));
            throw new AssertionError("Expected substituted root rejection at " + transition);
        } catch (java.io.IOException failure) {
            check(failure.getMessage().contains("identity changed") || failure.getMessage().contains("forced failure"),
                "root substitution fails closed at " + transition);
            check(Files.readString(sentinel).equals("retain"),
                "root substitution retains attacker data at " + transition);
        }
    }

    private static void expectUnsafeOutput(Path source, Path output, Path outputRoot) throws Exception {
        try {
            new UiCompiler(fsRoot(), outputRoot).compile(source, output);
            throw new AssertionError("Expected unsafe output rejection: " + output);
        } catch (java.io.IOException failure) {
            check(failure.getMessage().contains("Unsafe"), "unsafe output is rejected: " + output);
        }
    }

    private static void expectCompilerFailure(String source) throws Exception {
        Path file = Files.createTempFile("deal-ui-invalid-deal-", ".deal");
        try {
            Files.writeString(file, source);
            try {
                Path parent = Files.createTempDirectory("deal-ui-invalid-build-");
                new UiCompiler(fsRoot(), parent).compile(file, parent.resolve("output"));
                throw new AssertionError("Expected authoritative DEAL compiler failure");
            } catch (java.io.IOException failure) {
                check(failure.getMessage().contains("DEAL JVM compilation failed"),
                    "authoritative DEAL compiler rejects invalid ordinary DEAL");
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void expectDiagnostic(String code, String source) throws Exception {
        Path file = Files.createTempFile("deal-ui-invalid-", ".deal");
        try {
            Files.writeString(file, source);
            UiModel.ParsedSource parsed = UiParser.parse(file, source);
            new UiChecker().check(file, parsed);
            throw new AssertionError("Expected diagnostic " + code);
        } catch (UiDiagnostic diagnostic) {
            check(diagnostic.code().equals(code), "diagnostic " + code + " is reported");
            check(diagnostic.span().startLine() > 0 && diagnostic.span().startColumn() > 0,
                "diagnostic " + code + " carries source location");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static List<String> texts(SwingUiRuntime.Node node) {
        List<String> result = new ArrayList<>();
        collectTexts(node, result);
        return result;
    }

    private static void collectTexts(SwingUiRuntime.Node node, List<String> result) {
        if (node instanceof SwingUiRuntime.TextNode text) {
            result.add(text.text());
        } else if (node instanceof SwingUiRuntime.CardNode card) {
            card.children().forEach(child -> collectTexts(child, result));
        } else if (node instanceof SwingUiRuntime.ColumnNode column) {
            column.children().forEach(child -> collectTexts(child, result));
        }
    }

    private static AbstractButton findButton(Component component) {
        if (component instanceof AbstractButton button) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                AbstractButton found = findButton(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JLabel findLabel(Component component, String text) {
        if (component instanceof JLabel label && label.getText().equals(text)) {
            return label;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = findLabel(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String sourceText() throws Exception {
        return Files.readString(Path.of("examples/museum/museum.deal"));
    }

    private static Path fsRoot() {
        String configured = System.getenv("DEAL_FS_ROOT");
        return configured == null || configured.isBlank()
            ? Path.of("/home/igelhaus/coding/deal/fs") : Path.of(configured);
    }

    private static void recreateEmpty(Path directory) throws Exception {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(directory);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        passed++;
    }
}
