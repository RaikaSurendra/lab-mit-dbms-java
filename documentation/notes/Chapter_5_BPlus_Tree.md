# Chapter 5: B+ Tree Indexing

## 5.1 Overview of Relational Indexing

In a database, a **HeapFile** stores tuples in an unordered heap: finding a specific record requires a sequential scan ($\mathcal{O}(N)$ pages read). To support efficient point lookups ($\mathcal{O}(\log N)$ page reads) and range queries (e.g., `WHERE age >= 21 AND age <= 30`), RDBMSs implement **Indexes**.

SimpleDB implements a **B+ Tree Index** (`BTreeFile`), the most widely used index structure in database systems. A B+ Tree is a self-balancing, multi-way search tree that maintains sorted data with a high branching factor, ensuring shallow tree depth.

```
                                  +-------------------+
                                  | Root Pointer Page |
                                  +-------------------+
                                            |
                                            v
                                  +-------------------+
                                  |   Internal Node   |
                                  |  [ K1 | P1 | P2 ]  |
                                  +-------------------+
                                     /             \
                                    v               v
                      +-------------------+   +-------------------+
                      |   Internal Node   |   |     Leaf Node     |
                      |  [ K2 | P3 | P4 ]  |   | [K, Tuple] -> S1  |
                      +-------------------+   +-------------------+
                         /             \
                        v               v
          +-------------------+   +-------------------+
          |     Leaf Node     |   |     Leaf Node     |
          | [K, Tuple] -> S2  |   | [K, Tuple] -> S3  |  <- Sibling linked list (range scans)
          +-------------------+   +-------------------+
```

---

## 5.2 Formal Definitions

**Definition 1 (B+ Tree of Order $d$).** A B+ tree of order $d$ is a balanced search tree where:
- Each internal node has between $d$ and $2d$ keys (except the root, which may have fewer).
- Each internal node with $k$ keys has $k + 1$ child pointers.
- Each leaf node has between $d$ and $2d$ data entries.
- All leaves are at the same depth (perfectly balanced).

**Definition 2 (Height).** For a B+ tree of order $d$ with $N$ keys:

$$h = \mathcal{O}(\log_d N) = \mathcal{O}\left(\frac{\log N}{\log d}\right)$$

Since $d$ is typically large (100–1000 for 4KB pages), trees with millions of entries have height 2–3.

**Definition 3 (Fan-out).** The *fan-out* $F = 2d + 1$ is the maximum number of children per internal node. Higher fan-out → shallower tree → fewer I/Os per lookup.

$$F \approx \frac{\text{page size}}{\text{key size} + \text{pointer size}}$$

For 4KB pages with 4-byte keys and 4-byte pointers: $F \approx 4096 / 8 = 512$.

**Complexity:**

| Operation | I/O Cost | Notes |
|-----------|----------|-------|
| Point lookup | $O(\log_F N)$ | Traverse root → leaf (typically 2–3 pages) |
| Range scan for $K$ results | $O(\log_F N + K/S)$ | Find start leaf, then follow sibling pointers |
| Insert | $O(\log_F N)$ | Plus splits if needed (amortized $O(1)$ splits) |
| Delete | $O(\log_F N)$ | Plus merges/steals if needed |

---

## 5.3 B+ Tree Structure & SimpleDB Page Types

A B+ Tree distinguishes between **Internal nodes** (which store search keys and child page pointers to direct search flow) and **Leaf nodes** (which store the actual data tuples and pointer references to left/right sibling leaf pages).

In SimpleDB, a `BTreeFile` is composed of four specialized page types:
1.  **`BTreeRootPtrPage`:** A singleton page at the very beginning of the file (Page 0) that points to the current root page of the tree and the first header page.
2.  **`BTreeInternalPage`:** Nodes containing search keys and child page pointers (`PageId`).
3.  **`BTreeLeafPage`:** Nodes containing actual data `Tuple` records, as well as page pointers to left/right sibling pages.
4.  **`BTreeHeaderPage`:** Tracks which pages in the binary B+ Tree file are currently active vs. deleted (free list).

