package hmresize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hmresize.TreeHashMap.ResizeStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TreeHashMapTest {

    record CKey(int id, int hash) implements Comparable<CKey> {
        @Override public int hashCode() { return hash; }
        @Override public int compareTo(CKey o) { return Integer.compare(id, o.id); }
    }

    /** Not Comparable, so equal-hash ordering falls back to the sequence tie-breaker. */
    record NKey(int id, int hash) {
        @Override public int hashCode() { return hash; }
    }

    /** {@code distinct} raw hash codes whose spread values share their low 10 bits up to one bit. */
    static int[] collidingHashes(Random rnd, int distinct) {
        int[] pool = new int[distinct];
        for (int i = 0; i < distinct; i++) {
            pool[i] = TreeHashMap.unspread((rnd.nextInt() & ~1023) | rnd.nextInt(2));
        }
        return pool;
    }

    static Stream<Arguments> randomCases() {
        List<Arguments> cases = new ArrayList<>();
        for (ResizeStrategy strategy : ResizeStrategy.values()) {
            for (int distinct : new int[] {1, 5, 1000}) {
                for (boolean comparable : new boolean[] {true, false}) {
                    cases.add(Arguments.of(strategy, distinct, comparable));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, {1} distinct hashes, comparable={2}")
    @MethodSource("randomCases")
    void randomOperationsMatchHashMap(ResizeStrategy strategy, int distinct, boolean comparable) {
        Random rnd = new Random(distinct * 31L + (comparable ? 1 : 0));
        int[] hashes = collidingHashes(rnd, distinct);
        TreeHashMap<Object, Integer> map = new TreeHashMap<>(strategy);
        Map<Object, Integer> ref = new HashMap<>();
        for (int op = 0; op < 30_000; op++) {
            int id = rnd.nextInt(3000);
            int hash = hashes[id % hashes.length];
            Object key = comparable ? new CKey(id, hash) : new NKey(id, hash);
            int kind = rnd.nextInt(10);
            if (kind < 6) {
                assertEquals(ref.put(key, op), map.put(key, op));
            } else if (kind < 8) {
                assertEquals(ref.remove(key), map.remove(key));
            } else {
                assertEquals(ref.get(key), map.get(key));
                assertEquals(ref.containsKey(key), map.containsKey(key));
            }
            if (op % 1000 == 0) {
                map.verify();
                assertEquals(ref, map);
            }
        }
        map.verify();
        assertEquals(ref, map);
        assertEquals(map, ref);
        assertEquals(ref.hashCode(), map.hashCode());
    }

    @ParameterizedTest
    @EnumSource(ResizeStrategy.class)
    void repeatedForcedResizesKeepContents(ResizeStrategy strategy) {
        Random rnd = new Random(7);
        int[] hashes = collidingHashes(rnd, 9);
        TreeHashMap<CKey, Integer> map = new TreeHashMap<>(64, Float.MAX_VALUE, strategy);
        Map<CKey, Integer> ref = new HashMap<>();
        for (int id = 0; id < 5000; id++) {
            CKey key = new CKey(id, hashes[rnd.nextInt(hashes.length)]);
            map.put(key, id);
            ref.put(key, id);
        }
        for (int r = 0; r < 12; r++) {
            map.forceResize();
            map.verify();
            assertEquals(ref, map);
            assertEquals(map, ref);
        }
        assertEquals(64 << 12, map.capacity());
    }

    @Test
    void identicalHashBinMovesWithoutVisitingNodes() {
        int m = 10_000;
        long range = resizeVisits(ResizeStrategy.RANGE_SPLIT, m, 1);
        long rebuild = resizeVisits(ResizeStrategy.REBUILD, m, 1);
        assertTrue(range < 100, "range split visits: " + range);
        assertTrue(rebuild >= m, "rebuild visits: " + rebuild);
    }

    @Test
    void clusteredBinVisitsScaleWithClusterCount() {
        int m = 10_000;
        long range = resizeVisits(ResizeStrategy.RANGE_SPLIT, m, 4);
        long rebuild = resizeVisits(ResizeStrategy.REBUILD, m, 4);
        assertTrue(range < 1000, "range split visits: " + range);
        assertTrue(rebuild >= 2L * m, "rebuild visits: " + rebuild);
    }

    /** Tree-node visits for one resize of a single bin of m keys spread over {@code clusters} hashes. */
    private static long resizeVisits(ResizeStrategy strategy, int m, int clusters) {
        TreeHashMap<CKey, Integer> map = new TreeHashMap<>(64, Float.MAX_VALUE, strategy);
        Map<CKey, Integer> ref = new HashMap<>();
        for (int id = 0; id < m; id++) {
            // Bucket 3; prefixes 0, 1, 2, ... alternate between the low and high destination.
            int prefix = id % clusters;
            CKey key = new CKey(id, TreeHashMap.unspread((prefix << 6) | 3));
            map.put(key, id);
            ref.put(key, id);
        }
        long before = map.treeNodeVisits();
        map.forceResize();
        long visits = map.treeNodeVisits() - before;
        map.verify();
        assertEquals(ref, map);
        return visits;
    }
}
