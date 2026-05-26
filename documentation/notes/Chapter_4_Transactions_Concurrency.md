# Chapter 4: Transactions and Concurrency Control

## 4.1 Overview of Transactions

A **Transaction** is a logical unit of database processing that must be executed in its entirety or not at all. To guarantee database integrity under highly concurrent workloads and system crashes, database systems must enforce the **ACID** properties:

- **Atomicity:** All operations in a transaction succeed, or all are undone (all-or-nothing).
- **Consistency:** A transaction transforms the database from one valid consistent state to another.
- **Isolation:** Execution of concurrent transactions does not interfere with each other (appearing as if they executed serially).
- **Durability:** Once a transaction commits, its changes are guaranteed to survive any subsequent system failures.

This chapter focuses on **Isolation**—how the database allows multiple clients to read and write data concurrently without conflicts (like dirty reads, non-repeatable reads, or phantom reads).

---

## 4.2 Concurrency Control Mechanisms

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

### 3. Lock Manager Design (`LockManager`)
The Lock Manager is a central memory datastructure (usually inside or closely linked to the `BufferPool`) that tracks which transactions hold which locks on which pages.
- It is typically structured as a map: `Map<PageId, List<Lock>>` or `Map<PageId, PageLockInfo>`.
- `getPage(tid, pid, perm)` intercepts every database request. If a transaction `tid` requests a page but lacks the necessary lock:
  - If it is a read request and the page has no exclusive lock, the S-lock is granted immediately.
  - If it is a write request and no other transaction holds locks, the X-lock is granted (or an existing S-lock is upgraded to an X-lock).
  - Otherwise, the calling thread is put to sleep (or blocks) until the lock is released.

### 4. Deadlock Management
When transaction A holds a lock on page 1 and waits for page 2, while transaction B holds a lock on page 2 and waits for page 1, a **Deadlock** occurs. Because neither transaction can proceed, they will wait forever unless the database intervenes.
- **Deadlock Detection (Wait-For Graph):** The database maintains a directed graph where nodes are active transactions and edges represent "waits for" relations. It periodically runs cycle-detection (e.g., Depth-First Search). If a cycle is detected, one transaction in the cycle is chosen as a "victim" and is **aborted** (releasing its locks).
- **Deadlock Prevention (Timeout / Priority):** 
  - **Timeout:** SimpleDB implements a simple deadlock prevention strategy where if a thread blocks waiting for a lock for longer than a predefined threshold (e.g., 500ms), it assumes a deadlock occurred, aborts the transaction, and throws a `TransactionAbortedException`.

---

## 4.3 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 15: Transactions.** Focus on ACID, Serializability, and Schedules.
    *   **Chapter 16: Concurrency Control.** Focus on 2PL, Lock Compatibility, Lock Manager Implementation, and Deadlocks.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 16: Overview of Transaction Management.**
    *   **Chapter 17: Concurrency Control.** (Highly detailed explanations of Lock Manager internals).
3.  **Classic Database Systems Reference Book:**
    *   *Transaction Processing: Concepts and Techniques* by Jim Gray and Andreas Reuter.

---

## 4.4 Practice Coding Exercises

In this chapter, you will implement:
1.  **`LockManager.java`**: A custom lock manager tracking S/X lock grants, wait queues, and page conflicts.
2.  **`BufferPool.java` Lock Interception**: Modifying `getPage()` to acquire S/X locks and block when blocked, and `releasePage()` / `transactionComplete()` to release locks upon commit/abort.
3.  **Deadlock Prevention (Timeout)**: Adding timing logic to throw `TransactionAbortedException` when lock acquisition takes too long.
