package com.ninja6.ninjaarena.terrain;

/**
 * Builds one team's side of a generated map from a seed and a difficulty. A pure function:
 * it reads no world, server, scheduler, clock or configuration, so it can run on any thread
 * and gives a byte-identical {@link TerrainModel} for the same seed, difficulty and
 * dimensions on every run and every JVM.
 *
 * <p>The side is built in fixed passes, each drawing on its own stream derived from the seed
 * ({@link SplitMix64#derive}), so a later change to one feature leaves the others alone:
 * <ol>
 *   <li><b>Height.</b> Fractal {@link ValueNoise} around a base level of three eighths of the
 *       model height. Its range grows with difficulty.</li>
 *   <li><b>Cliffs.</b> The height is pulled towards terraces whose step grows with
 *       difficulty, more strongly where a second noise field says so, giving sheer steps.</li>
 *   <li><b>Ravines.</b> Where a ridge of noise passes near zero the ground is cut down in a
 *       V. Wider and deeper with difficulty.</li>
 *   <li><b>Overhangs.</b> Three-dimensional noise hollows the ground under a two-block roof,
 *       but only at heights above the lowest ground within a few blocks, so most hollows
 *       open sideways under a lip. Some stay sealed air pockets with no opening, so air
 *       below a column top is not necessarily reachable. A deeper band and a lower
 *       threshold with difficulty.</li>
 *   <li><b>Platforms.</b> Floating ellipses above the ground, more of them with
 *       difficulty.</li>
 *   <li><b>Kinds.</b> Each solid cell is labelled surface, cliff, soil or rock.</li>
 * </ol>
 * The spawn zone and the centre ledge are flat at the base level and untouched by ravines,
 * overhangs and platforms; the terrain eases into them over
 * {@link SideDimensions#blendDepth()} rows.
 */
public final class TerrainGenerator {

    public static final int MIN_DIFFICULTY = 1;
    public static final int MAX_DIFFICULTY = 5;

    /** Rows below this {@code y} are always rock; ravines and hollows stop here. */
    static final int FLOOR = 2;

    /** Layers under the column top labelled soil rather than rock. */
    static final int SOIL_DEPTH = 3;

    /** The ground stays at least this far below the model top, leaving room for platforms. */
    static final int HEADROOM = 12;

    /** Air kept between a floating platform and the ground beneath it. */
    static final int PLATFORM_GAP = 2;

    /** How far around a column overhangs look for a drop to open onto. */
    private static final int OVERHANG_REACH = 3;

    // Feature streams. Never renumber one: it would change every map using that feature.
    private static final long STREAM_THEME = 1;
    private static final long STREAM_HEIGHT = 2;
    private static final long STREAM_CLIFFS = 3;
    private static final long STREAM_RAVINES = 4;
    private static final long STREAM_OVERHANGS = 5;
    private static final long STREAM_PLATFORMS = 6;

    private static final byte AIR = (byte) BlockKind.AIR.ordinal();
    private static final byte ROCK = (byte) BlockKind.ROCK.ordinal();
    private static final byte PLATFORM = (byte) BlockKind.PLATFORM.ordinal();

    private TerrainGenerator() {
    }

    /** A side of {@link SideDimensions#DEFAULT} size. */
    public static TerrainModel generate(long seed, int difficulty) {
        return generate(seed, difficulty, SideDimensions.DEFAULT);
    }

    public static TerrainModel generate(long seed, int difficulty, SideDimensions dimensions) {
        if (difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty must be " + MIN_DIFFICULTY + " to " + MAX_DIFFICULTY);
        }
        return new Build(seed, difficulty, dimensions).run();
    }

    /** The theme a seed draws; the same for every difficulty and size. */
    public static TerrainTheme themeFor(long seed) {
        SplitMix64 random = new SplitMix64(SplitMix64.derive(seed, STREAM_THEME));
        return TerrainTheme.ofOrdinal(random.nextInt(TerrainTheme.count()));
    }

    /** One generation run's working state. Never shared between threads. */
    private static final class Build {

