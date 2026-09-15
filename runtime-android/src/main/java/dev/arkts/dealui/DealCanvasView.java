package dev.arkts.dealui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import deal.ui.PortableUiProgramRuntime;
import deal.ui.UiPortableBridge;
import deal.ui.runtime.IsometricProjection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Generic retained canvas renderer for typed Deal UI capabilities. */
public final class DealCanvasView extends View implements PortableUiProgramRuntime.Renderer {
    private static final int CYAN = Color.rgb(57, 229, 255);
    private static final int VIOLET = Color.rgb(188, 83, 255);
    private static final int AMBER = Color.rgb(255, 177, 50);
    private static final int RED = Color.rgb(255, 72, 91);
    private static final int TEXT = Color.rgb(224, 244, 250);
    private static final int MUTED = Color.rgb(119, 157, 173);

    private final UiPortableBridge bridge;
    private final Map<String, String> capabilities;
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final Map<UiPortableBridge.Identity, SpriteAnimation> spriteAnimations = new HashMap<>();
    private final List<HitTarget> hitTargets = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private volatile UiPortableBridge.Node tree;
    private PortableUiProgramRuntime runtime;
    private float designWidth = 1920f;
    private float designHeight = 1080f;
    private float viewportScale = 1f;
    private float viewportLeft;
    private float viewportTop;
    private final long animationStart = SystemClock.uptimeMillis();
    private boolean presentationPaused;

