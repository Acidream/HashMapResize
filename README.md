# Range-split resize for HashMap tree bins

When `java.util.HashMap` doubles its table, every bin is split between its old index `i` and
`i + n`. For a bin that has been turned into a red-black tree, the JDK walks every node and then
rebuilds both halves from scratch: O(m log m) for a bin of m nodes. This project splits the tree
by *ranges* instead, moving whole subtrees without visiting them.

## The idea

In a table of size `n` (a power of two), bucket `i` holds the nodes with `hash & (n - 1) == i`.
On resize to `2n`, a node stays at `i` or moves to `i + n` depending on one bit, `hash & n`.

A tree bin is ordered by hash first. Every node in it shares the same low `log2(n)` bits, so
writing

```
hash = (prefix << log2(n)) | i        where prefix = hash >> log2(n)
```

sorting by hash is sorting by `prefix`, and the destination is `prefix & 1`. In tree order the
nodes therefore come in groups of equal prefix, and **a whole group goes to the same side**.
The group boundaries are the multiples of `n` in hash space.

So instead of iterating over all nodes: walk down the tree carrying the hash range `[lb, ub]` a
subtree must lie in (from the tree's minimum and maximum and the ancestors' hashes). If
`lb >> log2(n) == ub >> log2(n)`, the whole subtree lies inside one bucket boundary range and moves
to one side untouched. Only nodes on the boundaries between groups are visited, and the pieces are
reassembled with join-based red-black tree operations (Blelloch, Ferizovic, Sun: *Just Join for
Parallel Ordered Sets*).

| Hashes in the bin (m nodes, d distinct hashes) | JDK split | Range split |
|---|---|---|
| all identical (bad `hashCode`, HashDoS) | O(m) walk, tree kept | **O(log m)** |
| a few distinct hashes | O(m log m) | **about O(d log(m/d))** |
| all distinct | O(m log m) | O(m) |

Two nodes in the same bucket with different hashes always have different prefixes, so `d` is at
most the number of distinct hash values in the bin. The idea pays off where tree bins actually
occur: keys with colliding or identical hash codes.

## Making it work inside HashMap

A HashMap tree bin also threads its nodes on a `next`/`prev` list, used for iteration, and the
table slot holds the tree's root. Splitting that list would cost O(m) again. So the range-split
map keeps one more invariant:

- **The chain lists the tree's nodes in tree (in-order) order, and the table slot holds the first
  of them** instead of the root. A subtree that moves whole is then one contiguous piece of the
  chain, and the chain is spliced in O(1) per piece.
- **The head node keeps a pointer to the root**, so lookups and inserts, which start from the
  table slot, don't have to climb to the root. Deletion through an iterator does not report the
  new root, so `root()` checks the pointer and falls back to the climb when it is stale.

HashMap does not specify iteration order, so iterating tree bins in hash order is allowed.

## Code

| File | What it is |
|---|---|
| `src/main/java/hmresize/JdkHashMapCopy.java` | JDK 24 `java.util.HashMap`, renamed. The only changes, each marked `hm_resize`, are visibility (what it reached inside `java.util` / `jdk.internal`) and the ability to override five `TreeNode` methods. |
| `src/main/java/hmresize/RangeSplitHashMap.java` | `extends JdkHashMapCopy`, containing only the overrides: a `TreeNode` subclass with the range `split`, the in-order chain upkeep and the root pointer. |
| `src/main/java/hmresize/TreeHashMap.java` | The first standalone prototype (own hash map, own join-based tree). |
| `src/jmh/java/hmresize/*` | JMH benchmarks. |
| `jdk-tests/` | OpenJDK's own `HashMap` and `Map` tests (tag `jdk-24-ga`), verbatim. |

## Tests

```
./gradlew test
```

- **Randomized tests** compare both maps with `java.util.HashMap` over put, remove, compute,
  merge, iterator removal, clone and serialization, with keys that collide heavily. After
  operations and forced resizes they check the red-black rules, the JDK's `TreeNode` invariants,
  bucket placement, the in-order chain and the root pointer.
