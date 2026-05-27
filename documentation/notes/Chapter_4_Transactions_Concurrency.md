# Chapter 4: Transactions and Concurrency Control

## 4.1 Overview of Transactions

A **Transaction** is a logical unit of database processing that must be executed in its entirety or not at all. To guarantee database integrity under highly concurrent workloads and system crashes, database systems must enforce the **ACID** properties:

- **Atomicity:** All operations in a transaction succeed, or all are undone (all-or-nothing).
- **Consistency:** A transaction transforms the database from one valid consistent state to another.
- **Isolation:** Execution of concurrent transactions does not interfere with each other (appearing as if they executed serially).
- **Durability:** Once a transaction commits, its changes are guaranteed to survive any subsequent system failures.

This chapter focuses on **Isolation**—how the database allows multiple clients to read and write data concurrently without conflicts (like dirty reads, non-repeatable reads, or phantom reads).

---

## 4.2 Formal Definitions

**Definition 1 (Schedule).** A *schedule* $S$ is an interleaving of operations (reads and writes) from a set of concurrent transactions $\{T_1, T_2, \ldots, T_n\}$, preserving the internal order of each transaction.

**Definition 2 (Serial Schedule).** A schedule is *serial* if no interleaving occurs—each transaction runs to completion before the next begins. There are $n!$ possible serial schedules for $n$ transactions.

**Definition 3 (Conflict).** Two operations *conflict* if they are from different transactions, access the same data item, and at least one is a write. The three conflict types are:
- **Read-Write (RW):** $T_i$ reads, then $T_j$ writes the same item (or vice versa).
- **Write-Write (WW):** Both transactions write the same item.

**Definition 4 (Conflict Serializability).** A schedule is *conflict serializable* if it can be transformed into a serial schedule by swapping adjacent non-conflicting operations. Equivalently, the *precedence graph* (a.k.a. *conflict graph*) of the schedule is acyclic.

**Definition 5 (Strict 2PL).** A protocol where (1) a transaction acquires all locks before releasing any, and (2) all locks are held until commit/abort. This guarantees both conflict serializability and *recoverability* (no cascading aborts).

---

## 4.3 Concurrency Control Mechanisms

SimpleDB implements **Strict Two-Phase Locking (Strict 2PL)**, the industry standard for guaranteeing **Conflict Serializability** and preventing cascading aborts (rigorous schedules).

### 1. Shared (S) vs. Exclusive (X) Locks
To read or write data inside a database page, a transaction must first acquire a lock:
- **Shared Lock (S-Lock):** Required for *reading* a page. Multiple transactions can hold S-locks on the same page simultaneously (Read-Read compatibility).
- **Exclusive Lock (X-Lock):** Required for *writing* (modifying) a page. Only one transaction can hold an X-lock on a page. No other transaction can hold S or X locks on that page (Read-Write or Write-Write incompatibility).

```
+------------------------------------------+
|            Lock Compatibility Matrix     |
+------------------------------------------+
|  Requested \ Held |    Shared (S)   |  Exclusive (X)  |
+-------------------+-----------------+-----------------+
|     Shared (S)    |     Granted     |     Blocked     |
|   Exclusive (X)   |     Blocked     |     Blocked     |
+-------------------------------------------------------+
```

### 2. Strict Two-Phase Locking (Strict 2PL) Protocol
Strict 2PL enforces two strict rules:
1.  **Growing Phase:** A transaction can acquire locks but cannot release any locks.
2.  **Shrinking Phase:** All locks acquired by a transaction are held until the transaction **commits** or **aborts**. They are released all at once at the very end.

### 3. Lock Upgrades and the Upgrade Deadlock

A transaction may initially read a page (acquiring an S-lock) and later need to write it (requiring an X-lock). This is called a **lock upgrade** (S → X).

**The classic upgrade deadlock:** If two transactions $T_1$ and $T_2$ both hold S-locks on the same page and both try to upgrade to X-locks, neither can proceed:

```
Time    T1                  T2                  Page P locks
──────────────────────────────────────────────────────────────
t1      S-lock(P) ✓                             S: {T1}
t2                          S-lock(P) ✓         S: {T1, T2}
t3      upgrade to X(P)?    ...                 BLOCKED — T2 holds S
t4      ...                 upgrade to X(P)?    BLOCKED — T1 holds S
                         *** DEADLOCK ***
```

**Solutions:**
- **Detect and abort one:** Use timeout or wait-for graph cycle detection.
- **Atomically upgrade:** Only allow upgrade if the requesting transaction is the sole S-lock holder.
- **Acquire X from the start:** If a transaction knows it will write, request X-lock immediately.

### 4. Lock Manager Design (`LockManager`)
The Lock Manager is a central memory datastructure (usually inside or closely linked to the `BufferPool`) that tracks which transactions hold which locks on which pages.
- It is typically structured as a map: `Map<PageId, List<Lock>>` or `Map<PageId, PageLockInfo>`.
- `getPage(tid, pid, perm)` intercepts every database request. If a transaction `tid` requests a page but lacks the necessary lock:
  - If it is a read request and the page has no exclusive lock, the S-lock is granted immediately.
  - If it is a write request and no other transaction holds locks, the X-lock is granted (or an existing S-lock is upgraded to an X-lock).
  - Otherwise, the calling thread is put to sleep (or blocks) until the lock is released.

### 5. Deadlock Management
When transaction A holds a lock on page 1 and waits for page 2, while transaction B holds a lock on page 2 and waits for page 1, a **Deadlock** occurs. Because neither transaction can proceed, they will wait forever unless the database intervenes.

