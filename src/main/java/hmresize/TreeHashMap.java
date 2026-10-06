package hmresize;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * A hash map with red-black tree bins, modelled on {@link java.util.HashMap}, used to compare two
 * ways of splitting a tree bin when the table doubles.
 *
 * <p>When the table grows from {@code n} to {@code 2n}, a node in bucket {@code i} moves to
 * {@code i} or {@code i + n} depending on the single bit {@code hash & n}. Tree bins are ordered by
 * hash first (signed), and every node in bucket {@code i} shares the low {@code s = log2(n)} bits, so
 * in tree order the nodes are grouped by {@code prefix = hash >> s} and the destination is
 * {@code prefix & 1}. A run of nodes with the same prefix therefore moves as a unit, and its
 * boundaries are the multiples of {@code n} in hash space.
 *
 * <ul>
 *   <li>{@link ResizeStrategy#REBUILD} walks every node and rebuilds both halves from sorted order:
 *       O(m) per bin. This is already stronger than the JDK, which re-treeifies in O(m log m).
 *   <li>{@link ResizeStrategy#RANGE_SPLIT} cuts the tree at prefix boundaries with join-based
 *       split/join (Blelloch, Ferizovic, Sun: "Just Join for Parallel Ordered Sets"), moving whole
 *       subtrees without visiting them: O(d log m), where d is the number of distinct hashes in the
 *       bin, and O(log m) when every node goes to the same side.
 * </ul>
 *
 * <p>Unlike {@code java.util.HashMap.TreeNode}, tree nodes keep no insertion-order linked list, since
 * splitting that list would cost O(m) on its own. Iteration walks the tree in order instead.
 * Not thread-safe; iterators are not fail-fast and do not support {@code remove}.
 */
public final class TreeHashMap<K, V> extends AbstractMap<K, V> {

    public enum ResizeStrategy {
        /** Walk every node of a tree bin, then rebuild both halves from sorted order. */
        REBUILD,
        /** Cut the tree at hash-prefix boundaries with split/join, moving untouched subtrees wholesale. */
        RANGE_SPLIT
    }

    static final int TREEIFY_THRESHOLD = 8;
    static final int UNTREEIFY_THRESHOLD = 6;
    static final int MIN_TREEIFY_CAPACITY = 64;
    static final int MAXIMUM_CAPACITY = 1 << 30;

    private final ResizeStrategy strategy;
    private final float loadFactor;
    private Object[] table;
    private int size;
    private int threshold;
    private long nextSeq;
    private long treeNodeVisits;

    // Out-parameters of the recursive tree operations, to avoid allocating result tuples.
    private TreeNode<K, V> outLo, outHi, outLast;
    private int outLoH, outHiH, joinH;
    private TreeNode<K, V>[] scratch;
    private int scratchLo, scratchHi;

    public TreeHashMap(ResizeStrategy strategy) {
        this(16, 0.75f, strategy);
    }

    public TreeHashMap(int initialCapacity, float loadFactor, ResizeStrategy strategy) {
        if (initialCapacity < 1 || initialCapacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("Illegal initial capacity: " + initialCapacity);
        }
        if (!(loadFactor > 0)) {
            throw new IllegalArgumentException("Illegal load factor: " + loadFactor);
        }
        this.strategy = Objects.requireNonNull(strategy);
        this.loadFactor = loadFactor;
        int cap = Integer.highestOneBit(initialCapacity);
        if (cap < initialCapacity) cap <<= 1;
        table = new Object[cap];
        threshold = thresholdFor(cap);
    }

    // ---------------------------------------------------------------- nodes

    abstract static class Node<K, V> implements Map.Entry<K, V> {
        final int hash;
        final K key;
        V value;

        Node(int hash, K key, V value) {
            this.hash = hash;
            this.key = key;
            this.value = value;
        }

        @Override public final K getKey() { return key; }
        @Override public final V getValue() { return value; }

        @Override
        public final V setValue(V newValue) {
            V old = value;
            value = newValue;
            return old;
        }

        @Override
        public final boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> e
                    && Objects.equals(key, e.getKey())
                    && Objects.equals(value, e.getValue());
        }

        @Override public final int hashCode() { return Objects.hashCode(key) ^ Objects.hashCode(value); }
        @Override public final String toString() { return key + "=" + value; }
    }

    static final class ListNode<K, V> extends Node<K, V> {
        ListNode<K, V> next;

        ListNode(int hash, K key, V value, ListNode<K, V> next) {
            super(hash, key, value);
            this.next = next;
        }
    }

    static final class TreeNode<K, V> extends Node<K, V> {
        /** Final tie-breaker so that tree order is total. */
        final long seq;
        TreeNode<K, V> left, right;
        boolean red;
        /** Subtree size, so the size of each half is known after a split without counting. */
        int size = 1;

        TreeNode(int hash, K key, V value, long seq) {
            super(hash, key, value);
            this.seq = seq;
        }
    }

    /** Table slot holding a tree; {@code bh} is the black height of the (black) root, counting it. */
    static final class TreeBin<K, V> {
        TreeNode<K, V> root;
        int bh;

        TreeBin(TreeNode<K, V> root, int bh) {
            this.root = root;
            this.bh = bh;
        }
    }

    // ---------------------------------------------------------------- hashing and ordering

    /** Same spreading as {@link java.util.HashMap}. */
    static int spread(Object key) {
        int h;
        return key == null ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    /** Inverse of the spreading step: {@code spread(k) == h} when {@code k.hashCode() == unspread(h)}. */
    static int unspread(int h) {
        return h ^ (h >>> 16);
    }

    /** Total order on tree nodes: hash (signed), then class, then natural order within a class, then seq. */
    static int compareNodes(TreeNode<?, ?> a, TreeNode<?, ?> b) {
        if (a.hash != b.hash) return a.hash < b.hash ? -1 : 1;
        int c = compareKeys(a.key, b.key);
        return c != 0 ? c : Long.compare(a.seq, b.seq);
    }

    private static int compareKeys(Object x, Object y) {
        Class<?> cx = x == null ? null : x.getClass();
        Class<?> cy = y == null ? null : y.getClass();
        if (cx != cy) {
            if (cx == null) return -1;
            if (cy == null) return 1;
            int c = cx.getName().compareTo(cy.getName());
            return c != 0 ? c : Integer.compare(System.identityHashCode(cx), System.identityHashCode(cy));
        }
        return compareSameClass(x, y);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareSameClass(Object x, Object y) {
        if (!(x instanceof Comparable)) return 0;
        try {
            return ((Comparable) x).compareTo(y);
        } catch (ClassCastException e) {
            return 0;
        }
    }

    private static boolean isRed(TreeNode<?, ?> n) {
        return n != null && n.red;
    }

    private static int sizeOf(TreeNode<?, ?> n) {
        return n == null ? 0 : n.size;
    }

    private static void fix(TreeNode<?, ?> n) {
        n.size = 1 + sizeOf(n.left) + sizeOf(n.right);
    }

    // ---------------------------------------------------------------- Map API

    @Override
    public int size() {
        return size;
    }

    @Override
    public V get(Object key) {
        Node<K, V> e = getNode(key);
        return e == null ? null : e.value;
    }

    @Override
    public boolean containsKey(Object key) {
        return getNode(key) != null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> getNode(Object key) {
        int h = spread(key);
        Object b = table[h & (table.length - 1)];
        if (b instanceof TreeBin<?, ?> bin) return find((TreeNode<K, V>) bin.root, h, key);
        for (ListNode<K, V> p = (ListNode<K, V>) b; p != null; p = p.next) {
            if (p.hash == h && Objects.equals(p.key, key)) return p;
        }
        return null;
    }

    private TreeNode<K, V> find(TreeNode<K, V> p, int h, Object key) {
        while (p != null) {
            if (h < p.hash) {
                p = p.left;
            } else if (h > p.hash) {
                p = p.right;
            } else if (Objects.equals(key, p.key)) {
                return p;
            } else if (p.left == null) {
                p = p.right;
            } else if (p.right == null) {
                p = p.left;
            } else {
                int dir = key != null && p.key != null && key.getClass() == p.key.getClass()
                        ? compareSameClass(key, p.key) : 0;
                if (dir < 0) {
                    p = p.left;
                } else if (dir > 0) {
                    p = p.right;
                } else {
                    TreeNode<K, V> q = find(p.right, h, key);
                    if (q != null) return q;
                    p = p.left;
                }
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        int h = spread(key);
        Object[] tab = table;
        int i = h & (tab.length - 1);
        Object b = tab[i];
        if (b instanceof TreeBin<?, ?> raw) {
            TreeBin<K, V> bin = (TreeBin<K, V>) raw;
            TreeNode<K, V> e = find(bin.root, h, key);
            if (e != null) return e.setValue(value);
            insert(bin, new TreeNode<>(h, key, value, nextSeq++));
        } else {
            int count = 0;
            ListNode<K, V> last = null;
            for (ListNode<K, V> p = (ListNode<K, V>) b; p != null; last = p, p = p.next, count++) {
                if (p.hash == h && Objects.equals(p.key, key)) return p.setValue(value);
            }
            ListNode<K, V> n = new ListNode<>(h, key, value, null);
            if (last == null) tab[i] = n;
            else last.next = n;
            if (++count > TREEIFY_THRESHOLD) treeifyBin(tab, i);
        }
        if (++size > threshold) resize();
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        int h = spread(key);
        Object[] tab = table;
        int i = h & (tab.length - 1);
        Object b = tab[i];
        if (b instanceof TreeBin<?, ?> raw) {
            TreeBin<K, V> bin = (TreeBin<K, V>) raw;
            TreeNode<K, V> p = find(bin.root, h, key);
            if (p == null) return null;
            split(bin.root, bin.bh, 0, p);
            TreeNode<K, V> root = join2(outLo, outLoH, outHi, outHiH);
            place(tab, i, root, joinH);
            p.left = p.right = null;
            size--;
            return p.value;
        }
        ListNode<K, V> prev = null;
        for (ListNode<K, V> p = (ListNode<K, V>) b; p != null; prev = p, p = p.next) {
            if (p.hash == h && Objects.equals(p.key, key)) {
                if (prev == null) tab[i] = p.next;
                else prev.next = p.next;
                size--;
                return p.value;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        Arrays.fill(table, null);
        size = 0;
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override public Iterator<Map.Entry<K, V>> iterator() { return new EntryIterator(); }
            @Override public int size() { return size; }
        };
    }

    // ---------------------------------------------------------------- instrumentation

    public int capacity() {
        return table.length;
    }

    public ResizeStrategy strategy() {
        return strategy;
    }

    /** Doubles the table now, regardless of the load factor. */
    public void forceResize() {
        resize();
    }

    /** Cumulative count of tree nodes touched by tree surgery (walks, splits, joins, min/max lookups). */
    public long treeNodeVisits() {
        return treeNodeVisits;
    }

    // ---------------------------------------------------------------- resize

    private int thresholdFor(int cap) {
        if (cap >= MAXIMUM_CAPACITY) return Integer.MAX_VALUE;
        float t = cap * loadFactor;
        return t < Integer.MAX_VALUE ? (int) t : Integer.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Object[] old = table;
        int oldCap = old.length;
        if (oldCap >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }
        Object[] tab = new Object[oldCap << 1];
        for (int j = 0; j < oldCap; j++) {
            Object b = old[j];
            if (b == null) continue;
            if (b instanceof TreeBin<?, ?> bin) {
                if (strategy == ResizeStrategy.RANGE_SPLIT) rangeSplit((TreeBin<K, V>) bin, tab, j, oldCap);
                else rebuildSplit((TreeBin<K, V>) bin, tab, j, oldCap);
            } else {
                splitList((ListNode<K, V>) b, tab, j, oldCap);
            }
        }
        table = tab;
        threshold = thresholdFor(tab.length);
    }

    private void splitList(ListNode<K, V> p, Object[] tab, int j, int oldCap) {
        ListNode<K, V> loHead = null, loTail = null, hiHead = null, hiTail = null;
        for (ListNode<K, V> next; p != null; p = next) {
            next = p.next;
            p.next = null;
            if ((p.hash & oldCap) == 0) {
                if (loTail == null) loHead = p;
                else loTail.next = p;
                loTail = p;
            } else {
                if (hiTail == null) hiHead = p;
                else hiTail.next = p;
                hiTail = p;
            }
        }
        tab[j] = loHead;
        tab[j + oldCap] = hiHead;
    }

    /** Baseline: one in-order walk distributes the nodes, then each half is rebuilt from sorted order. */
    private void rebuildSplit(TreeBin<K, V> bin, Object[] tab, int j, int oldCap) {
        int m = bin.root.size;
        TreeNode<K, V>[] a = scratch(2 * m);
        scratchLo = 0;
        scratchHi = m;
        collectSplit(bin.root, oldCap, a);
        int loCount = scratchLo, hiCount = scratchHi - m;
        if (hiCount == 0) {
            tab[j] = bin;
        } else if (loCount == 0) {
            tab[j + oldCap] = bin;
        } else {
            placeSorted(tab, j, a, 0, loCount);
            placeSorted(tab, j + oldCap, a, m, m + hiCount);
        }
        Arrays.fill(a, 0, 2 * m, null);
    }

    private void collectSplit(TreeNode<K, V> p, int bit, TreeNode<K, V>[] a) {
        if (p == null) return;
        collectSplit(p.left, bit, a);
        treeNodeVisits++;
        if ((p.hash & bit) == 0) a[scratchLo++] = p;
        else a[scratchHi++] = p;
        collectSplit(p.right, bit, a);
    }

    /**
     * Range split: recurse down the tree carrying the hash range {@code [lb, ub]} a subtree must lie in
     * (from the tree's min/max and the ancestors' hashes). A subtree whose range sits inside one prefix
     * {@code hash >> s} goes to one side whole, without being visited; the rest are re-assembled with
     * join. One run costs O(log m), few runs cost roughly O(d log(m/d)), and all-distinct hashes O(m).
     */
    private void rangeSplit(TreeBin<K, V> bin, Object[] tab, int j, int oldCap) {
        int s = Integer.numberOfTrailingZeros(oldCap);
        int min = leftmost(bin.root).hash, max = rightmost(bin.root).hash;
        if ((min >> s) == (max >> s)) {
            tab[(min & oldCap) == 0 ? j : j + oldCap] = bin;
            return;
        }
        partition(bin.root, bin.bh, min, max, oldCap);
        TreeNode<K, V> lo = outLo, hi = outHi;
        int loH = outLoH, hiH = outHiH;
        place(tab, j, lo, loH);
        place(tab, j + oldCap, hi, hiH);
    }

    /**
     * Splits {@code t}, whose hashes all lie in {@code [lb, ub]}, by {@code hash & bit}: the zero side goes
     * to {@code outLo/outLoH}, the other to {@code outHi/outHiH}.
     */
    private void partition(TreeNode<K, V> t, int h, int lb, int ub, int bit) {
        if (t == null) {
            outLo = outHi = null;
            outLoH = outHiH = 0;
            return;
        }
        treeNodeVisits++;
        int s = Integer.numberOfTrailingZeros(bit);
        if ((lb >> s) == (ub >> s)) {
            if (t.red) {
                t.red = false;
                h++;
            }
            if ((lb & bit) == 0) {
                outLo = t; outLoH = h;
                outHi = null; outHiH = 0;
            } else {
                outLo = null; outLoH = 0;
                outHi = t; outHiH = h;
            }
            return;
        }
        int ch = t.red ? h : h - 1;
        TreeNode<K, V> l = t.left, r = t.right;
        int lh = ch, rh = ch;
        if (isRed(l)) { l.red = false; lh++; }
        if (isRed(r)) { r.red = false; rh++; }
        partition(l, lh, lb, t.hash, bit);
        TreeNode<K, V> l0 = outLo, l1 = outHi;
        int l0h = outLoH, l1h = outHiH;
        partition(r, rh, t.hash, ub, bit);
        TreeNode<K, V> r0 = outLo, r1 = outHi;
        int r0h = outLoH, r1h = outHiH;
        TreeNode<K, V> lo, hi;
        int loH, hiH;
        if ((t.hash & bit) == 0) {
            lo = join(l0, l0h, t, r0, r0h);
            loH = joinH;
            hi = join2(l1, l1h, r1, r1h);
            hiH = joinH;
        } else {
            lo = join2(l0, l0h, r0, r0h);
            loH = joinH;
            hi = join(l1, l1h, t, r1, r1h);
            hiH = joinH;
        }
        outLo = lo; outLoH = loH;
        outHi = hi; outHiH = hiH;
    }

    private TreeNode<K, V> leftmost(TreeNode<K, V> p) {
        for (; p.left != null; p = p.left) treeNodeVisits++;
        treeNodeVisits++;
        return p;
    }

    private TreeNode<K, V> rightmost(TreeNode<K, V> p) {
        for (; p.right != null; p = p.right) treeNodeVisits++;
        treeNodeVisits++;
        return p;
    }

    // ---------------------------------------------------------------- treeify / untreeify

    @SuppressWarnings("unchecked")
    private void treeifyBin(Object[] tab, int i) {
        if (tab.length < MIN_TREEIFY_CAPACITY) {
            resize();
            return;
        }
        int count = 0;
        for (ListNode<K, V> p = (ListNode<K, V>) tab[i]; p != null; p = p.next) count++;
        TreeNode<K, V>[] a = new TreeNode[count];
        int k = 0;
        for (ListNode<K, V> p = (ListNode<K, V>) tab[i]; p != null; p = p.next) {
            a[k++] = new TreeNode<>(p.hash, p.key, p.value, nextSeq++);
        }
        Arrays.sort(a, TreeHashMap::compareNodes);
        placeSorted(tab, i, a, 0, count);
    }

    /** Stores {@code a[from, to)}, already in tree order, as a list or a tree depending on its size. */
    private void placeSorted(Object[] tab, int idx, TreeNode<K, V>[] a, int from, int to) {
        int count = to - from;
        if (count <= UNTREEIFY_THRESHOLD) {
            ListNode<K, V> head = null;
            for (int k = to - 1; k >= from; k--) head = new ListNode<>(a[k].hash, a[k].key, a[k].value, head);
            tab[idx] = head;
        } else {
            int redLevel = 31 - Integer.numberOfLeadingZeros(count + 1);
            tab[idx] = new TreeBin<>(build(a, from, to - 1, 0, redLevel), redLevel);
        }
    }

    /** Balanced build from sorted nodes; nodes on the deepest, incomplete level are red (as in TreeMap). */
    private TreeNode<K, V> build(TreeNode<K, V>[] a, int lo, int hi, int level, int redLevel) {
        if (lo > hi) return null;
        int mid = (lo + hi) >>> 1;
        TreeNode<K, V> n = a[mid];
        treeNodeVisits++;
        n.left = build(a, lo, mid - 1, level + 1, redLevel);
        n.right = build(a, mid + 1, hi, level + 1, redLevel);
        n.red = level == redLevel;
        fix(n);
        return n;
    }

    /** Stores a split-off tree; small halves become lists, like HashMap's untreeify. */
    @SuppressWarnings("unchecked")
    private void place(Object[] tab, int idx, TreeNode<K, V> root, int bh) {
        if (root == null) {
            tab[idx] = null;
        } else if (root.size <= UNTREEIFY_THRESHOLD) {
            TreeNode<K, V>[] a = new TreeNode[root.size];
            collect(root, a, 0);
            treeNodeVisits += a.length;
            placeSorted(tab, idx, a, 0, a.length);
        } else {
            tab[idx] = new TreeBin<>(root, bh);
        }
    }

    private static <K, V> int collect(TreeNode<K, V> p, TreeNode<K, V>[] out, int k) {
        if (p == null) return k;
        k = collect(p.left, out, k);
        out[k++] = p;
        return collect(p.right, out, k);
    }

    @SuppressWarnings("unchecked")
    private TreeNode<K, V>[] scratch(int n) {
        if (scratch == null || scratch.length < n) scratch = new TreeNode[Math.max(n, 64)];
        return scratch;
    }

    // ---------------------------------------------------------------- join-based red-black tree
    //
    // Every tree passed around has a black (or null) root and a known black height h, counting the root
    // (null has h = 0). All structural changes go through join; split, insert and delete are built on it.

    private void insert(TreeBin<K, V> bin, TreeNode<K, V> n) {
        split(bin.root, bin.bh, 0, n);
        bin.root = join(outLo, outLoH, n, outHi, outHiH);
        bin.bh = joinH;
    }

    /**
     * Splits {@code t} into the nodes before and after a cut, returned in {@code outLo/outLoH} and
     * {@code outHi/outHiH}. With {@code pivot == null} the cut is {@code hash < boundary}; otherwise it is
     * the tree order of {@code pivot}, and {@code pivot} itself (if present) is dropped.
     */
    private void split(TreeNode<K, V> t, int h, long boundary, TreeNode<K, V> pivot) {
        if (t == null) {
            outLo = outHi = null;
            outLoH = outHiH = 0;
            return;
        }
        treeNodeVisits++;
        int ch = t.red ? h : h - 1;
        TreeNode<K, V> l = t.left, r = t.right;
        int lh = ch, rh = ch;
        if (isRed(l)) { l.red = false; lh++; }
        if (isRed(r)) { r.red = false; rh++; }
        if (t == pivot) {
            outLo = l; outLoH = lh;
            outHi = r; outHiH = rh;
            return;
        }
        boolean goesLow = pivot != null ? compareNodes(t, pivot) < 0 : t.hash < boundary;
        if (goesLow) {
            split(r, rh, boundary, pivot);
            TreeNode<K, V> hi = outHi;
            int hiH = outHiH;
            outLo = join(l, lh, t, outLo, outLoH);
            outLoH = joinH;
            outHi = hi;
            outHiH = hiH;
        } else {
            split(l, lh, boundary, pivot);
            TreeNode<K, V> lo = outLo;
            int loH = outLoH;
            outHi = join(outHi, outHiH, t, r, rh);
            outHiH = joinH;
            outLo = lo;
            outLoH = loH;
        }
    }

    /** Joins {@code l < k < r} into one tree with a black root; its black height goes to {@code joinH}. */
    private TreeNode<K, V> join(TreeNode<K, V> l, int lh, TreeNode<K, V> k, TreeNode<K, V> r, int rh) {
        treeNodeVisits++;
        if (lh == rh) {
            k.left = l;
            k.right = r;
            k.red = false;
            fix(k);
            joinH = lh + 1;
            return k;
        }
        TreeNode<K, V> t = lh > rh ? joinRight(l, lh, k, r, rh) : joinLeft(l, lh, k, r, rh);
        int h = Math.max(lh, rh);
        if (t.red) {
            t.red = false;
            h++;
        }
        joinH = h;
        return t;
    }

    /** Descends the right spine of {@code t} to black height {@code rh} and hangs {@code k} there. */
    private TreeNode<K, V> joinRight(TreeNode<K, V> t, int h, TreeNode<K, V> k, TreeNode<K, V> r, int rh) {
        treeNodeVisits++;
        if (!isRed(t) && h == rh) {
            k.left = t;
            k.right = r;
            k.red = true;
            fix(k);
            return k;
        }
        t.right = joinRight(t.right, t.red ? h : h - 1, k, r, rh);
        if (!t.red && isRed(t.right) && isRed(t.right.right)) {
            t.right.right.red = false;
            return rotateLeft(t);
        }
        fix(t);
        return t;
    }

    private TreeNode<K, V> joinLeft(TreeNode<K, V> l, int lh, TreeNode<K, V> k, TreeNode<K, V> t, int h) {
        treeNodeVisits++;
        if (!isRed(t) && h == lh) {
            k.left = l;
            k.right = t;
            k.red = true;
            fix(k);
            return k;
        }
        t.left = joinLeft(l, lh, k, t.left, t.red ? h : h - 1);
        if (!t.red && isRed(t.left) && isRed(t.left.left)) {
            t.left.left.red = false;
            return rotateRight(t);
        }
        fix(t);
        return t;
    }

    private static <K, V> TreeNode<K, V> rotateLeft(TreeNode<K, V> t) {
        TreeNode<K, V> x = t.right;
        t.right = x.left;
        x.left = t;
        fix(t);
        fix(x);
        return x;
    }

    private static <K, V> TreeNode<K, V> rotateRight(TreeNode<K, V> t) {
        TreeNode<K, V> x = t.left;
        t.left = x.right;
        x.right = t;
        fix(t);
        fix(x);
        return x;
    }

    /** Joins {@code l < r} without a middle node by borrowing the maximum of {@code l}. */
    private TreeNode<K, V> join2(TreeNode<K, V> l, int lh, TreeNode<K, V> r, int rh) {
        if (l == null) {
            joinH = rh;
            return r;
        }
        if (r == null) {
            joinH = lh;
            return l;
        }
        splitLast(l, lh);
        return join(outLo, outLoH, outLast, r, rh);
    }

    /** Removes the maximum of {@code t} into {@code outLast}; the rest goes to {@code outLo/outLoH}. */
    private void splitLast(TreeNode<K, V> t, int h) {
        treeNodeVisits++;
        int ch = t.red ? h : h - 1;
        TreeNode<K, V> l = t.left, r = t.right;
        int lh = ch;
        if (isRed(l)) { l.red = false; lh++; }
        if (r == null) {
            outLast = t;
            outLo = l;
            outLoH = lh;
            return;
        }
        int rh = ch;
        if (r.red) { r.red = false; rh++; }
        splitLast(r, rh);
        outLo = join(l, lh, t, outLo, outLoH);
        outLoH = joinH;
    }

    // ---------------------------------------------------------------- iteration

    private final class EntryIterator implements Iterator<Map.Entry<K, V>> {
        private final Object[] tab = table;
        private final ArrayDeque<TreeNode<K, V>> stack = new ArrayDeque<>();
        private int index;
        private ListNode<K, V> nextInList;
        private Node<K, V> next;

        EntryIterator() {
            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Map.Entry<K, V> next() {
            Node<K, V> e = next;
            if (e == null) throw new NoSuchElementException();
            advance();
            return e;
        }

        @SuppressWarnings("unchecked")
        private void advance() {
            if (nextInList != null) {
                next = nextInList;
                nextInList = nextInList.next;
                return;
            }
            if (!stack.isEmpty()) {
                next = popInOrder();
                return;
            }
            while (index < tab.length) {
                Object b = tab[index++];
                if (b instanceof TreeBin<?, ?> bin) {
                    pushLeft((TreeNode<K, V>) bin.root);
                    next = popInOrder();
                    return;
                }
                if (b != null) {
                    ListNode<K, V> l = (ListNode<K, V>) b;
                    next = l;
                    nextInList = l.next;
                    return;
                }
            }
            next = null;
        }

        private TreeNode<K, V> popInOrder() {
            TreeNode<K, V> p = stack.pop();
            pushLeft(p.right);
            return p;
        }

        private void pushLeft(TreeNode<K, V> p) {
            for (; p != null; p = p.left) stack.push(p);
        }
    }

    // ---------------------------------------------------------------- invariants (for tests)

    /** Checks bucket placement, red-black invariants, subtree sizes, tree order and the entry count. */
    @SuppressWarnings("unchecked")
    void verify() {
        Object[] tab = table;
        int count = 0;
        for (int i = 0; i < tab.length; i++) {
            Object b = tab[i];
            if (b instanceof TreeBin<?, ?> raw) {
                TreeBin<K, V> bin = (TreeBin<K, V>) raw;
                check(bin.root != null && !bin.root.red, "tree root must be black, bucket " + i);
                check(checkSubtree(bin.root, i, tab.length) == bin.bh, "stored black height, bucket " + i);
                check(bin.root.size > UNTREEIFY_THRESHOLD, "tree bin too small, bucket " + i);
                TreeNode<K, V>[] a = new TreeNode[bin.root.size];
                check(collect(bin.root, a, 0) == a.length, "size field, bucket " + i);
                for (int k = 1; k < a.length; k++) {
                    check(compareNodes(a[k - 1], a[k]) < 0, "tree order, bucket " + i);
                }
                count += a.length;
            } else {
                for (ListNode<K, V> p = (ListNode<K, V>) b; p != null; p = p.next) {
                    check((p.hash & (tab.length - 1)) == i, "list node in wrong bucket " + i);
                    count++;
                }
            }
        }
        check(count == size, "size " + size + " but found " + count + " entries");
    }

    private static int checkSubtree(TreeNode<?, ?> p, int bucket, int length) {
        if (p == null) return 0;
        check((p.hash & (length - 1)) == bucket, "tree node in wrong bucket " + bucket);
        check(!p.red || (!isRed(p.left) && !isRed(p.right)), "red node with red child, bucket " + bucket);
        int l = checkSubtree(p.left, bucket, length);
        int r = checkSubtree(p.right, bucket, length);
        check(l == r, "unequal black heights, bucket " + bucket);
        check(p.size == 1 + sizeOf(p.left) + sizeOf(p.right), "subtree size, bucket " + bucket);
        return l + (p.red ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