    public DealCanvasView(Context context, UiPortableBridge bridge) {
        super(context);
        this.bridge = bridge;
        this.capabilities = bridge.componentCapabilities();
        stroke.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        setKeepScreenOn(true);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public void bindRuntime(PortableUiProgramRuntime runtime) {
        this.runtime = runtime;
    }

    public void setPresentationPaused(boolean paused) {
        presentationPaused = paused;
        if (!paused) requestFrame();
    }

    @Override public void show(UiPortableBridge.Node next) {
        updateSpriteAnimations(next);
        tree = next;
        requestFrame();
    }

    @Override public void apply(List<UiPortableBridge.Patch> patches, UiPortableBridge.Node next) {
        updateSpriteAnimations(next);
        tree = next;
        requestFrame();
    }

    private void requestFrame() {
        if (Looper.myLooper() == Looper.getMainLooper()) invalidate();
        else postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        UiPortableBridge.Node root = tree;
        if (root == null) return;
        designWidth = number(root, "designWidth", 1920f);
        designHeight = number(root, "designHeight", 1080f);
        viewportScale = Math.min(getWidth() / designWidth, getHeight() / designHeight);
        viewportLeft = (getWidth() - designWidth * viewportScale) * 0.5f;
        viewportTop = (getHeight() - designHeight * viewportScale) * 0.5f;
        canvas.drawColor(Color.rgb(1, 4, 10));
        int checkpoint = canvas.save();
        canvas.translate(viewportLeft, viewportTop);
        canvas.scale(viewportScale, viewportScale);
        drawBackground(canvas, string(root, "backgroundAsset", ""));
        hitTargets.clear();
        drawNode(canvas, root);
        canvas.restoreToCount(checkpoint);
        if (!presentationPaused) postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas canvas, String asset) {
        Bitmap bitmap = bitmap(asset);
        if (bitmap != null) {
            float sourceRatio = bitmap.getWidth() / (float) bitmap.getHeight();
            float targetRatio = designWidth / designHeight;
            Rect source;
            if (sourceRatio > targetRatio) {
                int width = Math.round(bitmap.getHeight() * targetRatio);
                int left = (bitmap.getWidth() - width) / 2;
                source = new Rect(left, 0, left + width, bitmap.getHeight());
            } else {
                int height = Math.round(bitmap.getWidth() / targetRatio);
                int top = (bitmap.getHeight() - height) / 2;
                source = new Rect(0, top, bitmap.getWidth(), top + height);
            }
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, source, new RectF(0, 0, designWidth, designHeight), paint);
        }
        paint.setShader(new LinearGradient(0, 0, 0, designHeight, 0x2508293C, 0xE802050B, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, designWidth, designHeight, paint);
        paint.setShader(null);
    }

    private void drawNode(Canvas canvas, UiPortableBridge.Node node) {
        if (!bool(node, "visible", true)) return;
        String capability = capabilities.get(node.component());
        if (capability == null) throw new IllegalStateException("No capability for " + node.component());
        switch (capability) {
            case "renderer.ui.root" -> drawChildren(canvas, node);
            case "renderer.ui.panel" -> {
                RectF bounds = bounds(node);
                if (string(node, "variant", "surface").equals("modal")) {
                    paint.setColor(0xB8000208);
                    canvas.drawRect(0, 0, designWidth, designHeight, paint);
                }
                panel(canvas, bounds, color(string(node, "accent", "cyan")), panelFill(string(node, "variant", "surface")));
                drawChildren(canvas, node);
            }
            case "renderer.ui.text" -> drawTextNode(canvas, node, string(node, "value", ""));
            case "renderer.ui.intText" -> drawTextNode(canvas, node, string(node, "prefix", "") + integer(node, "value", 0) + string(node, "suffix", ""));
            case "renderer.ui.progress" -> drawProgress(canvas, node);
            case "renderer.ui.button" -> drawButton(canvas, node);
            case "renderer.scene.isometric" -> drawIsometricScene(canvas, node);
            case "renderer.scene.tile", "renderer.scene.sprite", "renderer.scene.effect" -> { }
            default -> throw new IllegalStateException("Unsupported Deal UI capability " + capability);
        }
    }

    private void drawChildren(Canvas canvas, UiPortableBridge.Node node) {
        for (UiPortableBridge.Node child : node.children()) drawNode(canvas, child);
    }

    private void drawTextNode(Canvas canvas, UiPortableBridge.Node node, String value) {
        RectF bounds = bounds(node);
        String variant = string(node, "variant", "body");
        float size = switch (variant) {
            case "title" -> 42f;
            case "heading" -> 26f;
            case "caption", "label" -> 15f;
            default -> 18f;
        };
        Typeface typeface = variant.equals("body") ? regular : condensed;
        Paint.Align align = switch (string(node, "align", "left")) {
            case "center" -> Paint.Align.CENTER;
            case "right" -> Paint.Align.RIGHT;
            default -> Paint.Align.LEFT;
        };
        float x = align == Paint.Align.CENTER ? bounds.centerX() : align == Paint.Align.RIGHT ? bounds.right : bounds.left;
        if (variant.equals("body") && value.contains(" ")) drawWrapped(canvas, value, x, bounds.top + size, bounds.width(), bounds.height(), size, color(string(node, "color", "text")), align);
        else drawText(canvas, value, x, bounds.top + size, size, color(string(node, "color", "text")), typeface, align);
    }

    private void drawProgress(Canvas canvas, UiPortableBridge.Node node) {
        RectF bounds = bounds(node);
        long total = Math.max(1, integer(node, "total", 1));
        float ratio = Math.max(0f, Math.min(1f, integer(node, "value", 0) / (float) total));
        paint.setColor(0xB5132029);
        canvas.drawRoundRect(bounds, bounds.height() * 0.5f, bounds.height() * 0.5f, paint);
        RectF filled = new RectF(bounds.left, bounds.top, bounds.left + bounds.width() * ratio, bounds.bottom);
        paint.setColor(color(string(node, "color", "cyan")));
        paint.setShadowLayer(12f, 0, 0, paint.getColor());
        canvas.drawRoundRect(filled, bounds.height() * 0.5f, bounds.height() * 0.5f, paint);
        paint.clearShadowLayer();
    }

    private void drawButton(Canvas canvas, UiPortableBridge.Node node) {
        RectF bounds = bounds(node);
        boolean enabled = bool(node, "enabled", true);
        boolean selected = bool(node, "selected", false);
        int accent = color(string(node, "accent", "cyan"));
        paint.setAlpha(enabled ? 255 : 105);
        panel(canvas, bounds, accent, enabled ? selected ? withAlpha(accent, 52) : 0xD0081420 : 0xD00A0D11);
        String icon = string(node, "icon", "");
        if (!icon.isEmpty()) drawIcon(canvas, icon, bounds.centerX(), bounds.top + bounds.height() * 0.34f, bounds.height() * 0.18f, accent);
        float textY = icon.isEmpty() ? bounds.centerY() - 3f : bounds.bottom - bounds.height() * 0.28f;
        drawText(canvas, string(node, "text", ""), bounds.centerX(), textY, Math.min(22f, bounds.height() * 0.22f), enabled ? TEXT : MUTED, condensed, Paint.Align.CENTER);
        String detail = string(node, "detail", "");
        if (!detail.isEmpty()) drawText(canvas, detail, bounds.centerX(), bounds.bottom - 14f, 13f, enabled ? MUTED : 0xFF40515A, regular, Paint.Align.CENTER);
        UiPortableBridge.Prop action = node.props().get("onClick");
        if (enabled && action != null && action.actionSlot() >= 0) {
            hitTargets.add(HitTarget.button(bounds, action.actionSlot(), integer(node, "payload", 0)));
        }
        paint.setAlpha(255);
    }

    private void drawIcon(Canvas canvas, String icon, float centerX, float centerY, float size, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(2f, size * 0.12f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        if (icon.equals("cross")) {
            canvas.drawLine(centerX - size, centerY, centerX + size, centerY, stroke);
            canvas.drawLine(centerX, centerY - size, centerX, centerY + size, stroke);
        } else if (icon.equals("hexagon")) {
            for (int i = 0; i < 6; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 3;
                float x = centerX + (float) Math.cos(angle) * size;
                float y = centerY + (float) Math.sin(angle) * size;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            canvas.drawPath(path, stroke);
        } else {
            path.moveTo(centerX, centerY - size);
            path.lineTo(centerX + size * 0.28f, centerY - size * 0.25f);
            path.lineTo(centerX + size, centerY);
            path.lineTo(centerX + size * 0.28f, centerY + size * 0.25f);
            path.lineTo(centerX, centerY + size);
            path.lineTo(centerX - size * 0.28f, centerY + size * 0.25f);
            path.lineTo(centerX - size, centerY);
            path.lineTo(centerX - size * 0.28f, centerY - size * 0.25f);
            path.close();
            canvas.drawPath(path, stroke);
        }
    }

    private void drawIsometricScene(Canvas canvas, UiPortableBridge.Node scene) {
        RectF sceneBounds = bounds(scene);
        panel(canvas, sceneBounds, CYAN, 0x9901050B);
        int checkpoint = canvas.save();
        canvas.clipRect(inset(sceneBounds, 3f));
        int biome = (int) integer(scene, "biome", 0);
        paint.setColor(switch (Math.floorMod(biome, 3)) { case 1 -> 0x241E0A35; case 2 -> 0x24351D08; default -> 0x24102132; });
        canvas.drawRect(sceneBounds, paint);
        SceneProjection projection = new SceneProjection(
            sceneBounds,
            number(scene, "tileWidth", 128f),
            number(scene, "tileHeight", 64f),
            (int) integer(scene, "cameraX", 0),
            (int) integer(scene, "cameraY", 0)
        );
        List<UiPortableBridge.Node> tiles = nodesWithCapability(scene, "renderer.scene.tile");
        tiles.sort(Comparator.<UiPortableBridge.Node>comparingLong(tile -> integer(tile, "elevation", 0)).thenComparingDouble(this::depth));
        for (UiPortableBridge.Node tile : tiles) drawTile(canvas, tile, projection);
        List<UiPortableBridge.Node> sprites = nodesWithCapability(scene, "renderer.scene.sprite");
        sprites.sort(Comparator.comparingDouble(this::depth));
        for (UiPortableBridge.Node sprite : sprites) drawSprite(canvas, sprite, projection);
        for (UiPortableBridge.Node effect : nodesWithCapability(scene, "renderer.scene.effect")) drawEffect(canvas, effect, projection);
        canvas.restoreToCount(checkpoint);
        UiPortableBridge.Prop action = scene.props().get("onCell");
        if (action != null && action.actionSlot() >= 0) hitTargets.add(HitTarget.scene(sceneBounds, action.actionSlot(), projection, (int) integer(scene, "gridWidth", 1), (int) integer(scene, "gridHeight", 1)));
    }

    private List<UiPortableBridge.Node> nodesWithCapability(UiPortableBridge.Node parent, String capability) {
        List<UiPortableBridge.Node> result = new ArrayList<>();
        for (UiPortableBridge.Node child : parent.children()) if (capability.equals(capabilities.get(child.component()))) result.add(child);
        return result;
    }

    private double depth(UiPortableBridge.Node node) {
        return integer(node, "x", 0) + integer(node, "y", 0) + number(node, "zBias", 0f);
    }

    private void drawTile(Canvas canvas, UiPortableBridge.Node tile, SceneProjection projection) {
        if (!bool(tile, "render", true)) return;
        RectF cell = projection.cell((int) integer(tile, "x", 0), (int) integer(tile, "y", 0));
        int elevation = (int) integer(tile, "elevation", 0);
        float alpha = number(tile, "alpha", 1f);
        if (!bool(tile, "discovered", true)) alpha *= 0.15f;
        else if (!bool(tile, "illuminated", true)) alpha *= 0.48f;
        paint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        Bitmap atlas = bitmap(string(tile, "asset", ""));
        if (atlas == null) return;
        if (elevation <= 0) {
            int checkpoint = canvas.save();
            canvas.clipPath(diamond(cell));
            drawAtlasCell(canvas, atlas, (int) integer(tile, "atlasColumns", 1), (int) integer(tile, "atlasRows", 1), (int) integer(tile, "atlasIndex", 0), cell, paint);
            canvas.restoreToCount(checkpoint);
        } else {
            RectF destination = new RectF(cell.centerX() - cell.width() * 0.62f, cell.centerY() - cell.width() * 0.72f, cell.centerX() + cell.width() * 0.62f, cell.centerY() + cell.height() * 0.72f);
            drawAtlasCell(canvas, atlas, (int) integer(tile, "atlasColumns", 1), (int) integer(tile, "atlasRows", 1), (int) integer(tile, "atlasIndex", 0), destination, paint);
        }
        long dangerLevel = integer(tile, "dangerLevel", 0);
        if (dangerLevel > 0 && bool(tile, "illuminated", true)) {
            float wave = 0.5f + 0.5f * (float) Math.sin((SystemClock.uptimeMillis() - animationStart + depth(tile) * 43.0) / 520.0);
            int dangerColor = color(string(tile, "dangerColor", "red"));
            paint.setColor(withAlpha(dangerColor, Math.round(28f + wave * 42f + dangerLevel * 10f)));
            canvas.drawPath(diamond(cell), paint);
            stroke.setColor(withAlpha(dangerColor, Math.round(100f + wave * 90f)));
            stroke.setStrokeWidth(Math.max(1.5f, dangerLevel * 1.5f));
            canvas.drawPath(diamond(cell), stroke);
        }
        paint.setAlpha(255);
    }

    private void drawSprite(Canvas canvas, UiPortableBridge.Node sprite, SceneProjection projection) {
        if (!bool(sprite, "visible", true)) return;
        RectF cell = projection.cell((int) integer(sprite, "x", 0), (int) integer(sprite, "y", 0));
        float scale = number(sprite, "scale", 1f);
        long now = SystemClock.uptimeMillis();
        float idlePhase = (now - animationStart + (float) depth(sprite) * 71f) / 720f;
        float bob = (float) Math.sin(idlePhase * Math.PI * 2.0) * cell.width() * number(sprite, "bobAmplitude", 0f);
        SpriteAnimation animation;
        synchronized (spriteAnimations) { animation = spriteAnimations.get(sprite.identity()); }
        String animationName = string(sprite, "animation", "idle");
        float animationProgress = animation == null ? 1f : Math.min(1f, (now - animation.startedAt()) / animationDuration(animationName));
        float animationWave = (float) Math.sin(animationProgress * Math.PI);
        if (animationName.equals("walk") && animationProgress < 1f) bob -= animationWave * cell.height() * 0.22f;
        float width = cell.width() * 1.34f * scale;
        float height = cell.width() * 1.62f * scale;
        RectF destination = new RectF(cell.centerX() - width * 0.5f, cell.bottom - height + cell.height() * 0.2f + bob, cell.centerX() + width * 0.5f, cell.bottom + cell.height() * 0.2f + bob);
        String ring = string(sprite, "ringColor", "none");
        if (!ring.equals("none")) {
            int ringColor = color(ring);
            paint.setColor(withAlpha(ringColor, 80));
            canvas.drawOval(new RectF(cell.left + cell.width() * 0.12f, cell.top + cell.height() * 0.42f, cell.right - cell.width() * 0.12f, cell.bottom + cell.height() * 0.18f), paint);
            stroke.setColor(withAlpha(ringColor, 190));
            stroke.setStrokeWidth(Math.max(2f, cell.width() * 0.03f));
            canvas.drawOval(new RectF(cell.left + cell.width() * 0.1f, cell.top + cell.height() * 0.38f, cell.right - cell.width() * 0.1f, cell.bottom + cell.height() * 0.2f), stroke);
        }
        Bitmap atlas = bitmap(string(sprite, "asset", ""));
        if (atlas == null) return;
        float animationAlpha = animationName.equals("death") ? 1f - animationProgress * 0.82f : 1f;
        paint.setAlpha(Math.round(255f * number(sprite, "alpha", 1f) * animationAlpha));
        if ((animationName.equals("hit") && animationProgress < 1f) || integer(sprite, "flash", 0) > 0) paint.setColorFilter(new android.graphics.PorterDuffColorFilter(0x88FFFFFF, android.graphics.PorterDuff.Mode.SRC_ATOP));
        int columns = (int) integer(sprite, "atlasColumns", 1);
        int index = (int) integer(sprite, "row", 0) * columns + (int) integer(sprite, "column", 0);
        int animationCheckpoint = canvas.save();
        if (animationName.equals("walk") && animationProgress < 1f) canvas.rotate(animationWave * 3.5f, destination.centerX(), destination.bottom);
        if (animationName.equals("attack") && animationProgress < 1f) canvas.scale(1f + animationWave * 0.16f, 1f - animationWave * 0.06f, destination.centerX(), destination.bottom);
        if (animationName.equals("hit") && animationProgress < 1f) canvas.translate((float) Math.sin(animationProgress * Math.PI * 6.0) * cell.width() * 0.055f, 0f);
        if (animationName.equals("death")) canvas.rotate(animationProgress * 72f, destination.centerX(), destination.bottom);
        drawAtlasCell(canvas, atlas, columns, (int) integer(sprite, "atlasRows", 1), index, destination, paint);
        canvas.restoreToCount(animationCheckpoint);
        paint.setColorFilter(null);
        paint.setAlpha(255);
        if (bool(sprite, "showHealthBar", false)) {
            long maximum = Math.max(1, integer(sprite, "maxHealth", 1));
            float ratio = Math.max(0f, Math.min(1f, integer(sprite, "health", 0) / (float) maximum));
            float barWidth = cell.width() * 0.78f;
            RectF bar = new RectF(cell.centerX() - barWidth * 0.5f, destination.top - 8f, cell.centerX() + barWidth * 0.5f, destination.top - 2f);
            paint.setColor(0xD010141D);
            canvas.drawRoundRect(bar, 3f, 3f, paint);
            paint.setColor(RED);
            canvas.drawRoundRect(new RectF(bar.left, bar.top, bar.left + bar.width() * ratio, bar.bottom), 3f, 3f, paint);
        }
    }

    private void drawEffect(Canvas canvas, UiPortableBridge.Node effect, SceneProjection projection) {
        if (!bool(effect, "visible", true)) return;
        RectF cell = projection.cell((int) integer(effect, "x", 0), (int) integer(effect, "y", 0));
        float phase = ((SystemClock.uptimeMillis() - animationStart) % 700L) / 700f;
        int color = color(string(effect, "color", "cyan"));
        stroke.setColor(withAlpha(color, Math.round((1f - phase) * 220f)));
        stroke.setStrokeWidth(Math.max(2f, cell.width() * 0.05f));
        float radius = cell.width() * (0.25f + phase * 0.9f);
        canvas.drawCircle(cell.centerX(), cell.centerY(), radius, stroke);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP || runtime == null || viewportScale <= 0f) return true;
        float x = (event.getX() - viewportLeft) / viewportScale;
        float y = (event.getY() - viewportTop) / viewportScale;
        for (int i = hitTargets.size() - 1; i >= 0; i--) {
            HitTarget target = hitTargets.get(i);
            if (!target.bounds.contains(x, y)) continue;
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (target.projection == null) runtime.dispatch(target.actionSlot, target.payload);
            else {
                int[] cell = target.projection.inverse(x, y);
                if (cell[0] >= 0 && cell[0] < target.gridWidth && cell[1] >= 0 && cell[1] < target.gridHeight) runtime.dispatch(target.actionSlot, (long) cell[1] * target.gridWidth + cell[0]);
            }
            return true;
        }
        return true;
    }

    @Override public void close() {
        for (Bitmap bitmap : bitmaps.values()) if (!bitmap.isRecycled()) bitmap.recycle();
        bitmaps.clear();
        synchronized (spriteAnimations) { spriteAnimations.clear(); }
        tree = null;
    }

    private void updateSpriteAnimations(UiPortableBridge.Node root) {
        long now = SystemClock.uptimeMillis();
        HashSet<UiPortableBridge.Identity> live = new HashSet<>();
        updateSpriteAnimations(root, now, live);
        synchronized (spriteAnimations) { spriteAnimations.keySet().retainAll(live); }
    }

    private void updateSpriteAnimations(UiPortableBridge.Node node, long now, HashSet<UiPortableBridge.Identity> live) {
        if ("renderer.scene.sprite".equals(capabilities.get(node.component()))) {
            live.add(node.identity());
            String signature = string(node, "animation", "idle") + ":" + integer(node, "animationPulse", 0);
            synchronized (spriteAnimations) {
                SpriteAnimation prior = spriteAnimations.get(node.identity());
                if (prior == null || !prior.signature().equals(signature)) spriteAnimations.put(node.identity(), new SpriteAnimation(signature, now));
            }
        }
        for (UiPortableBridge.Node child : node.children()) updateSpriteAnimations(child, now, live);
    }

    private static float animationDuration(String animation) {
        return switch (animation) {
            case "walk" -> 420f;
            case "attack" -> 360f;
            case "hit" -> 300f;
            case "death" -> 780f;
            default -> 1400f;
        };
    }

    private Bitmap bitmap(String asset) {
        if (asset.isEmpty()) return null;
        Bitmap cached = bitmaps.get(asset);
        if (cached != null) return cached;
        try (InputStream stream = getContext().getAssets().open(asset)) {
            Bitmap decoded = BitmapFactory.decodeStream(stream);
            if (decoded == null) throw new IOException("Cannot decode " + asset);
            bitmaps.put(asset, decoded);
            return decoded;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load Deal UI asset " + asset, failure);
        }
    }

    private RectF bounds(UiPortableBridge.Node node) {
        float x = number(node, "x", 0f);
        float y = number(node, "y", 0f);
        return new RectF(x, y, x + number(node, "width", 0f), y + number(node, "height", 0f));
    }

    private static String string(UiPortableBridge.Node node, String name, String fallback) {
        UiPortableBridge.Prop prop = node.props().get(name);
        return prop != null && prop.value() instanceof String value ? value : fallback;
    }

    private static long integer(UiPortableBridge.Node node, String name, long fallback) {
        UiPortableBridge.Prop prop = node.props().get(name);
        return prop != null && prop.value() instanceof Number value ? value.longValue() : fallback;
    }

    private static float number(UiPortableBridge.Node node, String name, float fallback) {
        UiPortableBridge.Prop prop = node.props().get(name);
        return prop != null && prop.value() instanceof Number value ? value.floatValue() : fallback;
    }

    private static boolean bool(UiPortableBridge.Node node, String name, boolean fallback) {
        UiPortableBridge.Prop prop = node.props().get(name);
        return prop != null && prop.value() instanceof Boolean value ? value : fallback;
    }

    private static int color(String name) {
        return switch (name) {
            case "cyan" -> CYAN;
            case "violet" -> VIOLET;
            case "amber" -> AMBER;
            case "red" -> RED;
            case "muted" -> MUTED;
            default -> TEXT;
        };
    }

    private static int panelFill(String variant) {
        return switch (variant) {
            case "modal" -> 0xF207111D;
            case "hud" -> 0xE0050D16;
            case "inset" -> 0xC8040A12;
            default -> 0xB806111B;
        };
    }

    private void panel(Canvas canvas, RectF bounds, int borderColor, int fillColor) {
        float cut = Math.min(13f, Math.min(bounds.width(), bounds.height()) * 0.13f);
        Path path = new Path();
        path.moveTo(bounds.left + cut, bounds.top);
        path.lineTo(bounds.right - cut, bounds.top);
        path.lineTo(bounds.right, bounds.top + cut);
        path.lineTo(bounds.right, bounds.bottom - cut);
        path.lineTo(bounds.right - cut, bounds.bottom);
        path.lineTo(bounds.left + cut, bounds.bottom);
        path.lineTo(bounds.left, bounds.bottom - cut);
        path.lineTo(bounds.left, bounds.top + cut);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        canvas.drawPath(path, paint);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        stroke.setColor(withAlpha(borderColor, 185));
        canvas.drawPath(path, stroke);
    }

    private void drawText(Canvas canvas, String value, float x, float baseline, float size, int color, Typeface typeface, Paint.Align align) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        paint.setTextAlign(align);
        canvas.drawText(value, x, baseline, paint);
    }

    private void drawWrapped(Canvas canvas, String value, float x, float y, float width, float height, float size, int color, Paint.Align align) {
        String[] words = value.split(" ");
        String line = "";
        float baseline = y;
        paint.setTextSize(size);
        paint.setTypeface(regular);
        for (String word : words) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (paint.measureText(candidate) > width && !line.isEmpty()) {
                drawText(canvas, line, x, baseline, size, color, regular, align);
                baseline += size * 1.35f;
                if (baseline > y + height) return;
                line = word;
            } else line = candidate;
        }
        if (!line.isEmpty() && baseline <= y + height) drawText(canvas, line, x, baseline, size, color, regular, align);
    }

