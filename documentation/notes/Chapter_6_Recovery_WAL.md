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

## 6.2 Formal Definitions

**Definition 1 (Log Sequence Number / LSN).** An *LSN* is a monotonically increasing identifier assigned to each log record. It serves as the record's unique address within the log file.

**Definition 2 (pageLSN).** Each page in the buffer pool stores a *pageLSN* — the LSN of the most recent log record that modified this page. During Redo, if `pageLSN >= record.LSN`, the update is already on disk and can be skipped.

**Definition 3 (flushedLSN).** The *flushedLSN* is the highest LSN that has been written to the log file on disk. The WAL protocol requires: **a page can be written to disk only if `pageLSN ≤ flushedLSN`**.

**Definition 4 (recLSN).** The *recLSN* (recovery LSN) of a dirty page is the LSN of the earliest log record that made this page dirty since it was last flushed. During Redo, the system can skip all log records with `LSN < recLSN` for this page.

---

## 6.3 Core Recovery Concepts

### 1. Buffer Management Policies (STEAL / NO-FORCE)
How the buffer pool interacts with disk updates determines the complexity of recovery:

| Policy | FORCE | NO-FORCE |
|--------|-------|----------|
| **NO-STEAL** | Simplest: No UNDO, no REDO needed. But terrible performance. | No UNDO needed. REDO needed. (SimpleDB Lab 2 default) |
| **STEAL** | UNDO needed. No REDO needed. | **UNDO + REDO needed.** Best performance. (ARIES / most production DBs) |

- **STEAL:** The buffer pool is allowed to evict a dirty page containing changes from an *uncommitted* transaction. Requires **UNDO**.
- **NO-STEAL:** The buffer pool *never* evicts dirty pages of uncommitted transactions. No UNDO needed, but limits transaction size to buffer pool capacity.
- **FORCE:** All dirty pages flushed to disk before commit. Ensures durability without REDO, but very slow (random I/O at commit time).
- **NO-FORCE:** Commit only requires writing the log record. Pages flushed lazily. Requires **REDO**.

SimpleDB and almost all commercial database engines utilize a **STEAL / NO-FORCE** policy to achieve optimal performance, requiring both **REDO** and **UNDO** capabilities.

### 2. The Write-Ahead Logging (WAL) Protocol
The WAL protocol enforces two core rules:
1.  **Rule 1 (Undo rule):** Before a dirty page is written to disk, all log records describing updates on that page must be flushed to the log. Formally: flush log up to `pageLSN` before writing the page. (Enables STEAL — uncommitted changes can be undone).
2.  **Rule 2 (Redo rule):** A transaction is not considered "committed" until its `COMMIT` log record has been safely written and flushed to disk. (Enables NO-FORCE — committed changes can be redone).

### 3. Log Record Types

| Record Type | Fields | Purpose |
|------------|--------|---------|
| `START <tid>` | Transaction ID | Marks beginning of a transaction |
| `UPDATE <tid>` | tid, pageId, offset, beforeImage, afterImage | Records a page modification (UNDO + REDO data) |
| `COMMIT <tid>` | Transaction ID | Marks successful commit |
| `ABORT <tid>` | Transaction ID | Marks rollback |
| `CLR <tid>` | tid, pageId, undoNextLSN | Compensation record written during undo (prevents re-undoing) |
| `CHECKPOINT` | Active transaction table, dirty page table | Snapshot of system state for faster recovery |

### 4. LSN Plumbing: How the Pieces Connect

```
                       Log File (on disk, sequential)
┌──────┬──────────┬──────────┬──────────┬──────────┬──────┐
│LSN 1 │  LSN 2   │  LSN 3   │  LSN 4   │  LSN 5   │LSN 6 │
│START │ UPDATE   │ UPDATE   │CHECKPOINT│ UPDATE   │COMMIT│
│ T1   │ T1,P1    │ T2,P2    │{T1,T2}   │ T1,P3    │ T1   │
└──────┴──────────┴──────────┴──────────┴──────────┴──────┘
                                  │
                    ┌─────────────┼─────────────┐
                    v             v             v
              Page P1         Page P2        Page P3
           pageLSN=2        pageLSN=3      pageLSN=5
           recLSN=2         recLSN=3       recLSN=5

              flushedLSN = 6 (all records flushed)
```

