package dev.arkts.dealui;

import android.content.Context;
import android.content.SharedPreferences;

/** Process-level implementation behind the generic typed host/storage module. */
public final class StorageHostRuntime {
    private static final String PREFERENCES = "deal-ui-storage";
    private static volatile SharedPreferences preferences;

    private StorageHostRuntime() {}

    public static void initialize(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static String load(String key) {
        SharedPreferences storage = requireStorage();
        return storage.contains(key) ? storage.getString(key, null) : null;
    }

    public static String save(String key, String value) {
        if (!requireStorage().edit().putString(key, value).commit()) throw new IllegalStateException("Cannot persist Deal UI value");
        return value;
    }

    public static boolean remove(String key) {
        SharedPreferences storage = requireStorage();
        if (!storage.contains(key)) return false;
        if (!storage.edit().remove(key).commit()) throw new IllegalStateException("Cannot remove Deal UI value");
        return true;
    }

    private static SharedPreferences requireStorage() {
        SharedPreferences storage = preferences;
        if (storage == null) throw new IllegalStateException("Deal UI storage host is not initialized");
        return storage;
    }
}
