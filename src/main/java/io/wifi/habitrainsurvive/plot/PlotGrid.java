package io.wifi.habitrainsurvive.plot;

/** Fixed layout is deliberately immutable after creation: changing it would move existing ownership. */
public final class PlotGrid {
    public static final String WORLD = "habi_train_survive:plots";
    public static final int SIZE = 64, ROAD = 7, STRIDE = SIZE + ROAD, OFFSET = 4;
    public static final int SURFACE = -61, FEET = -60;
    private PlotGrid() { }

    public record Cell(int x, int z) {
        public String id() { return x + ":" + z; }
        public int minX() { return Math.addExact(Math.multiplyExact(x, STRIDE), OFFSET); }
        public int minZ() { return Math.addExact(Math.multiplyExact(z, STRIDE), OFFSET); }
        public int maxX() { return minX() + SIZE - 1; }
        public int maxZ() { return minZ() + SIZE - 1; }
        public boolean contains(int bx, int bz) { return bx >= minX() && bx <= maxX() && bz >= minZ() && bz <= maxZ(); }
        public boolean withinBorder(double border) {
            return Math.max(Math.abs((long) minX()), Math.abs((long) maxX())) + 2 < border / 2
                    && Math.max(Math.abs((long) minZ()), Math.abs((long) maxZ())) + 2 < border / 2;
        }
    }
    public static Cell at(int x, int z) {
        int localX = Math.floorMod(x, STRIDE), localZ = Math.floorMod(z, STRIDE);
        if (localX < OFFSET || localX >= OFFSET + SIZE || localZ < OFFSET || localZ >= OFFSET + SIZE) return null;
        return new Cell(Math.floorDiv(x, STRIDE), Math.floorDiv(z, STRIDE));
    }
    /** Outward square spiral, beginning beside the origin, without an unbounded search over coordinates. */
    public static Cell candidate(long index) {
        if (index < 0 || index > 1_000_000) throw new IllegalArgumentException("地皮索引超出范围");
        if (index == 0) return new Cell(0, 0);
        int radius = (int) Math.ceil((Math.sqrt(index + 1) - 1) / 2);
        int side = radius * 2;
        long distance = (long) (side + 1) * (side + 1) - 1 - index;
        if (distance < side) return new Cell(radius - (int) distance, -radius);
        if (distance < side * 2L) return new Cell(-radius, -radius + (int) (distance - side));
        if (distance < side * 3L) return new Cell(-radius + (int) (distance - side * 2L), radius);
        return new Cell(radius, radius - (int) (distance - side * 3L));
    }
}
