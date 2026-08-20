package deal.ui;

import deal.ui.runtime.SwingUiRuntime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiProgramRuntime implements AutoCloseable {
    private final UiModel.CheckedProgram program;
    private final SwingUiRuntime swing;
    private final Class<?> stateClass;
    private final Class<?> actionClass;
    private final Method updateMethod;
    private Object state;

    public UiProgramRuntime(UiModel.CheckedProgram program, String generatedModuleClass) {
        this.program = program;
        try {
            Class<?> moduleClass = Class.forName(generatedModuleClass, true,
                Thread.currentThread().getContextClassLoader());
            stateClass = nestedClass(moduleClass, "$C_" + program.ir().stateType());
            actionClass = nestedClass(moduleClass, "$C_" + program.actionType());
            updateMethod = moduleClass.getDeclaredMethod("update", stateClass, actionClass);
            updateMethod.setAccessible(true);
            state = construct(stateClass, program.stateFields(), initialValues(program.stateFields()));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to bind generated DEAL state/action/update surface", failure);
        }
        swing = new SwingUiRuntime(program.ir().name(), this::dispatch);
    }

    public Map<String, Object> stateSnapshot() { return readState(); }
    public SwingUiRuntime.Node tree() { return buildRoot(); }
    public void show() { swing.show(buildRoot()); }
    public void dispatch(String actionType) { dispatch(actionType, Map.of()); }

    public void dispatch(String actionType, Map<String, Object> payload) {
        if (!program.actionType().equals(actionType)) {
            throw new IllegalArgumentException("Unknown action type: " + actionType);
        }
        try {
            Map<String, Object> values = new LinkedHashMap<>(initialValues(program.actionFields()));
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (!program.actionFields().containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("Unknown action payload field: " + entry.getKey());
                }
                values.put(entry.getKey(), entry.getValue());
            }
            Object action = construct(actionClass, program.actionFields(), Map.copyOf(values));
            Object next = updateMethod.invoke(null, state, action);
            if (next == null || !stateClass.isInstance(next)) {
                throw new IllegalStateException("DEAL update returned a value outside the root state type");
            }
            state = next;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("DEAL update failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to dispatch generated DEAL action", failure);
        }
        swing.render(buildRoot());
    }

    public SwingUiRuntime renderer() { return swing; }
    public void capture(Path destination) { swing.capture(destination); }
    public void clickButton(String text) { swing.clickButton(text); }
    @Override public void close() { swing.close(); }

    private SwingUiRuntime.Node buildRoot() {
        List<SwingUiRuntime.Node> roots = buildNodes(program.ir().children());
        if (roots.size() != 1) {
            throw new IllegalStateException("Root view must evaluate to exactly one component, got " + roots.size());
        }
        return roots.get(0);
    }

    private List<SwingUiRuntime.Node> buildNodes(List<UiModel.IrNode> nodes) {
        List<SwingUiRuntime.Node> result = new ArrayList<>();
        for (UiModel.IrNode node : nodes) {
            if (node instanceof UiModel.IrWhen when) {
                if (booleanValue(when.condition())) result.addAll(buildNodes(when.children()));
                continue;
            }
            UiModel.IrComponent component = (UiModel.IrComponent) node;
            List<SwingUiRuntime.Node> children = buildNodes(component.children());
            result.add(switch (component.name()) {
                case "Card" -> new SwingUiRuntime.CardNode(children, component.span());
                case "Column" -> new SwingUiRuntime.ColumnNode(children, component.span());
                case "Text" -> new SwingUiRuntime.TextNode(stringValue(component.props().get("value")), component.span());
                case "Button" -> new SwingUiRuntime.ButtonNode(
                    stringValue(component.props().get("text")),
                    ((UiModel.IrAction) component.props().get("onClick")).typeName(), component.span());
                default -> throw new IllegalStateException("Unknown checked component: " + component.name());
            });
        }
        return List.copyOf(result);
    }

    private String stringValue(UiModel.IrExpression expression) {
        Object value = value(expression);
        if (value instanceof String string) return string;
        throw new IllegalStateException("Expected checked string expression");
    }

    private boolean booleanValue(UiModel.IrExpression expression) {
        Object value = value(expression);
        if (value instanceof Boolean bool) return bool;
        throw new IllegalStateException("Expected checked boolean expression");
    }

    private Object value(UiModel.IrExpression expression) {
        if (expression instanceof UiModel.IrString literal) return literal.value();
        if (expression instanceof UiModel.IrBoolean literal) return literal.value();
        if (expression instanceof UiModel.IrStateField field) return readField(state, field.field());
        if (expression instanceof UiModel.IrNot not) return !booleanValue(not.operand());
        throw new IllegalStateException("Action values are not scalar UI expressions");
    }

    private Map<String, Object> readState() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : program.stateFields().keySet()) result.put(field, readField(state, field));
        return Map.copyOf(result);
    }

    private Object readField(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to read generated DEAL field '" + name + "'", failure);
        }
    }

    private Object construct(Class<?> type, Map<String, UiModel.FieldInfo> fields, Map<String, Object> values)
            throws ReflectiveOperationException {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length != 1) {
            throw new IllegalStateException("Generated DEAL class must have exactly one constructor: " + type.getName());
        }
        Constructor<?> constructor = constructors[0];
        constructor.setAccessible(true);
        List<Object> args = new ArrayList<>();
        for (String field : fields.keySet()) args.add(values.get(field));
        return constructor.newInstance(args.toArray());
    }

    private Map<String, Object> initialValues(Map<String, UiModel.FieldInfo> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, UiModel.FieldInfo> field : fields.entrySet()) {
            result.put(field.getKey(), field.getValue().defaultValue());
        }
        return Map.copyOf(result);
    }

    private Class<?> nestedClass(Class<?> owner, String simpleName) {
        for (Class<?> nested : owner.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName)) return nested;
        }
        throw new IllegalStateException("Generated DEAL class not found: " + owner.getName() + "." + simpleName);
    }
}
