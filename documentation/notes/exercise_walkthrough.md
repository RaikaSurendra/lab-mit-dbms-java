# Exercise Walkthrough — LLD & Code-Level Details

A per-exercise deep dive into the low-level design decisions, data structures, and code flow for every implemented class. Use this alongside the [lessons learned](lessons_learned.md) for review.

---

# Lab 1: Storage Engine

## Exercise 1 — TupleDesc & Tuple

### TupleDesc (`src/java/simpledb/storage/TupleDesc.java`)

**Purpose:** Schema descriptor — defines the column types and names for a table.

**Data structure:**
```
TupleDesc
├── List<TDItem> items      // ordered list of (Type, name) pairs
└── TDItem
    ├── Type fieldType      // INT_TYPE (4 bytes) or STRING_TYPE (128+4 bytes)
    └── String fieldName    // nullable
```

**Key design decisions:**
- Uses `ArrayList<TDItem>` rather than parallel arrays for types and names. This keeps each field's metadata grouped together.
- `equals()` compares **only types**, not names. Two schemas with the same column types but different names are considered equal. This matters for insert validation and join output.
- `hashCode()` also depends only on types, consistent with `equals()`.
- `merge(td1, td2)` concatenates two schemas — used by `Join` to produce the output schema.
- `getSize()` sums `Type.getLen()` for each field. This determines the byte footprint of a single tuple on disk.
- `fieldNameToIndex()` does a linear scan — acceptable since schemas are small.

### Tuple (`src/java/simpledb/storage/Tuple.java`)

**Purpose:** A single row of data. Holds field values and a pointer back to its disk location.

**Data structure:**
```
Tuple
├── TupleDesc schema        // the schema this tuple conforms to
├── Field[] fields          // actual column values (IntField or StringField)
└── RecordId recordId       // (pageId, slotNumber) — where this tuple lives on disk
```

**Key design decisions:**
- `fields` is a fixed-size array allocated from `td.numFields()` at construction. Fields start as `null` and are set individually.
- `recordId` is mutable — it gets set when the tuple is read from a page or inserted into one.
- `toString()` outputs tab-separated values (`col1\tcol2\t...`) — this exact format is required by system tests.
- `resetTupleDesc()` changes the schema without reallocating the fields array. Used internally when projecting/aliasing.

---

## Exercise 2 — Catalog

### Catalog (`src/java/simpledb/common/Catalog.java`)

**Purpose:** The system catalog — maps table names/IDs to their files and schemas.

**Data structure:**
```
Catalog
├── ConcurrentHashMap<Integer, Table> tables    // tableId → Table
├── ConcurrentHashMap<String, Integer> nameToId // tableName → tableId
└── Table
    ├── DbFile file         // the backing HeapFile
    ├── String name         // table name
    └── String pkeyField    // primary key column name
```

**Key design decisions:**
- **Table ID** = `file.getAbsoluteFile().hashCode()`. This is deterministic for the same file path, so the same file always maps to the same ID.
- Two maps provide O(1) lookup by either name or ID. `ConcurrentHashMap` for thread safety.
- `addTable()` with a duplicate name overwrites the old entry — last write wins.
- `loadSchema()` parses a text catalog file (`name (col1 type, col2 type, ...)`) and creates HeapFiles.

**Call flow — registering a table:**
```
addTable(heapFile, "students", "id")
  → tables.put(heapFile.getId(), new Table(...))
  → nameToId.put("students", heapFile.getId())
```

---

## Exercise 3 — BufferPool.getPage()

### BufferPool (`src/java/simpledb/storage/BufferPool.java`)

**Purpose:** In-memory page cache sitting between operators and disk files.

**Data structure:**
```
BufferPool
├── int maxPages                                // capacity limit
└── ConcurrentHashMap<PageId, Page> pagesMap    // the cache
```

**getPage() flow:**
```
getPage(tid, pid, perm)
  ├── pagesMap.get(pid) → hit? return immediately
  ├── pagesMap.size() >= maxPages? → evictPage()
  └── Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid)
      → pagesMap.put(pid, newPage)
      → return newPage
```