### 5. Checkpoint Structure

A checkpoint record contains two in-memory tables captured at that instant:

**Transaction Table (ATT — Active Transaction Table):**

| Transaction | Status | lastLSN |
|------------|--------|---------|
| T1 | Running | 5 |
| T2 | Running | 3 |

**Dirty Page Table (DPT):**

| Page | recLSN |
|------|--------|
| P1 | 2 |
| P2 | 3 |
| P3 | 5 |

Checkpoints bound the work needed during recovery: Analysis starts from the checkpoint instead of the beginning of the log.

### 6. ARIES Recovery Algorithm

When the database restarts after a crash, recovery proceeds in three phases:

#### Phase 1: Analysis (forward scan from checkpoint)

Reconstructs the ATT and DPT by scanning forward from the last checkpoint:
- `START <tid>` → add tid to ATT
- `UPDATE <tid, pageId>` → add page to DPT (if not present, set recLSN = this LSN); update tid's lastLSN
- `COMMIT <tid>` → remove tid from ATT
- `ABORT <tid>` → remove tid from ATT

**Output:** The set of *loser transactions* (still in ATT) and the DPT (pages that may need redo).

#### Phase 2: Redo (forward scan from min(recLSN in DPT))

"Repeat history" — reapply ALL updates (even from losers) to restore exact crash-time state:

```
for each UPDATE record with LSN ≥ min(recLSN):
    if page NOT in DPT:           skip (page was clean)
    if record.LSN < page.recLSN:  skip (page already has this update)
    if record.LSN ≤ page.pageLSN: skip (page on disk is already up-to-date)
    else: apply afterImage to page, set pageLSN = record.LSN
```

#### Phase 3: Undo (backward scan from end of log)

Roll back all loser transactions by applying beforeImages:

```
for each loser transaction (in reverse LSN order):
    apply beforeImage of each UPDATE record
    write a CLR record to the log (so this undo is not repeated on re-crash)
```

#### Worked Example: ARIES Recovery Trace

**Log at crash time:**

```
LSN  Record
───  ────────────────────────────────
1    START T1
2    UPDATE T1, P1, old=A, new=B
3    START T2
4    UPDATE T2, P2, old=C, new=D
5    CHECKPOINT {ATT: T1(lastLSN=2), T2(lastLSN=4)} {DPT: P1(recLSN=2), P2(recLSN=4)}
6    UPDATE T1, P1, old=B, new=E
7    COMMIT T1
8    UPDATE T2, P3, old=F, new=G
     *** CRASH ***   (T2 is uncommitted)
```

**Assume on disk:** P1 has pageLSN=2 (LSN 6 not flushed), P2 has pageLSN=4, P3 has pageLSN=0 (never written).

**Phase 1 — Analysis** (scan from checkpoint at LSN 5):
- LSN 6: UPDATE T1,P1 → ATT: T1(lastLSN=6), DPT: P1(recLSN=2) unchanged
- LSN 7: COMMIT T1 → remove T1 from ATT
- LSN 8: UPDATE T2,P3 → ATT: T2(lastLSN=8), DPT: add P3(recLSN=8)
- **Losers:** {T2}
- **DPT:** {P1(recLSN=2), P2(recLSN=4), P3(recLSN=8)}

**Phase 2 — Redo** (scan forward from min(recLSN) = LSN 2):
- LSN 2: UPDATE T1,P1 → pageLSN=2 on disk, record.LSN=2 ≤ pageLSN → **skip**
- LSN 4: UPDATE T2,P2 → pageLSN=4 on disk, record.LSN=4 ≤ pageLSN → **skip**
- LSN 6: UPDATE T1,P1 → pageLSN=2 < 6 → **redo**: P1 = E, pageLSN=6
- LSN 8: UPDATE T2,P3 → pageLSN=0 < 8 → **redo**: P3 = G, pageLSN=8

