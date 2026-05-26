# Chapter 6: Crash Recovery (Logging & Recovery)

## 6.1 Overview of Crash Recovery

A database system can fail unexpectedly at any moment (e.g., operating system crash, power failure). Because the buffer pool resides in volatile memory (RAM), a crash wipes out all dirty pages that have not yet been written to disk. The **Recovery Manager** is responsible for ensuring:
1.  **Durability:** All changes made by transactions that successfully **committed** prior to the crash are permanent and survive the crash.
2.  **Atomicity:** All changes made by transactions that were active (but **uncommitted**) at the time of the crash are completely rolled back (undone), leaving no partial writes.

```
       Volatile Memory (RAM)                   Non-Volatile Disk
+-------------------------------+       +-------------------------------+
|         BufferPool            |       |           DbFiles             |
|  [Dirty Page 1] [Page 2]      |       |  [Page 1 (Old)] [Page 2]       |
+-------------------------------+       +-------------------------------+
                                                      ^
                                                      | Writes WAL records
                                                      v
                                        +-------------------------------+
                                        |         WAL LogFile           |
                                        | [Start T1] [Update] [Commit]  |
                                        +-------------------------------+
```

To achieve this without degrading transaction performance, modern databases implement the **Write-Ahead Logging (WAL)** protocol.

---

## 6.2 Core Recovery Concepts

### 1. Buffer Management Policies (STEAL / NO-FORCE)
How the buffer pool interacts with disk updates determines the complexity of recovery:
- **STEAL vs. NO-STEAL:** 
  - **STEAL:** The buffer pool is allowed to evict a dirty page containing changes from an *uncommitted* transaction to make room for other pages. This requires an **UNDO** mechanism to roll back these uncommitted changes if the transaction aborts or the system crashes.
  - **NO-STEAL:** The buffer pool *never* evicts dirty pages of uncommitted transactions. No UNDO is needed, but this severely limits the maximum transaction size to the size of the buffer pool.
- **FORCE vs. NO-FORCE:**
  - **FORCE:** The database forces all dirty pages updated by a transaction to disk *before* committing. This ensures durability without needing a **REDO** log, but disk writes are extremely slow, bottlenecking throughput.
  - **NO-FORCE:** Transactions can commit as soon as their updates are written to a fast, sequential log on disk. The actual dirty pages in the buffer pool are flushed asynchronously later. This requires a **REDO** mechanism to re-apply committed changes that hadn't reached the database files prior to a crash.

SimpleDB and almost all commercial database engines utilize a **STEAL / NO-FORCE** policy to achieve optimal performance, requiring both **REDO** and **UNDO** capabilities.

### 2. The Write-Ahead Logging (WAL) Protocol
The WAL protocol enforces two core rules:
1.  **Rule 1 (Undo rule):** Any dirty page in RAM must not be written to disk until all log records describing the updates on that page have been written and flushed to disk. (Enables STEAL).
2.  **Rule 2 (Redo rule):** A transaction is not considered "committed" until its `COMMIT` log record has been safely written and flushed to disk. (Enables NO-FORCE).

### 3. Log Record Structure
The `LogFile` is a sequential append-only file containing raw binary log records. Each log record has:
- A unique **LSN (Log Sequence Number)**.
- `START <tid>`: Marks the beginning of a transaction.
- `UPDATE <tid>, <pageId>, <offset>, <oldValue>, <newValue>`: Stores the before-image (for UNDO) and after-image (for REDO) of mutated page data.
- `COMMIT <tid>`: Marks the successful completion of a transaction.
- `ABORT <tid>`: Marks a rolled-back transaction.
- `CLR (Compensation Log Record)`: Written during rollback to indicate an undo action was completed (prevents infinite undo loops during crashed rollbacks).

### 4. ARIES Recovery Algorithm
When the database restarts after a crash, it reads the sequential log file from the beginning and performs a three-phase recovery:
1.  **Analysis Phase:** Scans the log forward from the last checkpoint to identify:
    - Which transactions were active at the time of the crash (the **Loser Transactions**).
    - Which dirty pages were in memory.
2.  **Redo Phase:** Scans the log forward starting from the earliest un-flushed update (based on the checkpoint). It re-applies the after-images (`newValue`) of all log records (including those of uncommitted/loser transactions) to restore the database to the exact state it was in at the time of the crash. ("Repeating History").
3.  **Undo Phase:** Scans the log backward from the end. For each update record belonging to a "loser transaction" (active but uncommitted), it re-applies the before-image (`oldValue`) to roll back their changes, writing CLR records to log the rollback.

---

## 6.3 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 17: Recovery System.** Focus on WAL, Steal/No-Force, Shadow Paging, Log-Based Recovery, and ARIES.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 18: Crash Recovery.** (Outstanding, highly detailed walkthrough of the ARIES algorithm, Analysis, Redo, and Undo phases).
3.  **Core Systems Paper:**
    *   *ARIES: A Transaction Recovery Method Supporting Fine-Granularity Locking and Partial Rollbacks Using Write-Ahead Logging* by C. Mohan et al. (ACM TODS 1992).

---

## 6.4 Practice Coding Exercises

In this chapter, you will implement:
1.  **`LogFile.java` Rollback**: Implementing `rollback(tid)` to scan backward and restore pre-transaction page states (UNDO).
2.  **`LogFile.java` Recover**: Implementing `recover()` to parse the binary log upon system startup, re-applying committed updates (REDO) and rolling back active transactions (UNDO).
