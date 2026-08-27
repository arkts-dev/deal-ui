package deal.ui;

public final class UiLauncher {
    private UiLauncher() {}

    public static void launch(UiBridge bridge) {
        UiProgramRuntime runtime = new UiProgramRuntime(bridge, bridge.title(), bridge.rendererBindings());
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
        runtime.show();
    }
}
