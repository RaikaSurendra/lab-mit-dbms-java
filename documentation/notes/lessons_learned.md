# Lessons Learned — SimpleDB Labs

A running log of observations, gotchas, and insights discovered while compiling and implementing the labs.
Organized by chapter to match [the course notes](README.md).

---

## Chapter 1: Storage Engine (Lab 1)

*See also: [Chapter_1_Storage_Engine.md](Chapter_1_Storage_Engine.md)*

### 1.1 Build & Compilation

- **Ambiguous class references between packages:**
  `simpledb.common` and `simpledb.storage` both define `Field`, `IntField`, and `StringField`. Using wildcard imports (`import simpledb.common.*`) alongside `import simpledb.storage.*` causes the compiler to report "incompatible types" or "reference to X is ambiguous".
  - **Fix:** Replace wildcard imports with explicit imports for the specific classes needed. In production code (`Utility.java`), use fully qualified names like `simpledb.storage.IntField` where necessary.
  - **Takeaway:** Avoid wildcard imports in Java when multiple packages export identically-named types. Prefer explicit imports — this is also the Google Java Style recommendation.

- **Files affected by the ambiguity fix:**
  - `src/java/simpledb/common/Utility.java` — qualified `IntField` as `simpledb.storage.IntField`
  - `test/simpledb/TestUtil.java` — replaced `import simpledb.common.*` with explicit imports
  - `test/simpledb/systemtest/SystemTestUtil.java` — same treatment

### 1.2 Architecture Observations

- **Duplicate class design (common vs storage):**
  The `simpledb.common` package contains `Field`, `IntField`, `StringField` interfaces/classes that appear to be older stubs or abstractions. The real implementations used by `Tuple`, `HeapPage`, etc. live in `simpledb.storage`. The `common` versions exist likely for backward compatibility or as an interface layer, but in practice the `storage` versions are what gets used everywhere.

- **Test structure follows Java conventions:**
  - Unit tests in `test/simpledb/` — same package as source, tests individual classes in isolation.
  - System tests in `test/simpledb/systemtest/` — end-to-end tests that create real files on disk, run queries through the full pipeline.
  - `ant test` runs unit tests; `ant systemtest` runs system tests. Separation is via build config, not folder renaming.

- **SimpleDbTestBase resets state:** Every test extends `SimpleDbTestBase` which calls `Database.reset()` in `@Before`. This ensures tests are independent — no shared catalog or buffer pool state leaks between tests.

### 1.3 Implementation Notes

- **Tuple & TupleDesc:** Straightforward data containers. `TupleDesc` holds `Type[]` and field names; `Tuple` holds `Field[]` and a `RecordId`. The `equals()` and `hashCode()` methods matter for correctness in later labs.

- **Catalog:** A simple in-memory map of table ID → (DbFile, name, pkeyField). Table IDs are derived from `file.getAbsoluteFile().hashCode()`.

- **BufferPool.getPage():** For Lab 1, just a cache from `PageId` → `Page`. No eviction needed yet. Pages are fetched via `Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid)`.

- **HeapPage slot bitmap:** The header is a byte array where bit `i` indicates whether slot `i` is occupied. Bit ordering is LSB-first within each byte: slot 0 is bit 0 of byte 0, slot 7 is bit 7 of byte 0, slot 8 is bit 0 of byte 1, etc.

- **HeapFile.iterator():** Returns a `DbFileIterator` that lazily reads pages one at a time from the buffer pool. Don't load all pages into memory at once.

- **Tuples per page formula:**
  `floor((pageSize * 8) / (tupleSize * 8 + 1))`
  The `+1` accounts for the 1-bit-per-slot header overhead.

### 1.4 Project Organization

- Markdown lab guides moved to `docs/labs/`, images to `docs/img/`, final project docs to `docs/FinalProjectIdeas/`.
- A `docs/README.md` index links to all documentation.
- `.gitignore` updated with `*.dat`, `*.tmp`, swap files, etc.

### 1.5 Tests — All Passing ✅

| Test | Count |
|------|-------|
| TupleTest | 3/3 |
| TupleDescTest | 6/6 |
| CatalogTest | 5/5 |
| HeapPageIdTest | 4/4 |
| RecordIdTest | 4/4 |
| HeapPageReadTest | 4/4 |
| HeapFileReadTest | 6/6 |
| ScanTest (system) | 4/4 |

---

## Chapter 2: Operators & Execution (Lab 2)

*See also: [Chapter_2_Operators_Execution.md](Chapter_2_Operators_Execution.md)*

### 2.1 Architecture Observations

- **Operator base class vs OpIterator interface:**
  Most query operators extend `Operator` rather than implementing `OpIterator` directly. `Operator` provides generic `hasNext()`/`next()` logic — subclasses only implement `fetchNext()`. This pattern eliminates boilerplate and reduces bugs in iterator state management.