#### Worked Example: Deadlock

```
Time    T1                  T2                  Locks
──────────────────────────────────────────────────────────────
t1      X-lock(P1) ✓                            P1: X{T1}
t2                          X-lock(P2) ✓        P2: X{T2}
t3      X-lock(P2)?         ...                 T1 BLOCKED on P2
t4      ...                 X-lock(P1)?         T2 BLOCKED on P1
                         *** DEADLOCK ***

Wait-for graph:  T1 → T2 → T1  (cycle detected!)
Resolution: Abort T2 (or T1), releasing its locks.
```

- **Deadlock Detection (Wait-For Graph):** The database maintains a directed graph where nodes are active transactions and edges represent "waits for" relations. It periodically runs cycle-detection (e.g., Depth-First Search). If a cycle is detected, one transaction in the cycle is chosen as a "victim" and is **aborted** (releasing its locks).
- **Deadlock Prevention (Timeout / Priority):** 
  - **Timeout:** SimpleDB implements a simple deadlock prevention strategy where if a thread blocks waiting for a lock for longer than a predefined threshold (e.g., 500ms), it assumes a deadlock occurred, aborts the transaction, and throws a `TransactionAbortedException`.
  - **Wait-Die / Wound-Wait:** Priority-based schemes using transaction timestamps:
    - *Wait-Die:* Older transactions wait for younger; younger ones abort immediately.
    - *Wound-Wait:* Older transactions preempt (wound) younger; younger ones wait.

---

## 4.4 Concurrency Control Trade-offs

### Strict 2PL vs. MVCC vs. OCC

| Property | Strict 2PL | MVCC (Multi-Version) | OCC (Optimistic) |
|----------|-----------|---------------------|-------------------|
| **Lock overhead** | High — every read/write acquires a lock | Low — readers never block writers | None during execution |
| **Readers block writers?** | Yes (S-lock blocks X-lock) | **No** — readers see a snapshot | No |
| **Writers block readers?** | Yes | **No** | No |
| **Deadlocks?** | Yes — must detect/prevent | Rare (write-write only) | None (validation at commit) |
| **Abort rate** | Low (blocks instead of aborting) | Low | High under contention |
| **Storage overhead** | Low | High — multiple versions per tuple | Medium — write sets buffered |
| **Isolation level** | Serializable | Snapshot Isolation (weaker) | Serializable |
| **Used by** | SimpleDB, DB2 | PostgreSQL, MySQL/InnoDB, Oracle | Google Spanner, CockroachDB |

**Why SimpleDB uses Strict 2PL:** Simplest to implement correctly. MVCC requires version chains, garbage collection, and snapshot management. OCC requires validation logic and retry loops. For a teaching database, 2PL provides clear, deterministic behavior.

### Isolation Levels (SQL Standard)

| Level | Dirty Read | Non-Repeatable Read | Phantom Read |
|-------|-----------|-------------------|-------------|
| **Read Uncommitted** | Possible | Possible | Possible |
| **Read Committed** | Prevented | Possible | Possible |
| **Repeatable Read** | Prevented | Prevented | Possible |
| **Serializable** | Prevented | Prevented | Prevented |

SimpleDB targets **Serializable** isolation via Strict 2PL.

---

## 4.5 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 15: Transactions.** Focus on ACID, Serializability, and Schedules.
    *   **Chapter 16: Concurrency Control.** Focus on 2PL, Lock Compatibility, Lock Manager Implementation, and Deadlocks.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 16: Overview of Transaction Management.**
    *   **Chapter 17: Concurrency Control.** (Highly detailed explanations of Lock Manager internals).
3.  **Classic Database Systems Reference Book:**
    *   *Transaction Processing: Concepts and Techniques* by Jim Gray and Andreas Reuter.

---

## 4.6 Glossary

| Term | Definition |
|------|-----------|
| **Transaction** | A logical unit of work that is atomic, consistent, isolated, and durable |
| **Schedule** | An interleaving of operations from concurrent transactions |
| **Conflict serializability** | A schedule equivalent to some serial schedule (acyclic precedence graph) |
| **S-lock (Shared)** | Read lock; compatible with other S-locks |
| **X-lock (Exclusive)** | Write lock; incompatible with all other locks |
| **Lock upgrade** | Converting an S-lock to an X-lock on the same page |
| **Deadlock** | Circular wait among transactions for locks held by each other |
| **Wait-for graph** | Directed graph of transaction dependencies; cycle = deadlock |
| **2PL (Two-Phase Locking)** | Protocol: growing phase (acquire), shrinking phase (release) |
| **Strict 2PL** | Variant of 2PL where all locks held until commit/abort |
| **MVCC** | Multi-Version Concurrency Control — readers see snapshots, never block |
| **OCC** | Optimistic Concurrency Control — validate at commit, retry on conflict |
| **Cascading abort** | When aborting one transaction forces aborting others that read its dirty data |

---

## 4.7 Practice Coding Exercises

In this chapter, you will implement:
1.  **`LockManager.java`**: A custom lock manager tracking S/X lock grants, wait queues, and page conflicts.
2.  **`BufferPool.java` Lock Interception**: Modifying `getPage()` to acquire S/X locks and block when blocked, and `releasePage()` / `transactionComplete()` to release locks upon commit/abort.
3.  **Deadlock Prevention (Timeout)**: Adding timing logic to throw `TransactionAbortedException` when lock acquisition takes too long.
