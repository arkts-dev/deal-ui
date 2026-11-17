package deal.ui;

import deal.codegen.jvm.JvmJson;
import deal.module.CompilationOrchestrator;
import deal.project.CliOverrides;
import deal.project.ProjectLocator;
import deal.semantic.CompilerProfileProvider;
import deal.semantic.ReleaseConfiguration;
import deal.semantic.ir.ClassLayout;
import deal.semantic.ir.RuntimeDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Downstream JS compilation and closed checkpoint inventory from the same checked IR.
 * Does not change the DEAL runtime or infer descriptors from current array contents.
 */
public final class UiCheckpointCompiler {
    private UiCheckpointCompiler() {}

    public static int compile(Path entry, Path output, Path diagnostics) throws IOException {
        var located = ProjectLocator.locate(entry.toString(), new CliOverrides("js", output.toString()));
        if (located.context() == null) {
            // Preserve the canonical CLI's configuration diagnostics and exit classification.
            return deal.Main.run(diagnostics == null
                ? new String[]{"compile",entry.toString(),"--backend","js","--output",output.toString()}
                : new String[]{"compile",entry.toString(),"--backend","js","--output",output.toString(),"--diagnostics-json",diagnostics.toString()});
        }
        var invocation = CompilerProfileProvider.resolve(ReleaseConfiguration.CURRENT_RELEASE_STATE,
            ReleaseConfiguration.releaseCapabilityRegistry());
        var compiler = new CompilationOrchestrator(located.context(), entry.toAbsolutePath().normalize(),
            false, false, false, false, diagnostics, invocation);
        if (!compiler.compile()) return 1;
        var inventory = new Inventory();
        inventory.layout(ClassLayout.BUILTIN_ERROR);
        var project = compiler.producedProject();
        project.classSeeds().registrations().values().forEach(registration -> inventory.layout(registration.layout()));
        project.modules().values().forEach(module -> {
            module.classLayouts().values().forEach(inventory::layout);
            module.ops().forEach(op -> {
                if (op.resultType() instanceof RuntimeDescriptor descriptor) inventory.type(descriptor);
                op.operandTypes().forEach(inventory::type);
            });
        });
        // Contextual Record JSON decoding creates unknown[] at arbitrary nesting depths.
        inventory.elements.add("unknown");
        Files.writeString(output.resolve("checkpoint-schema.js"), "\"use strict\";\nmodule.exports=" + inventory.json() + ";\n");
        return 0;
    }

    private static final class Inventory {
        private final Set<String> elements = new LinkedHashSet<>();
        private final Map<String,ClassLayout> classes = new LinkedHashMap<>();
        void type(RuntimeDescriptor descriptor) {
            if (descriptor instanceof RuntimeDescriptor.Array array) {
                elements.add(array.element().canonicalSpecText()); type(array.element());
            } else if (descriptor instanceof RuntimeDescriptor.Nullable nullable) type(nullable.inner());
            else if (descriptor instanceof RuntimeDescriptor.Func function) {
                function.paramTypes().forEach(this::type); type(function.returnType());
            }
        }
        void layout(ClassLayout layout) {
            classes.put(layout.classId().text(), layout);
            layout.fields().forEach(field -> type(field.descriptor()));
        }
        String json() {
            return "{\"elements\":[" + elements.stream().map(UiCheckpointCompiler::quote).collect(Collectors.joining(","))
                + "],\"classes\":{" + classes.entrySet().stream().map(entry -> quote(entry.getKey()) + ":["
                    + entry.getValue().fields().stream().map(field -> "{\"name\":" + quote(field.name())
                        + ",\"descriptor\":" + quote(field.descriptor().canonicalSpecText()) + ",\"optional\":" + !field.required() + "}")
                        .collect(Collectors.joining(",")) + "]").collect(Collectors.joining(",")) + "}}";
        }
    }
    private static String quote(String value) { return "\"" + JvmJson.escapeString(value) + "\""; }
    public static void main(String[] args) throws IOException {
        int status = compile(Path.of(args[0]), Path.of(args[1]), args.length > 2 ? Path.of(args[2]) : null);
        if (status != 0) System.exit(status);
    }
}
