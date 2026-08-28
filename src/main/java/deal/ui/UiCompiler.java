package deal.ui;

import deal.codegen.jvm.JvmBackend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiCompiler {
    public record Result(Path viewSource, Path dealSource, Path outputDirectory, Path generatedDirectory,
                         Path generatedUiJava, Path uiIr, String moduleClass, String uiClass,
                         String bridgeClass, UiModel.CheckedProgram program, Map<String, Path> packFiles) {}

    private final Path fsRoot;
    private final Path frameworkRoot;

    public UiCompiler(Path fsRoot) { this(fsRoot, Path.of(System.getProperty("deal.ui.frameworkRoot", Path.of("").toAbsolutePath().toString()))); }
    public UiCompiler(Path fsRoot, Path frameworkRoot) { this.fsRoot = fsRoot.toAbsolutePath().normalize(); this.frameworkRoot = frameworkRoot.toAbsolutePath().normalize(); }

    public Result compile(Path viewSource, Path output) throws IOException, InterruptedException {
        Path view = viewSource.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(view)) throw new IOException("UI source not found: " + view);
        if (Files.exists(destination)) throw new IOException("Output must not exist: " + destination);
        if (!Files.isRegularFile(fsRoot.resolve("build/deal/Main.class"))) throw new IOException("DEAL compiler is not built: " + fsRoot);
        Files.createDirectories(destination);

        UiModel.ViewModule views = UiParser.parseViews(view, Files.readString(view));
        Map<String, UiModel.PackModule> packs = new LinkedHashMap<>();
        Map<String, Path> packFiles = new LinkedHashMap<>();
        Path deal = null;
        for (UiModel.Import imported : views.imports()) {
            Path resolved = resolve(view.getParent(), imported.specifier());
            if (resolved.toString().endsWith(".dealui-pack")) {
                packs.put(imported.specifier(), UiParser.parsePack(resolved, Files.readString(resolved)));
                packFiles.put(imported.specifier(), resolved);
            } else if (resolved.toString().endsWith(".deal")) {
                if (deal != null && !deal.equals(resolved)) throw new IOException("One application DEAL module is supported per UI bundle");
                deal = resolved;
            }
        }
        if (deal == null) throw new IOException("Root view must import its application DEAL module");
        UiChecker checker = new UiChecker();
        UiModel.DealModule dealModule = checker.parseDeal(deal, Files.readString(deal));
        UiModel.CheckedProgram checked = checker.check(view, views, deal, dealModule, packs);
        UiDealGenerator.Output generatedDeal = new UiDealGenerator(checked, frameworkRoot).generate();

        Path project = destination.resolve("deal");
        Path framework = project.resolve("ui");
        Files.createDirectories(framework);
        String appSource = Files.readString(deal).replace("from \"../../ui/interaction\"", "from \"./ui/interaction\"");
        Files.writeString(project.resolve(deal.getFileName()), appSource + generatedDeal.appAugmentation());
        for (String module : List.of("core", "store", "reconcile", "actions", "effects", "interaction")) Files.copy(frameworkRoot.resolve("ui/" + module + ".deal"), framework.resolve(module + ".deal"), StandardCopyOption.REPLACE_EXISTING);
        Path entry = project.resolve("ui_application.deal");
        Files.writeString(entry, generatedDeal.source());

        Path generated = destination.resolve("generated");
        Files.createDirectories(generated);
        run(List.of(javaExecutable(), "-cp", fsRoot.resolve("build").toString(), "deal.Main", "compile",
            entry.toString(), "--backend", "jvm", "--output", generated.toString(), "--dump-ir"), project, "DEAL JVM compilation");
        String moduleClass = JvmBackend.classNameFor(stripSuffix(entry.getFileName().toString(), ".deal"));
        String appClass = JvmBackend.classNameFor(stripSuffix(deal.getFileName().toString(), ".deal"));
        String bridgeClass = moduleClass + "Bridge";
        Path bridge = generated.resolve(bridgeClass + ".java");
        Files.writeString(bridge, new UiJavaBridgeGenerator(checked, generatedDeal, moduleClass, appClass, bridgeClass).generate());
        String uiClass = moduleClass + "Main";
        Path generatedUi = generated.resolve(uiClass + ".java");
        Files.writeString(generatedUi, generateMain(uiClass, bridgeClass));
        Path ir = destination.resolve("ui.ir.txt");
        Files.writeString(ir, new UiIrDumper().dump(checked) + "generated DEAL " + entry + "\ntyped bridge " + bridge + "\n");
        return new Result(view, deal, destination, generated, generatedUi, ir, moduleClass, uiClass, bridgeClass, checked, Map.copyOf(packFiles));
    }

    public void build(Result result, Path runtimeClasses) throws IOException, InterruptedException {
        Path classes = result.outputDirectory().resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of(javacExecutable(), "--release", "25", "-Xlint:all,-serial,-auxiliaryclass", "-Werror",
            "-cp", runtimeClasses.toAbsolutePath().normalize().toString(), "-d", classes.toString()));
        try (var files = Files.list(result.generatedDirectory())) { files.filter(file -> file.toString().endsWith(".java")).sorted().forEach(file -> command.add(file.toString())); }
        run(command, result.outputDirectory(), "generated JVM compilation");
    }

    private String generateMain(String uiClass, String bridgeClass) {
        return "public final class " + uiClass + " {\n  private " + uiClass + "() {}\n  public static void main(String[] args) { deal.ui.UiLauncher.launch(new " + bridgeClass + "()); }\n}\n";
    }
    private Path resolve(Path parent, String specifier) { Path path = parent.resolve(specifier).normalize(); if (Files.isRegularFile(path)) return path; if (Files.isRegularFile(Path.of(path + ".deal"))) return Path.of(path + ".deal"); if (Files.isRegularFile(Path.of(path + ".dealui-pack"))) return Path.of(path + ".dealui-pack"); return path; }
    private void run(List<String> command, Path directory, String label) throws IOException, InterruptedException { Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); int exit = process.waitFor(); if (exit != 0) throw new IOException(label + " failed (" + exit + "):\n" + output); }
    private String stripSuffix(String value, String suffix) { return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value; }
    private String javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private String javacExecutable() { return Path.of(System.getProperty("java.home"), "bin", "javac").toString(); }
}
