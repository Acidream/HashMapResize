package hmresize;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * {@link JdkHashMapCopy} (JDK 24 {@code java.util.HashMap}) with a faster split of tree bins on resize.
 * Only the overridden parts live here; everything else is the JDK code.
 *
 * <p>When the table doubles, a node in bucket {@code i} moves to {@code i} or {@code i + bit} depending
 * on {@code hash & bit}. A tree bin is ordered by hash first and all its nodes share the low bits, so in
 * tree order the nodes come grouped by {@code prefix = hash >> log2(bit)}, and {@code prefix & 1} picks
 * the side. The JDK's {@code TreeNode.split} walks every node and re-treeifies both halves, O(m log m).
 * {@link RangeTreeNode#split} instead partitions the tree with join-based red-black tree operations,
 * moving every subtree whose hash range lies within one prefix without visiting it: O(log m) when the
 * whole bin goes to one side, roughly O(d log(m/d)) for d distinct hashes, O(m) at worst.
 *
 * <p>The bin's {@code next/prev} chain (used by iteration) must be split too, which would cost O(m) in
 * the JDK's layout. So tree bins here keep one more invariant: <b>the chain lists the nodes in tree
 * (in-order) order, and the table slot holds the first of them</b> rather than the root. A subtree that
 * moves whole is then one contiguous chain segment, spliced in O(1). The overrides that keep the
 * invariant are {@link RangeTreeNode#moveRootToFront} (a no-op), {@link RangeTreeNode#treeify} and
 * {@link RangeTreeNode#putTreeVal}. {@code removeTreeNode} needs no change: unlinking a node from the
 * chain keeps it in order. HashMap does not specify iteration order, so the different order is allowed.
 */
public class RangeSplitHashMap<K,V> extends JdkHashMapCopy<K,V> {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** The node most recently created by {@link #newTreeNode}, for {@link RangeTreeNode#putTreeVal}. */
    private transient RangeTreeNode<K,V> lastNewTreeNode;

    public RangeSplitHashMap(int initialCapacity, float loadFactor) {
        super(initialCapacity, loadFactor);
    }

    public RangeSplitHashMap(int initialCapacity) {
        super(initialCapacity);
    }

    public RangeSplitHashMap() {
        super();
    }

    public RangeSplitHashMap(Map<? extends K, ? extends V> m) {
        super(m);
    }

    // Like HashMap.newHashMap, which returns a JdkHashMapCopy here.
    public static <K, V> RangeSplitHashMap<K, V> newHashMap(int numMappings) {
        if (numMappings < 0) {
            throw new IllegalArgumentException("Negative number of mappings: " + numMappings);
        }
        return new RangeSplitHashMap<>(calculateHashMapCapacity(numMappings));
    }

    @Override
    TreeNode<K,V> newTreeNode(int hash, K key, V value, Node<K,V> next) {
        return lastNewTreeNode = new RangeTreeNode<>(hash, key, value, next);
    }

    @Override
    TreeNode<K,V> replacementTreeNode(Node<K,V> p, Node<K,V> next) {
        return new RangeTreeNode<>(p.hash, p.key, p.value, next);
    }

    /** A removed head would otherwise keep its root pointer, and with it the rest of the bin. */
    @Override
    void afterNodeRemoval(Node<K,V> p) {
        if (p instanceof RangeTreeNode<K,V> t)
            t.binRoot = null;
    }

    static final class RangeTreeNode<K,V> extends TreeNode<K,V> {

        /**
         * On the bin's head (the node in the table slot) only: the tree's root, so that lookups and
         * inserts, which start from the head, need not climb to it. Null on every other node. Kept
         * up to date by moveRootToFront (called by the JDK code with the new root after inserts and
         * most deletes), linkInOrder, relinkInOrder and the split; {@link #root()} checks it before use.
         */
        TreeNode<K,V> binRoot;

        RangeTreeNode(int hash, K key, V val, Node<K,V> next) {
            super(hash, key, val, next);
        }

        /**
         * The cached root when it is still valid, else the JDK's climb (whose result is cached). The
         * cache can only be stale after a deletion through an iterator, which does not report the new
         * root: then the cached node either has a parent now, or was the deleted node, which the JDK
         * unlinks completely (no parent and no children, and it is not this head).
         */
        @Override
        TreeNode<K,V> root() {
            TreeNode<K,V> r = binRoot;
            if (r != null && r.parent == null && (r == this || r.left != null || r.right != null))
                return r;
            return binRoot = super.root();
        }

        /** The slot keeps the in-order head instead of the root; just record the root on the head. */
        @Override
        void moveRootToFront(Node<K,V>[] tab, TreeNode<K,V> root) {
            ((RangeTreeNode<K,V>) tab[(tab.length - 1) & root.hash]).binRoot = root;
        }

        /** The JDK treeify, then the chain relinked in tree order. */
        @Override
        void treeify(Node<K,V>[] tab) {
            super.treeify(tab);
            linkInOrder(tab, super.root());
        }

        /**
         * The JDK putTreeVal, which links a new node right after its parent in the chain; then the node
         * is moved to its in-order position (it differs only when it was added as a left child).
         */
        @Override
        TreeNode<K,V> putTreeVal(JdkHashMapCopy<K,V> map, Node<K,V>[] tab, int h, K k, V v) {
            TreeNode<K,V> existing = super.putTreeVal(map, tab, h, k, v);
            if (existing == null) {
                RangeSplitHashMap<K,V> m = (RangeSplitHashMap<K,V>) map;
                relinkInOrder(tab, m.lastNewTreeNode);
                m.lastNewTreeNode = null;
            }
            return existing;
        }

        /** Range split instead of walking the chain and re-treeifying both halves. */
        @Override
        void split(JdkHashMapCopy<K,V> map, Node<K,V>[] tab, int index, int bit) {
            TreeNode<K,V> head = this, root = root(), tail = root;
            while (tail.right != null)
                tail = tail.right;
            int s = Integer.numberOfTrailingZeros(bit);
            if ((head.hash >> s) == (tail.hash >> s)) { // one prefix: the bin moves as is
                RangeSplitter.finish(map, tab, root, head, tail, (head.hash & bit) == 0 ? index : index + bit);
                return;
            }
            RangeSplitter<K,V> sp = new RangeSplitter<>(bit, head, tail);
            sp.partition(root, blackHeight(root), head.hash, tail.hash, null, null);
            RangeSplitter.finish(map, tab, sp.lo, sp.loHead, sp.loTail, index);
            RangeSplitter.finish(map, tab, sp.hi, sp.hiHead, sp.hiTail, index + bit);
        }

        /** Relinks the chain of the tree at root in tree order, headed by the bin's slot. */
        static <K,V> void linkInOrder(Node<K,V>[] tab, TreeNode<K,V> root) {
            TreeNode<K,V> head = null, tail = null;
            ArrayDeque<TreeNode<K,V>> stack = new ArrayDeque<>();
            for (TreeNode<K,V> p = root; p != null || !stack.isEmpty(); ) {
                if (p != null) {
                    stack.push(p);
                    p = p.left;
                } else {
                    p = stack.pop();
                    if ((p.prev = tail) == null)
                        head = p;
                    else
                        tail.next = p;
                    tail = p;
                    p = p.right;
                }
            }
            tail.next = null;
            setHead(tab, (tab.length - 1) & root.hash, head, root);
        }

        /** Makes head the bin's head, moving the root pointer to it from the previous head. */
        static <K,V> void setHead(Node<K,V>[] tab, int index, TreeNode<K,V> head, TreeNode<K,V> root) {
            if (tab[index] instanceof RangeTreeNode<K,V> old && old != head)
                old.binRoot = null;
            ((RangeTreeNode<K,V>) head).binRoot = root;
            tab[index] = head;
        }

        /** Moves x, which the rest of the chain does not yet hold in order, to its in-order position. */
        static <K,V> void relinkInOrder(Node<K,V>[] tab, TreeNode<K,V> x) {
            int index = (tab.length - 1) & x.hash;
            TreeNode<K,V> pred = x.left;
            if (pred != null) {
                while (pred.right != null)
                    pred = pred.right;
            } else {
                TreeNode<K,V> c = x;
                for (pred = x.parent; pred != null && c == pred.left; pred = pred.parent)
                    c = pred;
            }
            if (x.prev == pred)
                return;
            TreeNode<K,V> xp = x.prev, xn = (TreeNode<K,V>) x.next;
            if (xp != null)
                xp.next = xn;
            else
                tab[index] = xn;
            if (xn != null)
                xn.prev = xp;
            TreeNode<K,V> succ = pred == null ? (TreeNode<K,V>) tab[index] : (TreeNode<K,V>) pred.next;
            x.prev = pred;
            x.next = succ;
            if (succ != null)
                succ.prev = x;
            if (pred == null)
                setHead(tab, index, x, ((RangeTreeNode<K,V>) succ).root());
            else
                pred.next = x;
        }

        static int blackHeight(TreeNode<?,?> p) {
            int h = 0;
            for (; p != null; p = p.left)
                if (!p.red)
                    ++h;
            return h;
        }
    }

    /**
     * Partitions a tree bin by one hash bit using join-based red-black tree operations (Blelloch,
     * Ferizovic, Sun: "Just Join for Parallel Ordered Sets") and splices the in-order chain alongside.
     * Each tree handled here has a black (or null) root and a known black height counting the root.
     */
    static final class RangeSplitter<K,V> {
        final int bit, shift;
        final TreeNode<K,V> first, last; // chain ends of the whole bin
        TreeNode<K,V> loHead, loTail, hiHead, hiTail; // chains being built
        TreeNode<K,V> lo, hi; // out: trees from partition
        int loH, hiH;
        int joinH; // out: black height from join and join2
        TreeNode<K,V> restOut, lastOut; // out: splitLast
        int restH;

        RangeSplitter(int bit, TreeNode<K,V> first, TreeNode<K,V> last) {
            this.bit = bit;
            this.shift = Integer.numberOfTrailingZeros(bit);
            this.first = first;
            this.last = last;
        }

        /**
         * Splits t, whose hashes all lie in [lb, ub], into lo and hi. lbNode and ubNode are the nearest
         * ancestors with t in their right and left subtree, so t's chain segment starts right after
         * lbNode and ends right before ubNode. Segments are appended in tree order, and each is read
         * before anything after it is appended, so those links are still the original ones.
         */
        void partition(TreeNode<K,V> t, int h, int lb, int ub, TreeNode<K,V> lbNode, TreeNode<K,V> ubNode) {
            if (t == null) {
                lo = hi = null;
                loH = hiH = 0;
                return;
            }
            if ((lb >> shift) == (ub >> shift)) { // whole subtree on one side
                TreeNode<K,V> a = lbNode == null ? first : (TreeNode<K,V>) lbNode.next;
                TreeNode<K,V> b = ubNode == null ? last : ubNode.prev;
                boolean high = (lb & bit) != 0;
                append(high, a, b);
                if (t.red) {
                    t.red = false;
                    ++h;
                }
                t.parent = null;
                if (high) {
                    lo = null; loH = 0; hi = t; hiH = h;
                } else {
                    lo = t; loH = h; hi = null; hiH = 0;
                }
                return;
            }
            int ch = t.red ? h : h - 1;
            TreeNode<K,V> l = t.left, r = t.right;
            int lh = ch, rh = ch;
            if (l != null && l.red) { l.red = false; ++lh; }
            if (r != null && r.red) { r.red = false; ++rh; }
            boolean high = (t.hash & bit) != 0;

            partition(l, lh, lb, t.hash, lbNode, t);
            TreeNode<K,V> l0 = lo, l1 = hi;
            int l0h = loH, l1h = hiH;
            append(high, t, t);
            partition(r, rh, t.hash, ub, t, ubNode);
            TreeNode<K,V> r0 = lo, r1 = hi;
            int r0h = loH, r1h = hiH;

            TreeNode<K,V> a, b;
            int ah, bh;
            if (high) {
                a = join2(l0, l0h, r0, r0h); ah = joinH;
                b = join(l1, l1h, t, r1, r1h); bh = joinH;
            } else {
                a = join(l0, l0h, t, r0, r0h); ah = joinH;
                b = join2(l1, l1h, r1, r1h); bh = joinH;
            }
            lo = a; loH = ah;
            hi = b; hiH = bh;
        }

        /** Appends the chain segment a..b (in tree order) to one side. */
        void append(boolean high, TreeNode<K,V> a, TreeNode<K,V> b) {
            if (high) {
                if (hiTail == null)
                    hiHead = a;
                else
                    hiTail.next = a;
                a.prev = hiTail;
                hiTail = b;
            } else {
                if (loTail == null)
                    loHead = a;
                else
                    loTail.next = a;
                a.prev = loTail;
                loTail = b;
            }
        }

        /** Stores one side in its slot, untreeifying it if small (as the JDK split does). */
        static <K,V> void finish(JdkHashMapCopy<K,V> map, Node<K,V>[] tab, TreeNode<K,V> root,
                    TreeNode<K,V> head, TreeNode<K,V> tail, int index) {
            if (head == null)
                return;
            tail.next = null;
            root.parent = null;
            int n = 0; // count at most UNTREEIFY_THRESHOLD + 1 nodes
            for (Node<K,V> e = head; e != null && n <= UNTREEIFY_THRESHOLD; e = e.next)
                ++n;
            if (n <= UNTREEIFY_THRESHOLD) {
                tab[index] = head.untreeify(map);
            } else {
                ((RangeTreeNode<K,V>) head).binRoot = root;
                tab[index] = head;
            }
        }

        private static <K,V> void link(TreeNode<K,V> p) {
            if (p.left != null)
                p.left.parent = p;
            if (p.right != null)
                p.right.parent = p;
        }

        /** Joins l < k < r into a tree with a black root; height in joinH. */
        TreeNode<K,V> join(TreeNode<K,V> l, int lh, TreeNode<K,V> k, TreeNode<K,V> r, int rh) {
            if (lh == rh) {
                k.left = l;
                k.right = r;
                k.red = false;
                link(k);
                joinH = lh + 1;
                return k;
            }
            TreeNode<K,V> t = lh > rh ? joinRight(l, lh, k, r, rh) : joinLeft(l, lh, k, r, rh);
            int h = Math.max(lh, rh);
            if (t.red) {
                t.red = false;
                ++h;
            }
            joinH = h;
            return t;
        }

        private TreeNode<K,V> joinRight(TreeNode<K,V> t, int h, TreeNode<K,V> k, TreeNode<K,V> r, int rh) {
            if ((t == null || !t.red) && h == rh) {
                k.left = t;
                k.right = r;
                k.red = true;
                link(k);
                return k;
            }
            t.right = joinRight(t.right, t.red ? h : h - 1, k, r, rh);
            link(t);
            TreeNode<K,V> tr = t.right;
            if (!t.red && tr.red && tr.right != null && tr.right.red) {
                tr.right.red = false;
                return rotateLeft(t);
            }
            return t;
        }

        private TreeNode<K,V> joinLeft(TreeNode<K,V> l, int lh, TreeNode<K,V> k, TreeNode<K,V> t, int h) {
            if ((t == null || !t.red) && h == lh) {
                k.left = l;
                k.right = t;
                k.red = true;
                link(k);
                return k;
            }
            t.left = joinLeft(l, lh, k, t.left, t.red ? h : h - 1);
            link(t);
            TreeNode<K,V> tl = t.left;
            if (!t.red && tl.red && tl.left != null && tl.left.red) {
                tl.left.red = false;
                return rotateRight(t);
            }
            return t;
        }

        private static <K,V> TreeNode<K,V> rotateLeft(TreeNode<K,V> t) {
            TreeNode<K,V> x = t.right;
            t.right = x.left;
            x.left = t;
            link(t);
            link(x);
            return x;
        }

        private static <K,V> TreeNode<K,V> rotateRight(TreeNode<K,V> t) {
            TreeNode<K,V> x = t.left;
            t.left = x.right;
            x.right = t;
            link(t);
            link(x);
            return x;
        }

        /** Joins l < r by borrowing the maximum of l; height in joinH. */
        TreeNode<K,V> join2(TreeNode<K,V> l, int lh, TreeNode<K,V> r, int rh) {
            if (l == null) {
                joinH = rh;
                return r;
            }
            if (r == null) {
                joinH = lh;
                return l;
            }
            splitLast(l, lh);
            return join(restOut, restH, lastOut, r, rh);
        }

        /** Removes the maximum of t into lastOut, the rest into restOut. */
        private void splitLast(TreeNode<K,V> t, int h) {
            int ch = t.red ? h : h - 1;
            TreeNode<K,V> l = t.left, r = t.right;
            int lh = ch;
            if (l != null && l.red) { l.red = false; ++lh; }
            if (r == null) {
                lastOut = t;
                restOut = l;
                restH = lh;
                return;
            }
            int rh = ch;
            if (r.red) { r.red = false; ++rh; }
            splitLast(r, rh);
            restOut = join(l, lh, t, restOut, restH);
            restH = joinH;
        }
    }
}
