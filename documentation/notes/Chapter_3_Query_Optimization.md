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

## 3.2 Formal Definitions

**Definition 1 (Selectivity).** The *selectivity* $\text{sel}(\theta, R)$ of a predicate $\theta$ on relation $R$ is the fraction of tuples in $R$ that satisfy $\theta$. It is a real number in $[0, 1]$.

$$\text{sel}(\theta, R) = \frac{|\sigma_\theta(R)|}{|R|}$$

**Definition 2 (Cardinality).** The *cardinality* of a query result is the estimated number of output tuples: $\text{card} = \text{sel} \times |R|$.

**Definition 3 (Join Selectivity).** For an equi-join $R \bowtie_{R.a = S.b} S$, the selectivity under the uniformity and independence assumptions is:

$$\text{sel}(R.a = S.b) = \frac{1}{\max(\text{distinct}(R.a), \text{distinct}(S.b))}$$

**Definition 4 (Plan Search Space).** For $N$ tables joined with left-deep trees, the number of possible orderings is:

$$\frac{N!}{1} = N! \quad \text{(permutations of } N \text{ tables)}$$

For bushy trees, the count is the Catalan number $C_N = \frac{1}{N+1}\binom{2N}{N}$, which grows much faster.

---

## 3.3 Core Optimizer Concepts

### 1. Table Statistics & Histograms (`IntHistogram`, `StringHistogram`)
To calculate plan costs, the optimizer must estimate the number of tuples that will satisfy a given operator (known as **selectivity**). It keeps track of the data distribution of each table's columns using **Histograms**.
- **Equal-Width Histograms:** The range of values in a column is divided into a fixed number of bins (buckets). Each bin stores the count of occurrences of values falling in its sub-range.
- **`TableStats`:** Scans the table once at startup, building histograms for all fields to maintain table size, number of pages, and value distributions.

#### Selectivity Estimation Formulas

For an equal-width histogram with $B$ buckets over range $[\text{min}, \text{max}]$:

$$w = \frac{\text{max} - \text{min} + 1}{B} \quad \text{(width of each bucket)}$$

Let $h_b$ = height (count) of bucket $b$, and $N_{\text{total}} = \sum h_b$.

| Predicate | Selectivity Formula |
|-----------|-------------------|
| $A = v$ | $\frac{h_b / w}{N_{\text{total}}}$ where $b$ contains $v$ |
| $A > v$ | $\frac{h_b \cdot \frac{b_{\text{right}} - v}{w}}{N_{\text{total}}} + \frac{\sum_{i > b} h_i}{N_{\text{total}}}$ |
| $A < v$ | $1 - \text{sel}(A \geq v) = 1 - \text{sel}(A > v) - \text{sel}(A = v)$ |
| $A \neq v$ | $1 - \text{sel}(A = v)$ |

#### Worked Example: Histogram Selectivity

Table `Students` has 1000 tuples with `age` values ranging from 18 to 27. Using $B = 5$ buckets:

```
Bucket:   [18,19]  [20,21]  [22,23]  [24,25]  [26,27]
Height:     150      300      250      200      100
Width:       2        2        2        2        2
```

**Query:** $\sigma_{\text{age} = 22}(\text{Students})$

- Bucket containing 22: bucket 2 ($[22, 23]$), height $h = 250$
- $\text{sel} = \frac{250 / 2}{1000} = \frac{125}{1000} = 0.125$
- Estimated result: $0.125 \times 1000 = 125$ tuples

**Query:** $\sigma_{\text{age} > 22}(\text{Students})$

- Fraction of bucket 2 above 22: $\frac{23 - 22}{2} = 0.5$, contributing $250 \times 0.5 = 125$
- Full buckets 3 and 4: $200 + 100 = 300$
- $\text{sel} = \frac{125 + 300}{1000} = 0.425$
- Estimated result: $0.425 \times 1000 = 425$ tuples

### 2. Selinger Join Optimization (`JoinOptimizer`)
For a join of $N$ tables, there are an exponential number of possible join orders.
- **Left-Deep Trees:** In SimpleDB, the optimizer restricts its search space to left-deep join trees, where the right-hand child of every join is a leaf (a base scan relation), and the left-hand child is either another join or a base scan relation.
- **Selinger Algorithm:** A dynamic programming algorithm (proposed by Patricia Selinger in 1979) that constructs the optimal left-deep tree bottom-up.

#### The DP Recurrence

Let $\text{OPT}(S)$ = minimum cost to join the set of tables $S$, and $\text{best}(S)$ = the plan achieving that cost.

**Base case:** For a single table $\{R_i\}$:
$$\text{OPT}(\{R_i\}) = \text{scanCost}(R_i)$$

**Recursive case:** For $|S| \geq 2$:
$$\text{OPT}(S) = \min_{R_j \in S} \left[ \text{OPT}(S \setminus \{R_j\}) + \text{joinCost}(\text{best}(S \setminus \{R_j\}), R_j) \right]$$

where the join cost for nested loops is:

$$\text{joinCost}(L, R) = \text{scanCost}(L) + \text{card}(L) \times \text{scanCost}(R)$$

The total number of subproblems is $2^N$, so the algorithm runs in $O(2^N \cdot N)$ time — exponential, but feasible for typical queries ($N \leq 15$).

#### Worked Example: 3-Table Join Optimization

**Tables:**

