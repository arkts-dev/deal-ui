package deal.ui;

import deal.ast.ImportDeclaration;
import deal.ast.StatementNode;
import deal.ast.TokenType;
import deal.codegen.jvm.JvmBackend;
import deal.diagnostics.CompilerDiagnostic;
import deal.lexer.LexResult;
import deal.lexer.Lexer;
import deal.lexer.Token;
import deal.module.StdlibModuleResolver;
import deal.parser.ParseResult;
import deal.parser.Parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiCompiler {
    public enum Target { SWING, PORTABLE }

    public record Result(Path viewSource, Path dealSource, Path outputDirectory, Path generatedDirectory,
                         Path generatedUiJava, Path uiIr, String moduleClass, String uiClass,
                         String bridgeClass, UiModel.CheckedProgram program, Map<String, Path> packFiles) {}

    private final Path fsRoot;
    private final Path frameworkRoot;

    public UiCompiler(Path fsRoot) { this(fsRoot, Path.of(System.getProperty("deal.ui.frameworkRoot", Path.of("").toAbsolutePath().toString()))); }
    public UiCompiler(Path fsRoot, Path frameworkRoot) { this.fsRoot = fsRoot.toAbsolutePath().normalize(); this.frameworkRoot = frameworkRoot.toAbsolutePath().normalize(); }

    public Result compile(Path viewSource, Path output) throws IOException, InterruptedException {
        return compile(viewSource, output, Target.SWING);
    }

    public Result compile(Path viewSource, Path output, Target target) throws IOException, InterruptedException {
        Path view = viewSource.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(view)) throw new IOException("UI source not found: " + view);
        if (Files.exists(destination)) throw new IOException("Output must not exist: " + destination);
        if (!Files.isRegularFile(fsRoot.resolve("build/deal/Main.class"))) throw new IOException("DEAL compiler is not built: " + fsRoot);
        Files.createDirectories(destination);
        Path storageDeclaration = frameworkRoot.resolve("ui/host/storage.d.deal");
        if (!Files.isRegularFile(storageDeclaration)) throw new IOException("Deal UI storage host declaration is missing: " + storageDeclaration);
        Path bindings = destination.resolve("bindings");
        Files.createDirectories(bindings);
        Files.copy(storageDeclaration, bindings.resolve("storage.d.deal"));
        Files.writeString(destination.resolve("deal.json"), "{\n  \"languageVersion\": \"1.2\",\n  \"backend\": \"jvm\",\n  \"moduleRoots\": [\"deal\"],\n  \"externals\": {\n    \"host/storage\": { \"declaration\": \"bindings/storage.d.deal\" }\n  }\n}\n");

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
        Files.createDirectories(project);
        Path stagedApplication = stageDependencies(deal, project, generatedDeal.appAugmentation());
        Path entry = project.resolve("ui_application.deal");
        Files.writeString(entry, rewriteApplicationImport(generatedDeal.source(), deal, relativeSpecifier(entry, stagedApplication)));

        Path generated = destination.resolve("generated");
        Files.createDirectories(generated);
        run(List.of(javaExecutable(), "-cp", fsRoot.resolve("build").toString(), "deal.Main", "compile",
            entry.toString(), "--backend", "jvm", "--output", generated.toString(), "--dump-ir"), fsRoot, "DEAL JVM compilation");
        String moduleClass = JvmBackend.classNameFor(stripSuffix(entry.getFileName().toString(), ".deal"));
        String appClass = JvmBackend.classNameFor(stripSuffix(project.relativize(stagedApplication).toString().replace(java.io.File.separatorChar, '/'), ".deal"));
        String bridgeClass = moduleClass + "Bridge";
        Path bridge = generated.resolve(bridgeClass + ".java");
        UiJavaBridgeGenerator.Target bridgeTarget = target == Target.PORTABLE ? UiJavaBridgeGenerator.Target.PORTABLE : UiJavaBridgeGenerator.Target.SWING;
        Files.writeString(bridge, new UiJavaBridgeGenerator(checked, generatedDeal, moduleClass, appClass, bridgeClass, bridgeTarget).generate());
        String uiClass = moduleClass + "Main";
        Path generatedUi = generated.resolve(uiClass + ".java");
        Files.writeString(generatedUi, generateMain(uiClass, bridgeClass, target));
        Path ir = destination.resolve("ui.ir.txt");
        Files.writeString(ir, new UiIrDumper().dump(checked) + "generated DEAL " + entry + "\ntyped bridge " + bridge + "\n");
        return new Result(view, deal, destination, generated, generatedUi, ir, moduleClass, uiClass, bridgeClass, checked, Map.copyOf(packFiles));
    }

    public void build(Result result, Path runtimeClasses) throws IOException, InterruptedException {
        Path classes = result.outputDirectory().resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of(javacExecutable(), "--release", "25", "-Xlint:all,-serial,-auxiliaryclass,-rawtypes", "-Werror",
            "-cp", runtimeClasses.toAbsolutePath().normalize().toString(), "-d", classes.toString()));
        try (var files = Files.list(result.generatedDirectory())) { files.filter(file -> file.toString().endsWith(".java")).sorted().forEach(file -> command.add(file.toString())); }
        run(command, result.outputDirectory(), "generated JVM compilation");
    }

    private String generateMain(String uiClass, String bridgeClass, Target target) {
        if (target == Target.PORTABLE) return "public final class " + uiClass + " {\n  private " + uiClass + "() {}\n  public static deal.ui.UiPortableBridge bridge() { return new " + bridgeClass + "(); }\n}\n";
        return "public final class " + uiClass + " {\n  private " + uiClass + "() {}\n  public static void main(String[] args) { deal.ui.UiLauncher.launch(new " + bridgeClass + "()); }\n}\n";
    }

    private Path stageDependencies(Path application, Path project, String augmentation) throws IOException {
        Path canonicalApplication = application.toRealPath();
        List<ApprovedRoot> roots = List.of(
            new ApprovedRoot(canonicalApplication.getParent().toRealPath(), "application"),
            new ApprovedRoot(frameworkRoot.toRealPath(), "framework"),
            new ApprovedRoot(fsRoot.resolve("std").toRealPath(), "stdlib")
        );
        Map<Path, Path> staged = new LinkedHashMap<>();
        staged.put(canonicalApplication, stageTarget(canonicalApplication, project, roots));
        stageDependency(canonicalApplication, project, roots, staged, augmentation);
        return staged.get(canonicalApplication);
    }

    private void stageDependency(Path source, Path project, List<ApprovedRoot> roots, Map<Path, Path> staged, String augmentation) throws IOException {
        String value = Files.readString(source);
        ParsedImports parsed = parsedImports(source, value);
        List<Replacement> replacements = new ArrayList<>();
        for (ParsedImport imported : parsed.imports()) {
            String specifier = imported.declaration().modulePath();
            Path canonical;
            if (isRelative(specifier)) {
                canonical = resolveDealDependency(source, specifier, roots);
            } else if (specifier.startsWith("std/")) {
                validateStandardDependency(source, specifier, roots);
                continue;
            } else if (specifier.equals("host/storage")) {
                continue;
            } else if (Path.of(specifier).isAbsolute()) {
                throw escapedDependency(source, specifier, roots);
            } else {
                throw new IOException("Unsupported bare DEAL dependency '" + specifier + "' imported by " + logicalSource(source, roots));
            }
            Path target = staged.get(canonical);
            if (target == null) {
                target = stageTarget(canonical, project, roots);
                staged.put(canonical, target);
                stageDependency(canonical, project, roots, staged, "");
            }
            String rewritten = relativeSpecifier(staged.get(source), target);
            int start = value.offsetByCodePoints(0, imported.token().startScalarOffset());
            int end = value.offsetByCodePoints(0, imported.token().endScalarOffset());
            replacements.add(new Replacement(start, end, quote(rewritten)));
        }
        Path target = staged.get(source);
        Files.createDirectories(target.getParent());
        Files.writeString(target, compilerSource(rewrite(value, replacements) + augmentation));
    }

    private ParsedImports parsedImports(Path source, String value) throws IOException {
        LexResult lexed = new Lexer(compilerSource(value), source.toString()).tokenize();
        requireValid(source, lexed.diagnostics());
        ParseResult parsed = new Parser(lexed.tokens(), source.toString()).parse();
        requireValid(source, parsed.diagnostics());
        Map<Integer, Token> pathTokens = new HashMap<>();
        List<Token> tokens = lexed.tokens();
        for (int i = 0; i + 5 < tokens.size(); i++) {
            if (tokens.get(i).type() == TokenType.IMPORT && tokens.get(i + 1).type() == TokenType.STAR && tokens.get(i + 2).type() == TokenType.AS && tokens.get(i + 4).type() == TokenType.FROM && tokens.get(i + 5).type() == TokenType.STRING_LITERAL) pathTokens.put(tokens.get(i).startScalarOffset(), tokens.get(i + 5));
        }
        List<ParsedImport> imports = new ArrayList<>();
        for (StatementNode statement : parsed.program().statements()) {
            if (statement instanceof ImportDeclaration imported) {
                Token token = pathTokens.get(imported.span().startScalarOffset());
                if (token == null || !token.hasScalarOffsets()) throw new IOException("Unable to locate import source span in " + source + ": " + imported.modulePath());
                imports.add(new ParsedImport(imported, token));
            }
        }
        return new ParsedImports(List.copyOf(imports));
    }

    private void requireValid(Path source, List<CompilerDiagnostic> diagnostics) throws IOException {
        for (CompilerDiagnostic diagnostic : diagnostics) if (diagnostic.severity().equals("error")) throw new IOException("Unable to stage DEAL dependency " + source + ": " + diagnostic);
    }

    private Path resolveDealDependency(Path source, String specifier, List<ApprovedRoot> roots) throws IOException {
        Path path = source.getParent().resolve(specifier).normalize();
        if (approvedRoot(path, roots) == null) throw escapedDependency(source, specifier, roots);
        Path dependency = Files.isRegularFile(path) ? path : Path.of(path + ".deal");
        if (!Files.isRegularFile(dependency)) throw new IOException("Missing relative DEAL dependency '" + specifier + "' imported by " + logicalSource(source, roots));
        Path canonical = dependency.toRealPath();
        if (approvedRoot(canonical, roots) == null) throw escapedDependency(source, specifier, roots);
        return canonical;
    }

    private void validateStandardDependency(Path source, String specifier, List<ApprovedRoot> roots) throws IOException {
        if (!StdlibModuleResolver.isSpecStdlibModule(specifier)) throw new IOException("Unsupported DEAL standard library dependency '" + specifier + "' imported by " + logicalSource(source, roots));
        ApprovedRoot stdlib = roots.stream().filter(root -> root.prefix().equals("stdlib")).findFirst().orElseThrow();
        Path dependency;
        try { dependency = fsRoot.resolve(specifier + ".d.deal").normalize(); }
        catch (java.nio.file.InvalidPathException failure) { throw new IOException("Malformed DEAL standard library dependency '" + specifier + "' imported by " + logicalSource(source, roots), failure); }
        if (!dependency.startsWith(stdlib.path())) throw escapedDependency(source, specifier, roots);
        if (!Files.isRegularFile(dependency)) throw new IOException("Missing DEAL standard library dependency '" + specifier + "' imported by " + logicalSource(source, roots));
        Path canonical = dependency.toRealPath();
        ApprovedRoot root = approvedRoot(canonical, roots);
        if (root == null || !root.prefix().equals("stdlib")) throw escapedDependency(source, specifier, roots);
    }

    private Path stageTarget(Path source, Path project, List<ApprovedRoot> roots) throws IOException {
        ApprovedRoot root = approvedRoot(source, roots);
        if (root == null) throw new IOException("DEAL dependency escapes approved roots");
        return project.resolve(root.prefix()).resolve(root.path().relativize(source));
    }

    private ApprovedRoot approvedRoot(Path source, List<ApprovedRoot> roots) {
        ApprovedRoot selected = null;
        for (ApprovedRoot root : roots) if (source.startsWith(root.path()) && (selected == null || root.path().getNameCount() > selected.path().getNameCount())) selected = root;
        return selected;
    }

    private IOException escapedDependency(Path source, String specifier, List<ApprovedRoot> roots) {
        return new IOException("DEAL dependency escape rejected: '" + specifier + "' imported by " + logicalSource(source, roots));
    }

    private String logicalSource(Path source, List<ApprovedRoot> roots) {
        ApprovedRoot root = approvedRoot(source, roots);
        return root == null ? source.getFileName().toString() : root.prefix() + "/" + root.path().relativize(source).toString().replace(java.io.File.separatorChar, '/');
    }

    private String relativeSpecifier(Path importer, Path dependency) {
        String relative = importer.getParent().relativize(dependency).toString().replace(java.io.File.separatorChar, '/');
        if (!relative.startsWith(".")) relative = "./" + relative;
        return stripSuffix(relative, ".deal");
    }

    private String rewriteApplicationImport(String source, Path application, String specifier) throws IOException {
        LexResult lexed = new Lexer(source, application.toString()).tokenize();
        requireValid(application, lexed.diagnostics());
        List<Token> tokens = lexed.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).type() != TokenType.IMPORT) continue;
            for (int cursor = index + 1; cursor < tokens.size(); cursor++) {
                Token token = tokens.get(cursor);
                if (token.type() == TokenType.STRING_LITERAL && cursor > index && tokens.get(cursor - 1).type() == TokenType.FROM) {
                    int start = source.offsetByCodePoints(0, token.startScalarOffset());
                    int end = source.offsetByCodePoints(0, token.endScalarOffset());
                    return rewrite(source, List.of(new Replacement(start, end, quote(specifier))));
                }
                if (token.type() == TokenType.SEMICOLON) break;
            }
        }
        throw new IOException("Generated UI application has no application import");
    }

    private String rewrite(String source, List<Replacement> replacements) {
        StringBuilder rewritten = new StringBuilder(source);
        replacements.stream().sorted((left, right) -> Integer.compare(right.start(), left.start())).forEach(replacement -> rewritten.replace(replacement.start(), replacement.end(), replacement.value()));
        return rewritten.toString();
    }

    private String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private String compilerSource(String source) { return source.replaceAll("(?m)^(\\s*)// @(ui-(?:update|effect|effect-policy|effect-failure))(\\s*)$", "$1//  $2$3"); }
    private boolean isRelative(String specifier) { return specifier.startsWith("./") || specifier.startsWith("../"); }
    private Path resolve(Path parent, String specifier) { Path path = parent.resolve(specifier).normalize(); if (Files.isRegularFile(path)) return path; if (Files.isRegularFile(Path.of(path + ".deal"))) return Path.of(path + ".deal"); if (Files.isRegularFile(Path.of(path + ".dealui-pack"))) return Path.of(path + ".dealui-pack"); return path; }
    private record ApprovedRoot(Path path, String prefix) {}
    private record ParsedImport(ImportDeclaration declaration, Token token) {}
    private record ParsedImports(List<ParsedImport> imports) {}
    private record Replacement(int start, int end, String value) {}
    private void run(List<String> command, Path directory, String label) throws IOException, InterruptedException { Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); int exit = process.waitFor(); if (exit != 0) throw new IOException(label + " failed (" + exit + "):\n" + output); }
    private String stripSuffix(String value, String suffix) { return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value; }
    private String javaExecutable() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private String javacExecutable() { return Path.of(System.getProperty("java.home"), "bin", "javac").toString(); }
}
