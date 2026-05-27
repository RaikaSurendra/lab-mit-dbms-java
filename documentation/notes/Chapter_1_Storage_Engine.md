# Chapter 1: Disk-Based Storage Engine

## 1.1 Overview & Architecture

A database management system (DBMS) must persist data reliably on non-volatile media (spinning disks or SSDs) while providing high-performance access in memory (RAM). Because disks are slow and addressable in blocks, whereas applications operate on individual records, the **Storage Engine** serves as the translation layer between byte blocks on disk and tuple objects in memory.

```
+-------------------------------------------------------------+
|                       Application Layer                     |
|                   (Operating on Java Tuples)                |
+-------------------------------------------------------------+
                               | Tuple / TupleDesc
                               v
+-------------------------------------------------------------+
|                         BufferPool                          |
|         (Caches Fixed-Size Pages in RAM; e.g. 4KB)           |
+-------------------------------------------------------------+
                               | Page / PageId (Binary Bytes)
                               v
+-------------------------------------------------------------+
|                          Disk / SSD                         |
|        (DbFiles partitioned into raw fixed-size blocks)      |
+-------------------------------------------------------------+
```

---

## 1.2 Formal Definitions

**Definition 1 (Relation).** A *relation* $R$ is a subset of the Cartesian product $D_1 \times D_2 \times \cdots \times D_n$, where each $D_i$ is a *domain* (a set of permissible values). Each element of $R$ is called a *tuple* (or record).

**Definition 2 (Schema / TupleDesc).** A *schema* $S = \langle (A_1 : T_1), (A_2 : T_2), \ldots, (A_n : T_n) \rangle$ defines the structure of a relation, where $A_i$ is an *attribute name* and $T_i \in \{\text{INT}, \text{STRING}\}$ is its *type*. In SimpleDB, schemas are represented by `TupleDesc`.

**Definition 3 (Page).** A *page* is a fixed-size block of $P$ bytes (default $P = 4096$) that serves as the unit of I/O transfer between disk and memory. Pages are addressed by a `PageId = (tableId, pageNumber)`.

**Definition 4 (Heap File).** A *heap file* is an unordered collection of pages. Tuples are stored in the first available slot. Insertion is $O(1)$ (append), but search requires a full scan: $O(N/P)$ page reads, where $N$ is the total number of tuples.

---

## 1.3 Core Storage Concepts

### 1. Schema & Fields (`Type`, `Field`, `TupleDesc`, `Tuple`)
*   **Fields:** Relational column types are represented by implementations of the `Field` interface:
    *   `IntField`: Wraps a 32-bit (4-byte) integer.
    *   `StringField`: Wraps a fixed-length string (4-byte length prefix + 128 bytes payload = 132 bytes).
*   **TupleDesc (Schema):** Defines the structure of a row. It is an array of `Type` objects (e.g., `[INT, STRING]`) and optional field names (e.g., `["id", "name"]`).
*   **Tuple (Record):** A single record consisting of a series of `Field` objects, conforming to a specific `TupleDesc`.

### 2. Disk Storage (`PageId`, `RecordId`, `Page`, `DbFile`)
To minimize disk I/O cost (which is extremely high compared to RAM), the database partitions both memory and disk into uniform, fixed-sized chunks called **Pages** (often 4KB or 8KB; SimpleDB defaults to 4096 bytes).
*   **PageId:** Uniquely identifies a page in a database table. Typically consists of a `TableId` and a `PageNumber`.
*   **RecordId:** Uniquely identifies a tuple by storing its `PageId` and `SlotNumber` inside that page.
*   **Page:** The interface representing the unit of data loaded into memory. In SimpleDB, `HeapPage` implements this and contains a binary header (a bitmap of which slots are occupied) followed by the actual serialized bytes of the tuples.
*   **DbFile:** Represents the physical file on disk. In SimpleDB, `HeapFile` implements this. A `HeapFile` consists of pages laid out consecutively in a physical file. It must support:
    *   Reading pages by their page number.
    *   Writing dirty pages back to disk.
    *   Adding and deleting tuples from the file.

### 3. Slotted-Page Format (Slotted Bitmap)
A heap page is organized using a slotted-page layout where the page header contains a bitmap indicating which slots are active (1) or free (0).

$$\text{numSlots} = \left\lfloor \frac{P \times 8}{T \times 8 + 1} \right\rfloor$$

$$\text{headerSize} = \left\lceil \frac{\text{numSlots}}{8} \right\rceil \text{ bytes}$$

where $P$ = page size in bytes and $T$ = tuple size in bytes. The denominator $T \times 8 + 1$ accounts for $T$ bytes of tuple data (8 bits each) plus 1 header bit per slot.

#### Worked Example: Page Layout Calculation

Consider a table `Students(id INT, gpa INT)`:
- Tuple size: $T = 4 + 4 = 8$ bytes
- Page size: $P = 4096$ bytes

$$\text{numSlots} = \left\lfloor \frac{4096 \times 8}{8 \times 8 + 1} \right\rfloor = \left\lfloor \frac{32768}{65} \right\rfloor = 504$$

