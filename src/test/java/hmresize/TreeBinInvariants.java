package hmresize;

import hmresize.JdkHashMapCopy.Node;
import hmresize.JdkHashMapCopy.TreeNode;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Structural checks for {@link JdkHashMapCopy} and {@link RangeSplitHashMap} tables. */
final class TreeBinInvariants {

    private TreeBinInvariants() {
    }

    /**
     * Checks that every entry is in its bucket, the size, the JDK's TreeNode invariants, red-black
     * rules, and the chain layout: for {@link RangeSplitHashMap} the chain is the in-order sequence
     * starting at the slot; for the JDK layout the chain holds exactly the tree's nodes.
     */
    static void verify(JdkHashMapCopy<?, ?> map) {
        boolean inOrder = map instanceof RangeSplitHashMap;
        Node<?, ?>[] tab = map.table;
        int count = 0;
        if (tab != null) {
            for (int i = 0; i < tab.length; i++) {
                Node<?, ?> head = tab[i];
                if (head instanceof TreeNode<?, ?> t) {
                    count += verifyTreeBin(t, i, tab.length, inOrder);
                } else {
                    for (Node<?, ?> e = head; e != null; e = e.next) {
                        check(!(e instanceof TreeNode), "tree node in list bin " + i);
                        check((e.hash & (tab.length - 1)) == i, "wrong bucket " + i);
                        count++;
                    }
                }
            }
        }
        check(count == map.size(), "size " + map.size() + " but found " + count);
    }

    private static int verifyTreeBin(TreeNode<?, ?> head, int bucket, int length, boolean inOrder) {
        TreeNode<?, ?> root = head;
        while (root.parent != null) root = root.parent;
        check(head.root() == root, "root() is not the tree's root, bucket " + bucket);
        check(!root.red, "red root, bucket " + bucket);
        check(checkInvariants(root), "TreeNode.checkInvariants, bucket " + bucket);
        blackHeight(root, bucket);
        // The JDK layout usually has the root first, but not always: removal through an iterator
        // calls removeTreeNode(movable = false), which skips moveRootToFront. So that is not checked.
        Set<Node<?, ?>> treeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<TreeNode<?, ?>> stack = new ArrayDeque<>();
        Node<?, ?> e = head;
        TreeNode<?, ?> prev = null;
        for (TreeNode<?, ?> p = root; p != null || !stack.isEmpty(); ) {
            if (p != null) {
                stack.push(p);
                p = p.left;
            } else {
                p = stack.pop();
                check((p.hash & (length - 1)) == bucket, "wrong bucket " + bucket);
                check(prev == null || prev.hash <= p.hash, "hash order, bucket " + bucket);
                if (inOrder) {
                    check(e == p, "chain is not in tree order, bucket " + bucket);
                    check(p.prev == prev, "prev link, bucket " + bucket);
                    e = e.next;
                }
                if (p != head && p instanceof RangeSplitHashMap.RangeTreeNode<?, ?> r) {
                    check(r.binRoot == null, "root pointer on a node that is not the head, bucket " + bucket);
                }
                treeNodes.add(p);
                prev = p;
                p = p.right;
            }
        }
        if (inOrder) {
            check(e == null, "chain longer than tree, bucket " + bucket);
        } else {
            int chain = 0;
            for (Node<?, ?> n = head; n != null; n = n.next, chain++) {
                check(treeNodes.contains(n), "chain node not in tree, bucket " + bucket);
            }
            check(chain == treeNodes.size(), "chain and tree sizes differ, bucket " + bucket);
        }
        return treeNodes.size();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean checkInvariants(TreeNode<?, ?> root) {
        return TreeNode.checkInvariants((TreeNode) root);
    }

    private static int blackHeight(TreeNode<?, ?> p, int bucket) {
        if (p == null) return 0;
        check(!p.red || ((p.left == null || !p.left.red) && (p.right == null || !p.right.red)),
                "red node with red child, bucket " + bucket);
        int l = blackHeight(p.left, bucket), r = blackHeight(p.right, bucket);
        check(l == r, "unequal black heights, bucket " + bucket);
        return l + (p.red ? 0 : 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