**Eviction (NO STEAL policy):**
```
evictPage()
  └── for each page in pagesMap:
      ├── page.isDirty() != null → skip (do NOT evict dirty pages)
      └── clean page found → pagesMap.remove(pid), return
```

This is critical: dirty pages stay in memory until explicitly flushed. An aborted transaction's dirty pages are simply discarded, never written to disk.

---

## Exercise 4 — HeapPageId, RecordId, HeapPage

### HeapPageId (`src/java/simpledb/storage/HeapPageId.java`)

**Purpose:** Unique address for a page: which table, which page number.

```
HeapPageId
├── int tableId     // which table (matches DbFile.getId())
└── int pgNo        // 0-indexed page number within that file
```

- `hashCode() = 31 * tableId + pgNo` — ensures unique keys in BufferPool's HashMap.
- `equals()` compares both fields against any `PageId` implementation.

### RecordId (`src/java/simpledb/storage/RecordId.java`)

**Purpose:** Unique address for a tuple: which page, which slot.

```
RecordId
├── PageId pageId   // the page this tuple is on
└── int tupleNo     // 0-indexed slot within that page
```

- Used by `deleteTuple()` to locate the exact slot to clear.
- Set by `HeapPage.insertTuple()` when a tuple is placed into a slot.

### HeapPage (`src/java/simpledb/storage/HeapPage.java`)

**Purpose:** One page of a heap file. Fixed size (default 4096 bytes). Contains a header bitmap + tuple slots.

**Physical layout on disk:**
```
┌─────────────────────────────────────────────────┐
│ Header (⌈numSlots/8⌉ bytes)                     │
│   bit i = 1 → slot i occupied, 0 → empty        │
├─────────────────────────────────────────────────┤
│ Slot 0: [field0][field1]...[fieldN]             │
│ Slot 1: [field0][field1]...[fieldN]             │
│ ...                                              │
│ Slot K: [field0][field1]...[fieldN]             │
├─────────────────────────────────────────────────┤
│ Zero padding to fill page                        │
└─────────────────────────────────────────────────┘
```

**Tuples per page formula:**
```
numSlots = floor((pageSize * 8) / (tupleSize * 8 + 1))
```
The `+1` is for 1 header bit per slot. For a 4096-byte page with 8-byte tuples: `floor(32768 / 65) = 504 slots`.

**Header size:** `ceil(numSlots / 8)` bytes.

**Bit addressing (LSB-first):**
```
Slot 0 → byte 0, bit 0
Slot 1 → byte 0, bit 1
...
Slot 7 → byte 0, bit 7
Slot 8 → byte 1, bit 0
```

```java
isSlotUsed(i):   (header[i/8] >> (i%8)) & 1 == 1
markSlotUsed(i, true):  header[i/8] |=  (1 << (i%8))
markSlotUsed(i, false): header[i/8] &= ~(1 << (i%8))
```

**insertTuple() flow:**
```
insertTuple(t)
  ├── check td.equals(t.getTupleDesc()) → mismatch throws DbException
  ├── linear scan for first empty slot (isSlotUsed(i) == false)
  ├── markSlotUsed(i, true)
  ├── t.setRecordId(new RecordId(pid, i))
  └── tuples[i] = t
```

**deleteTuple() flow:**
```
deleteTuple(t)
  ├── verify t.getRecordId().getPageId() == this.pid
  ├── verify isSlotUsed(slotId) == true
  ├── markSlotUsed(slotId, false)
  └── tuples[slotId] = null
```

**Dirty tracking:**
```
markDirty(true, tid)  → dirty=true, dirtier=tid
markDirty(false, null)→ dirty=false, dirtier=null
isDirty()             → dirty ? dirtier : null
```

---

## Exercise 5 — HeapFile

### HeapFile (`src/java/simpledb/storage/HeapFile.java`)

**Purpose:** On-disk file storing a collection of HeapPages. Implements `DbFile`.

**Data structure:**
```
HeapFile
├── File file       // the .dat file on disk
└── TupleDesc td    // schema for all tuples in this file
```

**ID:** `file.getAbsoluteFile().hashCode()`

**numPages():** `file.length() / BufferPool.getPageSize()`