---

## 5.4 Core B+ Tree Algorithms

### 1. Tree Search (`findLeafPage`)
To retrieve or insert a record with key `K`, the engine must find the leaf page containing `K`. This is done via `findLeafPage(tid, dirtypages, pid, key)`:
- If `pid` points to a Leaf Page, return it.
- If `pid` points to an Internal Page, iterate through the entries inside the page to find the largest key `Ki` such that `Ki <= key`.
- Follow the corresponding child pointer to the next level and repeat recursively.

### 2. Tuple Insertion & Page Splitting
When inserting a tuple into a `BTreeFile`:
1.  Navigate to the target leaf page using `findLeafPage()`.
2.  If the leaf page has empty slots, insert the tuple in sorted order.
3.  If the leaf page is full, it must be **Split** into two pages:
    - Create a new leaf page.
    - Move the right half of the entries to the new page.
    - **Copy Up:** Send a copy of the smallest key in the new right page up to the parent internal page, along with a pointer to the new page.
    - If the parent internal page is also full, split it recursively by **Pushing Up** the middle key to its parent.

**Critical distinction:**
- **Leaf split → Copy Up:** The split key is *copied* into the parent (it still exists in the leaf).
- **Internal split → Push Up:** The middle key is *moved* into the parent (removed from the child).

#### Worked Example: Inserting Keys into an Order-2 B+ Tree

Insert keys **5, 8, 1, 7, 3, 12, 9, 6** into an initially empty B+ tree with $d = 2$ (max 4 keys per node).

**Step 1–4: Insert 5, 8, 1, 7** — fit in a single leaf:
```
Leaf: [1 | 5 | 7 | 8]  (full, 4 entries = 2d)
```

**Step 5: Insert 3** — leaf is full, must split:
```
Split leaf at median position:
  Left leaf:  [1 | 3]
  Right leaf: [5 | 7 | 8]
  Copy up key 5 to new root:

           [5]              ← Internal (root)
          /    \
    [1|3]      [5|7|8]     ← Leaves (linked: left → right)
```

**Step 6: Insert 12** — goes to right leaf, it has space:
```
           [5]
          /    \
    [1|3]      [5|7|8|12]  (right leaf now full)
```

**Step 7: Insert 9** — right leaf is full, split again:
```
Split right leaf:
  Left:  [5|7]
  Right: [8|9|12]
  Copy up key 8:

           [5 | 8]           ← Internal (root, 2 keys)
          /   |    \
    [1|3]  [5|7]  [8|9|12]   ← Three leaves linked
```

**Step 8: Insert 6** — goes to middle leaf [5|7], fits:
```
           [5 | 8]
          /   |    \
    [1|3] [5|6|7] [8|9|12]
```

### 3. Tuple Deletion, Redistribution & Merging
When deleting a tuple from a leaf page:
1.  Navigate to the leaf page containing the tuple and delete it.
2.  If the page falls below the minimum occupancy capacity ($d$ entries, i.e., $\text{capacity} / 2$):
    - Look at its left and right siblings.
    - **Redistribution (Stealing):** If a sibling has more than $d$ entries, borrow (steal) an entry from it. Adjust the parent key accordingly.
    - **Merging (Coalescing):** If siblings also have minimum occupancy, merge the page with a sibling. This decreases the number of children in the parent node. If the parent falls below capacity, propagate the merge/redistribution recursively up the tree.

#### Worked Example: Deletion with Redistribution

Starting from the tree above, delete key **12**:
```
           [5 | 8]
          /   |    \
    [1|3] [5|6|7] [8|9]     ← right leaf has 2 entries (= d), OK
```

Delete key **9** — right leaf drops below $d = 2$:
```
Right leaf: [8] — only 1 entry, underfull!
Sibling [5|6|7] has 3 entries (> d) → STEAL key 7:

           [5 | 7]          ← parent key updated from 8 to 7
          /   |    \
    [1|3] [5|6]   [7|8]     ← balanced
```

