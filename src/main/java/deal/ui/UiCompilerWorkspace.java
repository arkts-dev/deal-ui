package deal.ui;

import deal.compiler.CompilerProtocol;
import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol.ChangeInspection;
import deal.compiler.CompilerProtocol.AppInterfaceSnapshot;
import deal.compiler.CompilerProtocol.FieldSnapshot;
import deal.compiler.CompilerProtocol.TypeSnapshot;
import deal.compiler.CompilerProtocol.DependencyCone;
import deal.compiler.CompilerProtocol.DependencyEdge;
import deal.compiler.CompilerProtocol.DependencyGroup;
import deal.compiler.CompilerProtocol.DependencyMember;
import deal.compiler.CompilerProtocol.NodeSnapshot;
import deal.compiler.CompilerProtocol.OperationDescriptor;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.RepairSlot;
import deal.compiler.CompilerProtocol.RepairSlotStatus;
import deal.compiler.CompilerProtocol.RepairWorkspaceResult;
import deal.compiler.CompilerProtocol.RepairWorkspaceSnapshot;
import deal.compiler.CompilerProtocol.RevisionRef;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocol.SourceRange;
import deal.compiler.CompilerProtocol.StructuredDiagnostic;
import deal.compiler.CompilerProtocol.SlotPatch;
import deal.compiler.CompilerProtocol.SemanticSlice;
import deal.compiler.CompilerProtocolJson;
import deal.compiler.DealCompilerWorkspace;
import deal.compiler.RepairWorkspaceProtocol;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stateless compiler-owned inspection and atomic edits for one Deal UI document. */
public final class UiCompilerWorkspace {
    public static final String REPLACE_VIEW_BODY = "replaceViewBody";
    public static final String REPLACE_SUBTREE = "replaceSubtree";
    public static final String INSERT_CHILD = "insertChild";
    public static final String REMOVE_NODE = "removeNode";
    public static final String MOVE_NODE = "moveNode";
    public static final String SET_PROPERTY = "setProperty";
    public static final String ADD_VIEW = "addView";
    public static final String REMOVE_VIEW = "removeView";
    private static final List<String> ALLOWED_OPERATIONS = List.of(
            ADD_VIEW, REMOVE_VIEW, REPLACE_VIEW_BODY, REPLACE_SUBTREE,
            INSERT_CHILD, REMOVE_NODE, MOVE_NODE, SET_PROPERTY);

    private UiCompilerWorkspace() {}

    public record UiNodeSnapshot(
            SemanticId id,
            SemanticId ownerViewId,
            SemanticId parentId,
            String kind,
            String component,
            SourceRange range,
            String fingerprint,
            List<SemanticId> children,
            List<String> statePaths,
            List<String> actionBindings,
            Map<String, SourceRange> properties,
            List<String> writableProperties) {
        public UiNodeSnapshot {
            children = List.copyOf(children);
            statePaths = List.copyOf(statePaths);
            actionBindings = List.copyOf(actionBindings);
            properties = Map.copyOf(properties);
            writableProperties = List.copyOf(writableProperties);
        }
    }

    public record UiViewSnapshot(
            SemanticId id,
            String name,
            boolean root,
            SourceRange range,
            String fingerprint,
            List<SemanticId> rootNodes) {
        public UiViewSnapshot {
            rootNodes = List.copyOf(rootNodes);
        }
    }