**readPage(pid) flow:**
```
readPage(pid)
  ├── open RandomAccessFile in "r" mode
  ├── seek to offset = pgNo * pageSize
  ├── readFully(byte[pageSize])
  └── return new HeapPage(pid, data)
```

**writePage(page) flow:**
```
writePage(page)
  ├── open RandomAccessFile in "rw" mode
  ├── seek to offset = pgNo * pageSize
  └── write(page.getPageData())
```

**insertTuple() flow:**
```
insertTuple(tid, t)
  ├── for each existing page (0..numPages-1):
  │   ├── getPage via BufferPool (READ_WRITE)
  │   ├── if page has empty slots → page.insertTuple(t), markDirty, return
  │   └── else continue
  └── no space found:
      ├── create new HeapPage with createEmptyPageData()
      ├── page.insertTuple(t)
      ├── writePage(newPage) → extends the file on disk
      └── markDirty, return
```

**deleteTuple() flow:**
```
deleteTuple(tid, t)
  ├── get page via BufferPool using t.getRecordId().getPageId()
  ├── page.deleteTuple(t)
  └── markDirty, return
```

### HeapFileIterator (inner class)

**Purpose:** Lazy page-at-a-time scan through a HeapFile. Does NOT load all pages into memory.

**State:**
```
HeapFileIterator
├── TransactionId tid
├── int currentPageNo           // which page we're reading
└── Iterator<Tuple> currentTupleIterator  // iterator over current page's tuples
```

**next() flow:**
```
next()
  ├── currentTupleIterator.hasNext()? → return next tuple
  └── advance to next page:
      ├── currentPageNo++
      ├── load page via BufferPool.getPage()
      ├── get page.iterator()
      └── return first tuple from new page
```

---

## Exercise 6 — SeqScan

### SeqScan (`src/java/simpledb/execution/SeqScan.java`)

**Purpose:** Table scan operator. Wraps a `DbFileIterator` and adds alias-prefixed field names.

**Key design:** Implements `OpIterator` directly (not extends `Operator`) because it's a leaf node with no children.

**getTupleDesc()** prefixes each field name with the table alias:
```
Original:  (id:INT, name:STRING)
With alias "t1": (t1.id:INT, t1.name:STRING)
```

This prefix is essential for disambiguating columns in joins.

---

# Lab 2: Operators & Execution

## Exercise 1 — Filter & Join

### Predicate (`src/java/simpledb/execution/Predicate.java`)

**Purpose:** Encapsulates a comparison: "field X op value". Used by Filter.

**Data structure:**
```
Predicate
├── int fieldNumber     // which column of the input tuple to compare
├── Op op               // EQUALS, GREATER_THAN, LESS_THAN, etc.
└── Field operand       // the constant value to compare against
```

**filter(Tuple t):** Delegates to the Field's own compare method:
```java
t.getField(fieldNumber).compare(op, operand)
```

This polymorphism means IntField and StringField each define their own comparison logic.

### JoinPredicate (`src/java/simpledb/execution/JoinPredicate.java`)

**Purpose:** Like Predicate, but compares a field from two different tuples.

```
JoinPredicate
├── int field1      // column index in left tuple
├── Op op           // comparison operator
└── int field2      // column index in right tuple
```

**filter(t1, t2):**
```java
t1.getField(field1).compare(op, t2.getField(field2))
```

### Filter (`src/java/simpledb/execution/Filter.java`)

**Purpose:** σ (selection) operator. Passes through only tuples that match a Predicate.

**Operator tree position:** Has one child (the input relation).

**fetchNext() flow:**
```
fetchNext()
  └── while child.hasNext():
      ├── t = child.next()
      ├── predicate.filter(t) == true? → return t
      └── else skip, continue
  └── return null (exhausted)
```

**open/close pattern:**
```
open()  → super.open() + child.open()    // Operator tracks "open" state
close() → super.close() + child.close()
rewind()→ child.rewind()
```

### Join (`src/java/simpledb/execution/Join.java`)

**Purpose:** ⋈ (join) operator. Simple nested loops join.

**Data structure:**
```
Join
├── JoinPredicate predicate
├── OpIterator child1       // outer (left) relation
├── OpIterator child2       // inner (right) relation
└── Tuple current           // current outer tuple being matched
```

