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

## 2.2 Formal Definitions

**Definition 1 (Selection / Filter).** The selection operator $\sigma_{\theta}(R)$ returns all tuples $t \in R$ such that predicate $\theta(t)$ is true. Output schema is identical to the input schema.

**Definition 2 (Equi-Join).** The join $R \bowtie_{R.a = S.b} S$ returns all tuples $(r, s)$ where $r \in R$, $s \in S$, and $r.a = s.b$. Output schema is $\text{schema}(R) \| \text{schema}(S)$ (concatenation).

**Definition 3 (Aggregation).** The aggregation $\gamma_{G, F(A)}(R)$ groups tuples in $R$ by attribute(s) $G$ and applies aggregate function $F$ (MIN, MAX, SUM, COUNT, AVG) to attribute $A$ within each group. Output has one tuple per group.

**Definition 4 (Pipelined vs. Blocking Operator).**
- A *pipelined* (non-blocking) operator can produce its first output tuple without consuming all input (e.g., Filter, Join).
- A *blocking* operator must consume all input before producing any output (e.g., Aggregate, Sort).

---

## 2.3 Relational Operators & Volcano Model

### 1. The Volcano Iterator Model
In the Volcano model, every operator implements a simple iterator interface containing three core methods:
- `open()`: Prepares the operator to emit tuples (allocates memory, opens child iterators, etc.).
- `next()`: Fetches the next tuple in the stream. It calls `next()` on its child operator(s), processes the result, and returns the formatted tuple up the tree.
- `close()`: Releases allocated resources, cleans up state, and closes child iterators.

This model is extremely memory efficient because tuples are pipelined—they are pulled up the tree one-by-one as needed, avoiding the need to materialize entire intermediate relations in memory.

### 2. The Filter Operator ($\sigma$)
- Evaluates a conditional expression (`Predicate`) on each tuple.
- The child is scanned, and only tuples for which `predicate.filter(tuple)` returns `true` are passed up.
- **Selectivity** $\text{sel}(\theta)$: the fraction of input tuples that pass the filter. For a uniform distribution over range $[lo, hi]$:
  - $\sigma_{A=v}$: $\text{sel} = \frac{1}{hi - lo + 1}$
  - $\sigma_{A>v}$: $\text{sel} = \frac{hi - v}{hi - lo + 1}$

### 3. The Join Operator ($\bowtie$)
- Combines records from two child operators (Left and Right) based on a `JoinPredicate`.

#### Join Algorithm I/O Costs

Let $p_L, p_R$ = number of pages in left/right relations, $|L|, |R|$ = number of tuples, $B$ = buffer pool pages available for the join.

| Algorithm | I/O Cost (page reads) | When to use |
|-----------|-----------------------|-------------|
| **Simple Nested Loops (SNL)** | $p_L + |L| \cdot p_R$ | Never in practice — per-tuple inner scan |
| **Page Nested Loops (PNL)** | $p_L + p_L \cdot p_R$ | Small relations, no index |
| **Block Nested Loops (BNL)** | $p_L + \lceil p_L / (B-2) \rceil \cdot p_R$ | Medium relations, enough memory |
| **Index Nested Loops (INL)** | $p_L + |L| \cdot \text{indexCost}$ | Index on inner join key |
| **Sort-Merge Join** | $\text{sort}(L) + \text{sort}(R) + p_L + p_R$ | Both inputs sortable, equality joins |
| **Hash Join** | $3(p_L + p_R)$ | Equality joins, enough memory for partitioning |

**SimpleDB implements Page Nested Loops** (via the `Operator` framework — the inner relation is rewound per outer tuple). This is the simplest algorithm and adequate for small datasets.

#### Worked Example: Nested Loops Join Cost

**Setup:**
- Table `Students`: 1000 tuples, 8-byte tuples → 504 tuples/page → $p_L = 2$ pages
- Table `Enrollments`: 50000 tuples, 12-byte tuples → 341 tuples/page → $p_R = 147$ pages

**Page Nested Loops cost:**

$$\text{Cost} = p_L + p_L \cdot p_R = 2 + 2 \times 147 = 296 \text{ page I/Os}$$

**If we swap outer/inner (Enrollments outer, Students inner):**

$$\text{Cost} = p_R + p_R \cdot p_L = 147 + 147 \times 2 = 441 \text{ page I/Os}$$

**Key insight:** Always put the *smaller* relation as the outer. The optimizer (Lab 3) automates this choice.

