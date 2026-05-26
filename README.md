# SimpleDB — MIT 6.830 Lab Implementations

A complete, incremental implementation of **SimpleDB**, the educational relational database engine from MIT's [6.830 Database Systems](http://dsg.csail.mit.edu/6.830/) course. Built in Java from skeleton code, covering storage, query execution, optimization, transactions, indexing, and recovery.

## Progress

| Lab | Topic | Status | Key Classes |
|-----|-------|--------|-------------|
| **Lab 1** | Storage Engine | Done | `TupleDesc`, `Tuple`, `Catalog`, `BufferPool`, `HeapPage`, `HeapFile`, `SeqScan` |
| **Lab 2** | Operators & Execution | Done | `Predicate`, `Filter`, `Join`, `IntegerAggregator`, `StringAggregator`, `Aggregate`, `Insert`, `Delete` |
| **Lab 3** | Query Optimization | Pending | `IntHistogram`, `TableStats`, `JoinOptimizer` |
| **Lab 4** | Transactions & Locking | Pending | `BufferPool` (locking), `LockManager` |
| **Lab 5** | B+ Tree Indexing | Pending | `BTreeFile`, `BTreeLeafPage`, `BTreeInternalPage` |
| **Lab 6** | Recovery (WAL) | Pending | `LogFile` |

## Architecture

```
┌──────────────────────────────────────────────────┐
│                   SQL Parser                      │
├──────────────────────────────────────────────────┤
│              Query Optimizer (Lab 3)              │
│        Histograms · Cost Model · Join Order       │
├──────────────────────────────────────────────────┤
│            Execution Engine (Lab 2)               │
│    SeqScan · Filter · Join · Aggregate · Insert   │
├──────────────────────────────────────────────────┤
│   Transactions (Lab 4)  │  B+ Tree Index (Lab 5) │
│    Strict 2PL · Locks   │  Search · Split · Merge │
├──────────────────────────────────────────────────┤
│             Storage Engine (Lab 1)                │
│   BufferPool · HeapFile · HeapPage · TupleDesc    │
├──────────────────────────────────────────────────┤
│            Recovery — WAL (Lab 6)                 │
│         LogFile · Checkpoint · ARIES              │
└──────────────────────────────────────────────────┘
```

## Project Structure

```
src/java/simpledb/
├── common/        # Type system, Catalog, Database singleton, Utility
├── storage/       # BufferPool, HeapFile, HeapPage, Tuple, Field types
├── execution/     # Volcano-model operators (Filter, Join, Aggregate, etc.)
├── optimizer/     # Histograms, cost estimation, Selinger join optimizer
├── transaction/   # TransactionId, locking infrastructure
└── index/         # B+ Tree implementation

test/simpledb/           # JUnit unit tests
test/simpledb/systemtest # End-to-end system tests

documentation/notes/     # Study guides, lessons learned, exercise walkthroughs
docs/labs/               # Original MIT lab instructions (lab1–lab6)
```

## Build & Test

Requires **Java 11+** and **Apache Ant**.

```bash
# Compile
ant compile

# Run all unit tests
ant test

# Run all system tests
ant systemtest

# Run a specific unit test
ant runtest -Dtest=FilterTest

# Run a specific system test
ant runsystest -Dtest=JoinTest
```

## Documentation

| Document | Description |
|----------|-------------|
| [Exercise Walkthrough](documentation/notes/exercise_walkthrough.md) | LLD & code-level walkthrough of every exercise with data structures, algorithms, and call flows |
| [Lessons Learned](documentation/notes/lessons_learned.md) | Running log of gotchas, insights, and test results organized by chapter |
| [Study Guide](documentation/notes/README.md) | Chapter-wise learning curriculum with links to detailed notes |
| [Lab Instructions](docs/labs/) | Original MIT lab specs (lab1.md – lab6.md) |

## Git Workflow

Every new lab is developed on a **feature branch** and merged via **pull request** to `main`:

```
main ──●──────────────●──────────── ...
        \            /
         feat/lab3 ─●
```

Branch naming: `feat/lab<N>-<short-description>`

## Credits

Based on the [MIT 6.830 SimpleDB](https://github.com/MIT-DB-Class/simple-db-hw-2021) skeleton code. All implementations are original work for learning purposes.
