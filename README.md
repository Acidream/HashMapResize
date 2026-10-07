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

Total: 3,994 tests, all passing.

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
| IDENTICAL | 64 | 4.81 ± 0.36 | 4.40 ± 0.07 | 0.82 ± 0.28 | 5.86× |
| IDENTICAL | 1024 | 277 ± 17.6 | 247 ± 13.4 | 4.14 ± 0.60 | 67× |
| IDENTICAL | 16384 | 23 145 ± 543 | 22 589 ± 3 722 | 31.2 ± 7.20 | 742× |
| CLUSTERS_4 | 64 | 82.1 ± 0.81 | 92.1 ± 0.70 | 16.1 ± 0.50 | 5.11× |
| CLUSTERS_4 | 1024 | 2 237 ± 46.9 | 2 204 ± 42.9 | 58.9 ± 2.36 | 38× |
| CLUSTERS_4 | 16384 | 81 900 ± 16 383 | 70 169 ± 11 382 | 163 ± 21.9 | 504× |
| DISTINCT | 64 | 45.9 ± 0.38 | 48.0 ± 0.26 | 49.0 ± 0.49 | 0.94× |
| DISTINCT | 1024 | 1 336 ± 14.0 | 1 306 ± 18.9 | 1 247 ± 40.9 | 1.07× |
| DISTINCT | 16384 | 48 299 ± 3 544 | 73 107 ± 27 341 | 27 092 ± 1 662 | 1.78× |
| RANDOM | 64 | 10.3 ± 0.20 | 9.97 ± 0.23 | 12.8 ± 1.11 | 0.81× |
| RANDOM | 1024 | 304 ± 4.06 | 306 ± 18.8 | 293 ± 3.76 | 1.04× |
| RANDOM | 16384 | 14 998 ± 2 391 | 14 688 ± 1 531 | 14 396 ± 1 310 | 1.04× |

For RANDOM the map is built at its natural size (list bins only), so this row is the ordinary
resize and the same code runs in all three maps. At m = 64 RangeSplitHashMap measured 12.8
against about 10 for the other two, outside the intervals; an earlier run had 11.0 against
10.9, and the larger sizes show no difference. When a whole bin moves to one side, the split
also counts up to 7 nodes to turn a bin that has shrunk to 6 or fewer back into a list, as the
JDK does; that is at most a few ns per bin. Measured in JMH's sample mode, which times each call
separately, because a fresh map has to be built (untimed) before every resize.

### Insert and lookup

<!-- insert-lookup-results -->
Mean µs per operation on all `16 × m` keys, ± JMH's 99.9% confidence interval (5 measurement
iterations). Where the intervals of two maps overlap, their difference is not significant.
"vs HashMap" is HashMap's mean divided by RangeSplitHashMap's (above 1 means RangeSplitHashMap is faster).

#### Insert into a growing map (default constructor, so the time includes every resize)

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 142 ± 28.0 | 128 ± 61.9 | 140 ± 14.8 | 1.02× |
| IDENTICAL | 1024 | 9 113 ± 5 432 | 3 950 ± 836 | 4 208 ± 954 | 2.17× |
| IDENTICAL | 16384 | 315 365 ± 195 967 | 269 842 ± 144 920 | 329 871 ± 516 676 | 0.96× |
| CLUSTERS_4 | 64 | 136 ± 65.1 | 119 ± 28.5 | 155 ± 87.3 | 0.88× |
| CLUSTERS_4 | 1024 | 8 540 ± 8 722 | 3 986 ± 2 245 | 6 302 ± 15 810 | 1.36× |
| CLUSTERS_4 | 16384 | 307 351 ± 147 422 | 238 194 ± 90 356 | 316 912 ± 306 468 | 0.97× |
| DISTINCT | 64 | 36.6 ± 44.0 | 25.6 ± 6.60 | 25.3 ± 13.2 | 1.44× |
| DISTINCT | 1024 | 1 009 ± 424 | 860 ± 146 | 941 ± 210 | 1.07× |
| DISTINCT | 16384 | 100 550 ± 46 358 | 123 754 ± 286 295 | 88 815 ± 44 316 | 1.13× |
| RANDOM | 64 | 25.3 ± 5.88 | 21.4 ± 1.31 | 23.3 ± 4.90 | 1.09× |
| RANDOM | 1024 | 717 ± 282 | 644 ± 104 | 738 ± 271 | 0.97× |
| RANDOM | 16384 | 83 021 ± 100 934 | 68 937 ± 14 857 | 63 271 ± 33 385 | 1.31× |

#### Insert into a fixed 64-bucket map (no resizes; every bin is a tree of about m nodes, even for RANDOM keys)

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 157 ± 191 | 99.8 ± 15.4 | 117 ± 26.0 | 1.35× |
| IDENTICAL | 1024 | 4 161 ± 1 323 | 3 645 ± 1 464 | 3 837 ± 1 049 | 1.08× |
| IDENTICAL | 16384 | 215 081 ± 29 188 | 216 760 ± 37 262 | 208 099 ± 66 652 | 1.03× |
| CLUSTERS_4 | 64 | 147 ± 204 | 109 ± 15.9 | 122 ± 27.1 | 1.20× |
| CLUSTERS_4 | 1024 | 5 431 ± 7 635 | 3 610 ± 691 | 4 283 ± 1 689 | 1.27× |
| CLUSTERS_4 | 16384 | 218 105 ± 39 394 | 225 000 ± 69 082 | 197 683 ± 44 872 | 1.10× |
| DISTINCT | 64 | 106 ± 261 | 57.4 ± 9.69 | 73.4 ± 13.1 | 1.44× |
| DISTINCT | 1024 | 3 376 ± 5 414 | 2 594 ± 859 | 2 429 ± 502 | 1.39× |
| DISTINCT | 16384 | 123 945 ± 8 626 | 140 119 ± 44 527 | 117 956 ± 8 509 | 1.05× |
| RANDOM | 64 | 86.1 ± 53.3 | 51.1 ± 9.93 | 66.0 ± 13.5 | 1.31× |
| RANDOM | 1024 | 2 235 ± 793 | 2 636 ± 1 463 | 2 416 ± 459 | 0.92× |
| RANDOM | 16384 | 118 605 ± 6 657 | 112 800 ± 14 865 | 135 470 ± 20 086 | 0.88× |

