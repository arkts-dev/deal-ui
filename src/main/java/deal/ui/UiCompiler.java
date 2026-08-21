package deal.ui;

import deal.codegen.jvm.JvmBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class UiCompiler {
    public record Result(Path sourceFile, Path outputDirectory, Path dealSource,
                         Path generatedJava, Path generatedUiJava, Path uiIr,
                         String moduleClass, String uiClass, UiModel.CheckedProgram program) {}

    interface OutputHook {
        void run(String transition) throws IOException;
    }

    private final Path fsRoot;
    private final Path outputRoot;
    private final OutputHook outputHook;

    public UiCompiler(Path fsRoot, Path outputRoot) {
        this(fsRoot, outputRoot, transition -> {});
    }

    UiCompiler(Path fsRoot, Path outputRoot, OutputHook outputHook) {
        this.fsRoot = fsRoot.toAbsolutePath().normalize();
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.outputHook = outputHook;
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
        Object rootKey = fileKey(outputRoot);
        try (var rootStream = Files.newDirectoryStream(outputRoot)) {
            if (!(rootStream instanceof java.nio.file.SecureDirectoryStream<Path> root)) {
                throw new IOException("Filesystem does not support secure output handling: " + outputRoot);
            }
            outputHook.run("before-staging");
            ensureRootIdentity(rootKey);
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
            outputHook.run("before-publication");
            ensureRootIdentity(rootKey);
            publish(root, staging.getFileName(), output.getFileName());
            return new Result(input, output,
                output.resolve("deal-src").resolve(input.getFileName().toString()),
                output.resolve("generated").resolve(moduleClass + ".java"),
                output.resolve("generated").resolve(uiClass + ".java"), output.resolve("ui.ir.txt"),
                moduleClass, uiClass, checked);
            } catch (IOException | InterruptedException | RuntimeException failure) {
                outputHook.run("before-cleanup");
                ensureRootIdentity(rootKey);
                deleteTree(root, staging.getFileName());
                throw failure;
            }
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
        Path rootReal = outputRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(outputRoot) || !Files.isDirectory(rootReal)
                || !output.startsWith(rootReal) || output.equals(rootReal)
                || output.getParent() == null || !output.getParent().equals(rootReal)
                || sourceReal.startsWith(output)) {
            throw new IOException("Unsafe output directory must be a direct child of the compiler-owned root: "
                + rootReal);
        }
        String name = output.getFileName().toString();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("Unsafe output name: " + name);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe existing output; choose a new compiler-owned output name: " + output);
        }
    }

    private Path createStagingDirectory(Path output) throws IOException {
        return Files.createTempDirectory(outputRoot, ".deal-ui-stage-");
    }

    private Object fileKey(Path path) throws IOException {
        return Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private void ensureRootIdentity(Object expected) throws IOException {
        Object current = fileKey(outputRoot);
        if (expected == null || !expected.equals(current)) {
            throw new IOException("Compiler output root identity changed during compilation: " + outputRoot);
        }
    }

    private void publish(java.nio.file.SecureDirectoryStream<Path> root, Path staging, Path output)
            throws IOException {
        root.move(staging, root, output);
    }

    private void deleteTree(java.nio.file.SecureDirectoryStream<Path> root, Path name) throws IOException {
        if (!name.toString().startsWith(".deal-ui-stage-")) {
            throw new IOException("Refusing to clean non-staging name: " + name);
        }
        deleteSecureDirectory(root, name);
    }

    private void deleteSecureDirectory(java.nio.file.SecureDirectoryStream<Path> parent, Path name)
            throws IOException {
        try (var child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
            if (!(child instanceof java.nio.file.SecureDirectoryStream<Path> secureChild)) {
                throw new IOException("Filesystem does not support secure nested cleanup: " + name);
            }
            List<Path> entries = new ArrayList<>();
            for (Path entry : secureChild) entries.add(entry.getFileName());
            for (Path entry : entries) {
                try {
                    deleteSecureDirectory(secureChild, entry);
                } catch (java.nio.file.NotDirectoryException failure) {
                    secureChild.deleteFile(entry);
                }
            }
        }
        parent.deleteDirectory(name);
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
