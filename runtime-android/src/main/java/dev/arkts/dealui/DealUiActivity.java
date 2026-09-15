package dev.arkts.dealui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import deal.ui.PortableUiProgramRuntime;
import deal.ui.UiPortableBridge;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Universal Android bootstrap for a generated portable Deal UI bridge. */
public final class DealUiActivity extends Activity {
    private PortableUiProgramRuntime runtime;
    private DealCanvasView renderer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        try {
            StorageHostRuntime.initialize(getApplicationContext());
            JSONObject application = readJson("deal-ui-app.json");
            Class<?> bridgeClass = Class.forName(application.getString("bridgeClass"));
            UiPortableBridge bridge = (UiPortableBridge) bridgeClass.getConstructor().newInstance();
            renderer = new DealCanvasView(this, bridge);
            runtime = new PortableUiProgramRuntime(bridge, renderer);
            renderer.bindRuntime(runtime);
            renderer.setContentDescription(bridge.title());
            setContentView(renderer);
            getWindow().getDecorView().post(this::hideSystemBars);
        } catch (ReflectiveOperationException | java.io.IOException | org.json.JSONException failure) {
            throw new IllegalStateException("Cannot start compiled Deal UI application", failure);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (renderer != null) renderer.setPresentationPaused(false);
    }

    @Override protected void onPause() {
        if (renderer != null) renderer.setPresentationPaused(true);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (runtime != null) runtime.close();
        runtime = null;
        renderer = null;
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private JSONObject readJson(String asset) throws java.io.IOException, org.json.JSONException {
        try (InputStream stream = getAssets().open(asset); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