- **Nested loops join must preserve state across `fetchNext()` calls:**
  Since `fetchNext()` returns one tuple at a time, the Join operator must remember which outer tuple (`current`) it's currently matching. The inner relation (`child2`) is rewound each time the outer advances. Forgetting to track `current` is a common bug.

- **Aggregator pattern separates computation from iteration:**
  `IntegerAggregator`/`StringAggregator` accumulate results via `mergeTupleIntoGroup()`. The `Aggregate` operator feeds all child tuples in `open()`, then wraps the result in a `TupleIterator`. This means aggregation is eager (all input consumed before first output), not pipelined.

- **Insert/Delete operators return a count tuple:**
  Both return a single 1-field tuple with the number of affected rows, then `null` on subsequent calls. A `boolean called` flag gates this one-shot behavior.

### 2.2 Implementation Notes

- **IntegerAggregator tracks `[min, max, sum, count]` per group** in a 4-element `int[]`. AVG is computed as `sum/count` (integer division) at iterator time, not during merge. This avoids needing a separate running average.

- **StringAggregator only supports COUNT.** Other operations (SUM, AVG, etc.) don't make sense for strings. The constructor throws `IllegalArgumentException` for non-COUNT ops.

- **HeapFile.insertTuple creates new pages on disk:**
  When all existing pages are full, a new page is created with `HeapPage.createEmptyPageData()`, the tuple is inserted, and the page is written to disk via `writePage()`. The file grows automatically.

- **BufferPool eviction uses NO STEAL policy:**
  Dirty pages are never evicted — only clean pages can be removed. This is critical for transaction safety: if a dirty page were evicted and written to disk, an aborted transaction's changes would persist. The `AbortEvictionTest` validates this behavior (Lab 4 test).

- **`flushPage` vs `evictPage` distinction:**
  `flushPage()` writes a dirty page to disk and marks it clean **but keeps it in the pool**. `evictPage()` removes a page from the pool entirely. `flushAllPages()` calls `flushPage` on every page without removing any. Getting this wrong breaks `ScanTest.cacheTest`.

### 2.3 Tests — All Passing ✅

| Test | Type | Count |
|------|------|-------|
| PredicateTest | unit | 1/1 |
| JoinPredicateTest | unit | 1/1 |
| FilterTest | unit | 6/6 |
| JoinTest | unit | 4/4 |
| IntegerAggregatorTest | unit | 5/5 |
| StringAggregatorTest | unit | 2/2 |
| AggregateTest | unit | 8/8 |
| HeapPageWriteTest | unit | 4/4 |
| HeapFileWriteTest | unit | 2/2 |
| BufferPoolWriteTest | unit | 3/3 |
| InsertTest | unit | 2/2 |
| FilterTest | system | 5/5 |
| JoinTest | system | 3/3 |
| AggregateTest | system | 6/6 |
| InsertTest | system | 4/4 |
| DeleteTest | system | 5/5 |
| EvictionTest | system | 1/1 |

**Note:** `AbortEvictionTest` (system) fails — requires Lab 4 transaction support (`transactionComplete`). Not a Lab 2 requirement.

---

## Chapter 3: Query Optimization (Lab 3)

*See also: [Chapter_3_Query_Optimization.md](Chapter_3_Query_Optimization.md)*

*(To be filled as we progress)*

---

## Chapter 4: Transactions & Concurrency (Lab 4)

*See also: [Chapter_4_Transactions_Concurrency.md](Chapter_4_Transactions_Concurrency.md)*

*(To be filled as we progress)*

---

## Chapter 5: B+ Tree (Lab 5)

*See also: [Chapter_5_BPlus_Tree.md](Chapter_5_BPlus_Tree.md)*

*(To be filled as we progress)*

---

## Chapter 6: Recovery & WAL (Lab 6)

*See also: [Chapter_6_Recovery_WAL.md](Chapter_6_Recovery_WAL.md)*

*(To be filled as we progress)*

---

## General Tips

- **Run individual tests often:** `ant runtest -Dtest=TupleTest` is much faster than `ant test` (which runs everything including later-lab tests that will fail).
- **Read the test before implementing:** The test file shows exactly what API behavior is expected — method names, edge cases, return values.
- **Use `ant runsystest -Dtest=ScanTest`** to run the final Lab 1 system test that exercises the full read pipeline.
- **Lab ↔ Chapter mapping:** Lab 1 = Ch1 (Storage), Lab 2 = Ch2 (Operators), Lab 3 = Ch3 (Optimization), Lab 4 = Ch4 (Transactions), Lab 5 = Ch5 (B+ Tree), Lab 6 = Ch6 (Recovery).