$$\text{headerSize} = \left\lceil \frac{504}{8} \right\rceil = 63 \text{ bytes}$$

**Physical layout of the 4096-byte page:**

```
Byte 0                                                    Byte 4095
┌────────────────┬──────────┬──────────┬─────┬──────────┬─────────┐
│  Header (63B)  │ Slot 0   │ Slot 1   │ ... │ Slot 503 │ Pad (1B)│
│  504 bits      │  (8B)    │  (8B)    │     │  (8B)    │         │
└────────────────┴──────────┴──────────┴─────┴──────────┴─────────┘
                  ◄─────────── 504 × 8 = 4032 bytes ──────────────►

Verification: 63 + 4032 + 1 = 4096 ✓
```

**Header bitmap detail (first 2 bytes, slots 0–15):**

```
Byte 0: [s0 s1 s2 s3 s4 s5 s6 s7]   (LSB = slot 0)
Byte 1: [s8 s9 s10 s11 s12 s13 s14 s15]

Example: slots 0,1,3,8 occupied, rest empty
  Byte 0 = 0b00001011 = 0x0B
  Byte 1 = 0b00000001 = 0x01
```

### 4. BufferPool (Memory Manager)
The `BufferPool` acts as the cache manager for all disk pages.
*   Instead of letting operators read files directly, they must request pages via `BufferPool.getPage(tid, pid, perm)`.
*   If the page is in the `BufferPool`, it is returned immediately (cache hit).
*   If not, the `BufferPool` reads it from the corresponding `DbFile` on disk, stores it in RAM, and returns it (cache miss).
*   If the `BufferPool` runs out of space, it must choose a page to evict using a replacement algorithm (like LRU or MRU), flushing the evicted page to disk if it was modified (dirty).

#### I/O Cost Model

For a heap file with $N$ tuples and $P$-byte pages holding $S$ tuples each, the number of pages is:

$$\text{numPages} = \left\lceil \frac{N}{S} \right\rceil$$

| Operation | I/O Cost (pages) |
|-----------|------------------|
| Full table scan | $\text{numPages}$ |
| Point lookup (no index) | $\text{numPages}$ (worst case) |
| Insert (append) | 2 (read last page + write) |
| Delete by RecordId | 2 (read page + write) |

### 5. SeqScan (Access Method)
The first operator in any execution query plan. It sequentially reads all pages in a given table, iterating through all active slots to return the tuples page-by-page.

---

## 1.4 File Organization Trade-offs

| Property | Heap File | Sorted File | Hashed File |
|----------|-----------|-------------|-------------|
| **Insert** | $O(1)$ — append to end | $O(N)$ — find position, shift | $O(1)$ — hash to bucket |
| **Delete** | $O(N)$ — scan to find | $O(N)$ — scan + shift | $O(1)$ — hash to bucket |
| **Equality search** | $O(N)$ — full scan | $O(\log N)$ — binary search | $O(1)$ — hash lookup |
| **Range search** | $O(N)$ — full scan | $O(\log N + K)$ — binary + scan | $O(N)$ — full scan |
| **Used by SimpleDB?** | Yes (`HeapFile`) | No | No |

SimpleDB uses heap files because they are the simplest to implement and have optimal insert performance. The cost of full scans is mitigated by the BufferPool cache and (in later labs) by B+ tree indexes for selective queries.

---

## 1.5 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 12: Data Storage.** Focus on File Structure, Page Layouts, and Slotted-Page Architecture.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 8: Overview of Storage and Indexing.**
    *   **Chapter 9: Storing Data: Disks and Files.** (Highly recommended for deep understanding of page formats and record formats).
3.  **Key Paper:**
    *   *Architecture of a Database System* (Section 2 & 3: Process Models & Storage Manager).

---

## 1.6 Glossary

| Term | Definition |
|------|-----------|
| **Relation** | A table; a set of tuples conforming to a common schema |
| **Tuple** | A single row/record in a relation |
| **Schema (TupleDesc)** | The ordered list of (name, type) pairs defining a relation's structure |
| **Page** | Fixed-size block (4096B default) — the unit of disk I/O |
| **Slot** | A position within a page that can hold one tuple |
| **Header bitmap** | Per-page bit array tracking which slots are occupied |
| **BufferPool** | In-memory cache of recently accessed pages |
| **Dirty page** | A page modified in memory but not yet written to disk |
| **Heap file** | Unordered collection of pages; tuples go in the first free slot |
| **RecordId** | Address of a tuple: (PageId, slotNumber) |
| **SeqScan** | Sequential scan operator — reads every page in a table |

---

## 1.7 Practice Coding Exercises

To start building the disk storage layer, you will implement the following classes in `src/java/simpledb/`:
1.  **`TupleDesc.java` & `Tuple.java`**: Memory representation of records.
2.  **`Catalog.java`**: Storing all table schemas and physical files in a global registry.
3.  **`BufferPool.java`**: Caching and retrieving pages.
4.  **`HeapPage.java` & `HeapFile.java`**: Physical binary serialization and page iteration.
5.  **`SeqScan.java`**: Sequential scanning operator.