- **OpenJDK's tests** (25 files from `test/jdk/java/util/HashMap` and `test/jdk/java/util/Map`)
  run against both `JdkHashMapCopy` and `RangeSplitHashMap`. A Gradle task generates a copy per
  class by renaming `HashMap`; the only other edit is 4 lines in `WhiteBoxResizeTest`, which reads
  the private `table` field (listed in `build.gradle.kts`). All pass.
- Deliberately broken versions of the split were caught by the randomized tests every time. The
  OpenJDK suite missed one of them, so it checks compatibility but is not enough on its own to
  verify the split.

Total: 3,991 tests, all passing.

## Benchmarks

```
./gradlew jmh                                   # everything, JSON in build/reports/jmh/
./gradlew jmh -Pjmh="ResizeJmh -p perBin=1024"  # any JMH options
```

Setup: 16 bins of `m` keys each in a 64-bucket table, keys of four shapes:

- **IDENTICAL**: every key in a bin has the same hash.
- **CLUSTERS_4**: 4 distinct hashes per bin.
- **DISTINCT**: random hashes that share only the bucket bits.
- **RANDOM**: ordinary well-spread keys, no tree bins at all.

Implementations: `java.util.HashMap`, the verbatim `JdkHashMapCopy` (shows copying alone costs
nothing), and `RangeSplitHashMap`.

Machine: Intel Core i3-9350KF (4 cores), 16 GB, Windows 10, Temurin JDK 24.0.2, JMH 1.37,
1 fork, 3 × 1 s warmup, 5 × 1 s measurement. This is a desktop, not a quiet benchmark machine;
treat differences under about 20% as noise.

### Resize (one doubling)

Mean µs per call ± JMH's 99.9% confidence interval, over every call sampled in 5 one-second
iterations (tens of thousands of calls for small maps, 20 to 80 at m = 16384). "vs HashMap" is
HashMap's mean divided by RangeSplitHashMap's (above 1 means RangeSplitHashMap is faster).

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 4.76 ± 0.19 | 4.73 ± 0.20 | 0.57 ± 0.08 | 8.33× |
| IDENTICAL | 1024 | 323 ± 23.7 | 291 ± 23.2 | 4.17 ± 0.95 | 78× |
| IDENTICAL | 16384 | 22 648 ± 2 484 | 23 293 ± 1 975 | 24.2 ± 9.27 | 935× |
| CLUSTERS_4 | 64 | 89.9 ± 3.11 | 85.1 ± 4.45 | 16.8 ± 0.39 | 5.34× |
| CLUSTERS_4 | 1024 | 2 444 ± 79.0 | 2 351 ± 60.5 | 66.1 ± 5.95 | 37× |
| CLUSTERS_4 | 16384 | 79 505 ± 8 939 | 73 204 ± 8 998 | 156 ± 18.0 | 511× |
| DISTINCT | 64 | 48.7 ± 0.90 | 49.9 ± 0.50 | 51.5 ± 1.06 | 0.95× |
| DISTINCT | 1024 | 1 497 ± 122 | 1 504 ± 97.8 | 1 292 ± 36.0 | 1.16× |
| DISTINCT | 16384 | 53 674 ± 4 518 | 47 810 ± 3 771 | 27 284 ± 2 004 | 1.97× |
| RANDOM | 64 | 12.5 ± 0.50 | 10.9 ± 0.24 | 11.0 ± 0.32 | 1.13× |
| RANDOM | 1024 | 354 ± 47.6 | 317 ± 6.08 | 308 ± 4.95 | 1.15× |
| RANDOM | 16384 | 14 980 ± 969 | 15 406 ± 910 | 15 645 ± 2 255 | 0.96× |

For RANDOM the map is built at its natural size (list bins only), so this row is the ordinary
resize and shows it is unaffected. Measured in JMH's sample mode, which times each call
separately, because a fresh map has to be built (untimed) before every resize.

### Insert and lookup

