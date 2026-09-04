package deal.ui;

import deal.compiler.CompilerProtocol.ChangeResult;
import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol.ProtocolHandshake;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocol.SemanticSlice;
import deal.compiler.CompilerProtocol.StructuredDiagnostic;
import deal.compiler.DealCompilerWorkspace;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Stateless cross-artifact facade over the DEAL and Deal UI transpilers. */
public final class CanonicalCompiler {
    private CanonicalCompiler() {}

    public static ProtocolHandshake handshake() {
        return new ProtocolHandshake(
                deal.compiler.CompilerProtocol.VERSION,
                "DEAL 1.2 + Deal UI",
                List.of(
                        "canonical-cross-artifact-check",
                        "deal-semantic-slices",
                        "dealui-semantic-slices",
                        "fingerprint-preconditions",
                        "atomic-change-sets"));
    }

    public static deal.compiler.CompilerProtocol.AppInterfaceSnapshot extractAppInterface(String source) {
        return DealCompilerWorkspace.inspect(
                source, "/generated/app.deal", rejectingResolver(), DealUiDealSource.ADAPTER).appInterface();
    }

    public record Inspection(
            deal.compiler.CompilerProtocol.Inspection deal,
            UiCompilerWorkspace.UiInspection dealUi,
            String packVersion,
            String packDigest,
            ComponentPackSnapshot componentPack,
            boolean valid,
            List<StructuredDiagnostic> diagnostics) {
        public Inspection {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Compact compiler-owned description used to derive an LLM agent surface. */
    public record ComponentPackSnapshot(
            String version,
            String digest,
            List<ComponentSnapshot> components,
            List<TokenSnapshot> tokens) {
        public ComponentPackSnapshot {
            components = List.copyOf(components);
            tokens = List.copyOf(tokens);
        }
    }

    public record ComponentSnapshot(
            String name,
            List<PropertySnapshot> properties,
            String children,
            List<EventSnapshot> events,
            List<String> capabilities) {
        public ComponentSnapshot {
            properties = List.copyOf(properties);
            events = List.copyOf(events);
            capabilities = List.copyOf(capabilities);
        }
    }

    public record PropertySnapshot(String name, String type, boolean optional) {}

    public record EventSnapshot(String property, String payloadType) {}

    public record TokenSnapshot(String name, String type) {}

    public record CanonicalBootstrap(String deal, String dealUi) {}

    public record EditContract(
            String code,
            SemanticId ownerId,
            List<RepairScope> allowedOperations,
            String minimumContextQuery,
            String expected,
            String actual) {
        public EditContract {
            allowedOperations = List.copyOf(allowedOperations);
        }
    }

    public static Inspection inspectCanonicalApp(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier) {
        var deal = DealCompilerWorkspace.inspect(
                dealSource, "/generated/app.deal", DealUiDealSource.ADAPTER);
        UiModel.PackModule pack;
        try {
            pack = UiParser.parsePack(Path.of("/generated/platform-ui.dealui-pack"), packSource);
        } catch (UiDiagnostic failure) {
            StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                    failure.code(), "error", failure.getMessage(),
                    new deal.compiler.CompilerProtocol.SourceRange(
                            failure.file().toString(), failure.line(), failure.column(), failure.line(), failure.column()),
                    new SemanticId("dealui:pack:platform"), "valid component pack", "invalid pack",
                    List.of(), List.of(), "inspectComponentPack");
            List<StructuredDiagnostic> diagnostics = new ArrayList<>(deal.diagnostics());
            diagnostics.add(diagnostic);
            return new Inspection(deal, null, "", "", null, false, diagnostics);
        }
        ComponentPackSnapshot componentPack = componentPackSnapshot(pack);
        List<StructuredDiagnostic> diagnostics = new ArrayList<>(deal.diagnostics());
        if (hasErrors(diagnostics)) {
            return new Inspection(deal, null, pack.version(), pack.digest(), componentPack, false, diagnostics);
        }
        var dealUi = UiCompilerWorkspace.inspect(dealSource, dealUiSource, packSource, packSpecifier);
        diagnostics.addAll(dealUi.diagnostics());
        return new Inspection(
                deal, dealUi, pack.version(), pack.digest(), componentPack,
                !hasErrors(diagnostics), diagnostics);
    }

    public static ComponentPackSnapshot inspectComponentPack(String packSource) {
        return componentPackSnapshot(UiParser.parsePack(
                Path.of("/generated/platform-ui.dealui-pack"), packSource));
    }

    /** Creates the smallest valid source pair for compiler-mediated greenfield generation. */
    public static CanonicalBootstrap bootstrapCanonicalApp(
            String packSource,
            String packSpecifier) {
        UiModel.PackModule pack = UiParser.parsePack(
                Path.of("/generated/platform-ui.dealui-pack"), packSource);
        UiModel.Component root = pack.components().values().stream()
                .filter(component -> component.contracts().stream().noneMatch(contract ->
                        contract instanceof UiModel.Children children && children.required()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Component pack has no component suitable for a bootstrap root"));
        UiModel.PackClass props = pack.classes().get(simpleName(root.propsType()));
        List<String> arguments = new ArrayList<>();
        if (props != null) {
            for (UiModel.Field field : props.fields()) {
                if (field.type().optional() || field.defaultValue() != null) continue;
                arguments.add(field.name() + ": " + defaultExpression(field.type()));
            }
        }
        String deal = "export class AppState { title: string = \"\"; }\n"
                + "export function initialState(): AppState { return {title: \"\"}; }\n";
        String dealUi = "import * as app from \"./app.deal\";\n"
                + "import * as ui from \"" + packSpecifier + "\";\n"
                + "// @ui-root\n"
                + "export view App(state: app.AppState): View {\n"
                + "  ui." + root.name() + "(" + String.join(", ", arguments) + ")\n"
                + "}\n";
        Inspection checked = inspectCanonicalApp(deal, dealUi, packSource, packSpecifier);
        if (!checked.valid()) {
            throw new IllegalStateException("Compiler produced an invalid bootstrap: " + checked.diagnostics());
        }
        return new CanonicalBootstrap(deal, dealUi);
    }

    private static ComponentPackSnapshot componentPackSnapshot(UiModel.PackModule pack) {
        List<ComponentSnapshot> components = new ArrayList<>();
        for (UiModel.Component component : pack.components().values()) {
            UiModel.PackClass props = pack.classes().get(simpleName(component.propsType()));
            List<PropertySnapshot> properties = props == null ? List.of() : props.fields().stream()
                    .map(field -> new PropertySnapshot(
                            field.name(), typeText(field.type()), field.type().optional()))
                    .toList();
            String children = "none";
            List<EventSnapshot> events = new ArrayList<>();
            List<String> capabilities = new ArrayList<>();
            for (UiModel.Contract contract : component.contracts()) {
                if (contract instanceof UiModel.Children value) {
                    children = (value.required() ? "required" : "optional")
                            + (value.componentType() == null ? "" : ":" + value.componentType());
                } else if (contract instanceof UiModel.Event value) {
                    events.add(new EventSnapshot(
                            value.prop(), value.payload() == null ? "none" : typeText(value.payload())));
                } else if (contract instanceof UiModel.Capability value) {
                    capabilities.add(value.name());
                }
            }
            components.add(new ComponentSnapshot(
                    component.name(), properties, children, events, capabilities));
        }
        List<TokenSnapshot> tokens = pack.tokens().values().stream()
                .map(token -> new TokenSnapshot(token.name(), typeText(token.type())))
                .toList();
        return new ComponentPackSnapshot(pack.version(), pack.digest(), components, tokens);
    }

    private static String typeText(UiModel.TypeRef type) {
        return type.name() + "[]".repeat(type.dimensions()) + (type.optional() ? "?" : "");
    }

    private static String defaultExpression(UiModel.TypeRef type) {
        if (type.dimensions() > 0) return "[]";
        return switch (type.name()) {
            case "string" -> "\"\"";
            case "boolean" -> "false";
            case "int" -> "0";
            case "number" -> "0.0";
            default -> throw new IllegalArgumentException(
                    "Required bootstrap property has no scalar default: " + typeText(type));
        };
    }

    private static String simpleName(String qualified) {
        int separator = qualified.lastIndexOf('.');
        return separator < 0 ? qualified : qualified.substring(separator + 1);
    }

    public static Inspection compileCanonicalApp(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier) {
        return inspectCanonicalApp(dealSource, dealUiSource, packSource, packSpecifier);
    }

    public static ChangeResult applyDealChange(
            String source,
            String baseDigest,
            List<? extends DealCompilerWorkspace.Operation> operations) {
        return DealCompilerWorkspace.apply(
                source, "/generated/app.deal", baseDigest, operations, DealUiDealSource.ADAPTER);
    }

    public static SemanticSlice queryDealSymbol(
            String source,
            SemanticId symbolId) {
        return DealCompilerWorkspace.querySymbol(
                source, "/generated/app.deal", symbolId,
                rejectingResolver(), DealUiDealSource.ADAPTER);
    }

    public static SemanticSlice queryDealModule(String source) {
        return DealCompilerWorkspace.queryModule(
                source, "/generated/app.deal", rejectingResolver(), DealUiDealSource.ADAPTER);
    }

    public static SemanticSlice queryDealNode(
            String source,
            SemanticId nodeId) {
        return DealCompilerWorkspace.queryNode(
                source, "/generated/app.deal", nodeId,
                rejectingResolver(), DealUiDealSource.ADAPTER);
    }

    public static ChangeResult applyDealChangeChecked(
            String source,
            ChangeSetPrecondition precondition,
            List<? extends DealCompilerWorkspace.Operation> operations) {
        return DealCompilerWorkspace.applyChecked(
                source, "/generated/app.deal", precondition, operations,
                rejectingResolver(), DealUiDealSource.ADAPTER);
    }

    public static UiCompilerWorkspace.UiChangeResult applyDealUiChange(
            String dealSource,
            String source,
            String packSource,
            String packSpecifier,
            String baseDigest,
            List<? extends UiCompilerWorkspace.Operation> operations) {
        return UiCompilerWorkspace.apply(
                dealSource, source, packSource, packSpecifier, baseDigest, operations);
    }

    public static UiCompilerWorkspace.UiSemanticSlice queryDealUiView(
            String dealSource,
            String source,
            String packSource,
            String packSpecifier,
            SemanticId viewId) {
        return UiCompilerWorkspace.queryView(
                dealSource, source, packSource, packSpecifier, viewId);
    }

    public static UiCompilerWorkspace.UiSemanticSlice queryDealUiDocument(
            String dealSource,
            String source,
            String packSource,
            String packSpecifier) {
        return UiCompilerWorkspace.queryDocument(
                dealSource, source, packSource, packSpecifier);
    }

    public static UiCompilerWorkspace.UiSemanticSlice queryDealUiNode(
            String dealSource,
            String source,
            String packSource,
            String packSpecifier,
            SemanticId nodeId) {
        return UiCompilerWorkspace.queryNode(
                dealSource, source, packSource, packSpecifier, nodeId);
    }

    public static UiCompilerWorkspace.UiChangeResult applyDealUiChangeChecked(
            String dealSource,
            String source,
            String packSource,
            String packSpecifier,
            ChangeSetPrecondition precondition,
            List<? extends UiCompilerWorkspace.Operation> operations) {
        return UiCompilerWorkspace.applyChecked(
                dealSource, source, packSource, packSpecifier, precondition, operations);
    }

    public static EditContract explainDiagnostic(StructuredDiagnostic diagnostic) {
        return new EditContract(
                diagnostic.code(), diagnostic.ownerId(), diagnostic.repairScopes(),
                diagnostic.contextQuery(), diagnostic.expected(), diagnostic.actual());
    }

    private static boolean hasErrors(List<StructuredDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity().equals("error"));
    }

    private static deal.checker.ModuleResolver rejectingResolver() {
        return new deal.checker.ModuleResolver() {
            @Override
            public java.util.Map<String, deal.types.Type> resolveModule(
                    String modulePath, String importingModule, java.util.Set<String> inProgress)
                    throws ModuleNotFoundException {
                throw new ModuleNotFoundException("Module not available in canonical compiler: " + modulePath);
            }

            @Override
            public deal.checker.Symbol.ClassSymbol resolveClassSymbol(
                    String className, String modulePath, String importingModule)
                    throws ModuleNotFoundException {
                throw new ModuleNotFoundException("Module not available in canonical compiler: " + modulePath);
            }
        };
    }
}
