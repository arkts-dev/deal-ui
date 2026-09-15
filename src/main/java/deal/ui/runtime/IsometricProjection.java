package deal.ui.runtime;

/** Platform-neutral isometric grid projection shared by native Deal UI renderers. */
public final class IsometricProjection {
    public record Point(double x, double y) {}
    public record Cell(int x, int y) {}

    private final double tileWidth;
    private final double tileHeight;
    private final double originX;
    private final double originY;

    public IsometricProjection(double tileWidth, double tileHeight, double originX, double originY) {
        if (tileWidth <= 0 || tileHeight <= 0) throw new IllegalArgumentException("Tile dimensions must be positive");
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.originX = originX;
        this.originY = originY;
    }

    public Point project(int x, int y) {
        return new Point(originX + (x - y) * tileWidth * 0.5, originY + (x + y) * tileHeight * 0.5);
    }

    public Cell inverse(double x, double y) {
        double horizontal = (x - originX) / (tileWidth * 0.5);
        double vertical = (y - originY) / (tileHeight * 0.5);
        return new Cell((int) Math.round((horizontal + vertical) * 0.5), (int) Math.round((vertical - horizontal) * 0.5));
    }
}