**Phase 3 — Undo** (loser T2, scan backward):
- LSN 8: UNDO UPDATE T2,P3 → restore P3 = F, write CLR
- LSN 4: UNDO UPDATE T2,P2 → restore P2 = C, write CLR

**Final state:** P1=E (T1 committed), P2=C (T2 undone), P3=F (T2 undone). ✓

---

## 6.4 Recovery Trade-offs

### ARIES vs. Alternative Approaches

| Approach | UNDO? | REDO? | Performance | Complexity | Used By |
|----------|-------|-------|-------------|------------|---------|
| **ARIES (STEAL/NO-FORCE)** | Yes | Yes | Best — async flushes, large txns | High | PostgreSQL, DB2, SQL Server |
| **NO-STEAL/FORCE** | No | No | Poor — sync flush at commit, txn size ≤ buffer | Lowest | SimpleDB Lab 2 (without recovery) |
| **Shadow Paging** | No (copy-on-write) | No | Poor — random I/O, fragmentation | Medium | SQLite (WAL mode uses a hybrid) |
| **Command Logging** | No | Yes (replay commands) | Good for deterministic workloads | Medium | VoltDB, H-Store |

### Why ARIES Dominates

1. **STEAL** allows transactions larger than memory — essential for batch operations.
2. **NO-FORCE** avoids synchronous random writes at commit — only a sequential log write is needed.
3. **Repeating History** simplifies redo logic — no special cases for committed vs. uncommitted.
4. **CLR records** prevent infinite undo loops if the system crashes during recovery.
5. **Fine-granularity locking** — ARIES supports page-level and record-level undo independently.

---

## 6.5 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 17: Recovery System.** Focus on WAL, Steal/No-Force, Shadow Paging, Log-Based Recovery, and ARIES.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 18: Crash Recovery.** (Outstanding, highly detailed walkthrough of the ARIES algorithm, Analysis, Redo, and Undo phases).
3.  **Core Systems Paper:**
    *   *ARIES: A Transaction Recovery Method Supporting Fine-Granularity Locking and Partial Rollbacks Using Write-Ahead Logging* by C. Mohan et al. (ACM TODS 1992).

---

## 6.6 Glossary

| Term | Definition |
|------|-----------|
| **WAL (Write-Ahead Logging)** | Protocol: log must be flushed before dirty page is written to disk |
| **LSN** | Log Sequence Number — unique, monotonically increasing ID for each log record |
| **pageLSN** | LSN of the most recent update applied to a page |
| **flushedLSN** | Highest LSN guaranteed to be on the log disk |
| **recLSN** | Earliest LSN that made a dirty page dirty (since last flush) |
| **Before-image** | Old value of data before an update (used for UNDO) |
| **After-image** | New value of data after an update (used for REDO) |
| **CLR** | Compensation Log Record — logged during undo to prevent re-undoing on re-crash |
| **Checkpoint** | Snapshot of ATT and DPT written to log to bound recovery work |
| **ATT** | Active Transaction Table — tracks running transactions at checkpoint time |
| **DPT** | Dirty Page Table — tracks pages modified but not yet flushed at checkpoint time |
| **Loser transaction** | Transaction that was active (uncommitted) at crash time — must be undone |
| **STEAL** | Buffer pool may evict uncommitted dirty pages → requires UNDO |
| **NO-FORCE** | Pages not forced to disk at commit → requires REDO |
| **Shadow paging** | Copy-on-write recovery — old pages kept as shadows until commit |

---

## 6.7 Practice Coding Exercises

In this chapter, you will implement:
1.  **`LogFile.java` Rollback**: Implementing `rollback(tid)` to scan backward and restore pre-transaction page states (UNDO).
2.  **`LogFile.java` Recover**: Implementing `recover()` to parse the binary log upon system startup, re-applying committed updates (REDO) and rolling back active transactions (UNDO).
