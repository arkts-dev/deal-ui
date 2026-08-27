package deal.ui;

public final class UiLauncher {
    private UiLauncher() {}

    public static void launch(UiBridge bridge) {
        UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings());
        runtime.onError(failure -> Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), failure));
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.show();
    }
}
