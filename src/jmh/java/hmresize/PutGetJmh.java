package hmresize;

import hmresize.ResizeBenchmark.Impl;
import hmresize.ResizeBenchmark.Key;
import hmresize.ResizeBenchmark.Shape;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Normal operations on {@code 16 * perBin} keys, to weigh the faster resize against any cost elsewhere.
 * <ul>
 *   <li>{@link #insertGrowing}: put every key into a default-constructed map, so the time includes
 *       every resize (and treeification) on the way, as in real use;
 *   <li>{@link #insertPresized}: put every key into a fixed 64-bucket map that never resizes, isolating
 *       the cost of insertion itself (for the collision shapes, mostly tree-bin inserts);
 *   <li>{@link #getAll}: look up every key in a filled default map.
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "--add-opens", "java.base/java.util=ALL-UNNAMED"})
public class PutGetJmh {

    @Param({"IDENTICAL", "CLUSTERS_4", "DISTINCT", "RANDOM"})
    public String shape;

    @Param({"64", "1024", "16384"})
    public int perBin;

    @Param({"JDK_HASHMAP", "JDK_COPY", "RANGE_SPLIT"})
    public String impl;

    private Key[] keys;
    private Impl implementation;
    private Map<Key, Integer> filled;

    @Setup(Level.Trial)
    public void setUp() {
        keys = ResizeBenchmark.keys(Shape.valueOf(shape), perBin, 1234);
        implementation = Impl.valueOf(impl);
        filled = insertGrowing();
    }

    @Benchmark
    public Map<Key, Integer> insertGrowing() {
        Map<Key, Integer> map = implementation.createDefault();
        for (Key k : keys) map.put(k, k.id());
        return map;
    }

    @Benchmark
    public Map<Key, Integer> insertPresized() {
        Map<Key, Integer> map = implementation.createFixed();
        for (Key k : keys) map.put(k, k.id());
        return map;
    }

    @Benchmark
    public long getAll() {
        long sum = 0;
        for (Key k : keys) sum += filled.get(k);
        return sum;
    }
}