<!-- insert-lookup-results -->
Mean µs per operation on all `16 × m` keys, ± JMH's 99.9% confidence interval (5 measurement
iterations). Where the intervals of two maps overlap, their difference is not significant.
"vs HashMap" is HashMap's mean divided by RangeSplitHashMap's (above 1 means RangeSplitHashMap is faster).

#### Insert into a growing map (default constructor, so the time includes every resize)

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 133 ± 32.1 | 112 ± 11.5 | 121 ± 8.45 | 1.10× |
| IDENTICAL | 1024 | 4 120 ± 963 | 5 197 ± 2 588 | 3 734 ± 592 | 1.10× |
| IDENTICAL | 16384 | 235 593 ± 48 655 | 249 815 ± 44 480 | 249 586 ± 113 858 | 0.94× |
| CLUSTERS_4 | 64 | 129 ± 8.83 | 109 ± 10.3 | 130 ± 15.7 | 0.99× |
| CLUSTERS_4 | 1024 | 3 676 ± 1 143 | 3 741 ± 1 140 | 3 697 ± 505 | 0.99× |
| CLUSTERS_4 | 16384 | 229 518 ± 39 202 | 239 831 ± 108 857 | 231 120 ± 74 372 | 0.99× |
| DISTINCT | 64 | 36.3 ± 2.25 | 24.7 ± 17.3 | 30.8 ± 9.83 | 1.18× |
| DISTINCT | 1024 | 817 ± 159 | 907 ± 280 | 913 ± 407 | 0.90× |
| DISTINCT | 16384 | 62 680 ± 13 537 | 84 677 ± 21 977 | 83 221 ± 26 660 | 0.75× |
| RANDOM | 64 | 21.6 ± 3.04 | 20.5 ± 6.62 | 24.1 ± 6.14 | 0.90× |
| RANDOM | 1024 | 639 ± 117 | 727 ± 254 | 635 ± 152 | 1.01× |
| RANDOM | 16384 | 54 134 ± 22 337 | 62 305 ± 12 909 | 53 775 ± 28 361 | 1.01× |

#### Insert into a fixed 64-bucket map (no resizes; every bin is a tree of about m nodes, even for RANDOM keys)

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 109 ± 11.5 | 123 ± 70.3 | 116 ± 21.7 | 0.94× |
| IDENTICAL | 1024 | 3 917 ± 1 900 | 3 852 ± 1 717 | 4 082 ± 1 128 | 0.96× |
| IDENTICAL | 16384 | 183 283 ± 24 960 | 194 644 ± 27 912 | 234 443 ± 71 645 | 0.78× |
| CLUSTERS_4 | 64 | 107 ± 18.1 | 111 ± 43.4 | 115 ± 11.2 | 0.92× |
| CLUSTERS_4 | 1024 | 3 626 ± 462 | 3 981 ± 1 943 | 4 022 ± 797 | 0.90× |
| CLUSTERS_4 | 16384 | 199 316 ± 64 257 | 187 413 ± 25 759 | 246 724 ± 69 474 | 0.81× |
| DISTINCT | 64 | 61.4 ± 7.18 | 65.7 ± 12.7 | 77.1 ± 20.3 | 0.80× |
| DISTINCT | 1024 | 2 362 ± 283 | 2 469 ± 531 | 3 093 ± 1 388 | 0.76× |
| DISTINCT | 16384 | 189 030 ± 162 815 | 105 065 ± 14 343 | 117 815 ± 16 917 | 1.60× |
| RANDOM | 64 | 56.5 ± 10.7 | 54.4 ± 16.6 | 70.3 ± 30.3 | 0.80× |
| RANDOM | 1024 | 2 079 ± 330 | 2 310 ± 913 | 2 622 ± 1 073 | 0.79× |
| RANDOM | 16384 | 144 124 ± 24 169 | 110 544 ± 9 352 | 167 905 ± 73 599 | 0.86× |