| Table | Pages | Tuples | Scan Cost |
|-------|-------|--------|-----------|
| A (Users) | 10 | 5000 | 10 |
| B (Orders) | 50 | 25000 | 50 |
| C (Products) | 5 | 500 | 5 |

**Join predicates:** `A.id = B.uid` and `B.pid = C.id`

**Join selectivities:** $\text{sel}(A \bowtie B) = 0.0002$, $\text{sel}(B \bowtie C) = 0.002$

**Step 1 — Base cases:**
- $\text{OPT}(\{A\}) = 10$, $\text{OPT}(\{B\}) = 50$, $\text{OPT}(\{C\}) = 5$

**Step 2 — Pairs (left-deep only, smaller set on left):**

$\text{OPT}(\{A, B\})$: Cost of $A \bowtie B$:
$$10 + 5000 \times 50 = 250{,}010$$
Cardinality of result: $5000 \times 25000 \times 0.0002 = 25{,}000$

$\text{OPT}(\{B, C\})$: Cost of $C \bowtie B$:  (C smaller → C outer)
$$5 + 500 \times 50 = 25{,}005$$
Cardinality: $25000 \times 500 \times 0.002 = 25{,}000$

$\text{OPT}(\{A, C\})$: No join predicate between A and C → cross product (sel = 1):
$$5 + 500 \times 10 = 5{,}005$$
Cardinality: $5000 \times 500 = 2{,}500{,}000$

**Step 3 — All three ({A, B, C}), try removing each table:**

Remove C: $\text{OPT}(\{A,B\}) + \text{joinCost}(\text{result}_{AB}, C) = 250{,}010 + 25{,}000 \times 5 = 375{,}010$

Remove A: $\text{OPT}(\{B,C\}) + \text{joinCost}(\text{result}_{BC}, A) = 25{,}005 + 25{,}000 \times 10 = 275{,}005$  ← **Winner**

Remove B: $\text{OPT}(\{A,C\}) + \text{joinCost}(\text{result}_{AC}, B) = 5{,}005 + 2{,}500{,}000 \times 50 = 125{,}005{,}005$

**Optimal plan:** $(C \bowtie B) \bowtie A$ with cost **275,005**.

The key insight: joining the two smaller tables first ($C \bowtie B$) dramatically reduces the intermediate result size, making the final join with $A$ much cheaper.

---

## 3.4 Optimizer Trade-offs

### Cost-Based (Selinger) vs. Alternatives

| Approach | Pros | Cons |
|----------|------|------|
| **Selinger DP** | Optimal for left-deep trees; polynomial subproblems ($2^N$) | Exponential in N; impractical for $N > 15$ |
| **Greedy / Heuristic** | $O(N^2)$ — fast for many tables | No optimality guarantee; can miss 10× better plans |
| **Randomized (e.g., simulated annealing)** | Explores bushy trees; scales to large N | Non-deterministic; hard to reason about |
| **Rule-based (no statistics)** | Simple; no need for histograms | Ignores data distribution; poor plans for skewed data |

### Why Equal-Width Histograms?

| Histogram Type | Accuracy | Build Cost | Used By |
|----------------|----------|------------|---------|
| **Equal-Width** | Moderate (poor for skewed data) | $O(N)$ single pass | SimpleDB |
| **Equal-Depth (Equi-Height)** | Better for skewed data | $O(N \log N)$ — requires sorting | PostgreSQL, Oracle |
| **Compressed / Hybrid** | Best — tracks frequent values separately | Higher memory + build cost | SQL Server, DB2 |

SimpleDB uses equal-width for simplicity. The main weakness is that heavily skewed columns (e.g., 90% of values in one bucket) produce poor selectivity estimates.

---

## 3.5 Recommended Readings & Textbooks

1.  **Silberschatz (SKS):**
    *   **Chapter 14: Query Optimization.** Pay special attention to Cost Estimation, Selectivity, Equivalence Rules, and Join Order Selection.
2.  **Ramakrishnan & Gehrke (R&G):**
    *   **Chapter 14: A Typical Relational Query Optimizer.**
3.  **Classic Relational Database Paper:**
    *   *Access Path Selection in a Relational Database Management System* by Patricia Selinger et al. (ACM SIGMOD 1979). This is the most famous paper in database systems and lays out the basis of the Selinger optimizer.

---

## 3.6 Glossary

| Term | Definition |
|------|-----------|
| **Selectivity** | Fraction of tuples passing a predicate (0.0 to 1.0) |
| **Cardinality** | Estimated number of output tuples from an operator |
| **Histogram** | Data structure approximating the value distribution of a column |
| **Equal-width histogram** | Buckets cover equal value ranges; heights vary |
| **Equal-depth histogram** | Buckets contain approximately equal tuple counts; widths vary |
| **Left-deep tree** | Join tree where every right child is a base table (scan) |
| **Bushy tree** | Join tree where both children of a join can be other joins |
| **Cost model** | Formula estimating I/O + CPU cost of a physical plan |
| **Selinger optimizer** | DP algorithm finding optimal left-deep join order |
| **Interesting order** | A sort order that could benefit later operators (e.g., ORDER BY, merge join) |

---

## 3.7 Practice Coding Exercises

In this chapter, you will implement the following classes in `src/java/simpledb/`:
1.  **`IntHistogram.java` & `StringHistogram.java`**: Datastructures for building data distributions.
2.  **`TableStats.java`**: The stats collection manager scanning the catalog to generate selectivities.
3.  **`JoinOptimizer.java`**: The Selinger dynamic programming optimizer constructing optimal left-deep join orders.