    public record UiInspection(
            String protocolVersion,
            String sourceDigest,
            SemanticId documentId,
            String appInterfaceFingerprint,
            List<UiViewSnapshot> views,
            List<UiNodeSnapshot> nodes,
            UiModel.CheckedMetadata checkedMetadata,
            List<String> allowedOperations,
            List<StructuredDiagnostic> diagnostics) {
        public UiInspection {
            views = List.copyOf(views);
            nodes = List.copyOf(nodes);
            allowedOperations = List.copyOf(allowedOperations);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record UiImpactReport(
            List<SemanticId> changedViews,
            List<SemanticId> changedNodes,
            List<String> changedActionBindings,
            List<String> changedStatePaths,
            boolean componentUsageChanged,
            boolean capabilityUsageChanged) {
        public UiImpactReport {
            changedViews = List.copyOf(changedViews);
            changedNodes = List.copyOf(changedNodes);
            changedActionBindings = List.copyOf(changedActionBindings);
            changedStatePaths = List.copyOf(changedStatePaths);
        }
    }

    public record UiChangeResult(
            boolean accepted,
            String source,
            String sourceDigest,
            UiInspection inspection,
            UiImpactReport impact,
            List<StructuredDiagnostic> diagnostics) {
        public UiChangeResult {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record UiSemanticSlice(
            RevisionRef revision,
            SemanticId ownerId,
            String kind,
            String source,
            UiViewSnapshot view,
            UiNodeSnapshot node,
            List<UiNodeSnapshot> children,
            List<OperationDescriptor> allowedOperations) {
        public UiSemanticSlice {
            Objects.requireNonNull(revision, "revision");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(source, "source");
            children = List.copyOf(children);
            allowedOperations = List.copyOf(allowedOperations);
        }
    }

    /** Compiler-owned context for one local visual refinement. */
    public record UiComponentContract(
            String name,
            List<FieldSnapshot> properties,
            String children,
            String parent,
            List<String> events,
            List<String> capabilities) {
        public UiComponentContract {
            properties = List.copyOf(properties);
            events = List.copyOf(events);
            capabilities = List.copyOf(capabilities);
        }
    }

    public record UiEditSurface(
            RevisionRef revision,
            SemanticId targetId,
            String source,
            UiNodeSnapshot node,
            UiNodeSnapshot parent,
            List<UiNodeSnapshot> children,
            List<String> statePaths,
            List<TypeSnapshot> compatibleActions,
            String appThemeSource,
            List<UiComponentContract> componentContracts,
            List<UiModel.Parameter> lexicalBindings,
            List<TypeSnapshot> bindingTypes,
            OperationDescriptor allowedOperation) {
        public UiEditSurface {
            children = List.copyOf(children);
            statePaths = List.copyOf(statePaths);
            compatibleActions = List.copyOf(compatibleActions);
            componentContracts = List.copyOf(componentContracts);
            lexicalBindings = List.copyOf(lexicalBindings);
            bindingTypes = List.copyOf(bindingTypes);
        }
    }

    public sealed interface Operation permits AddView, RemoveView, ReplaceViewBody, ReplaceSubtree, InsertChild, RemoveNode, MoveNode, SetProperty {
        SemanticId targetId();
    }

    public record AddView(SemanticId targetId, String source) implements Operation {}
    public record RemoveView(SemanticId targetId) implements Operation {}
    public record ReplaceViewBody(SemanticId targetId, String body) implements Operation {}
    public record ReplaceSubtree(SemanticId targetId, String source) implements Operation {}
    public record InsertChild(SemanticId targetId, int index, String source) implements Operation {}
    public record RemoveNode(SemanticId targetId) implements Operation {}
    public record MoveNode(SemanticId targetId, SemanticId newParentId, int index) implements Operation {}
    public record SetProperty(SemanticId targetId, String property, String expression) implements Operation {}

    public static ChangeInspection inspectChange(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            String baseDigest,
            List<SemanticId> anchors,
            List<String> requestedOperations) {
        Analysis analysis = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        if (!analysis.inspection().sourceDigest().equals(baseDigest)) {
            StructuredDiagnostic diagnostic = diagnostic(
                    "CP1001", "Stale Deal UI source digest", analysis.documentId(), null,
                    analysis.inspection().sourceDigest(), baseDigest, List.of(), "inspectCanonicalApp");
            DependencyCone empty = new DependencyCone(DealCompilerWorkspace.digest("empty"), List.of(), List.of(), List.of());
            return new ChangeInspection(revision(analysis), DealCompilerWorkspace.digest(baseDigest + "\u0000stale"),
                    empty, List.of(), List.of(), List.of(diagnostic));
        }
        LinkedHashSet<SemanticId> selected = new LinkedHashSet<>(anchors);
        if (selected.isEmpty()) selected.add(analysis.documentId());
        LinkedHashMap<SemanticId, DependencyMember> members = new LinkedHashMap<>();
        LinkedHashSet<DependencyEdge> edges = new LinkedHashSet<>();
        LinkedHashMap<String, OperationDescriptor> operations = new LinkedHashMap<>();
        List<SemanticSlice> slices = new ArrayList<>();
        for (SemanticId anchor : selected) {
            Target target = analysis.targets().get(anchor);
            if (target == null) {
                StructuredDiagnostic diagnostic = diagnostic(
                        "CP2020", "Unknown Deal UI change anchor", anchor, null,
                        "target issued for " + baseDigest, "missing", List.of(), "inspectCanonicalApp");
                DependencyCone empty = new DependencyCone(DealCompilerWorkspace.digest("empty"),
                        List.copyOf(selected), List.of(), List.of());
                return new ChangeInspection(revision(analysis), DealCompilerWorkspace.digest(baseDigest + "\u0000unknown"),
                        empty, List.of(), List.of(), List.of(diagnostic));
            }
            UiSemanticSlice uiSlice = target.kind().equals("document")
                    ? queryDocument(dealSource, dealUiSource, packSource, packSpecifier)
                    : target.kind().equals("view")
                            ? queryView(dealSource, dealUiSource, packSource, packSpecifier, anchor)
                            : queryNode(dealSource, dealUiSource, packSource, packSpecifier, anchor);
            List<NodeSnapshot> nodes = new ArrayList<>();
            if (uiSlice.node() != null) nodes.add(protocolNode(uiSlice.node()));
            uiSlice.children().forEach(value -> nodes.add(protocolNode(value)));
            List<SemanticId> dependencies = new ArrayList<>();
            if (uiSlice.node() != null && uiSlice.node().parentId() != null) dependencies.add(uiSlice.node().parentId());
            dependencies.addAll(uiSlice.children().stream().map(UiNodeSnapshot::id).toList());
            SemanticSlice slice = new SemanticSlice(
                    uiSlice.revision(), uiSlice.ownerId(), uiSlice.kind(), uiSlice.source(),
                    List.of(), nodes, dependencies, uiSlice.allowedOperations());
            slices.add(slice);
            members.put(anchor, new DependencyMember(
                    anchor, target.kind(), "EDIT_BODY", targetFingerprint(analysis, anchor)));
            uiSlice.allowedOperations().stream()
                    .filter(value -> requestedOperations.isEmpty() || requestedOperations.contains(value.operation()))
                    .forEach(value -> operations.put(value.operation() + "\u0000" + value.targetId().value(), value));
            if (uiSlice.node() != null) {
                UiNodeSnapshot node = uiSlice.node();
                if (node.parentId() != null) {
                    addUiMember(analysis, members, node.parentId(), "SIGNATURE_ONLY");
                    edges.add(new DependencyEdge(node.parentId(), node.id(), "PARENT_CHILD"));
                }
                for (SemanticId child : node.children()) {
                    addUiMember(analysis, members, child, "IMPACT_ONLY");
                    edges.add(new DependencyEdge(node.id(), child, "PARENT_CHILD"));
                }
                for (UiNodeSnapshot candidate : analysis.inspection().nodes()) {
                    if (candidate.id().equals(node.id())) continue;
                    if (!disjoint(node.statePaths(), candidate.statePaths())) {
                        addUiMember(analysis, members, candidate.id(), "IMPACT_ONLY");
                        edges.add(new DependencyEdge(node.id(), candidate.id(), "SHARES_STATE_PATH"));
                    }
                    if (!disjoint(node.actionBindings(), candidate.actionBindings())) {
                        addUiMember(analysis, members, candidate.id(), "IMPACT_ONLY");
                        edges.add(new DependencyEdge(node.id(), candidate.id(), "SHARES_ACTION_BINDING"));
                    }
                }
            }
        }
        List<DependencyMember> memberList = List.copyOf(members.values());
        List<DependencyEdge> edgeList = List.copyOf(edges);
        String coneFingerprint = DealCompilerWorkspace.digest(CompilerProtocolJson.encode(
                List.of(List.copyOf(selected), memberList, edgeList)));
        DependencyCone cone = new DependencyCone(
                coneFingerprint, List.copyOf(selected), memberList, edgeList);
        String inspectionDigest = DealCompilerWorkspace.digest(CompilerProtocolJson.encode(
                List.of(baseDigest, coneFingerprint, List.copyOf(operations.values()))));
        return new ChangeInspection(revision(analysis), inspectionDigest, cone, slices,
                List.copyOf(operations.values()), List.of());
    }

    public static UiInspection inspect(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier) {
        return analyze(dealSource, dealUiSource, packSource, packSpecifier).inspection();
    }

    public static UiSemanticSlice queryView(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            SemanticId viewId) {
        Analysis analysis = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        Target target = requireTarget(analysis, viewId, "view");
        UiViewSnapshot view = analysis.inspection().views().stream()
                .filter(value -> value.id().equals(viewId))
                .findFirst().orElseThrow();
        return new UiSemanticSlice(
                revision(analysis), viewId, "view",
                analysis.source().substring(target.start(), target.end()),
                view, null,
                childSnapshots(analysis, target.children()),
                List.of(
                        descriptor(analysis, target, REPLACE_VIEW_BODY, List.of("body")),
                        descriptor(analysis, target, REMOVE_VIEW, List.of())));
    }

    public static UiSemanticSlice queryDocument(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier) {
        Analysis analysis = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        Target target = requireTarget(analysis, analysis.documentId(), "document");
        return new UiSemanticSlice(
                revision(analysis), analysis.documentId(), "document", "",
                null, null, List.of(),
                List.of(descriptor(analysis, target, ADD_VIEW, List.of("source"))));
    }

    public static UiSemanticSlice queryNode(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            SemanticId nodeId) {
        Analysis analysis = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        Target target = requireTarget(analysis, nodeId, null);
        if (target.kind().equals("view")) {
            throw new IllegalArgumentException("Deal UI node query requires a node target");
        }
        UiNodeSnapshot node = nodeSnapshot(analysis, nodeId);
        List<OperationDescriptor> operations = new ArrayList<>();
        operations.add(descriptor(analysis, target, REPLACE_SUBTREE, List.of("source")));
        operations.add(descriptor(analysis, target, REMOVE_NODE, List.of()));
        operations.add(descriptor(analysis, target, MOVE_NODE, List.of("newParentId", "index")));
        if (target.hasChildrenBlock()) {
            operations.add(descriptor(analysis, target, INSERT_CHILD, List.of("index", "source")));
        }
        if (!target.writableProperties().isEmpty()) {
            operations.add(descriptor(analysis, target, SET_PROPERTY, List.of("property", "expression")));
        }
        return new UiSemanticSlice(
                revision(analysis), nodeId, target.kind(),
                analysis.source().substring(target.start(), target.end()),
                null, node,
                childSnapshots(analysis, target.children()),
                operations);
    }

    public static UiEditSurface queryEditSurface(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            SemanticId nodeId) {
        Analysis analysis = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        Target target = requireTarget(analysis, nodeId, null);
        if (target.kind().equals("view") || target.kind().equals("document")) {
            throw new IllegalArgumentException("Deal UI edit surface requires a node target");
        }
        UiNodeSnapshot node = nodeSnapshot(analysis, nodeId);
        UiNodeSnapshot parent = node.parentId() == null ? null : nodeSnapshotOrNull(analysis, node.parentId());
        List<UiNodeSnapshot> directChildren = childSnapshots(analysis, node.children());

        LinkedHashSet<SemanticId> subtreeIds = new LinkedHashSet<>();
        collectSubtreeIds(analysis, node.id(), subtreeIds);
        LinkedHashSet<String> statePaths = new LinkedHashSet<>();
        LinkedHashSet<String> componentNames = new LinkedHashSet<>();
        if (parent != null) componentNames.add(parent.component());
        analysis.inspection().nodes().stream()
                .filter(value -> subtreeIds.contains(value.id()))
                .forEach(value -> {
                    statePaths.addAll(value.statePaths());
                    componentNames.add(value.component());
                });

        String themeSource = analysis.inspection().nodes().stream()
                .filter(value -> simpleName(value.component()).equals("AppTheme"))
                .findFirst()
                .map(value -> {
                    Target theme = analysis.targets().get(value.id());
                    return theme == null || theme.argumentEnd() < theme.start()
                            ? ""
                            : analysis.source().substring(theme.start(), theme.argumentEnd() + 1);
                })
                .orElse("");
        UiModel.PackModule packModule = UiParser.parsePack(
                Path.of("/generated/platform-ui.dealui-pack"), packSource);
        var dealInspection = DealCompilerWorkspace.inspect(
                dealSource, "/generated/app.deal", DealUiDealSource.ADAPTER);
        List<UiComponentContract> contracts = componentNames.stream()
                .map(UiCompilerWorkspace::simpleName)
                .distinct()
                .map(packModule.components()::get)
                .filter(Objects::nonNull)
                .map(component -> componentContract(component, packModule))
                .toList();
        OperationDescriptor replacement = descriptor(
                analysis, target, REPLACE_SUBTREE, List.of("source"));
        UiModel.ViewModule module = UiParser.parseViews(Path.of("/generated/app.dealui"), dealUiSource);
        List<UiModel.Parameter> bindings = new ArrayList<>();
        for (UiModel.View view : module.views()) {
            if (containsSpan(view.span(), node.range().startLine(), node.range().startColumn())) {
                bindings.addAll(view.parameters());
                collectLexicalBindings(view.nodes(), node.range().startLine(), node.range().startColumn(), bindings);
                break;
            }
        }
        Set<String> requiredTypes = bindings.stream().map(value -> simpleName(value.type().name()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<TypeSnapshot> types = dealInspection.appInterface().types();
        boolean expanded;
        do {
            int before = requiredTypes.size();
            types.stream().filter(value -> requiredTypes.contains(simpleName(value.name())))
                    .forEach(value -> value.fields().forEach(field -> requiredTypes.add(simpleName(field.type().replace("[]", "")))));
            expanded = before != requiredTypes.size();
        } while (expanded);
        Set<String> usedActions = analysis.inspection().nodes().stream().filter(value -> subtreeIds.contains(value.id()))
                .flatMap(value -> value.actionBindings().stream()).map(UiCompilerWorkspace::simpleName)
                .collect(java.util.stream.Collectors.toSet());
        return new UiEditSurface(
                revision(analysis), node.id(), analysis.source().substring(target.start(), target.end()),
                node, parent, directChildren, List.copyOf(statePaths),
                dealInspection.appInterface().actions().stream().filter(value -> usedActions.contains(simpleName(value.name()))).toList(),
                themeSource, contracts, bindings,
                types.stream().filter(value -> requiredTypes.contains(simpleName(value.name()))).toList(), replacement);
    }

    private static boolean containsSpan(UiModel.Span span, int line, int column) {
        return (line > span.line() || line == span.line() && column >= span.column())
                && (line < span.endLine() || line == span.endLine() && column <= span.endColumn());
    }

    private static void collectLexicalBindings(List<UiModel.Node> nodes, int line, int column, List<UiModel.Parameter> bindings) {
        for (UiModel.Node node : nodes) {
            if (!containsSpan(node.span(), line, column)) continue;
            if (node instanceof UiModel.ForEach each) bindings.add(each.item());
            collectLexicalBindings(children(node), line, column, bindings);
            return;
        }
    }

    private static void collectSubtreeIds(
            Analysis analysis,
            SemanticId nodeId,
            Set<SemanticId> result) {
        if (!result.add(nodeId)) return;
        UiNodeSnapshot node = nodeSnapshotOrNull(analysis, nodeId);
        if (node != null) node.children().forEach(child -> collectSubtreeIds(analysis, child, result));
    }

    private static UiComponentContract componentContract(
            UiModel.Component component,
            UiModel.PackModule pack) {
        UiModel.PackClass props = pack.classes().get(simpleName(component.propsType()));
        List<FieldSnapshot> properties = props == null ? List.of() : props.fields().stream()
                .map(field -> new FieldSnapshot(
                        field.name(), field.type().name(), field.type().optional(), field.type().array()))
                .toList();
        String children = "none";
        String parent = "any";
        List<String> events = new ArrayList<>();
        List<String> capabilities = new ArrayList<>();
        for (UiModel.Contract contract : component.contracts()) {
            if (contract instanceof UiModel.Children value) {
                children = (value.required() ? "required" : "optional")
                        + (value.componentType() == null ? "" : ":" + value.componentType());
            } else if (contract instanceof UiModel.Parent value) {
                parent = value.componentType();
            } else if (contract instanceof UiModel.Event value) {
                events.add(value.prop() + "(payload:"
                        + (value.payload() == null ? "none" : typeText(value.payload())) + ")");
            } else if (contract instanceof UiModel.Capability value) {
                capabilities.add(value.name());
            }
        }
        return new UiComponentContract(
                component.name(), properties, children, parent, events, capabilities);
    }

    public static UiChangeResult applyChecked(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            ChangeSetPrecondition precondition,
            List<? extends Operation> operations) {
        Objects.requireNonNull(precondition, "precondition");
        Analysis base = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        if (!base.inspection().sourceDigest().equals(precondition.baseDigest())) {
            return rejected(base, diagnostic(
                    "CP1001", "Stale Deal UI source digest", base.documentId(), null,
                    base.inspection().sourceDigest(), precondition.baseDigest(), List.of(), "inspectCanonicalApp"));
        }
        for (Operation operation : operations) {
            UiChangeResult rejected = requireFingerprint(base, precondition, operation.targetId());
            if (rejected != null) return rejected;
            if (operation instanceof MoveNode move) {
                rejected = requireFingerprint(base, precondition, move.newParentId());
                if (rejected != null) return rejected;
            }
        }
        return apply(
                dealSource, dealUiSource, packSource, packSpecifier,
                precondition.baseDigest(), operations);
    }

    public static RepairWorkspaceResult stageChange(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            ChangeSetPrecondition precondition,
            ChangeInspection changeInspection,
            List<? extends Operation> operations) {
        UiChangeResult change = applyChecked(
                dealSource, dealUiSource, packSource, packSpecifier, precondition, operations);
        RepairWorkspaceSnapshot workspace = workspace(
                dealSource, dealUiSource, packSource, packSpecifier, precondition,
                changeInspection, operations, change, 0, List.of());
        return new RepairWorkspaceResult(
                change.accepted(), change.accepted() ? change.source() : dealUiSource,
                change.accepted() ? change.sourceDigest() : DealCompilerWorkspace.digest(dealUiSource),
                workspace, change, change.diagnostics());
    }

    public static RepairWorkspaceResult patchRepairWorkspace(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            RepairWorkspaceSnapshot workspace,
            List<SlotPatch> patches) {
        return applyRepairTransaction(dealSource, dealUiSource, packSource, packSpecifier, workspace, null, patches);
    }

    /** An insertion permission, bound to the complete candidate and its compiler context. */
    public record RepairInsertion(String id, String workspaceDigest, SemanticId parentId,
                                  int index, List<UiComponentContract> parentContracts,
                                  List<StructuredDiagnostic> obligations) {
        public RepairInsertion { parentContracts = List.copyOf(parentContracts); obligations = List.copyOf(obligations); }
    }

    public static List<RepairInsertion> inspectRepairInsertions(
            String deal, String ui, String pack, String specifier, RepairWorkspaceSnapshot workspace) {
        requireRepairContext(deal, ui, pack, specifier, workspace);
        var offer = CanonicalCompiler.inspectRepair(workspace);
        if (offer.disposition() == RepairWorkspaceProtocol.Disposition.UNSUPPORTED) return List.of();
        Analysis base = analyze(deal, ui, pack, specifier);
        var packModule = UiParser.parsePack(Path.of("/generated/platform-ui.dealui-pack"), pack);
        List<RepairInsertion> result = new ArrayList<>();
        for (Target parent : base.targets().values()) {
            if (!parent.hasChildrenBlock()) continue;
            // A second placeholder at the same parent must be repaired, not expanded again.
            if (workspace.slots().stream().anyMatch(slot -> slot.operation().equals(INSERT_CHILD)
                    && slot.targetId().equals(parent.id()) && slot.payload().getOrDefault("source", "").isBlank())) continue;
            List<StructuredDiagnostic> obligations = offer.diagnostics().stream()
                    .filter(d -> d.repairScopes().contains(new RepairScope(INSERT_CHILD, parent.id()))).toList();
            if (obligations.isEmpty()) continue;
            int index = base.inspection().nodes().stream().filter(n -> n.id().equals(parent.id()))
                    .findFirst().map(n -> n.children().size()).orElse(0);
            String id = DealCompilerWorkspace.digest(CompilerProtocolJson.encode(List.of(
                    "ui-repair-insertion-v1", workspace.workspaceDigest(), parent.id(), index, obligations)));
            var contracts = base.inspection().nodes().stream().filter(n -> n.id().equals(parent.id()))
                    .map(n -> packModule.components().get(simpleName(n.component())))
                    .filter(Objects::nonNull).map(c -> componentContract(c, packModule)).toList();
            result.add(new RepairInsertion(id, workspace.workspaceDigest(), parent.id(), index, contracts, obligations));
        }
        return List.copyOf(result);
    }

    /** Scope expansion creates a compiler-owned slot; it never supplies or commits author code. */
    public static RepairWorkspaceSnapshot expandRepairInsertion(
            String deal, String ui, String pack, String specifier, RepairWorkspaceSnapshot workspace,
            String expectedDigest, String insertionId) {
        if (!workspace.workspaceDigest().equals(expectedDigest)) throw new IllegalArgumentException("Stale UI repair expansion");
        RepairInsertion permission = inspectRepairInsertions(deal, ui, pack, specifier, workspace).stream()
                .filter(value -> value.id().equals(insertionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UI repair insertion is not granted"));
        List<Operation> operations = new ArrayList<>();
        workspace.slots().forEach(slot -> operations.add(operation(slot.operation(), slot.targetId(), slot.payload())));
        operations.add(new InsertChild(permission.parentId(), permission.index(), ""));
        ChangeInspection inspection = inspectChange(deal, ui, pack, specifier, workspace.baseRevision().sourceDigest(),
                operations.stream().map(Operation::targetId).distinct().toList(),
                operations.stream().map(UiCompilerWorkspace::operationName).distinct().toList());
        Map<String, String> fingerprints = new LinkedHashMap<>(workspace.precondition().expectedTargetFingerprints());
        Analysis base = analyze(deal, ui, pack, specifier);
        fingerprints.put(permission.parentId().value(), targetFingerprint(base, permission.parentId()));
        var precondition = new ChangeSetPrecondition(workspace.precondition().baseDigest(), fingerprints);
        UiChangeResult candidate = applyChecked(deal, ui, pack, specifier, precondition, operations);
        return workspace(deal, ui, pack, specifier, precondition, inspection, operations, candidate,
                workspace.repairRound(), workspace.slots());
    }

    private static void requireRepairContext(String deal, String ui, String pack, String specifier,
                                             RepairWorkspaceSnapshot workspace) {
        if (!DealCompilerWorkspace.digest(ui).equals(workspace.baseRevision().sourceDigest())
                || !uiWorkspaceDigest(workspace).equals(workspace.workspaceDigest())
                || !workspace.workspaceId().equals(uiWorkspaceId(deal, pack, specifier,
                    workspace.precondition().baseDigest(), workspace.inspectionDigest(),
                    workspace.slots().stream().map(RepairSlot::payload).toList())))
            throw new IllegalArgumentException("Stale or invalid UI repair workspace context");
    }

    public static RepairWorkspaceResult applyRepairTransaction(
            String dealSource, String dealUiSource, String packSource, String packSpecifier,
            RepairWorkspaceSnapshot workspace, deal.compiler.RepairWorkspaceProtocol.Grant grant,
            List<SlotPatch> patches) {
        if (!DealCompilerWorkspace.digest(dealUiSource).equals(workspace.baseRevision().sourceDigest())) {
            return rejectedWorkspace(dealUiSource, workspace, "CP2021", "Repair workspace base source is stale");
        }
        if (!uiWorkspaceDigest(workspace).equals(workspace.workspaceDigest())) {
            return rejectedWorkspace(dealUiSource, workspace, "CP2022", "Repair workspace digest is invalid");
        }
        if (!workspace.workspaceId().equals(uiWorkspaceId(dealSource, packSource, packSpecifier,
                workspace.precondition().baseDigest(), workspace.inspectionDigest(),
                workspace.slots().stream().map(RepairSlot::payload).toList()))) {
            return rejectedWorkspace(dealUiSource, workspace, "CP2021", "Repair workspace DEAL or component-pack context is stale");
        }
        if (grant != null) {
            try {
                deal.compiler.RepairWorkspaceProtocol.validateGrant(workspace, grant, UiRepairDiagnostics.registry());
                if (!grant.obligations().isEmpty()) throw new IllegalArgumentException("UI transaction cannot fulfill DEAL declaration obligations");
            } catch (IllegalArgumentException invalid) {
                return rejectedWorkspace(dealUiSource, workspace, "CP1030", invalid.getMessage());
            }
        }
        Map<String, SlotPatch> bySlot = new LinkedHashMap<>();
        for (SlotPatch patch : patches) {
            if (bySlot.put(patch.slotId(), patch) != null) {
                return rejectedWorkspace(dealUiSource, workspace, "CP2023", "Repair slot was patched more than once");
            }
        }
        List<Operation> operations = new ArrayList<>();
        for (RepairSlot slot : workspace.slots()) {
            SlotPatch patch = bySlot.remove(slot.slotId());
            if (patch != null && (grant == null ? slot.status() != RepairSlotStatus.REJECTED : !grant.slots().contains(slot.slotId()))) {
                return rejectedWorkspace(dealUiSource, workspace, "CP2024", "Repair slot is outside the writable scope");
            }
            Map<String, String> payload = new LinkedHashMap<>(slot.payload());
            if (patch != null) {
                if (patch.drop()) return rejectedWorkspace(dealUiSource, workspace, "CP2025", "Dropping UI slots is not granted");
                if (!payload.keySet().equals(patch.payload().keySet())) {
                    return rejectedWorkspace(dealUiSource, workspace, "CP2025", "Repair patch fields do not match the slot contract");
                }
                payload.putAll(patch.payload());
            }
            operations.add(operation(slot.operation(), slot.targetId(), payload));
        }
        if (!bySlot.isEmpty()) {
            return rejectedWorkspace(dealUiSource, workspace, "CP2026", "Unknown repair slot " + bySlot.keySet().iterator().next());
        }
        ChangeInspection inspection = inspectChange(
                dealSource, dealUiSource, packSource, packSpecifier,
                workspace.baseRevision().sourceDigest(),
                workspace.slots().stream().map(RepairSlot::targetId).distinct().toList(),
                workspace.slots().stream().map(RepairSlot::operation).distinct().toList());
        UiChangeResult change = applyChecked(
                dealSource, dealUiSource, packSource, packSpecifier, workspace.precondition(), operations);
        RepairWorkspaceSnapshot next = workspace(
                dealSource, dealUiSource, packSource, packSpecifier, workspace.precondition(),
                inspection, operations, change, workspace.repairRound() + 1, workspace.slots());
        return new RepairWorkspaceResult(
                change.accepted(), change.accepted() ? change.source() : dealUiSource,
                change.accepted() ? change.sourceDigest() : DealCompilerWorkspace.digest(dealUiSource),
                next, change, change.diagnostics());
    }

    public static UiChangeResult apply(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            String baseDigest,
            List<? extends Operation> operations) {
        Objects.requireNonNull(baseDigest, "baseDigest");
        List<? extends Operation> requested = List.copyOf(operations);
        if (requested.isEmpty()) throw new IllegalArgumentException("A UI change requires at least one operation");
        Analysis base = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        if (!base.inspection().sourceDigest().equals(baseDigest)) {
            return rejected(base, diagnostic(
                    "CP1001", "Stale Deal UI source digest", base.documentId(), null,
                    base.inspection().sourceDigest(), baseDigest, List.of(), "inspectCanonicalApp"));
        }
        if (hasErrors(base.inspection().diagnostics()) && base.targets().isEmpty()) {
            return rejected(base, diagnostic(
                    "CP1002", "Base Deal UI source is not compiler-clean", base.documentId(), null,
                    "valid base source", "compiler diagnostics", List.of(), "inspectCanonicalApp"));
        }
        if (hasErrors(base.inspection().diagnostics())) {
            List<RepairScope> writable = base.inspection().diagnostics().stream()
                    .flatMap(value -> value.repairScopes().stream()).distinct().toList();
            boolean outsideRepairScope = requested.stream().anyMatch(value ->
                    writable.stream().noneMatch(scope -> operationCoversRepairScope(base, value, scope)));
            if (outsideRepairScope) {
                return rejected(base, diagnostic(
                        "CP1007", "Change is outside the compiler-scoped Deal UI repair target",
                        base.documentId(), null, "one allowed repair operation", "unrelated UI edit",
                        base.inspection().diagnostics().stream()
                                .flatMap(value -> value.repairScopes().stream()).toList(),
                        "inspectCanonicalApp"));
            }
        }

        List<Replacement> replacements = new ArrayList<>();
        Set<SemanticId> changedViews = new LinkedHashSet<>();
        Set<SemanticId> changedNodes = new LinkedHashSet<>();
        for (Operation operation : requested) {
            Target target = base.targets().get(operation.targetId());
            if (target == null) {
                return rejected(base, diagnostic(
                        "CP1003", "Unknown or stale Deal UI target " + operation.targetId().value(),
                        operation.targetId(), null, "target issued for " + baseDigest, "missing target",
                        List.of(), "queryDealUiNode"));
            }
            changedViews.add(target.ownerViewId());
            if (!target.kind().equals("view")) changedNodes.add(target.id());
            switch (operation) {
                case AddView value -> {
                    if (!target.kind().equals("document")) return wrongKind(base, operation, target, "document");
                    if (value.source() == null || value.source().isBlank()) {
                        return rejected(base, diagnostic(
                                "CP2004", "A Deal UI view declaration is required", target.id(), null,
                                "complete export view declaration", "empty source",
                                List.of(new RepairScope(ADD_VIEW, target.id())), "queryDealUiDocument"));
                    }
                    String separator = dealUiSource.isEmpty() || dealUiSource.endsWith("\n") ? "" : "\n";
                    replacements.add(new Replacement(
                            target.end(), target.end(), separator + value.source().strip() + "\n", false));
                }
                case RemoveView ignored -> {
                    if (!target.kind().equals("view")) return wrongKind(base, operation, target, "view");
                    replacements.add(new Replacement(target.start(), target.end(), "", false));
                }
                case ReplaceViewBody value -> {
                    if (!target.kind().equals("view")) return wrongKind(base, operation, target, "view");
                    replacements.add(new Replacement(target.contentStart(), target.contentEnd(), value.body(), true));
                }
                case ReplaceSubtree value -> {
                    if (target.kind().equals("view")) return wrongKind(base, operation, target, "node");
                    replacements.add(new Replacement(target.start(), target.end(), value.source().trim(), false));
                }
                case RemoveNode ignored -> {
                    if (target.kind().equals("view")) return wrongKind(base, operation, target, "node");
                    replacements.add(new Replacement(target.start(), target.end(), "", false));
                }
                case InsertChild value -> {
                    if (value.source() == null || value.source().isBlank()) {
                        return rejected(base, diagnostic("CP2004", "A Deal UI child node is required", target.id(), target.range(),
                                "one constructed UI node", "empty child slot", List.of(new RepairScope(INSERT_CHILD, target.id())),
                                "queryDealUiNode"));
                    }
                    if (!target.hasChildrenBlock()) {
                        return rejected(base, diagnostic(
                                "CP2001", "Target has no child block", target.id(), target.range(),
                                "component with children", target.kind(),
                                List.of(new RepairScope(REPLACE_SUBTREE, target.id())), "queryDealUiNode"));
                    }
                    int insertion = insertionOffset(base, target, value.index());
                    replacements.add(new Replacement(insertion, insertion, value.source().trim(), true));
                }
                case MoveNode value -> {
                    if (target.kind().equals("view")) return wrongKind(base, operation, target, "node");
                    Target parent = base.targets().get(value.newParentId());
                    if (parent == null || !parent.hasChildrenBlock()) {
                        return rejected(base, diagnostic(
                                "CP2002", "Move destination is missing or cannot contain children", value.newParentId(), null,
                                "container node", "invalid destination", List.of(), "queryDealUiNode"));
                    }
                    String moved = dealUiSource.substring(target.start(), target.end());
                    replacements.add(new Replacement(target.start(), target.end(), "", false));
                    replacements.add(new Replacement(insertionOffset(base, parent, value.index()),
                            insertionOffset(base, parent, value.index()), moved, true));
                    changedViews.add(parent.ownerViewId());
                    changedNodes.add(parent.id());
                }
                case SetProperty value -> {
                    SourceRange property = target.properties().get(value.property());
                    if (property == null) {
                        if (!target.writableProperties().contains(value.property()) || target.argumentEnd() < 0) {
                            return rejected(base, diagnostic(
                                    "CP2003", "Property is not declared by the selected component", target.id(), target.range(),
                                    "one of " + target.writableProperties(), value.property(),
                                    List.of(new RepairScope(REPLACE_SUBTREE, target.id())), "queryDealUiNode"));
                        }
                        String separator = target.properties().isEmpty() ? "" : ", ";
                        replacements.add(new Replacement(
                                target.argumentEnd(), target.argumentEnd(),
                                separator + value.property() + ": " + value.expression().trim(), false));
                        break;
                    }
                    replacements.add(new Replacement(
                            base.index().offset(property.startLine(), property.startColumn()),
                            base.index().offsetAfter(property.endLine(), property.endColumn()),
                            value.expression().trim(), false));
                }
            }
        }
        replacements.sort(Comparator.comparingInt(Replacement::start).thenComparingInt(Replacement::end));
        for (int index = 1; index < replacements.size(); index++) {
            Replacement previous = replacements.get(index - 1);
            Replacement current = replacements.get(index);
            if (previous.end() > current.start() && previous.start() != previous.end()) {
                return rejected(base, diagnostic(
                        "CP1006", "UI transaction contains overlapping edits", base.documentId(), null,
                        "non-overlapping operations", "overlapping ranges", List.of(), "inspectCanonicalApp"));
            }
        }

        String candidate = applyReplacements(dealUiSource, replacements, base.index());
        Analysis checked = analyze(dealSource, candidate, packSource, packSpecifier);
        if (hasErrors(checked.inspection().diagnostics())) {
            List<StructuredDiagnostic> scoped = checked.inspection().diagnostics().stream()
                    .map(value -> scopeDiagnostic(value, requested, base))
                    .toList();
            return new UiChangeResult(
                    false, dealUiSource, base.inspection().sourceDigest(), base.inspection(),
                    emptyImpact(), scoped);
        }
        Set<String> beforeActions = allActions(base.inspection());
        Set<String> afterActions = allActions(checked.inspection());
        Set<String> beforePaths = allPaths(base.inspection());
        Set<String> afterPaths = allPaths(checked.inspection());
        UiImpactReport impact = new UiImpactReport(
                List.copyOf(changedViews),
                List.copyOf(changedNodes),
                symmetricDifference(beforeActions, afterActions),
                symmetricDifference(beforePaths, afterPaths),
                !usedComponents(base.inspection()).equals(usedComponents(checked.inspection())),
                !componentCapabilities(base.inspection())
                        .equals(componentCapabilities(checked.inspection())));
        return new UiChangeResult(
                true, candidate, checked.inspection().sourceDigest(), checked.inspection(), impact, List.of());
    }

    private static boolean operationCoversRepairScope(
            Analysis analysis,
            Operation operation,
            RepairScope scope) {
        if (operationName(operation).equals(scope.operation())
                && operation.targetId().equals(scope.ownerId())) return true;
        if (operation instanceof ReplaceViewBody) {
            Target view = analysis.targets().get(operation.targetId());
            Target repair = analysis.targets().get(scope.ownerId());
            return view != null && repair != null && view.kind().equals("view")
                    && view.id().equals(repair.ownerViewId());
        }
        if (!(operation instanceof ReplaceSubtree)) return false;
        SemanticId cursor = scope.ownerId();
        while (cursor != null) {
            if (cursor.equals(operation.targetId())) return true;
            UiNodeSnapshot node = nodeSnapshotOrNull(analysis, cursor);
            cursor = node == null ? null : node.parentId();
        }
        return false;
    }

    private static UiNodeSnapshot nodeSnapshotOrNull(Analysis analysis, SemanticId id) {
        return analysis.inspection().nodes().stream()
                .filter(node -> node.id().equals(id)).findFirst().orElse(null);
    }

    private static NodeSnapshot protocolNode(UiNodeSnapshot value) {
        return new NodeSnapshot(value.id(), value.ownerViewId(), value.kind(), value.range(), value.fingerprint());
    }

    private static void addUiMember(
            Analysis analysis,
            Map<SemanticId, DependencyMember> members,
            SemanticId id,
            String exposure) {
        UiNodeSnapshot node = analysis.inspection().nodes().stream()
                .filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (node == null) return;
        DependencyMember current = members.get(id);
        if (current != null && exposureRank(current.exposure()) >= exposureRank(exposure)) return;
        members.put(id, new DependencyMember(id, node.kind(), exposure, node.fingerprint()));
    }

    private static int exposureRank(String value) {
        return switch (value) {
            case "EDIT_BODY" -> 3;
            case "SIGNATURE_ONLY" -> 2;
            default -> 1;
        };
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        return left.stream().noneMatch(right::contains);
    }

    private static RepairWorkspaceSnapshot workspace(
            String dealSource,
            String dealUiSource,
            String packSource,
            String packSpecifier,
            ChangeSetPrecondition precondition,
            ChangeInspection inspection,
            List<? extends Operation> operations,
            UiChangeResult change,
            int round,
            List<RepairSlot> previousSlots) {
        Analysis base = analyze(dealSource, dealUiSource, packSource, packSpecifier);
        List<UiChangeResult> isolated = operations.stream().map(operation -> applyChecked(
                dealSource, dealUiSource, packSource, packSpecifier,
                precondition, List.of(operation))).toList();
        List<Set<Integer>> dependencies = uiOperationDependencies(base, operations);
        SccResult dependencyGroups = stronglyConnectedComponents(dependencies);
        int[] groupIndexes = dependencyGroups.groupByNode();
        Set<Integer> rejected = new LinkedHashSet<>();
        for (int index = 0; index < isolated.size(); index++) {
            if (!isolated.get(index).accepted()) rejected.add(index);
        }
        if (rejected.isEmpty() && !change.accepted() && !operations.isEmpty()) rejected.add(0);
        List<RepairSlot> slots = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            int slotIndex = index;
            Operation operation = operations.get(index);
            boolean direct = rejected.contains(index);
            boolean blocked = !direct && rejected.stream().anyMatch(other ->
                    groupIndexes[other] == groupIndexes[slotIndex]
                            || groupDependsOn(groupIndexes[slotIndex], groupIndexes[other],
                                    dependencyGroups.groupDependencies()));
            RepairSlotStatus status = change.accepted()
                    ? RepairSlotStatus.COMMIT_READY
                    : direct ? RepairSlotStatus.REJECTED
                    : blocked ? RepairSlotStatus.BLOCKED
                    : previouslyStaged(previousSlots, "R" + (index + 1), payload(operation))
                            ? RepairSlotStatus.SEALED : RepairSlotStatus.STAGED;
            Map<String, String> payload = payload(operation);
            List<StructuredDiagnostic> candidateDiagnostics = change.diagnostics().stream()
                    .filter(value -> diagnosticMatches(operation, value)).toList();
            List<StructuredDiagnostic> owned = !candidateDiagnostics.isEmpty()
                    ? candidateDiagnostics
                    : isolated.get(index).accepted() ? List.of() : isolated.get(index).diagnostics();
            slots.add(new RepairSlot(
                    "R" + (index + 1), operationName(operation), operation.targetId(),
                    precondition.expectedTargetFingerprints().getOrDefault(operation.targetId().value(), ""),
                    payload, DealCompilerWorkspace.digest(CompilerProtocolJson.encode(payload)), status,
                    "G" + (groupIndexes[index] + 1), owned));
        }
        List<DependencyGroup> groups = new ArrayList<>();
        int groupCount = java.util.Arrays.stream(groupIndexes).max().orElse(-1) + 1;
        for (int group = 0; group < groupCount; group++) {
            String groupId = "G" + (group + 1);
            List<RepairSlot> members = slots.stream()
                    .filter(value -> value.dependencyGroupId().equals(groupId)).toList();
            String status = members.stream().anyMatch(value -> value.status() == RepairSlotStatus.REJECTED)
                    ? "REPAIR_REQUIRED"
                    : members.stream().anyMatch(value -> value.status() == RepairSlotStatus.BLOCKED)
                            ? "BLOCKED"
                    : members.stream().allMatch(value -> value.status() == RepairSlotStatus.COMMIT_READY)
                            ? "COMMIT_READY"
                    : members.stream().anyMatch(value -> value.status() == RepairSlotStatus.STAGED)
                            ? "STAGED" : "SEALED";
            groups.add(new DependencyGroup(
                    groupId,
                    members.stream().map(RepairSlot::slotId).toList(),
                    dependencyGroups.groupDependencies().get(group).stream()
                            .sorted().map(value -> "G" + (value + 1)).toList(),
                    status));
        }
        String workspaceId = uiWorkspaceId(dealSource, packSource, packSpecifier, precondition.baseDigest(),
                inspection.inspectionDigest(), operations.stream().map(UiCompilerWorkspace::payload).toList());
        RepairWorkspaceSnapshot draft = new RepairWorkspaceSnapshot(
                workspaceId, "", new RevisionRef(CompilerProtocol.VERSION, DealCompilerWorkspace.digest(dealUiSource)),
                inspection.inspectionDigest(), precondition, slots, groups, round);
        return new RepairWorkspaceSnapshot(
                workspaceId, uiWorkspaceDigest(draft), draft.baseRevision(), draft.inspectionDigest(),
                draft.precondition(), draft.slots(), draft.groups(), draft.repairRound());
    }

    private static boolean previouslyStaged(
            List<RepairSlot> previousSlots,
            String slotId,
            Map<String, String> payload) {
        String fingerprint = DealCompilerWorkspace.digest(CompilerProtocolJson.encode(payload));
        return previousSlots.stream().anyMatch(previous ->
                previous.slotId().equals(slotId)
                        && previous.payloadFingerprint().equals(fingerprint)
                        && (previous.status() == RepairSlotStatus.STAGED
                                || previous.status() == RepairSlotStatus.SEALED));
    }

    private static boolean diagnosticMatches(Operation operation, StructuredDiagnostic diagnostic) {
        return operation.targetId().equals(diagnostic.ownerId())
                || diagnostic.relatedIds().contains(operation.targetId())
                || diagnostic.repairScopes().stream().anyMatch(scope ->
                        scope.ownerId().equals(operation.targetId())
                                && scope.operation().equals(operationName(operation)));
    }

    private static List<Set<Integer>> uiOperationDependencies(
            Analysis analysis,
            List<? extends Operation> operations) {
        Map<SemanticId, UiNodeSnapshot> nodes = analysis.inspection().nodes().stream()
                .collect(java.util.stream.Collectors.toMap(UiNodeSnapshot::id, value -> value));
        List<Set<Integer>> result = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) result.add(new LinkedHashSet<>());
        for (int consumer = 0; consumer < operations.size(); consumer++) {
            Operation operation = operations.get(consumer);
            UiNodeSnapshot node = nodes.get(operation.targetId());
            for (int provider = 0; provider < operations.size(); provider++) {
                if (consumer == provider) continue;
                Operation candidate = operations.get(provider);
                if (operation.targetId().equals(candidate.targetId())) {
                    result.get(consumer).add(provider);
                    continue;
                }
                if (node != null && Objects.equals(node.parentId(), candidate.targetId())) {
                    result.get(consumer).add(provider);
                }
                UiNodeSnapshot other = nodes.get(candidate.targetId());
                if (node != null && other != null && (!disjoint(node.statePaths(), other.statePaths())
                        || !disjoint(node.actionBindings(), other.actionBindings()))) result.get(consumer).add(provider);
                if (operation instanceof MoveNode move
                        && move.newParentId().equals(candidate.targetId())) {
                    result.get(consumer).add(provider);
                }
            }
        }
        return result;
    }

    private static SccResult stronglyConnectedComponents(List<Set<Integer>> dependencies) {
        int size = dependencies.size();
        int[] index = new int[size];
        int[] low = new int[size];
        int[] groupByNode = new int[size];
        boolean[] onStack = new boolean[size];
        java.util.Arrays.fill(index, -1);
        java.util.Arrays.fill(groupByNode, -1);
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        int[] nextIndex = {0};
        int[] nextGroup = {0};
        for (int node = 0; node < size; node++) {
            if (index[node] < 0) strongConnect(
                    node, dependencies, index, low, groupByNode, onStack, stack, nextIndex, nextGroup);
        }
        List<Set<Integer>> groupDependencies = new ArrayList<>();
        for (int group = 0; group < nextGroup[0]; group++) groupDependencies.add(new LinkedHashSet<>());
        for (int node = 0; node < size; node++) {
            for (int dependency : dependencies.get(node)) {
                int from = groupByNode[node];
                int to = groupByNode[dependency];
                if (from != to) groupDependencies.get(from).add(to);
            }
        }
        return new SccResult(groupByNode, groupDependencies);
    }

    private static void strongConnect(
            int node,
            List<Set<Integer>> dependencies,
            int[] index,
            int[] low,
            int[] groupByNode,
            boolean[] onStack,
            java.util.ArrayDeque<Integer> stack,
            int[] nextIndex,
            int[] nextGroup) {
        index[node] = nextIndex[0];
        low[node] = nextIndex[0]++;
        stack.push(node);
        onStack[node] = true;
        for (int dependency : dependencies.get(node)) {
            if (index[dependency] < 0) {
                strongConnect(dependency, dependencies, index, low, groupByNode,
                        onStack, stack, nextIndex, nextGroup);
                low[node] = Math.min(low[node], low[dependency]);
            } else if (onStack[dependency]) {
                low[node] = Math.min(low[node], index[dependency]);
            }
        }
        if (low[node] != index[node]) return;
        while (true) {
            int member = stack.pop();
            onStack[member] = false;
            groupByNode[member] = nextGroup[0];
            if (member == node) break;
        }
        nextGroup[0]++;
    }

    private static boolean groupDependsOn(
            int group,
            int target,
            List<Set<Integer>> dependencies) {
        if (group == target) return true;
        Set<Integer> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>(dependencies.get(group));
        while (!pending.isEmpty()) {
            int current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current == target) return true;
            pending.addAll(dependencies.get(current));
        }
        return false;
    }

    private record SccResult(int[] groupByNode, List<Set<Integer>> groupDependencies) {}

    private static Map<String, String> payload(Operation operation) {
        return switch (operation) {
            case AddView value -> Map.of("source", value.source());
            case RemoveView ignored -> Map.of();
            case ReplaceViewBody value -> Map.of("body", value.body());
            case ReplaceSubtree value -> Map.of("source", value.source());
            case InsertChild value -> Map.of("index", Integer.toString(value.index()), "source", value.source());
            case RemoveNode ignored -> Map.of();
            case MoveNode value -> Map.of(
                    "newParentId", value.newParentId().value(), "index", Integer.toString(value.index()));
            case SetProperty value -> Map.of("property", value.property(), "expression", value.expression());
        };
    }

    private static Operation operation(String name, SemanticId target, Map<String, String> payload) {
        return switch (name) {
            case ADD_VIEW -> new AddView(target, payload.get("source"));
            case REMOVE_VIEW -> new RemoveView(target);
            case REPLACE_VIEW_BODY -> new ReplaceViewBody(target, payload.get("body"));
            case REPLACE_SUBTREE -> new ReplaceSubtree(target, payload.get("source"));
            case INSERT_CHILD -> new InsertChild(target, Integer.parseInt(payload.get("index")), payload.get("source"));
            case REMOVE_NODE -> new RemoveNode(target);
            case MOVE_NODE -> new MoveNode(target, new SemanticId(payload.get("newParentId")),
                    Integer.parseInt(payload.get("index")));
            case SET_PROPERTY -> new SetProperty(target, payload.get("property"), payload.get("expression"));
            default -> throw new IllegalArgumentException("Unknown Deal UI repair operation " + name);
        };
    }

    private static String uiWorkspaceDigest(RepairWorkspaceSnapshot workspace) {
        return DealCompilerWorkspace.digest(CompilerProtocolJson.encode(List.of(
                workspace.workspaceId(), workspace.baseRevision(), workspace.inspectionDigest(),
                workspace.precondition(), workspace.slots(), workspace.groups(), workspace.repairRound())));
    }

    private static String uiWorkspaceId(String deal, String pack, String packSpecifier, String baseDigest,
                                        String inspectionDigest, List<Map<String, String>> payloads) {
        return DealCompilerWorkspace.digest(CompilerProtocolJson.encode(List.of(
                "ui-repair-context-v2", DealCompilerWorkspace.digest(deal), DealCompilerWorkspace.digest(pack),
                packSpecifier, baseDigest, inspectionDigest, payloads)));
    }

    private static RepairWorkspaceResult rejectedWorkspace(
            String source, RepairWorkspaceSnapshot workspace, String code, String message) {
        SemanticId owner = workspace.slots().isEmpty()
                ? new SemanticId("dealui:document:app.dealui") : workspace.slots().get(0).targetId();
        StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                code, "error", message, null, owner, "valid repair workspace",
                "invalid repair request", List.of(), List.of(), "inspectChange");
        return new RepairWorkspaceResult(false, source, DealCompilerWorkspace.digest(source),
                workspace, null, List.of(diagnostic));
    }

    private static Analysis analyze(String dealSource, String uiSource, String packSource, String packSpecifier) {
        String digest = DealCompilerWorkspace.digest(uiSource);
        SemanticId documentId = new SemanticId("dealui:document:app.dealui");
        SourceIndex index = new SourceIndex(uiSource);
        Map<SemanticId, Target> targets = new LinkedHashMap<>();
        List<UiViewSnapshot> viewSnapshots = new ArrayList<>();
        List<UiNodeSnapshot> nodeSnapshots = new ArrayList<>();
        String interfaceFingerprint = "";
        targets.put(documentId, new Target(
                documentId, documentId, "document", null,
                uiSource.length(), uiSource.length(), uiSource.length(), uiSource.length(),
                false, List.of(), Map.of(), List.of(), -1));
        try {
            Path dealFile = Path.of("/generated/app.deal");
            Path uiFile = Path.of("/generated/app.dealui");
            Path packFile = Path.of("/generated/platform-ui.dealui-pack");
            UiChecker checker = new UiChecker();
            UiModel.DealModule deal = checker.parseDeal(dealFile, dealSource);
            UiModel.ViewModule views = UiParser.parseViews(uiFile, uiSource);
            UiModel.PackModule pack = UiParser.parsePack(packFile, packSource);
            for (UiModel.View view : views.views()) {
                SemanticId viewId = new SemanticId("dealui:view:" + view.name());
                int[] body = viewBodyRange(index, view.span());
                List<SemanticId> roots = collectNodes(
                        view.nodes(), viewId, null, view.name(), digest, index, pack, targets, nodeSnapshots);
                Target viewTarget = new Target(
                        viewId, viewId, "view", range(view.span()),
                        index.offset(view.span().line(), view.span().column()),
                        index.offsetAfter(view.span().endLine(), view.span().endColumn()),
                        body[0], body[1], true, roots, Map.of(), List.of(), -1);
                targets.put(viewId, viewTarget);
                viewSnapshots.add(new UiViewSnapshot(
                        viewId, view.name(), view.root(), range(view.span()),
                        DealCompilerWorkspace.digest(index.slice(view.span())), roots));
            }
            var dealInspection = DealCompilerWorkspace.inspect(
                    dealSource, dealFile.toString(), DealUiDealSource.ADAPTER);
            interfaceFingerprint = dealInspection.appInterface() == null
                    ? "" : dealInspection.appInterface().fingerprint();
            UiModel.CheckedProgram structurallyChecked = checker.check(
                    uiFile, views, dealFile, deal, Map.of(packSpecifier, pack), true);
            requireHostCapabilities(
                    dealInspection.appInterface(),
                    structurallyChecked.metadata().componentCapabilities(),
                    pack,
                    views.views().stream().filter(UiModel.View::root)
                            .findFirst().orElse(views.views().get(0)).span());
            UiModel.CheckedProgram checked = checker.check(
                    uiFile, views, dealFile, deal, Map.of(packSpecifier, pack));
            UiInspection inspection = new UiInspection(
                    CompilerProtocol.VERSION, digest, documentId, interfaceFingerprint,
                    viewSnapshots, nodeSnapshots, checked.metadata(), ALLOWED_OPERATIONS, List.of());
            return new Analysis(uiSource, inspection, documentId, targets, index);
        } catch (UiDiagnostic failure) {
            boolean uiOwned = failure.repairArtifact().equals("dealui") || failure.file().toString().equals("/generated/app.dealui");
            Target owner = failure.file().toString().equals("/generated/app.dealui")
                    ? narrowestTarget(targets.values(), failure.line(), failure.column()) : null;
            if (uiOwned && owner == null && !viewSnapshots.isEmpty()) {
                SemanticId root = viewSnapshots.stream().filter(UiViewSnapshot::root)
                        .map(UiViewSnapshot::id).findFirst().orElse(viewSnapshots.get(0).id());
                owner = targets.get(root);
            }
            SemanticId ownerId = owner == null ? documentId : owner.id();
            List<RepairScope> repairScopes = new ArrayList<>();
            if (owner != null) repairScopes.add(new RepairScope(
                    owner.kind().equals("view") ? REPLACE_VIEW_BODY : REPLACE_SUBTREE, owner.id()));
            else if (uiOwned && viewSnapshots.isEmpty()) repairScopes.add(new RepairScope(ADD_VIEW, documentId));
            if ((failure.code().equals("UI2006") || failure.code().equals("UI2061")) && owner != null) {
                SemanticId ownerView = owner.ownerViewId();
                targets.values().stream()
                        .filter(Target::hasChildrenBlock)
                        .filter(value -> value.ownerViewId().equals(ownerView))
                        .map(value -> new RepairScope(INSERT_CHILD, value.id()))
                        .forEach(repairScopes::add);
            }
            StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                    failure.code(), "error", failure.getMessage(),
                    new SourceRange(failure.file().toString(), failure.line(), failure.column(), failure.endLine(), failure.endColumn()),
                    ownerId, failure.expected(), failure.actual(), List.of(), repairScopes,
                    owner == null ? "queryDealUiDocument" : "queryDealUiNode(" + owner.id().value() + ")",
                    null, failure.notes());
            String diagnosticSource = switch (failure.file().toString()) {
                case "/generated/app.deal" -> dealSource;
                case "/generated/app.dealui" -> uiSource;
                case "/generated/platform-ui.dealui-pack" -> packSource;
                default -> null;
            };
            if (diagnosticSource != null) diagnostic = diagnostic.withSourceContext(diagnosticSource);
            UiInspection inspection = new UiInspection(
                    CompilerProtocol.VERSION, digest, documentId, interfaceFingerprint,
                    viewSnapshots, nodeSnapshots, null,
                    ALLOWED_OPERATIONS, List.of(diagnostic));
            return new Analysis(uiSource, inspection, documentId, targets, index);
        } catch (RuntimeException failure) {
            List<RepairScope> repairScopes = viewSnapshots.isEmpty()
                    ? List.of(new RepairScope(ADD_VIEW, documentId)) : List.of();
            StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                    "CP2999", "error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    null, documentId, "", "", List.of(), repairScopes, "queryDealUiDocument");
            UiInspection inspection = new UiInspection(
                    CompilerProtocol.VERSION, digest, documentId, "", List.of(), List.of(), null,
                    ALLOWED_OPERATIONS, List.of(diagnostic));
            return new Analysis(uiSource, inspection, documentId, targets, index);
        }
    }

    private static void requireHostCapabilities(
            AppInterfaceSnapshot appInterface,
            Map<String, String> usedComponents,
            UiModel.PackModule pack,
            UiModel.Span span) {
        List<String> required = appInterface.capabilities();
        Set<String> implemented = usedComponents.values().stream()
                .filter(value -> value.startsWith("host."))
                .map(value -> value.substring("host.".length()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> missing = required.stream().filter(value -> !implemented.contains(value)).toList();
        if (missing.isEmpty()) return;
        List<UiModel.Component> matchingComponents = pack.components().values().stream()
                .filter(component -> component.contracts().stream()
                        .filter(UiModel.Capability.class::isInstance)
                        .map(UiModel.Capability.class::cast)
                        .map(UiModel.Capability::name)
                        .anyMatch(value -> value.startsWith("host.")
                                && missing.contains(value.substring("host.".length()))))
                .sorted(Comparator.comparing(UiModel.Component::name))
                .toList();
        throw new UiDiagnostic(
                "UI2061",
                "Declared host capabilities are not implemented by the view graph: "
                        + String.join(", ", missing),
                span.file(), span.line(), span.column(),
                matchingComponents.isEmpty()
                        ? "Use a component whose pack capability is host.<declared capability>"
                        : "Add and bind one of these host components: "
                                + matchingComponents.stream()
                                .map(component -> hostComponentRepairContract(component, appInterface))
                                .reduce((left, right) -> left + "; " + right).orElse(""),
                String.join(", ", missing)).withRepairArtifact("dealui");
    }

    private static String hostComponentRepairContract(
            UiModel.Component component,
            AppInterfaceSnapshot appInterface) {
        List<UiModel.Event> events = component.contracts().stream()
                .filter(UiModel.Event.class::isInstance)
                .map(UiModel.Event.class::cast)
                .toList();
        if (events.isEmpty()) return "ui." + component.name() + "()";
        return "ui." + component.name() + "; event bindings (choose exactly one alternative per event): "
                + events.stream().map(event -> eventRepairContract(event, appInterface))
                .reduce((left, right) -> left + "; " + right).orElse("");
    }

    private static String eventRepairContract(
            UiModel.Event event,
            AppInterfaceSnapshot appInterface) {
        String payloadType = event.payload() == null ? null : typeText(event.payload());
        List<String> bindings = appInterface.actions().stream()
                .map(action -> compatibleActionBinding(action.name(), action.fields(), payloadType))
                .filter(Objects::nonNull)
                .toList();
        String expected = event.prop() + " event"
                + (payloadType == null ? "" : " payload:" + payloadType);
        if (bindings.isEmpty()) return expected + " (no compatible app action; update DEAL first)";
        return event.prop() + " alternatives: " + bindings.stream()
                .map(binding -> "`" + event.prop() + ": " + binding + "`")
                .reduce((left, right) -> left + " or " + right).orElse("");
    }

    private static String compatibleActionBinding(
            String actionName,
            List<FieldSnapshot> fields,
            String payloadType) {
        if (payloadType == null) {
            return fields.isEmpty() ? "action app." + actionName + " {}" : null;
        }
        if (fields.isEmpty()) return "action app." + actionName + " {}";
        if (fields.size() != 1) return null;
        FieldSnapshot field = fields.get(0);
        if (field.array() || field.optional() || !field.type().equals(payloadType)) return null;
        return "action app." + actionName + " { " + field.name() + ": payload }";
    }

    private static String typeText(UiModel.TypeRef type) {
        return type.name() + "[]".repeat(type.dimensions()) + (type.optional() ? "?" : "");
    }

    private static RevisionRef revision(Analysis analysis) {
        return new RevisionRef(CompilerProtocol.VERSION, analysis.inspection().sourceDigest());
    }

    private static Target requireTarget(Analysis analysis, SemanticId id, String kind) {
        Target target = analysis.targets().get(id);
        if (target == null || kind != null && !target.kind().equals(kind)) {
            throw new IllegalArgumentException("Unknown Deal UI target " + id.value());
        }
        return target;
    }

    private static UiNodeSnapshot nodeSnapshot(Analysis analysis, SemanticId id) {
        return analysis.inspection().nodes().stream()
                .filter(value -> value.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Deal UI node " + id.value()));
    }

    private static List<UiNodeSnapshot> childSnapshots(Analysis analysis, List<SemanticId> children) {
        Map<SemanticId, UiNodeSnapshot> snapshots = new LinkedHashMap<>();
        analysis.inspection().nodes().forEach(value -> snapshots.put(value.id(), value));
        return children.stream().map(snapshots::get).filter(Objects::nonNull).toList();
    }

    private static OperationDescriptor descriptor(
            Analysis analysis, Target target, String operation, List<String> requiredFields) {
        return new OperationDescriptor(
                operation, target.id(), target.kind(), targetFingerprint(analysis, target.id()), requiredFields);
    }

    private static String targetFingerprint(Analysis analysis, SemanticId id) {
        if (id.equals(analysis.documentId())) return analysis.inspection().sourceDigest();
        UiViewSnapshot view = analysis.inspection().views().stream()
                .filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (view != null) return view.fingerprint();
        UiNodeSnapshot node = analysis.inspection().nodes().stream()
                .filter(value -> value.id().equals(id)).findFirst().orElse(null);
        return node == null ? "" : node.fingerprint();
    }

    private static UiChangeResult requireFingerprint(
            Analysis base, ChangeSetPrecondition precondition, SemanticId targetId) {
        String expected = precondition.expectedTargetFingerprints().get(targetId.value());
        String actual = targetFingerprint(base, targetId);
        if (expected == null) {
            return rejected(base, diagnostic(
                    "CP1010", "Missing target fingerprint precondition", targetId, null,
                    actual, "missing", List.of(), "queryDealUiNode"));
        }
        if (!expected.equals(actual)) {
            return rejected(base, diagnostic(
                    "CP1011", "Stale target fingerprint; query the target again before editing", targetId, null,
                    actual, expected, List.of(), "queryDealUiNode"));
        }
        return null;
    }

    private static Target narrowestTarget(Iterable<Target> targets, int line, int column) {
        Target selected = null;
        for (Target target : targets) {
            SourceRange range = target.range();
            if (range == null || !contains(range, line, column)) continue;
            if (selected == null || (target.end() - target.start()) < (selected.end() - selected.start())) {
                selected = target;
            }
        }
        return selected;
    }

    private static boolean contains(SourceRange range, int line, int column) {
        boolean afterStart = line > range.startLine()
                || line == range.startLine() && column >= range.startColumn();
        boolean beforeEnd = line < range.endLine()
                || line == range.endLine() && column <= range.endColumn();
        return afterStart && beforeEnd;
    }

    private static List<SemanticId> collectNodes(
            List<UiModel.Node> nodes,
            SemanticId viewId,
            SemanticId parentId,
            String path,
            String sourceDigest,
            SourceIndex index,
            UiModel.PackModule pack,
            Map<SemanticId, Target> targets,
            List<UiNodeSnapshot> snapshots) {
        List<SemanticId> result = new ArrayList<>();
        for (int position = 0; position < nodes.size(); position++) {
            UiModel.Node node = nodes.get(position);
            String nodePath = path + "/" + position;
            SemanticId id = new SemanticId("dealui-node:" + DealCompilerWorkspace
                    .digest(sourceDigest + "\u0000" + nodePath).substring(0, 24));
            List<UiModel.Node> children = children(node);
            List<SemanticId> childIds = collectNodes(
                    children, viewId, id, nodePath, sourceDigest, index, pack, targets, snapshots);
            String kind = node instanceof UiModel.Call ? "call"
                    : node instanceof UiModel.When ? "when" : "for-each";
            String component = node instanceof UiModel.Call call ? call.name() : kind;
            Set<String> paths = new LinkedHashSet<>();
            Set<String> actions = new LinkedHashSet<>();
            Map<String, SourceRange> properties = new LinkedHashMap<>();
            collectFacts(node, paths, actions, properties);
            List<String> writableProperties = writableProperties(node, pack);
            int start = index.offset(node.span().line(), node.span().column());
            int end = index.offsetAfter(node.span().endLine(), node.span().endColumn());
            boolean childBlock = node instanceof UiModel.Call call && call.childBlock() != null
                    || node instanceof UiModel.ForEach;
            int childStart = childBlock ? childContentStart(node, index, start, end) : -1;
            int childEnd = childBlock ? childContentEnd(node, index, start, end) : -1;
            int argumentEnd = node instanceof UiModel.Call ? callArgumentEnd(index.source(), start, end) : -1;
            Target target = new Target(
                    id, viewId, kind, range(node.span()), start, end,
                    childStart, childEnd,
                    childBlock, childIds, properties, writableProperties, argumentEnd);
            targets.put(id, target);
            snapshots.add(new UiNodeSnapshot(
                    id, viewId, parentId, kind, component, range(node.span()),
                    DealCompilerWorkspace.digest(index.source().substring(start, end)),
                    childIds, List.copyOf(paths), List.copyOf(actions), properties, writableProperties));
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static List<UiModel.Node> children(UiModel.Node node) {
        if (node instanceof UiModel.Call value) return value.children();
        if (node instanceof UiModel.ForEach value) return value.children();
        if (node instanceof UiModel.When value) {
            List<UiModel.Node> result = new ArrayList<>(value.thenNodes());
            result.addAll(value.elseNodes());
            return result;
        }
        return List.of();
    }

    private static void collectFacts(
            UiModel.Node node,
            Set<String> paths,
            Set<String> actions,
            Map<String, SourceRange> properties) {
        if (node instanceof UiModel.Call call) {
            call.arguments().forEach((name, expression) -> {
                properties.put(name, range(expression.span()));
                collectFacts(expression, paths, actions);
            });
        } else if (node instanceof UiModel.When when) {
            collectFacts(when.condition(), paths, actions);
        } else if (node instanceof UiModel.ForEach each) {
            paths.add(String.join(".", each.source().parts()));
            paths.add(String.join(".", each.key().parts()));
        }
    }

    private static void collectFacts(UiModel.Expr expression, Set<String> paths, Set<String> actions) {
        if (expression instanceof UiModel.PathExpr value) paths.add(String.join(".", value.parts()));
        else if (expression instanceof UiModel.Action value) {
            actions.add(value.name());
            value.fields().values().forEach(field -> collectFacts(field, paths, actions));
        } else if (expression instanceof UiModel.Binary value) {
            collectFacts(value.left(), paths, actions);
            collectFacts(value.right(), paths, actions);
        } else if (expression instanceof UiModel.Unary value) collectFacts(value.operand(), paths, actions);
        else if (expression instanceof UiModel.Has value) paths.add(String.join(".", value.path().parts()));
    }

    private static List<String> writableProperties(UiModel.Node node, UiModel.PackModule pack) {
        if (!(node instanceof UiModel.Call call)) return List.of();
        UiModel.Component component = pack.components().get(simpleName(call.name()));
        if (component == null) return List.of();
        UiModel.PackClass props = pack.classes().get(simpleName(component.propsType()));
        if (props == null) return List.of();
        return props.fields().stream().map(UiModel.Field::name).toList();
    }

    private static String simpleName(String qualified) {
        int separator = qualified.lastIndexOf('.');
        return separator < 0 ? qualified : qualified.substring(separator + 1);
    }

    private static int callArgumentEnd(String source, int start, int end) {
        int open = source.indexOf('(', start);
        if (open < 0 || open >= end) return -1;
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;
        for (int position = open + 1; position < end; position++) {
            char value = source.charAt(position);
            if (inString) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') inString = false;
                continue;
            }
            if (value == '"') inString = true;
            else if (value == '(') depth++;
            else if (value == ')' && --depth == 0) return position;
        }
        return -1;
    }

    private static int insertionOffset(Analysis analysis, Target parent, int index) {
        if (index < 0 || index > parent.children().size()) throw new IllegalArgumentException("Child index is out of range");
        if (index == parent.children().size()) return parent.contentEnd();
        Target child = analysis.targets().get(parent.children().get(index));
        return child.start();
    }

    private static int[] viewBodyRange(SourceIndex index, UiModel.Span span) {
        int start = index.offset(span.line(), span.column());
        int end = index.offsetAfter(span.endLine(), span.endColumn());
        int open = index.source().indexOf('{', start);
        int close = index.source().lastIndexOf('}', end - 1);
        if (open < 0 || close < open) throw new IllegalArgumentException("View body range is unavailable");
        return new int[] {open + 1, close};
    }

    private static int childContentStart(UiModel.Node node, SourceIndex index, int start, int end) {
        if (node instanceof UiModel.Call call && call.childBlock() != null) {
            return index.offset(call.childBlock().line(), call.childBlock().column()) + 1;
        }
        return index.source().indexOf('{', start) + 1;
    }

    private static int childContentEnd(UiModel.Node node, SourceIndex index, int start, int end) {
        if (node instanceof UiModel.Call call && call.childBlock() != null) {
            return index.offsetAfter(call.childBlock().endLine(), call.childBlock().endColumn()) - 1;
        }
        return index.source().lastIndexOf('}', end - 1);
    }

    private static String applyReplacements(String source, List<Replacement> replacements, SourceIndex index) {
        StringBuilder result = new StringBuilder(source);
        for (int position = replacements.size() - 1; position >= 0; position--) {
            Replacement replacement = replacements.get(position);
            String value = replacement.blockContent()
                    ? formatBlockContent(replacement.source(), source, replacement.start())
                    : replacement.source();
            result.replace(replacement.start(), replacement.end(), value);
        }
        return result.toString();
    }

    private static String formatBlockContent(String value, String source, int offset) {
        if (value.isBlank()) return "";
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        String indentation = source.substring(lineStart, offset).replaceAll("[^ \\t]", "") + "  ";
        return deal.compiler.ConstructionProjection.indentBlock(
                new deal.compiler.ConstructionProjection.Result(value, List.of()), indentation,
                indentation.substring(0, Math.max(0, indentation.length() - 2))).source();
    }

    private static UiChangeResult wrongKind(Analysis base, Operation operation, Target target, String expected) {
        return rejected(base, diagnostic(
                "CP1005", "Operation does not match Deal UI target kind", target.id(), target.range(),
                expected, target.kind(), List.of(), "queryDealUiNode"));
    }

    private static UiChangeResult rejected(Analysis base, StructuredDiagnostic diagnostic) {
        return new UiChangeResult(
                false, base.source(), base.inspection().sourceDigest(), base.inspection(),
                emptyImpact(), List.of(diagnostic));
    }

    private static UiImpactReport emptyImpact() {
        return new UiImpactReport(List.of(), List.of(), List.of(), List.of(), false, false);
    }

    private static StructuredDiagnostic scopeDiagnostic(
            StructuredDiagnostic diagnostic,
            List<? extends Operation> operations, Analysis base) {
        List<RepairScope> scopes = new ArrayList<>(operations.stream()
                .map(value -> new RepairScope(operationName(value), value.targetId())).toList());
        // Candidate node ids are revision-scoped. Reissue global insertion permissions on
        // untouched base containers, never forward a candidate id into a base transaction.
        if (diagnostic.repairScopes().stream().anyMatch(scope -> scope.operation().equals(INSERT_CHILD))) {
            for (Target parent : base.targets().values()) {
                if (!parent.hasChildrenBlock()) continue;
                boolean nearestContainer = operations.stream().anyMatch(operation -> {
                    if (operation instanceof InsertChild) return false;
                    Target target = base.targets().get(operation.targetId());
                    if (target == null || parent.start() > target.start() || parent.end() < target.end()) return false;
                    return base.targets().values().stream().noneMatch(other -> other.hasChildrenBlock()
                            && !other.id().equals(parent.id()) && !other.id().equals(target.id())
                            && other.start() >= parent.start() && other.end() <= parent.end()
                            && other.start() <= target.start() && other.end() >= target.end());
                });
                if (!nearestContainer) continue;
                boolean replaced = operations.stream().anyMatch(operation -> {
                    Target target = base.targets().get(operation.targetId());
                    return target != null && !(operation instanceof InsertChild) && !(operation instanceof SetProperty)
                            && target.start() <= parent.start() && target.end() >= parent.end();
                });
                if (!replaced) scopes.add(new RepairScope(INSERT_CHILD, parent.id()));
            }
        }
        return new StructuredDiagnostic(
                diagnostic.code(), diagnostic.severity(), diagnostic.message(), diagnostic.range(),
                operations.get(0).targetId(), diagnostic.expected(), diagnostic.actual(),
                operations.stream().map(Operation::targetId).toList(),
                scopes,
                "queryDealUiNode(" + operations.get(0).targetId().value() + ")",
                diagnostic.context(), diagnostic.notes(), diagnostic.operationIndex(), diagnostic.missingSymbols());
    }

    private static StructuredDiagnostic diagnostic(
            String code,
            String message,
            SemanticId owner,
            SourceRange range,
            String expected,
            String actual,
            List<RepairScope> repairScopes,
            String query) {
        return new StructuredDiagnostic(
                code, "error", message, range, owner, expected, actual,
                List.of(), repairScopes, query);
    }

    private static String operationName(Operation operation) {
        return switch (operation) {
            case AddView ignored -> ADD_VIEW;
            case RemoveView ignored -> REMOVE_VIEW;
            case ReplaceViewBody ignored -> REPLACE_VIEW_BODY;
            case ReplaceSubtree ignored -> REPLACE_SUBTREE;
            case InsertChild ignored -> INSERT_CHILD;
            case RemoveNode ignored -> REMOVE_NODE;
            case MoveNode ignored -> MOVE_NODE;
            case SetProperty ignored -> SET_PROPERTY;
        };
    }

    private static boolean hasErrors(List<StructuredDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.severity().equals("error"));
    }

    private static SourceRange range(UiModel.Span span) {
        return new SourceRange(span.file().toString(), span.line(), span.column(), span.endLine(), span.endColumn());
    }

    private static Set<String> allActions(UiInspection inspection) {
        Set<String> result = new LinkedHashSet<>();
        inspection.nodes().forEach(node -> result.addAll(node.actionBindings()));
        return result;
    }

    private static Set<String> allPaths(UiInspection inspection) {
        Set<String> result = new LinkedHashSet<>();
        inspection.nodes().forEach(node -> result.addAll(node.statePaths()));
        return result;
    }

    private static Set<String> usedComponents(UiInspection inspection) {
        if (inspection.checkedMetadata() != null) {
            return Set.copyOf(inspection.checkedMetadata().usedComponents());
        }
        Set<String> result = new LinkedHashSet<>();
        inspection.nodes().stream().filter(value -> value.kind().equals("call"))
                .map(UiNodeSnapshot::component).forEach(result::add);
        return result;
    }

    private static Map<String, String> componentCapabilities(UiInspection inspection) {
        return inspection.checkedMetadata() == null
                ? Map.of() : inspection.checkedMetadata().componentCapabilities();
    }

    private static List<String> symmetricDifference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        Set<String> common = new LinkedHashSet<>(left);
        common.retainAll(right);
        result.removeAll(common);
        return List.copyOf(result);
    }

    private record Target(
            SemanticId id,
            SemanticId ownerViewId,
            String kind,
            SourceRange range,
            int start,
            int end,
            int contentStart,
            int contentEnd,
            boolean hasChildrenBlock,
            List<SemanticId> children,
            Map<String, SourceRange> properties,
            List<String> writableProperties,
            int argumentEnd) {}

    private record Replacement(int start, int end, String source, boolean blockContent) {}

    private record Analysis(
            String source,
            UiInspection inspection,
            SemanticId documentId,
            Map<SemanticId, Target> targets,
            SourceIndex index) {}

    private static final class SourceIndex {
        private final String source;
        private final int[] lines;

        private SourceIndex(String source) {
            this.source = source;
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int position = 0; position < source.length(); position++) {
                if (source.charAt(position) == '\n') offsets.add(position + 1);
            }
            lines = offsets.stream().mapToInt(Integer::intValue).toArray();
        }

        private String source() { return source; }
        private int offset(int line, int column) { return lines[line - 1] + column - 1; }
        private int offsetAfter(int line, int column) { return Math.min(source.length(), offset(line, column) + 1); }
        private String slice(UiModel.Span span) {
            return source.substring(offset(span.line(), span.column()), offsetAfter(span.endLine(), span.endColumn()));
        }
    }
}