        private final long seed;
        private final int difficulty;
        private final SideDimensions dimensions;
        private final int width;
        private final int length;
        private final int height;
        private final int base;
        private final byte[] cells;
        private final int[] top;
        private final double[] openness;

        Build(long seed, int difficulty, SideDimensions dimensions) {
            this.seed = seed;
            this.difficulty = difficulty;
            this.dimensions = dimensions;
            this.width = dimensions.width();
            this.length = dimensions.length();
            this.height = dimensions.height();
            this.base = height * 3 / 8;
            this.cells = new byte[dimensions.volume()];
            this.top = new int[width * length];
            this.openness = new double[length];
            for (int z = 0; z < length; z++) {
                openness[z] = openness(z);
            }
        }

        TerrainModel run() {
            shapeHeight();
            cutRavines();
            hollowOverhangs();
            placePlatforms();
            labelKinds();
            return new TerrainModel(seed, difficulty, dimensions, themeFor(seed), base, base, cells);
        }

        /**
         * {@code 0} in the flat zones, rising to {@code 1} over the blend rows after the spawn
         * zone and before the centre ledge.
         */
        private double openness(int z) {
            int blend = dimensions.blendDepth();
            int fromSpawn = z - dimensions.spawnDepth();
            int fromCentre = length - dimensions.centreDepth() - 1 - z;
            return Math.min(ramp(fromSpawn, blend), ramp(fromCentre, blend));
        }

        private static double ramp(int distance, int blend) {
            if (distance < 0) {
                return 0;
            }
            return Math.min(1.0, (distance + 1) / (blend + 1.0));
        }

