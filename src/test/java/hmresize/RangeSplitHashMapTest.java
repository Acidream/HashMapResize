package hmresize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Randomized tests of {@link RangeSplitHashMap} and {@link JdkHashMapCopy} against java.util.HashMap,
 * with structural checks ({@link TreeBinInvariants}) after operations and resizes.
 */
class RangeSplitHashMapTest {

    record CKey(int id, int hash) implements Comparable<CKey>, Serializable {
        @Override public int hashCode() { return hash; }
        @Override public int compareTo(CKey o) { return Integer.compare(id, o.id); }
    }

    /** Not Comparable, so equal-hash ordering uses HashMap's tieBreakOrder. */
    record NKey(int id, int hash) {
        @Override public int hashCode() { return hash; }
    }

    /** Raw hash codes whose spread values share their low 10 bits up to one bit. */
    static int[] collidingHashes(Random rnd, int distinct) {
        int[] pool = new int[distinct];
        for (int i = 0; i < distinct; i++) {
            pool[i] = TreeHashMap.unspread((rnd.nextInt() & ~1023) | rnd.nextInt(2));
        }
        return pool;
    }

    static <K, V> JdkHashMapCopy<K, V> create(boolean rangeSplit) {
        return rangeSplit ? new RangeSplitHashMap<>() : new JdkHashMapCopy<>();
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean rangeSplit : new boolean[] {true, false}) {
            for (int distinct : new int[] {1, 2, 5, 40, 1000}) {
                for (boolean comparable : new boolean[] {true, false}) {
                    cases.add(Arguments.of(rangeSplit, distinct, comparable));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "rangeSplit={0}, {1} distinct hashes, comparable={2}")
    @MethodSource("cases")
    void randomOperationsMatchHashMap(boolean rangeSplit, int distinct, boolean comparable) {
        Random rnd = new Random(distinct * 31L + (comparable ? 1 : 0));
        int[] hashes = collidingHashes(rnd, distinct);
        JdkHashMapCopy<Object, Integer> map = create(rangeSplit);
        Map<Object, Integer> ref = new HashMap<>();
        for (int op = 0; op < 40_000; op++) {
            int id = rnd.nextInt(3000);
            int hash = hashes[id % hashes.length];
            Object key = comparable ? new CKey(id, hash) : new NKey(id, hash);
            switch (rnd.nextInt(12)) {
                case 0, 1, 2, 3, 4 -> assertEquals(ref.put(key, op), map.put(key, op));
                case 5, 6 -> assertEquals(ref.remove(key), map.remove(key));
                case 7 -> assertEquals(ref.get(key), map.get(key));
                case 8 -> assertEquals(ref.computeIfAbsent(key, k -> -1), map.computeIfAbsent(key, k -> -1));
                case 9 -> assertEquals(ref.merge(key, 1, Integer::sum), map.merge(key, 1, Integer::sum));
                case 10 -> assertEquals(
                        ref.compute(key, (k, v) -> v == null || v % 2 == 0 ? null : v + 1),
                        map.compute(key, (k, v) -> v == null || v % 2 == 0 ? null : v + 1));
                default -> {
                    // Remove a few entries through the iterator (tree removal with movable = false).
                    Iterator<Map.Entry<Object, Integer>> it = map.entrySet().iterator();
                    for (int k = 0; k < 3 && it.hasNext(); k++) {
                        Map.Entry<Object, Integer> e = it.next();
                        if (e.getValue() % 3 == 0) {
                            ref.remove(e.getKey());
                            it.remove();
                        }
                    }
                }
            }
            if (op % 500 == 0) {
                TreeBinInvariants.verify(map);
                assertEquals(ref, map);
            }
        }
        TreeBinInvariants.verify(map);
        assertEquals(ref, map);
        assertEquals(map, ref);
        assertEquals(ref.hashCode(), map.hashCode());
        @SuppressWarnings("unchecked")
        JdkHashMapCopy<Object, Integer> copy = (JdkHashMapCopy<Object, Integer>) map.clone();
        assertEquals(map.getClass(), copy.getClass());
        TreeBinInvariants.verify(copy);
        assertEquals(ref, copy);
    }

    @ParameterizedTest(name = "rangeSplit={0}")
    @ValueSource(booleans = {true, false})
    void repeatedForcedResizesKeepContents(boolean rangeSplit) {
        for (int distinct : new int[] {1, 2, 9, 500}) {
            Random rnd = new Random(distinct);
            int[] hashes = collidingHashes(rnd, distinct);
            JdkHashMapCopy<CKey, Integer> map = rangeSplit
                    ? new RangeSplitHashMap<>(64, Float.MAX_VALUE) : new JdkHashMapCopy<>(64, Float.MAX_VALUE);
            Map<CKey, Integer> ref = new HashMap<>();
            for (int id = 0; id < 5000; id++) {
                CKey key = new CKey(id, hashes[rnd.nextInt(hashes.length)]);
                map.put(key, id);
                ref.put(key, id);
            }
            TreeBinInvariants.verify(map);
            for (int r = 0; r < 12; r++) {
                map.resize();
                TreeBinInvariants.verify(map);
                assertEquals(ref, map);
                assertEquals(map, ref);
            }
            assertEquals(64 << 12, map.table.length);
        }
    }

    /**
     * Deleting through an iterator does not report the new root (no moveRootToFront call), so the
     * root pointer RangeSplitHashMap keeps on the bin's head goes stale; root() must notice. This
     * deletes the current root of a tree bin through the iterator, again and again.
     */
    @ParameterizedTest(name = "rangeSplit={0}")
    @ValueSource(booleans = {true, false})
    void iteratorRemovalOfTreeRoot(boolean rangeSplit) {
        JdkHashMapCopy<CKey, Integer> map = create(rangeSplit);
        Map<CKey, Integer> ref = new HashMap<>();
        for (int id = 0; id < 500; id++) {
            CKey key = new CKey(id, TreeHashMap.unspread((id % 50) << 10 | 3)); // all in bucket 3
            map.put(key, id);
            ref.put(key, id);
        }
        for (int round = 0; round < 200; round++) {
            JdkHashMapCopy.TreeNode<?, ?> root = (JdkHashMapCopy.TreeNode<?, ?>) map.table[3];
            while (root.parent != null) root = root.parent;
            Iterator<Map.Entry<CKey, Integer>> it = map.entrySet().iterator();
            while (it.next() != root) { }
            ref.remove(root.key);
            it.remove();
            TreeBinInvariants.verify(map);
            assertEquals(ref, map);
            for (CKey k : ref.keySet()) assertEquals(ref.get(k), map.get(k));
            CKey extra = new CKey(10_000 + round, TreeHashMap.unspread((round % 50) << 10 | 3));
            assertEquals(ref.put(extra, round), map.put(extra, round));
            TreeBinInvariants.verify(map);
        }
    }

    @Test
    void serializationRoundTrip() throws Exception {
        for (Supplier<JdkHashMapCopy<Object, Integer>> factory :
                List.<Supplier<JdkHashMapCopy<Object, Integer>>>of(RangeSplitHashMap::new, JdkHashMapCopy::new)) {
            JdkHashMapCopy<Object, Integer> map = factory.get();
            Map<Object, Integer> ref = new HashMap<>();
            for (int id = 0; id < 2000; id++) {
                CKey key = new CKey(id, TreeHashMap.unspread((id % 7) << 10 | 5));
                map.put(key, id);
                ref.put(key, id);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(map);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                @SuppressWarnings("unchecked")
                JdkHashMapCopy<Object, Integer> copy = (JdkHashMapCopy<Object, Integer>) in.readObject();
                assertEquals(map.getClass(), copy.getClass());
                TreeBinInvariants.verify(copy);
                assertEquals(ref, copy);
            }
        }
    }
}