**Algorithm — nested loops with state preservation:**
```
fetchNext()
  └── while current != null OR child1.hasNext():
      ├── if current == null → current = child1.next()
      ├── while child2.hasNext():
      │   ├── t2 = child2.next()
      │   ├── predicate.filter(current, t2)?
      │   │   └── YES: build result tuple (concat fields), return it
      │   └── NO: continue inner loop
      ├── child2.rewind()       // reset inner for next outer tuple
      └── current = null        // advance to next outer tuple
  └── return null (exhausted)
```

**Result tuple construction:**
```
TupleDesc td = merge(child1.td, child2.td)
Tuple result = new Tuple(td)
// copy all fields from current (left), then all from t2 (right)
```

**Why `current` matters:** `fetchNext()` is called once per output tuple. Between calls, we need to remember where we left off in the outer scan and continue the inner scan from that point.

---

## Exercise 2 — Aggregates

### IntegerAggregator (`src/java/simpledb/execution/IntegerAggregator.java`)

**Purpose:** Computes MIN, MAX, SUM, COUNT, or AVG over integer fields, grouped by an optional group-by field.

**Data structure:**
```
IntegerAggregator
├── int gbfield             // group-by field index (-1 = no grouping)
├── Type gbfieldtype        // type of group-by field
├── int afield              // aggregate field index
├── Op what                 // MIN, MAX, SUM, COUNT, AVG
└── LinkedHashMap<Field, int[4]> groups
    └── key: group value (or null for NO_GROUPING)
    └── value: [min, max, sum, count]
```

**Why a 4-element array?** We track all four running values simultaneously. At iterator time, we pick the one that matches the requested op. AVG = `sum/count` (integer division). This avoids needing separate data structures per op.

**mergeTupleIntoGroup() flow:**
```
mergeTupleIntoGroup(tup)
  ├── groupVal = (gbfield == NO_GROUPING) ? null : tup.getField(gbfield)
  ├── val = ((IntField) tup.getField(afield)).getValue()
  ├── first time seeing group?
  │   └── agg = [val, val, val, 1]    // min=max=sum=val, count=1
  └── existing group:
      ├── agg[0] = min(agg[0], val)
      ├── agg[1] = max(agg[1], val)
      ├── agg[2] += val
      └── agg[3] += 1
```

**iterator()** builds result tuples:
- With grouping: `(groupVal, aggregateVal)` — 2-field tuples
- Without grouping: `(aggregateVal)` — 1-field tuples

**LinkedHashMap** preserves insertion order, so groups appear in the order they were first seen.

### StringAggregator (`src/java/simpledb/execution/StringAggregator.java`)

**Purpose:** Only supports COUNT on string fields. SUM/AVG/MIN/MAX don't make sense for strings.

**Data structure:**
```
StringAggregator
└── LinkedHashMap<Field, Integer> counts    // group → count
```

**mergeTupleIntoGroup():** Simply `counts.merge(groupVal, 1, Integer::sum)`.

### Aggregate (`src/java/simpledb/execution/Aggregate.java`)

**Purpose:** The query plan operator that wraps an Aggregator. Extends `Operator`.

**Data structure:**
```
Aggregate
├── OpIterator child        // input relation
├── int afield              // which column to aggregate
├── int gfield              // which column to group by (-1 = none)
├── Aggregator.Op aop       // the operation
└── OpIterator aggIterator  // result iterator (created in open())
```

**open() flow — eager aggregation:**
```
open()
  ├── super.open() + child.open()
  ├── choose aggregator based on child.getTupleDesc().getFieldType(afield):
  │   ├── STRING_TYPE → new StringAggregator(...)
  │   └── INT_TYPE    → new IntegerAggregator(...)
  ├── while child.hasNext():
  │   └── agg.mergeTupleIntoGroup(child.next())    // consume ALL input
  ├── aggIterator = agg.iterator()
  └── aggIterator.open()
```

**This is a blocking operator.** All input must be consumed before the first result is produced. This is fundamentally different from Filter/Join which are pipelined.

---

## Exercise 3 — HeapFile Mutability

### HeapPage.insertTuple / deleteTuple

Already detailed in Lab 1, Exercise 4 above. Lab 2 adds the actual implementation.