    private static Path diamond(RectF cell) {
        Path path = new Path();
        path.moveTo(cell.centerX(), cell.top);
        path.lineTo(cell.right, cell.centerY());
        path.lineTo(cell.centerX(), cell.bottom);
        path.lineTo(cell.left, cell.centerY());
        path.close();
        return path;
    }

    private static void drawAtlasCell(Canvas canvas, Bitmap atlas, int columns, int rows, int index, RectF destination, Paint paint) {
        int cellWidth = atlas.getWidth() / Math.max(1, columns);
        int cellHeight = atlas.getHeight() / Math.max(1, rows);
        int column = Math.floorMod(index, Math.max(1, columns));
        int row = Math.max(0, Math.min(Math.max(1, rows) - 1, index / Math.max(1, columns)));
        Rect source = new Rect(column * cellWidth, row * cellHeight, (column + 1) * cellWidth, (row + 1) * cellHeight);
        canvas.drawBitmap(atlas, source, destination, paint);
    }

    private static RectF inset(RectF source, float amount) {
        RectF result = new RectF(source);
        result.inset(amount, amount);
        return result;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static final class SceneProjection {
        private final RectF bounds;
        private final float tileWidth;
        private final float tileHeight;
        private final float originX;
        private final float originY;
        private final IsometricProjection delegate;

        private SceneProjection(RectF bounds, float tileWidth, float tileHeight, int cameraX, int cameraY) {
            this.bounds = new RectF(bounds);
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            originX = bounds.centerX() - (cameraX - cameraY) * tileWidth * 0.5f;
            originY = bounds.centerY() - (cameraX + cameraY) * tileHeight * 0.5f;
            delegate = new IsometricProjection(tileWidth, tileHeight, originX, originY);
        }

        private RectF cell(int x, int y) {
            IsometricProjection.Point point = delegate.project(x, y);
            float centerX = (float) point.x();
            float centerY = (float) point.y();
            return new RectF(centerX - tileWidth * 0.5f, centerY - tileHeight * 0.5f, centerX + tileWidth * 0.5f, centerY + tileHeight * 0.5f);
        }

        private int[] inverse(float x, float y) {
            IsometricProjection.Cell cell = delegate.inverse(x, y);
            return new int[] {cell.x(), cell.y()};
        }
    }

    private static final class HitTarget {
        private final RectF bounds;
        private final int actionSlot;
        private final SceneProjection projection;
        private final int gridWidth;
        private final int gridHeight;
        private final Object payload;

        private HitTarget(RectF bounds, int actionSlot, SceneProjection projection, int gridWidth, int gridHeight, Object payload) {
            this.bounds = new RectF(bounds);
            this.actionSlot = actionSlot;
            this.projection = projection;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
            this.payload = payload;
        }

        private static HitTarget button(RectF bounds, int actionSlot, long payload) { return new HitTarget(bounds, actionSlot, null, 0, 0, payload); }
        private static HitTarget scene(RectF bounds, int actionSlot, SceneProjection projection, int gridWidth, int gridHeight) { return new HitTarget(bounds, actionSlot, projection, gridWidth, gridHeight, null); }
    }

    private record SpriteAnimation(String signature, long startedAt) {}
}