#### Get every key (from a filled default map)

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 86.5 ± 28.8 | 72.8 ± 8.88 | 74.2 ± 7.62 | 1.17× |
| IDENTICAL | 1024 | 2 836 ± 632 | 2 705 ± 178 | 2 816 ± 428 | 1.01× |
| IDENTICAL | 16384 | 245 580 ± 65 474 | 133 646 ± 25 942 | 177 174 ± 32 363 | 1.39× |
| CLUSTERS_4 | 64 | 72.9 ± 21.7 | 62.6 ± 30.4 | 56.5 ± 10.3 | 1.29× |
| CLUSTERS_4 | 1024 | 2 964 ± 1 523 | 2 345 ± 292 | 2 839 ± 2 966 | 1.04× |
| CLUSTERS_4 | 16384 | 167 194 ± 78 498 | 151 043 ± 45 589 | 175 662 ± 17 671 | 0.95× |
| DISTINCT | 64 | 5.12 ± 2.15 | 5.01 ± 3.14 | 7.08 ± 4.80 | 0.72× |
| DISTINCT | 1024 | 316 ± 130 | 274 ± 35.6 | 390 ± 293 | 0.81× |
| DISTINCT | 16384 | 15 628 ± 5 146 | 15 529 ± 4 919 | 15 198 ± 2 076 | 1.03× |
| RANDOM | 64 | 3.48 ± 1.18 | 3.27 ± 1.25 | 2.79 ± 0.05 | 1.25× |
| RANDOM | 1024 | 176 ± 71.0 | 129 ± 30.2 | 141 ± 33.7 | 1.25× |
| RANDOM | 16384 | 10 504 ± 5 721 | 9 908 ± 4 659 | 9 072 ± 781 | 1.16× |

How to read it:

- **The RANDOM rows of the growing-map insert and of get show the noise floor.** There, with
  well-spread keys, there are no tree bins, so JdkHashMapCopy and RangeSplitHashMap run exactly the
  same code; their means still differ by up to about 20%, with wide intervals. Note too that the
  real HashMap and its verbatim copy differ by similar amounts.
- **Growing maps (the normal way to fill a map)** show no difference beyond that noise.
- **Inserting into large tree bins without resizes** is the one consistent cost: RangeSplitHashMap
  is up to about 30% slower than JdkHashMapCopy at m ≤ 1024, and 12–52% at m = 16384, where
  the intervals are widest. Each tree insert also moves the new node to its in-order place in the
  chain. Before the root pointer was added this case was 30–90% slower, because every lookup and
  insert first climbed from the table slot to the root.
- **Lookups** show no consistent difference. The DISTINCT keys mostly end up in list bins, where
  JdkHashMapCopy and RangeSplitHashMap run the same code; their 0.72× and 0.81× at m = 64 and
  1024 come with intervals (± 4.80, ± 293) as large as the gap.
- **Rows where "vs HashMap" is far from 1 for reasons other than the range split:**
  - insert into a growing map, DISTINCT, m = 16384: 0.75×. JdkHashMapCopy is just as slow
    (84 677 against RangeSplitHashMap's 83 221), so the gap is between the JDK's own class and
    its copy, not the range split.
  - insert into a fixed map, DISTINCT, m = 16384: 1.60×. HashMap's interval (± 162 815) is
    nearly as large as its mean, so a few slow iterations inflated it; this is not a real speedup.
  - get, IDENTICAL, m = 16384: 1.39×. HashMap again differs from its own copy (245 580 against
    133 646), so this is not a real speedup either.
<!-- /insert-lookup-results -->

## Where this matters

With well-distributed hash codes, HashMap almost never builds tree bins (the JDK's own estimate
puts a bin of 8 at about 6 in 100 million), so ordinary maps see no difference. Tree bins appear
with poor or adversarial hash codes: many identical or clustered hashes. That is exactly the case
where this resize goes from O(m log m) to near O(log m), and where the JDK's resize is slowest.

## License

`JdkHashMapCopy.java` and everything under `jdk-tests/` come from OpenJDK and keep their
original license (GPLv2, or GPLv2 with the Classpath Exception).