---

## 5.5 Index Trade-offs

### B+ Tree vs. Hash Index vs. Heap Scan

| Property | B+ Tree | Hash Index | Heap Scan |
|----------|---------|-----------|-----------|
| **Equality lookup** | $O(\log_F N)$ — 2-3 page I/Os | $O(1)$ — 1-2 page I/Os | $O(N/S)$ — full scan |
| **Range query** | $O(\log_F N + K/S)$ — excellent | **Not supported** | $O(N/S)$ — full scan |
| **Sorted output** | Free (leaf order) | Not sorted | Requires external sort |
| **Insert cost** | $O(\log_F N)$ amortized | $O(1)$ amortized | $O(1)$ |
| **Delete cost** | $O(\log_F N)$ | $O(1)$ | $O(N/S)$ to find + $O(1)$ |
| **Space overhead** | ~50% of data size (internal nodes) | ~25% (hash buckets) | None |
| **Handles skew?** | Yes (balanced) | Poorly (bucket overflow) | N/A |

**When to use each:**
- **B+ Tree:** Default choice. Supports both equality and range queries. Required for `ORDER BY`, `GROUP BY`, and `BETWEEN` clauses.
- **Hash Index:** Only when range queries are guaranteed unnecessary and you need $O(1)$ lookups (e.g., primary key joins).
- **No Index (Heap Scan):** Small tables, write-heavy workloads where index maintenance overhead exceeds query savings, or full-table analytics.

### Clustered vs. Unclustered Indexes

| Property | Clustered | Unclustered |
|----------|-----------|-------------|
| **Data order** | Tuples on disk sorted by index key | Tuples in heap order (random) |
| **Range scan cost** | $O(\log_F N + K/S)$ — sequential I/O | $O(\log_F N + K)$ — up to 1 random I/O per tuple |
| **Limit** | At most 1 clustered index per table | Multiple allowed |
| **Maintenance** | Expensive (must reorder data on insert) | Cheap (only update index) |

---

## 5.6 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 11: Indexing.** Focus on B+ Tree Index Files, B+ Tree Queries, and Updates (Insertion/Deletion algorithms).
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 10: Tree-Structured Indexing.**
    *   **Chapter 11: B+ Tree Indexes.** (Highly recommended for visual illustrations of page splits, merges, and redistributions).
3.  **Classic Research Reference:**
    *   *The Ubiquitous B-Tree* by Douglas Comer (ACM Computing Surveys, 1979).

---

## 5.7 Glossary

| Term | Definition |
|------|-----------|
| **Order ($d$)** | Min keys per node (except root); max = $2d$ |
| **Fan-out** | Max children per internal node ($2d + 1$) |
| **Internal node** | Non-leaf node storing keys and child pointers |
| **Leaf node** | Bottom-level node storing actual data entries |
| **Sibling pointer** | Link between adjacent leaf nodes enabling range scans |
| **Copy Up** | Leaf split: copy the split key into the parent |
| **Push Up** | Internal split: move the middle key into the parent |
| **Redistribution (Steal)** | Borrow an entry from a sibling to fix underflow |
| **Merge (Coalesce)** | Combine two underflowing siblings into one node |
| **Clustered index** | Data tuples stored in index key order on disk |
| **Unclustered index** | Index leaves point to tuples scattered across the heap |

---

## 5.8 Practice Coding Exercises

In this chapter, you will implement:
1.  **`BTreeFile.findLeafPage()`**: Recursive navigation logic to traverse internal nodes.
2.  **`BTreeFile.splitLeafPage()` & `BTreeFile.splitInternalPage()`**: Implementing key splits and parent push-ups.
3.  **`BTreeFile.mergeLeafPage()` & `BTreeFile.mergeInternalPage()`**: Implementing node coalescing and page cleanup.
4.  **`BTreeFile.stealFromLeafPage()` & `BTreeFile.stealFromInternalPage()`**: Implementing peer-to-peer key redistribution.
