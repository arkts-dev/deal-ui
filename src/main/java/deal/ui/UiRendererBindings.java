package deal.ui;

import java.awt.Color;
import java.util.Map;

public final class UiRendererBindings {
    public enum Kind { COLUMN, CARD, TEXT, INT_TEXT, BUTTON, INPUT, SPINNER }
    public record Binding(String component, Kind kind) {}

    private final Map<String, Binding> components;
    private final Map<String, Object> tokens;
    private final int mountActionSlot;
    private final Color background;
    private final Color accent;

    public UiRendererBindings(Map<String, Binding> components, Map<String, Object> tokens, int mountActionSlot, Color background, Color accent) {
        this.components = Map.copyOf(components);
        this.tokens = Map.copyOf(tokens);
        this.mountActionSlot = mountActionSlot;
        this.background = background;
        this.accent = accent;
    }

    public Binding require(String component) {
        Binding binding = components.get(component);
        if (binding == null) throw new IllegalStateException("No renderer binding for " + component);
        return binding;
    }
    public int spacing(String token) {
        Object value = tokens.get(token);
        if (value instanceof Number number) return number.intValue();
        throw new IllegalStateException("Spacing token is not numeric: " + token);
    }
    public int mountActionSlot() { return mountActionSlot; }
    public Color background() { return background; }
    public Color accent() { return accent; }
}