### BufferPool.insertTuple / deleteTuple

**insertTuple() flow:**
```
insertTuple(tid, tableId, t)
  ├── file = catalog.getDatabaseFile(tableId)
  ├── dirtyPages = file.insertTuple(tid, t)
  └── for each dirty page:
      ├── p.markDirty(true, tid)
      └── pagesMap.put(p.getId(), p)    // update cache
```

**deleteTuple() flow:**
```
deleteTuple(tid, t)
  ├── file = catalog.getDatabaseFile(t.getRecordId().getPageId().getTableId())
  ├── dirtyPages = file.deleteTuple(tid, t)
  └── for each dirty page:
      ├── p.markDirty(true, tid)
      └── pagesMap.put(p.getId(), p)
```

**Important:** Both methods put the modified page back into `pagesMap`. This ensures subsequent reads see the updated version.

---

## Exercise 4 — Insert & Delete Operators

### Insert (`src/java/simpledb/execution/Insert.java`)

**Purpose:** Reads tuples from child, inserts them into a table, returns count.

**Data structure:**
```
Insert
├── TransactionId tid
├── OpIterator child        // source of tuples to insert
├── int tableId             // target table
└── boolean called          // one-shot guard
```

**fetchNext() flow:**
```
fetchNext()
  ├── called == true? → return null (already produced result)
  ├── called = true
  ├── count = 0
  ├── while child.hasNext():
  │   ├── BufferPool.insertTuple(tid, tableId, child.next())
  │   └── count++
  └── return Tuple(INT_TYPE) with field[0] = count
```

### Delete (`src/java/simpledb/execution/Delete.java`)

**Purpose:** Same pattern as Insert but calls `BufferPool.deleteTuple()`.

Identical structure and flow, except `BufferPool.deleteTuple(tid, child.next())` instead of insert.

---

## Exercise 5 — Page Eviction

### BufferPool eviction, flush, and discard

**Three distinct operations:**

| Method | Writes to disk? | Removes from pool? | Use case |
|--------|----------------|-------------------|----------|
| `flushPage(pid)` | Yes (if dirty) | No | Persist changes, keep cached |
| `evictPage()` | No (skips dirty) | Yes | Make room for new page |
| `discardPage(pid)` | No | Yes | Abort / B+tree cleanup |

**evictPage() — NO STEAL policy:**
```
evictPage()
  └── for each page in pagesMap:
      ├── page.isDirty() != null → skip
      └── clean page → pagesMap.remove(pid), return
  └── throw DbException("All pages dirty")
```

**Why NO STEAL?** If we wrote dirty pages to disk before a transaction commits, and then the transaction aborts, the on-disk data would be corrupted. By never evicting dirty pages, we guarantee that only committed data reaches disk.

**flushAllPages():**
```
flushAllPages()
  └── for each pid in pagesMap.keySet():
      └── flushPage(pid)    // write dirty pages but keep them cached
```

---

# Class Relationship Diagram

```
┌──────────────┐     ┌──────────┐     ┌───────────┐
│   Catalog    │────▶│  DbFile  │────▶│ TupleDesc │
│ (name→table) │     │(HeapFile)│     │  (schema) │
└──────────────┘     └────┬─────┘     └───────────┘
                          │
                    readPage/writePage
                          │
                    ┌─────▼──────┐
                    │ BufferPool │
                    │  (cache)   │
                    └─────┬──────┘
                          │ getPage()
                    ┌─────▼──────┐
                    │  HeapPage  │──── Tuple[] + header bitmap
                    └────────────┘
                          │
              ┌───────────┼───────────┐
              ▼           ▼           ▼
         ┌────────┐  ┌────────┐  ┌──────────┐
         │ SeqScan│  │ Filter │  │   Join   │
         └───┬────┘  └───┬────┘  └──┬───┬───┘
             │           │          │   │
             ▼           ▼          ▼   ▼
         (leaf)     (1 child)   (2 children)
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
         ┌─────────┐ ┌────────┐ ┌────────┐
         │Aggregate│ │ Insert │ │ Delete │
         └─────────┘ └────────┘ └────────┘
```

---

*This document will be extended with Lab 3–6 exercises as we implement them.*
