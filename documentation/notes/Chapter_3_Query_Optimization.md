# Chapter 3: Cost-Based Query Optimizer

## 3.1 Overview of Query Optimization

Writing a query in SQL is declarative: you specify *what* data you want, not *how* to get it. A query optimizer is the brain of an RDBMS. It takes a logical relational algebra expression and finds the most efficient physical execution plan (the lowest-cost order of joins, index scans, and scan types) to compute the results.

```
       +------------------------------------+
       |             SQL Query              |  (Declarative)
       +------------------------------------+
                         |
                         v
       +------------------------------------+
       |          Parser & Binder           |
       +------------------------------------+
                         |
                         v
       +------------------------------------+
       |      Logical Query Plan Tree       |
       +------------------------------------+
                         |
                         v
       +------------------------------------+
       |          Query Optimizer           |  <- Estimates Costs & Orders Joins
       +------------------------------------+
                         |
                         v
       +------------------------------------+
       |      Physical Execution Plan       |  (Procedural / Executable)
       +------------------------------------+
```

Commercial database systems utilize **Cost-Based Optimizers (CBO)**, which use mathematical formulas to calculate the estimated CPU and I/O cost of each possible plan.

---

## 3.2 Core Optimizer Concepts

### 1. Table Statistics & Histograms (`IntHistogram`, `StringHistogram`)
To calculate plan costs, the optimizer must estimate the number of tuples that will satisfy a given operator (known as **selectivity**). It keeps track of the data distribution of each table's columns using **Histograms**.
- **Equal-Width Histograms:** The range of values in a column is divided into a fixed number of bins (buckets). Each bin stores the count of occurrences of values falling in its sub-range.
- **Selectivity Estimation:**
  - For `col = value`: Selectivity is calculated as the height of the bucket containing `value` divided by the total number of records.
  - For `col > value`: Selectivity is calculated as the sum of all elements in higher buckets plus the fraction of the current bucket representing values larger than `value`, divided by the total number of records.
- **`TableStats`:** Scans the table once at startup, building histograms for all fields to maintain table size, number of pages, and value distributions.

### 2. Selinger Join Optimization (`JoinOptimizer`)
For a join of $N$ tables, there are an exponential number of possible join orders (Catalan number sequences).
- **Left-Deep Trees:** In SimpleDB, the optimizer restricts its search space to left-deep join trees, where the right-hand child of every join is a leaf (a base scan relation), and the left-hand child is either another join or a base scan relation.
- **Selinger Algorithm:** A dynamic programming algorithm (proposed by Patricia Selinger in 1979) that constructs the optimal left-deep tree bottom-up:
  1. Find the best 1-table access path for each relation (e.g., table scans vs. index scans).
  2. For $k = 2$ to $N$:
     - Find the optimal join order for each subset of size $k$ by building on top of the previously computed optimal subset plans of size $k-1$.
     - Compare the cost of joining the best plan for a subset of size $k-1$ with the $k$-th table.
  3. The final plan is the minimum-cost tree of size $N$.

$$\text{Join Cost Formula:} \quad \text{Cost}(A \bowtie B) = \text{ScanCost}(A) + \text{Selectivity}(A) \times \text{Size}(A) \times \text{ScanCost}(B)$$

---

## 3.3 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 14: Query Optimization.** Pay special attention to Cost Estimation, Selectivity, Equivalence Rules, and Join Order Selection.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 14: A Typical Relational Query Optimizer.**
3.  **Classic Relational Database Paper:**
    *   *Access Path Selection in a Relational Database Management System* by Patricia Selinger et al. (ACM SIGMOD 1979). This is the most famous paper in database systems and lays out the basis of the Selinger optimizer.

---

## 3.4 Practice Coding Exercises

In this chapter, you will implement the following classes in `src/java/simpledb/`:
1.  **`IntHistogram.java` & `StringHistogram.java`**: Datastructures for building data distributions.
2.  **`TableStats.java`**: The stats collection manager scanning the catalog to generate selectivities.
3.  **`JoinOptimizer.java`**: The Selinger dynamic programming optimizer constructing optimal left-deep join orders.