#### Get every key (from a filled default map)

| Keys | m | HashMap | JdkHashMapCopy | RangeSplitHashMap | vs HashMap |
|---|---|---|---|---|---|
| IDENTICAL | 64 | 75.3 ± 13.6 | 69.5 ± 8.97 | 73.6 ± 15.9 | 1.02× |
| IDENTICAL | 1024 | 2 597 ± 198 | 3 002 ± 1 798 | 2 780 ± 760 | 0.93× |
| IDENTICAL | 16384 | 155 794 ± 9 456 | 154 226 ± 37 037 | 165 213 ± 39 430 | 0.94× |
| CLUSTERS_4 | 64 | 59.4 ± 9.63 | 53.6 ± 3.39 | 57.1 ± 14.4 | 1.04× |
| CLUSTERS_4 | 1024 | 2 392 ± 441 | 2 304 ± 519 | 2 474 ± 354 | 0.97× |
| CLUSTERS_4 | 16384 | 146 040 ± 7 666 | 141 615 ± 30 164 | 162 423 ± 24 376 | 0.90× |
| DISTINCT | 64 | 3.59 ± 0.67 | 3.64 ± 0.19 | 4.08 ± 0.42 | 0.88× |
| DISTINCT | 1024 | 258 ± 3.83 | 251 ± 10.6 | 279 ± 25.0 | 0.92× |
| DISTINCT | 16384 | 13 606 ± 1 372 | 14 664 ± 4 762 | 19 453 ± 8 720 | 0.70× |
| RANDOM | 64 | 2.92 ± 0.60 | 3.12 ± 1.14 | 2.92 ± 0.21 | 1.00× |
| RANDOM | 1024 | 128 ± 3.36 | 125 ± 8.07 | 132 ± 37.1 | 0.97× |
| RANDOM | 16384 | 7 972 ± 1 293 | 8 028 ± 2 044 | 13 301 ± 9 968 | 0.60× |

How to read it:

- **The RANDOM rows of the growing-map insert and of get show the noise floor.** There, with
  well-spread keys, there are no tree bins, so JdkHashMapCopy and RangeSplitHashMap run exactly the
  same code; their means still differ by up to about 15%, and once by 66% (get, m = 16384:
  13 301 ± 9 968 against 8 028). Note too that the real HashMap and its verbatim copy differ by
  similar amounts.
- **Growing maps (the normal way to fill a map)** show no difference beyond that noise.
- **Inserting into large tree bins without resizes** is where a cost shows: at m = 64
  RangeSplitHashMap is 12–29% slower than JdkHashMapCopy in every row. At m = 1024 and 16384 it
  ranges from 16% faster to 20% slower, inside the intervals. Each tree insert also moves the new
  node to its in-order place in the chain. An earlier run measured up to 30% at m ≤ 1024 and
  12–52% at m = 16384, so treat the size of this cost as uncertain. Before the root pointer was
  added this case was 30–90% slower, because every lookup and insert first climbed from the table
  slot to the root.
- **Lookups** in tree bins (IDENTICAL, CLUSTERS_4) are within 15% of JdkHashMapCopy, with
  overlapping intervals. The DISTINCT keys mostly end up in list bins, where both maps run the
  same code, yet RangeSplitHashMap measured 11–12% slower at m = 64 and 1024 with intervals that
  barely miss each other; at m = 16384 its 0.70× comes with an interval (± 8 720) half the mean.
- **Rows where "vs HashMap" is far from 1 for reasons other than the range split:**
  - insert into a growing map, IDENTICAL and CLUSTERS_4, m = 1024: 2.17× and 1.36×. HashMap
    differs from its own copy (9 113 against 3 950, and 8 540 ± 8 722 against 3 986), so these
    are not real speedups.
  - insert into a fixed map, all m = 64 rows and DISTINCT m = 1024: 1.20–1.44×. HashMap's
    intervals are larger than its means (± 191, ± 204, ± 261, ± 5 414), so a few slow iterations
    inflated them; compare with JdkHashMapCopy there instead.
  - get, RANDOM, m = 16384: 0.60×. No tree bins, so this is the noise described above.
<!-- /insert-lookup-results -->

## Where this matters

With well-distributed hash codes, HashMap almost never builds tree bins (the JDK's own estimate
puts a bin of 8 at about 6 in 100 million), so ordinary maps see no difference. Tree bins appear
with poor or adversarial hash codes: many identical or clustered hashes. That is exactly the case
where this resize goes from O(m log m) to near O(log m), and where the JDK's resize is slowest.

## License

`JdkHashMapCopy.java` and everything under `jdk-tests/` come from OpenJDK and keep their
original license (GPLv2, or GPLv2 with the Classpath Exception).
