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

## 5.2 B+ Tree Structure & SimpleDB Page Types

A B+ Tree distinguishes between **Internal nodes** (which store search keys and child page pointers to direct search flow) and **Leaf nodes** (which store the actual data tuples and pointer references to left/right sibling leaf pages).

In SimpleDB, a `BTreeFile` is composed of four specialized page types:
1.  **`BTreeRootPtrPage`:** A singleton page at the very beginning of the file (Page 0) that points to the current root page of the tree and the first header page.
2.  **`BTreeInternalPage`:** Nodes containing search keys and child page pointers (`PageId`).
3.  **`BTreeLeafPage`:** Nodes containing actual data `Tuple` records, as well as page pointers to left/right sibling pages.
4.  **`BTreeHeaderPage`:** Tracks which pages in the binary B+ Tree file are currently active vs. deleted (free list).

---

## 5.3 Core B+ Tree Algorithms

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

```
Page Splitting (Leaf Node):
  Full Page:    [ 1 | 2 | 3 | 4 ]  -- (Insert 5) --> Overflow!
  Split Result: [ 1 | 2 ] <----> [ 3 | 4 | 5 ]  (Key 3 is "Copied Up" to parent)

Page Splitting (Internal Node):
  Full Page:    [ P0 | K1 | P1 | K2 | P2 | K3 | P3 ] -- (Insert K4) --> Overflow!
  Split Result: Left Internal:  [ P0 | K1 | P1 ]
                Right Internal: [ P2 | K3 | P3 | K4 | P4 ]
                Key K2 is "Pushed Up" to parent (removed from child internal pages)
```

### 3. Tuple Deletion, Redistribution & Merging
When deleting a tuple from a leaf page:
1.  Navigate to the leaf page containing the tuple and delete it.
2.  If the page falls below the minimum occupancy capacity ($\text{capacity} / 2$):
    - Look at its left and right siblings.
    - **Redistribution (Stealing):** If a sibling has extra entries, borrow (steal) an entry from it. Adjust the parent key accordingly.
    - **Merging (Coalescing):** If siblings also have minimum occupancy, merge the page with a sibling. This decreases the number of children in the parent node. If the parent falls below capacity, propagate the merge/redistribution recursively up the tree.

---

## 5.4 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 11: Indexing.** Focus on B+ Tree Index Files, B+ Tree Queries, and Updates (Insertion/Deletion algorithms).
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 10: Tree-Structured Indexing.**
    *   **Chapter 11: B+ Tree Indexes.** (Highly recommended for visual illustrations of page splits, merges, and redistributions).
3.  **Classic Research Reference:**
    *   *The Ubiquitous B-Tree* by Douglas Comer (ACM Computing Surveys, 1979).

---

## 5.5 Practice Coding Exercises

In this chapter, you will implement:
1.  **`BTreeFile.findLeafPage()`**: Recursive navigation logic to traverse internal nodes.
2.  **`BTreeFile.splitLeafPage()` & `BTreeFile.splitInternalPage()`**: Implementing key splits and parent push-ups.
3.  **`BTreeFile.mergeLeafPage()` & `BTreeFile.mergeInternalPage()`**: Implementing node coalescing and page cleanup.
4.  **`BTreeFile.stealFromLeafPage()` & `BTreeFile.stealFromInternalPage()`**: Implementing peer-to-peer key redistribution.
