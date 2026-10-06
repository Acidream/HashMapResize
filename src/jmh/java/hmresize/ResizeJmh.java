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
 * One table doubling. For the collision shapes: a 64-bucket table with 16 tree bins of {@code perBin}
 * keys each. For RANDOM: an ordinary map of {@code 16 * perBin} keys at its natural size (list bins only).
 *
 * <p>Resizing is destructive, so a fresh map is built before every invocation ({@link Level#Invocation},
 * not timed), and this runs in {@link Mode#SampleTime}, which times every call separately and reports
 * the mean with its error as well as percentiles. (Forcing a GC in the setup to keep collections out of
 * the timed call made things worse: the resize then runs on freshly moved, cache-cold objects.)
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "--add-opens", "java.base/java.util=ALL-UNNAMED"})
public class ResizeJmh {

    @Param({"IDENTICAL", "CLUSTERS_4", "DISTINCT", "RANDOM"})
    public String shape;

    @Param({"64", "1024", "16384"})
    public int perBin;

    @Param({"JDK_HASHMAP", "JDK_COPY", "RANGE_SPLIT"})
    public String impl;

    private Key[] keys;
    private Impl implementation;
    private Shape keyShape;
    private Map<Key, Integer> map;

    @Setup(Level.Trial)
    public void generateKeys() {
        keyShape = Shape.valueOf(shape);
        keys = ResizeBenchmark.keys(keyShape, perBin, 1234);
        implementation = Impl.valueOf(impl);
    }

    @Setup(Level.Invocation)
    public void buildMap() {
        map = ResizeBenchmark.buildForResize(implementation, keyShape, keys);
    }

    @Benchmark
    public Map<Key, Integer> resize() throws ReflectiveOperationException {
        implementation.resize(map);
        return map;
    }
}
