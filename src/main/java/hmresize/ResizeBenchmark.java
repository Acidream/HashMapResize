package hmresize;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Shared benchmark setup (keys, implementations), plus a quick nanoTime harness ({@link #main}) that
 * times one table doubling (64 -> 128) of a map whose 16 bins each hold {@code perBin} keys. The JMH
 * benchmarks in {@code src/jmh} are the authoritative measurements.
 *
 * <p>HashMap's private {@code resize()} is called reflectively, which needs
 * {@code --add-opens java.base/java.util=ALL-UNNAMED}.
 */
public final class ResizeBenchmark {

    public record Key(int id, int hash) implements Comparable<Key> {
        @Override public int hashCode() { return hash; }
        @Override public int compareTo(Key o) { return Integer.compare(id, o.id); }
    }

    public enum Shape {
        /** Every key in a bin has the same hash: one run, the whole bin moves to one side. */
        IDENTICAL,
        /** Four distinct hashes per bin, alternating destination: four runs. */
        CLUSTERS_4,
        /** Random hashes that only share the bucket bits: about m/2 runs. */
        DISTINCT,
        /** Ordinary well-spread random hashes: no tree bins at all, the common case. */
        RANDOM
    }

    public enum Impl {
        /** java.util.HashMap itself. */
        JDK_HASHMAP,
        /** The verbatim copy, to show what copying alone costs (e.g. no JDK intrinsics or CDS). */
        JDK_COPY,
        /** The copy with the range split. */
        RANGE_SPLIT;

        /** A map with {@link #CAPACITY} buckets that never grows on its own. */
        public Map<Key, Integer> createFixed() {
            return switch (this) {
                case JDK_HASHMAP -> new HashMap<>(CAPACITY, Float.MAX_VALUE);
                case JDK_COPY -> new JdkHashMapCopy<>(CAPACITY, Float.MAX_VALUE);
                case RANGE_SPLIT -> new RangeSplitHashMap<>(CAPACITY, Float.MAX_VALUE);
            };
        }

        /** A default-constructed map, which grows as usual. */
        public Map<Key, Integer> createDefault() {
            return switch (this) {
                case JDK_HASHMAP -> new HashMap<>();
                case JDK_COPY -> new JdkHashMapCopy<>();
                case RANGE_SPLIT -> new RangeSplitHashMap<>();
            };
        }

        public void resize(Map<Key, Integer> map) throws ReflectiveOperationException {
            if (map instanceof JdkHashMapCopy<Key, Integer> c) c.resize();
            else HASHMAP_RESIZE.invoke(map);
        }

        public int capacity(Map<Key, Integer> map) throws ReflectiveOperationException {
            if (map instanceof JdkHashMapCopy<Key, Integer> c) return c.table.length;
            return ((Object[]) HASHMAP_TABLE.get(map)).length;
        }
    }

    static final int CAPACITY = 64;
    static final int SHIFT = 6;
    static final int BINS = 16;
    static final Method HASHMAP_RESIZE;
    static final Field HASHMAP_TABLE;

    static {
        try {
            HASHMAP_RESIZE = HashMap.class.getDeclaredMethod("resize");
            HASHMAP_RESIZE.setAccessible(true);
            HASHMAP_TABLE = HashMap.class.getDeclaredField("table");
            HASHMAP_TABLE.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new ExceptionInInitializerError(
                    "needs --add-opens java.base/java.util=ALL-UNNAMED (" + e + ")");
        }
    }

    static long sink;

    public static void main(String[] args) throws ReflectiveOperationException {
        System.out.printf("%-10s %6s  %-12s %12s %10s%n", "shape", "perBin", "impl", "resize us", "vs JDK");
        for (Shape shape : Shape.values()) {
            for (int perBin : new int[] {64, 1024, 16_384}) {
                int iterations = perBin >= 16_384 ? 25 : perBin >= 1024 ? 150 : 1000;
                run(shape, perBin, iterations / 2, iterations);
            }
        }
        if (sink == 42) System.out.println();
    }

    /**
     * The map a resize is measured on. Collision shapes use a fixed 64-bucket table, so their 16 bins
     * are tree bins of {@code perBin} keys. RANDOM keys would also pile up in a 64-bucket table, so
     * that map is built normally (default load factor) and holds list bins only.
     */
    public static Map<Key, Integer> buildForResize(Impl impl, Shape shape, Key[] keys) {
        Map<Key, Integer> map = shape == Shape.RANDOM ? impl.createDefault() : impl.createFixed();
        for (Key k : keys) map.put(k, k.id);
        return map;
    }

    static void run(Shape shape, int perBin, int warmup, int iterations) throws ReflectiveOperationException {
        Key[] keys = keys(shape, perBin, 1234);
        Impl[] impls = Impl.values();
        long[][] resize = new long[impls.length][iterations];
        for (int it = -warmup; it < iterations; it++) {
            for (int i = 0; i < impls.length; i++) {
                Map<Key, Integer> map = buildForResize(impls[i], shape, keys);
                int before = impls[i].capacity(map);
                long t0 = System.nanoTime();
                impls[i].resize(map);
                long t = System.nanoTime() - t0;
                if (it == -warmup && (impls[i].capacity(map) != 2 * before || map.size() != keys.length)) {
                    throw new AssertionError(impls[i] + " did not resize correctly");
                }
                if (it >= 0) resize[i][it] = t;
                sink += map.size();
            }
        }
        double jdk = median(resize[0]);
        for (int i = 0; i < impls.length; i++) {
            double r = median(resize[i]);
            System.out.printf("%-10s %6d  %-12s %12.1f %9.2fx%n", shape, perBin, impls[i], r, jdk / r);
        }
        System.out.println();
    }

    static double median(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1000.0;
    }

    /** {@code BINS * perBin} keys; for the collision shapes, {@code perBin} of them per bin of a 64-table. */
    public static Key[] keys(Shape shape, int perBin, long seed) {
        Random rnd = new Random(seed);
        Key[] out = new Key[BINS * perBin];
        int id = 0;
        for (int bucket = 0; bucket < BINS; bucket++) {
            int[] clusters = new int[4];
            for (int c = 0; c < clusters.length; c++) clusters[c] = ((rnd.nextInt() >> SHIFT) & ~1) | (c & 1);
            for (int i = 0; i < perBin; i++) {
                int hash = switch (shape) {
                    case IDENTICAL -> (12345 << SHIFT) | bucket;
                    case CLUSTERS_4 -> (clusters[i % clusters.length] << SHIFT) | bucket;
                    case DISTINCT -> ((rnd.nextInt() >> SHIFT) << SHIFT) | bucket;
                    case RANDOM -> rnd.nextInt();
                };
                out[id] = new Key(id, TreeHashMap.unspread(hash));
                id++;
            }
        }
        // Random insertion order, so tree shapes do not depend on the generator.
        for (int i = out.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            Key k = out[i];
            out[i] = out[j];
            out[j] = k;
        }
        return out;
    }
}