        private void shapeHeight() {
            ValueNoise heightNoise = new ValueNoise(SplitMix64.derive(seed, STREAM_HEIGHT));
            ValueNoise cliffNoise = new ValueNoise(SplitMix64.derive(seed, STREAM_CLIFFS));
            double amplitude = 3 + 3 * difficulty;
            double step = 2 + difficulty;
            double cliffStrength = 0.3 * difficulty;
            int maxTop = height - HEADROOM - 1;
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    double raw = amplitude * heightNoise.fractal(x / 40.0, z / 40.0, 4);
                    double mask = clamp(0.5 + 1.5 * cliffNoise.fractal(x / 48.0, z / 48.0, 2), 0, 1);
                    double terraced = Math.floor(raw / step) * step;
                    double shaped = raw + (terraced - raw) * Math.min(1.0, cliffStrength * mask);
                    int y = base + (int) Math.floor(shaped * openness[z]);
                    int columnTop = Math.clamp(y, FLOOR + SOIL_DEPTH, maxTop);
                    top[z * width + x] = columnTop;
                    int column = column(x, z);
                    for (int fill = 0; fill <= columnTop; fill++) {
                        cells[column + fill] = ROCK;
                    }
                }
            }
        }

        private void cutRavines() {
            ValueNoise ravineNoise = new ValueNoise(SplitMix64.derive(seed, STREAM_RAVINES));
            double halfWidth = 0.03 * difficulty;
            int maxDepth = 6 + 5 * difficulty;
            for (int z = 0; z < length; z++) {
                if (openness[z] <= 0) {
                    continue;
                }
                double reach = halfWidth * openness[z];
                for (int x = 0; x < width; x++) {
                    double ridge = Math.abs(ravineNoise.fractal(x / 56.0, z / 56.0, 2));
                    if (ridge >= reach) {
                        continue;
                    }
                    double middle = 1 - ridge / reach;
                    int columnTop = top[z * width + x];
                    int floor = Math.max(FLOOR, columnTop - 2 - (int) Math.floor(maxDepth * middle));
                    int column = column(x, z);
                    for (int y = floor + 1; y <= columnTop; y++) {
                        cells[column + y] = AIR;
                    }
                    top[z * width + x] = floor;
                }
            }
        }

        private void hollowOverhangs() {
            ValueNoise overhangNoise = new ValueNoise(SplitMix64.derive(seed, STREAM_OVERHANGS));
            int band = 3 + 2 * difficulty;
            double threshold = 0.42 - 0.06 * difficulty;
            int[] lowestNear = lowestNearby();
            for (int z = 0; z < length; z++) {
                if (openness[z] < 1) {
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int columnTop = top[z * width + x];
                    int from = Math.max(Math.max(FLOOR, columnTop - band), lowestNear[z * width + x] + 1);
                    int column = column(x, z);
                    for (int y = from; y < columnTop - 1; y++) {
                        if (overhangNoise.fractal(x / 12.0, y / 6.0, z / 12.0, 2) > threshold) {
                            cells[column + y] = AIR;
                        }
                    }
                }
            }
        }

        /** For each column, the lowest ground top within {@link #OVERHANG_REACH}. */
        private int[] lowestNearby() {
            int[] lowest = new int[width * length];
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int min = top[z * width + x];
                    for (int dz = -OVERHANG_REACH; dz <= OVERHANG_REACH; dz++) {
                        int nz = z + dz;
                        if (nz < 0 || nz >= length) {
                            continue;
                        }
                        for (int dx = -OVERHANG_REACH; dx <= OVERHANG_REACH; dx++) {
                            int nx = x + dx;
                            if (nx >= 0 && nx < width) {
                                min = Math.min(min, top[nz * width + nx]);
                            }
                        }
                    }
                    lowest[z * width + x] = min;
                }
            }
            return lowest;
        }

        private void placePlatforms() {
            SplitMix64 random = new SplitMix64(SplitMix64.derive(seed, STREAM_PLATFORMS));
            int count = (int) ((long) width * length * (1 + 2 * difficulty) / 6912);
            for (int i = 0; i < count; i++) {
                // Every platform draws the same six numbers, placed or not, so the stream
                // stays aligned.
                int centreX = random.nextInt(width);
                int centreZ = random.nextInt(length);
                int radiusX = 2 + random.nextInt(3);
                int radiusZ = 2 + random.nextInt(3);
                int thickness = 1 + random.nextInt(2);
                int lift = 4 + random.nextInt(3 + 2 * difficulty);
                int upper = Math.min(height - 1, top[centreZ * width + centreX] + lift);
                long limit = (long) radiusX * radiusX * radiusZ * radiusZ;
                for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                    for (int dx = -radiusX; dx <= radiusX; dx++) {
                        int x = centreX + dx;
                        int z = centreZ + dz;
                        if (x < 0 || x >= width || z < 0 || z >= length || openness[z] < 1
                                || (long) dx * dx * radiusZ * radiusZ + (long) dz * dz * radiusX * radiusX > limit) {
                            continue;
                        }
                        int column = column(x, z);
                        int lowest = top[z * width + x] + PLATFORM_GAP + 1;
                        for (int y = Math.max(lowest, upper - thickness + 1); y <= upper; y++) {
                            cells[column + y] = PLATFORM;
                        }
                    }
                }
            }
        }

        private void labelKinds() {
            byte surface = (byte) BlockKind.SURFACE.ordinal();
            byte cliff = (byte) BlockKind.CLIFF.ordinal();
            byte soil = (byte) BlockKind.SOIL.ordinal();
            byte spawnPad = (byte) BlockKind.SPAWN_PAD.ordinal();
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int column = column(x, z);
                    int columnTop = top[z * width + x];
                    for (int y = 0; y <= columnTop; y++) {
                        if (cells[column + y] != ROCK) {
                            continue;
                        }
                        if (y == height - 1 || cells[column + y + 1] == AIR) {
                            cells[column + y] = surface;
                        } else if (openSide(x, y, z)) {
                            cells[column + y] = cliff;
                        } else if (y > columnTop - SOIL_DEPTH) {
                            cells[column + y] = soil;
                        }
                    }
                    if (z < dimensions.spawnDepth()) {
                        cells[column + columnTop] = spawnPad;
                    }
                }
            }
        }

        private boolean openSide(int x, int y, int z) {
            return (x > 0 && cells[column(x - 1, z) + y] == AIR)
                    || (x < width - 1 && cells[column(x + 1, z) + y] == AIR)
                    || (z > 0 && cells[column(x, z - 1) + y] == AIR)
                    || (z < length - 1 && cells[column(x, z + 1) + y] == AIR);
        }

        private int column(int x, int z) {
            return (z * width + x) * height;
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
