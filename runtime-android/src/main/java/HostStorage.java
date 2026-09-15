import java.util.concurrent.CompletableFuture;

/** JVM host ABI facade for the reusable Deal UI host/storage module. */
public final class HostStorage {
    private HostStorage() {}

    public static Object load(String key) {
        return CompletableFuture.completedFuture(dev.arkts.dealui.StorageHostRuntime.load(key));
    }

    public static Object save(String key, String value) {
        return CompletableFuture.completedFuture(dev.arkts.dealui.StorageHostRuntime.save(key, value));
    }

    public static Object remove(String key) {
        return CompletableFuture.completedFuture(Boolean.valueOf(dev.arkts.dealui.StorageHostRuntime.remove(key)));
    }
}
