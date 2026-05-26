# Chapter 2: Relational Operators & Execution Engine

## 2.1 Overview of Query Execution

Once a query (e.g., `SELECT name, COUNT(*) FROM Users JOIN Sales ON Users.id = Sales.userId WHERE age > 21 GROUP BY name`) is parsed and compiled, it is represented as a tree of relational algebra operators. The **Execution Engine** is responsible for evaluating this tree and generating results.

```
                  +-----------------------------------+
                  |        Aggregate Operator         |  <- Root (e.g. Group-by, COUNT)
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |           Join Operator           |  <- Joins Users and Sales
                  +-----------------------------------+
                                 /     \
                                v       v
            +--------------------+     +--------------------+
            |   Filter Operator  |     |   SeqScan Operator |  <- Right Child (Sales)
            +--------------------+     +--------------------+
                      |
                      v
            +--------------------+
            |  SeqScan Operator  |  <- Left Child (Users)
            +--------------------+
```

SimpleDB uses the **Volcano Iterator Model** (also known as the Pipeline or Iterator model), which is the standard processing model for most commercial RDBMS (PostgreSQL, MySQL, Oracle, SQL Server).

---

## 2.2 Relational Operators & Volcano Model

### 1. The Volcano Iterator Model
In the Volcano model, every operator implements a simple iterator interface containing three core methods:
- `open()`: Prepares the operator to emit tuples (allocates memory, opens child iterators, etc.).
- `next()`: Fetches the next tuple in the stream. It calls `next()` on its child operator(s), processes the result, and returns the formatted tuple up the tree.
- `close()`: Releases allocated resources, cleans up state, and closes child iterators.

This model is extremely memory efficient because tuples are pipelined—they are pulled up the tree one-by-one as needed, avoiding the need to materialize entire intermediate relations in memory.

### 2. The Filter Operator
- Evaluates a conditional expression (`Predicate`) on each tuple.
- The child is scanned, and only tuples for which `predicate.filter(tuple)` returns `true` are passed up.

### 3. The Join Operator
- Combines records from two child operators (Left and Right) based on a `JoinPredicate`.
- **Nested Loop Join:** The most basic join algorithm. For every tuple in the outer (left) relation, we scan the entire inner (right) relation to find matches.
  $$\mathcal{O}(|L| \times |R|)$$ I/O cost.
- **Optimizations:** Block Nested Loop Join, Index Nested Loop Join, or Hash Joins.

### 4. The Aggregate Operator
- Performs grouping and mathematical aggregate operations (MIN, MAX, SUM, AVG, COUNT).
- Relies on **Aggregators** (`IntegerAggregator`, `StringAggregator`) to build hash maps mapping Grouping Keys to running aggregate values.
- Unlike other operators, aggregation is a **blocking operator**: it cannot yield its first output tuple until it has completely read all input tuples from its child to construct the final grouped sums/counts.

### 5. Data Mutation (Insert and Delete Operators)
- Operations like `INSERT` and `DELETE` are also modeled as operators in the tree.
- They modify the database files on disk via the `BufferPool`:
  - `BufferPool.insertTuple(tid, tableId, tuple)`: Locates a page with free space in the heap file, serializes the tuple, and marks the page as **dirty**.
  - `BufferPool.deleteTuple(tid, tuple)`: Sets the page bitmap slot to 0 and marks the page as **dirty**.
- They return a single tuple containing the count of affected rows (e.g., `[1 row inserted]`).

---

## 2.3 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 13: Query Processing.** Read sections on Operator Evaluation, Join Algorithms (Nested-Loop, Block Nested-Loop, Hash-Join), and Aggregation.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 12: Evaluation of Relational Operators.**
    *   **Chapter 14: A Typical Relational Query Optimizer.**
3.  **Key Paper:**
    *   *Volcano—An Extensible and Parallel Query Evaluation System* by Goetz Graefe (IEEE TKDE 1994).

---

## 2.4 Practice Coding Exercises

In this chapter, you will implement the following classes in `src/java/simpledb/`:
1.  **`Predicate.java` & `JoinPredicate.java`**: Defining logical comparison conditions.
2.  **`Filter.java` & `Join.java`**: Implementing query execution operators.
3.  **`IntegerAggregator.java` & `StringAggregator.java`**: Aggregation builders.
4.  **`Aggregate.java`**: The aggregate operator executing Volcano iteration over grouped values.
5.  **`Insert.java` & `Delete.java`**: Core mutation engines.
