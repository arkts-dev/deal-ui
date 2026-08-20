package deal.ui;

import deal.codegen.jvm.JvmBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class UiCompiler {
    public record Result(Path sourceFile, Path outputDirectory, Path dealSource,
                         Path generatedJava, Path generatedUiJava, Path uiIr,
                         String moduleClass, String uiClass, UiModel.CheckedProgram program) {}

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
        recreateDirectory(output);
        Path dealSource = output.resolve("deal-src").resolve(input.getFileName().toString());
        Files.createDirectories(dealSource.getParent());
        Files.writeString(dealSource, parsed.dealSource());
        Path jvmOutput = output.resolve("generated");
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
        Path uiIr = output.resolve("ui.ir.txt");
        Files.writeString(uiIr, new UiIrDumper().dump(checked));
        String uiClass = moduleClass + "Ui";
        Path generatedUiJava = jvmOutput.resolve(uiClass + ".java");
        Files.writeString(generatedUiJava, new UiJavaGenerator().generate(checked, moduleClass, uiClass));
        return new Result(input, output, dealSource, generatedJava, generatedUiJava, uiIr,
            moduleClass, uiClass, checked);
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
        Path sourceParent = sourceReal.getParent();
        Path fsReal = fsRoot.toRealPath();
        Path cwdReal = Path.of("").toAbsolutePath().normalize().toRealPath();
        Path outputAbsolute = output.toAbsolutePath().normalize();
        Path existing = outputAbsolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("Output has no existing filesystem ancestor: " + output);
        if (Files.isSymbolicLink(existing)) {
            throw new IOException("Unsafe symbolic-link output path: " + existing);
        }
        Path existingReal = existing.toRealPath();
        Path resolved = existing.getNameCount() == outputAbsolute.getNameCount()
            ? existingReal
            : existingReal.resolve(outputAbsolute.subpath(existing.getNameCount(),
                outputAbsolute.getNameCount())).normalize();
        if (resolved.getParent() == null || resolved.equals(cwdReal) || resolved.equals(fsReal)
                || resolved.startsWith(fsReal) || sourceReal.startsWith(resolved)
                || (sourceParent != null && sourceParent.startsWith(resolved))
                || Files.exists(resolved.resolve(".git"))) {
            throw new IOException("Unsafe output directory overlaps source, repository, compiler, or filesystem root: "
                + outputAbsolute);
        }
        Path cursor = outputAbsolute;
        while (cursor != null && !cursor.equals(existing)) {
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Unsafe symbolic-link output path: " + cursor);
            }
            cursor = cursor.getParent();
        }
    }

    private void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.createDirectories(directory);
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