### 4. The Aggregate Operator ($\gamma$)
- Performs grouping and mathematical aggregate operations (MIN, MAX, SUM, AVG, COUNT).
- Relies on **Aggregators** (`IntegerAggregator`, `StringAggregator`) to build hash maps mapping Grouping Keys to running aggregate values.
- Unlike other operators, aggregation is a **blocking operator**: it cannot yield its first output tuple until it has completely read all input tuples from its child to construct the final grouped sums/counts.

**Formal cost:**
- **Time:** $O(|R|)$ to scan input + $O(G)$ to iterate groups, where $G$ = number of distinct groups.
- **Space:** $O(G)$ for the hash map.
- **I/O:** Same as scanning the child relation.

**AVG implementation note:** SimpleDB computes AVG as integer division: $\text{AVG} = \lfloor \text{sum} / \text{count} \rfloor$. This matches Java's `int / int` semantics. Real databases compute floating-point averages.

### 5. Data Mutation (Insert and Delete Operators)
- Operations like `INSERT` and `DELETE` are also modeled as operators in the tree.
- They modify the database files on disk via the `BufferPool`:
  - `BufferPool.insertTuple(tid, tableId, tuple)`: Locates a page with free space in the heap file, serializes the tuple, and marks the page as **dirty**.
  - `BufferPool.deleteTuple(tid, tuple)`: Sets the page bitmap slot to 0 and marks the page as **dirty**.
- They return a single tuple containing the count of affected rows (e.g., `[1 row inserted]`).

---

## 2.4 Join Algorithm Trade-offs

### Why does SimpleDB use Nested Loops?

| Consideration | Nested Loops | Sort-Merge | Hash Join |
|---------------|-------------|------------|-----------|
| **Implementation complexity** | Low | Medium | Medium |
| **Supports non-equality predicates** ($<$, $>$, $\neq$) | Yes | Equality only* | Equality only |
| **Memory requirement** | $O(1)$ extra pages | $O(\sqrt{N})$ for external sort | $O(\sqrt{N})$ for partitioning |
| **Best-case I/O** | $p_L + p_R$ (if inner fits in memory) | $3(p_L + p_R)$ | $3(p_L + p_R)$ |
| **Worst-case I/O** | $p_L \cdot p_R$ | $p_L \log p_L + p_R \log p_R$ | $p_L + p_R$ (with enough memory) |

\* Sort-Merge can handle ranges if both inputs are sorted on the join key, but the standard algorithm targets equality.

**When would you choose each?**
- **Nested Loops:** Small tables, inequality predicates, or when one relation fits in the buffer pool.
- **Sort-Merge:** Large tables already sorted (e.g., clustered index), or when output must be sorted.
- **Hash Join:** Large equi-joins where both relations exceed memory — the best general-purpose choice for equality joins.

---

## 2.5 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 13: Query Processing.** Read sections on Operator Evaluation, Join Algorithms (Nested-Loop, Block Nested-Loop, Hash-Join), and Aggregation.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 12: Evaluation of Relational Operators.**
    *   **Chapter 14: A Typical Relational Query Optimizer.**
3.  **Key Paper:**
    *   *Volcano—An Extensible and Parallel Query Evaluation System* by Goetz Graefe (IEEE TKDE 1994).

---

## 2.6 Glossary

| Term | Definition |
|------|-----------|
| **Selection ($\sigma$)** | Filter operator — passes tuples matching a predicate |
| **Projection ($\pi$)** | Removes columns from tuples (not separately implemented in SimpleDB) |
| **Join ($\bowtie$)** | Combines tuples from two relations based on a condition |
| **Selectivity** | Fraction of input tuples that satisfy a predicate (0.0 to 1.0) |
| **Blocking operator** | Must consume all input before producing first output |
| **Pipelined operator** | Can produce output incrementally as input arrives |
| **Volcano model** | Iterator-based execution: `open()` → `next()` → `close()` |
| **Outer relation** | The relation scanned in the outer loop of a nested-loops join |
| **Inner relation** | The relation rescanned for each outer tuple |
| **Rewind** | Resetting an iterator to re-read from the beginning |

---

## 2.7 Practice Coding Exercises

In this chapter, you will implement the following classes in `src/java/simpledb/`:
1.  **`Predicate.java` & `JoinPredicate.java`**: Defining logical comparison conditions.
2.  **`Filter.java` & `Join.java`**: Implementing query execution operators.
3.  **`IntegerAggregator.java` & `StringAggregator.java`**: Aggregation builders.
4.  **`Aggregate.java`**: The aggregate operator executing Volcano iteration over grouped values.
5.  **`Insert.java` & `Delete.java`**: Core mutation engines.
