package deal.ui;

import deal.compiler.CompilerProtocol;
import deal.compiler.CompilerProtocol.ChangeSetPrecondition;
import deal.compiler.CompilerProtocol.OperationDescriptor;
import deal.compiler.CompilerProtocol.RepairScope;
import deal.compiler.CompilerProtocol.RevisionRef;
import deal.compiler.CompilerProtocol.SemanticId;
import deal.compiler.CompilerProtocol.SourceRange;
import deal.compiler.CompilerProtocol.StructuredDiagnostic;
import deal.compiler.DealCompilerWorkspace;

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
    private static final List<String> ALLOWED_OPERATIONS = List.of(
            REPLACE_VIEW_BODY, REPLACE_SUBTREE, INSERT_CHILD, REMOVE_NODE, MOVE_NODE, SET_PROPERTY);

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

    public sealed interface Operation permits ReplaceViewBody, ReplaceSubtree, InsertChild, RemoveNode, MoveNode, SetProperty {
        SemanticId targetId();
    }

    public record ReplaceViewBody(SemanticId targetId, String body) implements Operation {}
    public record ReplaceSubtree(SemanticId targetId, String source) implements Operation {}
    public record InsertChild(SemanticId targetId, int index, String source) implements Operation {}
    public record RemoveNode(SemanticId targetId) implements Operation {}
    public record MoveNode(SemanticId targetId, SemanticId newParentId, int index) implements Operation {}
    public record SetProperty(SemanticId targetId, String property, String expression) implements Operation {}

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
                List.of(descriptor(analysis, target, REPLACE_VIEW_BODY, List.of("body"))));
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
            Set<String> writable = new LinkedHashSet<>();
            base.inspection().diagnostics().forEach(value -> value.repairScopes().forEach(scope ->
                    writable.add(scope.operation() + ":" + scope.ownerId().value())));
            boolean outsideRepairScope = requested.stream().anyMatch(value ->
                    !writable.contains(operationName(value) + ":" + value.targetId().value()));
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
                    .map(value -> scopeDiagnostic(value, requested))
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

    private static Analysis analyze(String dealSource, String uiSource, String packSource, String packSpecifier) {
        String digest = DealCompilerWorkspace.digest(uiSource);
        SemanticId documentId = new SemanticId("dealui:document:app.dealui");
        SourceIndex index = new SourceIndex(uiSource);
        Map<SemanticId, Target> targets = new LinkedHashMap<>();
        List<UiViewSnapshot> viewSnapshots = new ArrayList<>();
        List<UiNodeSnapshot> nodeSnapshots = new ArrayList<>();
        String interfaceFingerprint = "";
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
            UiModel.CheckedProgram checked = checker.check(
                    uiFile, views, dealFile, deal, Map.of(packSpecifier, pack));
            UiInspection inspection = new UiInspection(
                    CompilerProtocol.VERSION, digest, interfaceFingerprint,
                    viewSnapshots, nodeSnapshots, checked.metadata(), ALLOWED_OPERATIONS, List.of());
            return new Analysis(uiSource, inspection, documentId, targets, index);
        } catch (UiDiagnostic failure) {
            Target owner = narrowestTarget(targets.values(), failure.line(), failure.column());
            if (owner == null && !viewSnapshots.isEmpty()) {
                SemanticId root = viewSnapshots.stream().filter(UiViewSnapshot::root)
                        .map(UiViewSnapshot::id).findFirst().orElse(viewSnapshots.get(0).id());
                owner = targets.get(root);
            }
            SemanticId ownerId = owner == null ? documentId : owner.id();
            List<RepairScope> repairScopes = new ArrayList<>();
            if (owner != null) repairScopes.add(new RepairScope(
                    owner.kind().equals("view") ? REPLACE_VIEW_BODY : REPLACE_SUBTREE, owner.id()));
            if (failure.code().equals("UI2006") && owner != null) {
                SemanticId ownerView = owner.ownerViewId();
                targets.values().stream()
                        .filter(Target::hasChildrenBlock)
                        .filter(value -> value.ownerViewId().equals(ownerView))
                        .map(value -> new RepairScope(INSERT_CHILD, value.id()))
                        .forEach(repairScopes::add);
            }
            StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                    failure.code(), "error", failure.getMessage(),
                    new SourceRange(failure.file().toString(), failure.line(), failure.column(), failure.line(), failure.column()),
                    ownerId, "", "", List.of(), repairScopes,
                    owner == null ? "inspectCanonicalApp" : "queryDealUiNode(" + owner.id().value() + ")");
            UiInspection inspection = new UiInspection(
                    CompilerProtocol.VERSION, digest, interfaceFingerprint,
                    viewSnapshots, nodeSnapshots, null,
                    ALLOWED_OPERATIONS, List.of(diagnostic));
            return new Analysis(uiSource, inspection, documentId, targets, index);
        } catch (RuntimeException failure) {
            StructuredDiagnostic diagnostic = new StructuredDiagnostic(
                    "CP2999", "error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    null, documentId, "", "", List.of(), List.of(), "inspectCanonicalApp");
            UiInspection inspection = new UiInspection(
                    CompilerProtocol.VERSION, digest, "", List.of(), List.of(), null,
                    ALLOWED_OPERATIONS, List.of(diagnostic));
            return new Analysis(uiSource, inspection, documentId, Map.of(), index);
        }
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
        return "\n" + value.lines().map(line -> indentation + line.stripLeading())
                .reduce((left, right) -> left + "\n" + right).orElse("") + "\n" + indentation.substring(0, Math.max(0, indentation.length() - 2));
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
            List<? extends Operation> operations) {
        return new StructuredDiagnostic(
                diagnostic.code(), diagnostic.severity(), diagnostic.message(), diagnostic.range(),
                operations.get(0).targetId(), diagnostic.expected(), diagnostic.actual(),
                operations.stream().map(Operation::targetId).toList(),
                operations.stream().map(value -> new RepairScope(operationName(value), value.targetId())).toList(),
                "queryDealUiNode(" + operations.get(0).targetId().value() + ")");
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
