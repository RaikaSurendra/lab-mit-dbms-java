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

## 1.2 Core Storage Concepts

### 1. Schema & Fields (`Type`, `Field`, `TupleDesc`, `Tuple`)
*   **Fields:** Relational column types are represented by implementations of the `Field` interface:
    *   `IntField`: Wraps a 32-bit (4-byte) integer.
    *   `StringField`: Wraps a fixed-length string (usually 128 bytes limit).
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
*   $$\text{Header Size (bytes)} = \lceil \frac{\text{Number of Slots}}{8} \rceil$$
*   $$\text{Number of Slots} = \lfloor \frac{\text{Page Size}}{\text{Tuple Size} + 0.125} \rfloor$$ (approximate, since header requires 1 bit per slot).

### 4. BufferPool (Memory Manager)
The `BufferPool` acts as the cache manager for all disk pages.
*   Instead of letting operators read files directly, they must request pages via `BufferPool.getPage(tid, pid, perm)`.
*   If the page is in the `BufferPool`, it is returned immediately (cache hit).
*   If not, the `BufferPool` reads it from the corresponding `DbFile` on disk, stores it in RAM, and returns it (cache miss).
*   If the `BufferPool` runs out of space, it must choose a page to evict using a replacement algorithm (like LRU or MRU), flushing the evicted page to disk if it was modified (dirty).

### 5. SeqScan (Access Method)
The first operator in any execution query plan. It sequentially reads all pages in a given table, iterating through all active slots to return the tuples page-by-page.

---

## 1.3 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 12: Data Storage.** Focus on File Structure, Page Layouts, and Slotted-Page Architecture.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 8: Overview of Storage and Indexing.**
    *   **Chapter 9: Storing Data: Disks and Files.** (Highly recommended for deep understanding of page formats and record formats).
3.  **Key Paper:**
    *   *Architecture of a Database System* (Section 2 & 3: Process Models & Storage Manager).

---

## 1.4 Practice Coding Exercises

To start building the disk storage layer, you will implement the following classes in `src/java/simpledb/`:
1.  **`TupleDesc.java` & `Tuple.java`**: Memory representation of records.
2.  **`Catalog.java`**: Storing all table schemas and physical files in a global registry.
3.  **`BufferPool.java`**: Caching and retrieving pages.
4.  **`HeapPage.java` & `HeapFile.java`**: Physical binary serialization and page iteration.
5.  **`SeqScan.java`**: Sequential scanning operator.
