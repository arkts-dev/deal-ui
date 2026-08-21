package deal.ui;

import deal.codegen.jvm.JvmBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class UiCompiler {
    public record Result(Path sourceFile, Path outputDirectory, Path dealSource,
                         Path generatedJava, Path generatedUiJava, Path uiIr,
                         String moduleClass, String uiClass, UiModel.CheckedProgram program) {}

    private static final String OWNER_MARKER = ".deal-ui-output";
    private final Path fsRoot;

    public UiCompiler(Path fsRoot) {
        this.fsRoot = fsRoot.toAbsolutePath().normalize();
    }

    public Result compile(Path sourceFile, Path outputDirectory) throws IOException, InterruptedException {
        Path input = sourceFile.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(input)) {
            throw new IOException("UI source file not found: " + input);
        }
        if (!Files.isRegularFile(fsRoot.resolve("docs/spec-v1.2.md"))) {
            throw new IOException("Authoritative DEAL specification not found: "
                + fsRoot.resolve("docs/spec-v1.2.md"));
        }
        if (!Files.isRegularFile(fsRoot.resolve("build/deal/Main.class"))) {
            throw new IOException("DEAL compiler classes not found under " + fsRoot.resolve("build")
                + "; run /home/igelhaus/coding/deal/fs/run_tests.sh first");
        }
        validateOutput(input, output);
        String source = Files.readString(input);
        UiModel.ParsedSource parsed = UiParser.parse(input, source);
        Path staging = createStagingDirectory(output);
        try {
            Path dealSource = staging.resolve("deal-src").resolve(input.getFileName().toString());
            Files.createDirectories(dealSource.getParent());
            Files.writeString(dealSource, parsed.dealSource());
            Path jvmOutput = staging.resolve("generated");
            Files.createDirectories(jvmOutput);
            run(List.of(
                javaExecutable(),
                "-cp", fsRoot.resolve("build").toString(),
                "deal.Main",
                "compile", dealSource.toString(),
                "--backend", "jvm",
                "--output", jvmOutput.toString(),
                "--dump-ir"
            ), fsRoot, "DEAL JVM compilation");
            String modulePath = stripDealSuffix(dealSource.getFileName().toString());
            String moduleClass = JvmBackend.classNameFor(modulePath);
            Path generatedJava = jvmOutput.resolve(moduleClass + ".java");
            if (!Files.isRegularFile(generatedJava)) {
                throw new IOException("DEAL JVM backend did not emit expected artifact: " + generatedJava);
            }
            UiModel.CheckedProgram checked = new UiChecker().check(input, parsed);
            Path uiIr = staging.resolve("ui.ir.txt");
            Files.writeString(uiIr, new UiIrDumper().dump(checked));
            String uiClass = moduleClass + "Ui";
            Path generatedUiJava = jvmOutput.resolve(uiClass + ".java");
            Files.writeString(generatedUiJava, new UiJavaGenerator().generate(checked, moduleClass, uiClass));
            Files.writeString(staging.resolve(OWNER_MARKER), "DEAL UI compiler output\n");
            publish(staging, output);
            return new Result(input, output,
                output.resolve("deal-src").resolve(input.getFileName().toString()),
                output.resolve("generated").resolve(moduleClass + ".java"),
                output.resolve("generated").resolve(uiClass + ".java"), output.resolve("ui.ir.txt"),
                moduleClass, uiClass, checked);
        } catch (IOException | InterruptedException | RuntimeException failure) {
            deleteTree(staging);
            throw failure;
        }
    }

    public void build(Result result, Path runtimeClasses) throws IOException, InterruptedException {
        Path classes = result.outputDirectory().resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>();
        command.add(javacExecutable());
        command.add("--release");
        command.add("25");
        command.add("-cp");
        command.add(runtimeClasses.toAbsolutePath().normalize().toString());
        command.add("-d");
        command.add(classes.toString());
        try (var paths = Files.list(result.generatedJava().getParent())) {
            paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .forEach(path -> command.add(path.toString()));
        }
        run(command, result.outputDirectory(), "generated JVM artifact compilation");
    }

    private void run(List<String> command, Path directory, String operation)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(operation + " failed with exit code " + exit + ":\n" + output);
        }
    }

    private void validateOutput(Path source, Path output) throws IOException {
        Path sourceReal = source.toRealPath();
        Path fsReal = fsRoot.toRealPath();
        Path outputAbsolute = output.toAbsolutePath().normalize();
        if (outputAbsolute.getParent() == null || sourceReal.startsWith(outputAbsolute)
                || outputAbsolute.startsWith(fsReal)) {
            throw new IOException("Unsafe output directory overlaps source, compiler, or filesystem root: "
                + outputAbsolute);
        }
        rejectSymbolicLinks(outputAbsolute);
        if (Files.exists(outputAbsolute, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(outputAbsolute, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(outputAbsolute.resolve(OWNER_MARKER), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe unowned output directory: " + outputAbsolute);
            }
        }
    }

    private void rejectSymbolicLinks(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Unsafe symbolic-link output path: " + cursor);
            }
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) break;
        }
    }

    private Path createStagingDirectory(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent == null) throw new IOException("Output directory requires a parent: " + output);
        Files.createDirectories(parent);
        rejectSymbolicLinks(parent);
        return Files.createTempDirectory(parent, ".deal-ui-stage-");
    }

    private void publish(Path staging, Path output) throws IOException {
        validateOwnedDestination(output);
        Path backup = null;
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            backup = output.resolveSibling(".deal-ui-backup-" + java.util.UUID.randomUUID());
            Files.move(output, backup, StandardCopyOption.ATOMIC_MOVE);
            if (!Files.isRegularFile(backup.resolve(OWNER_MARKER), LinkOption.NOFOLLOW_LINKS)) {
                Files.move(backup, output, StandardCopyOption.ATOMIC_MOVE);
                throw new IOException("Output ownership changed during publication: " + output);
            }
        }
        try {
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            if (backup != null && !Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(backup, output, StandardCopyOption.ATOMIC_MOVE);
            }
            throw failure;
        }
        if (backup != null) deleteTree(backup);
    }

    private void validateOwnedDestination(Path output) throws IOException {
        rejectSymbolicLinks(output);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(output.resolve(OWNER_MARKER), LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Unsafe unowned output directory: " + output);
        }
    }

    private void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private String stripDealSuffix(String name) {
        return name.endsWith(".deal") ? name.substring(0, name.length() - 5) : name;
    }

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private String javacExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "javac").toString();
    }
}
