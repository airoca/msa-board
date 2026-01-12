# MSA Board Project

<details>
<summary>

## ✅ Basic Infrastructure Assumptions

</summary>

---

### ☑ Distributed Relational Database

- We assume traffic volumes that are difficult to handle with a single database instance.
- Considering the limitations of scale-up, we adopt a **scale-out strategy**.
- Data can be distributed across multiple databases through **sharding**.
  - **Vertical Sharding**: A strategy that splits data vertically (by columns).
  - **Horizontal Sharding**: A strategy that splits data horizontally (by rows).
- Vertical sharding can increase complexity in joins and transaction management due to data separation and has scalability limitations (e.g., number of columns).
- Therefore, we adopt a **horizontal sharding strategy**, and we assume that the current database is already horizontally sharded.

---

### ☑ Logical Shard

<p align="center">
  <img src="https://github.com/user-attachments/assets/9ad4b420-2c8c-4f25-894c-92364b298875" />
</p>

- When the database is expanded or modified, changes on the client side are required  
  (e.g., routing requests to different shards).
- To allow flexible database scaling **without client-side changes**, a **logical shard layer** can be introduced.

<p align="center">
  <img src="https://github.com/user-attachments/assets/4a14f167-643a-4f08-b719-778ec1e1ddb4" />
</p>

- In the structure above, there are only two physical shards, but the client assumes the existence of four shards.
- A router that maps a requested **logical shard** to the corresponding **physical shard** can be implemented within the database system.
- In this setup, even if the number of physical shards is later expanded to four, **no client-side changes are required**.

---

### ☑ High Availability

<p align="center">
  <img src="https://github.com/user-attachments/assets/5c3decd3-40ee-4157-bc9c-289828207126" />
</p>

- To handle shard failures, database replicas can be managed.
- Data is written to the **Primary** database and replicated to **Replica** databases.
- Terms such as **Primary/Replica**, **Leader/Follower**, **Master/Slave**, and **Main/Standby** represent similar concepts, but their usage may vary depending on the system or purpose.

#### Replication Strategies

- **Synchronous Replication**
  - Ensures strong data consistency.
  - May degrade write performance.

- **Asynchronous Replication**
  - Maintains write performance.
  - Replicas may not immediately reflect the latest data.

</details>

<details>
<summary>

## ✅ Article Service

</summary>

---

### ☑ Shard Key

- Since a distributed database environment is assumed, articles are expected to be distributed across **N shards**.
- Although sharding is not implemented directly at this stage, we assume **Shard Key = board_id (Board ID)**.
- The reason for choosing `board_id` as the shard key is that article access patterns often involve reading **consecutive articles within the same board**.
- If `article_id` were used as the shard key, listing articles would require querying **N shards** and aggregating the results, which would significantly increase complexity.

---

### ☑ Primary Key

- Given the distributed system environment, the **primary key is generated at the application level** as a monotonically increasing unique number.
- We adopt **Snowflake**, an algorithm designed to generate **globally unique 64-bit IDs** in distributed systems.
  - `| timestamp | workerId | sequence |`
  - The higher bits represent the timestamp, allowing IDs to be **naturally ordered by creation time** (millisecond precision).
  - `workerId` uniquely identifies each application instance, resolving concurrency across multiple instances.
  - `sequence` increments when multiple IDs are generated within the same millisecond, resolving intra-instance concurrency.
- **AUTO_INCREMENT** and **UUID** are not used.
  - AUTO_INCREMENT cannot guarantee global uniqueness in a distributed database environment.
  - UUIDs are randomly generated and lack ordering, which negatively impacts **index performance and pagination efficiency**.

---

### ☑ Indexing

- **Index Fundamentals**
  - Relational databases typically use **B+ Trees (Balanced Trees)**.
  - Data is stored in sorted order, and leaf nodes are linked, making **range queries efficient**.
  - Creating an index generates a B+ Tree structure for the specified column(s).

- **Clustered Index**
  - A clustered index is automatically created based on the **primary key**.
  - The leaf nodes store the actual **row data**.

- **Secondary Index**
  - Leaf nodes of a secondary index contain the indexed column values along with the primary key.
  - The primary key is then used to traverse the clustered index to retrieve the full row.
  - As a result, queries using secondary indexes involve **two index traversals**.

- **Covering Index**
  - A covering index allows queries to be satisfied **entirely from the index** without accessing the data table.
  - When using secondary indexes, a covering index can be created as follows:

    ```sql
    SELECT board_id, article_id
    FROM article
    ...
    ```

  - This approach first retrieves only the required `article_id`s, minimizing access to the data table.

---

### ☑ Paging

- **Offset Paging**
  - Uses the `OFFSET + LIMIT` approach.
  - Page-number-based navigation (page 1, page 2, ...).
  - Performance degrades as the OFFSET value increases due to additional scanning.
  - Data changes during pagination may cause **duplicate or missing results**.

    ```sql
    SELECT *
    FROM article
    ORDER BY id DESC
    LIMIT 20 OFFSET 40;
    ```

- **Cursor Paging**
  - Uses the last retrieved value as a cursor.
  - Implements pagination using `WHERE id < lastId`.
  - Always leverages indexes, ensuring **consistent performance**.
  - Well-suited for **large datasets and infinite scrolling**.

    ```sql
    SELECT *
    FROM article
    WHERE id < :lastId
    ORDER BY id DESC
    LIMIT 20;
    ```

</details>

<details>
<summary>

## ✅ Comment Service

</summary>

---

### ☑ Comment List Retrieval API

- Hierarchical structure (max 2 depth, infinite depth)
  - A parent comment can be deleted **only after all child comments are deleted**.
  - If there are no child comments, the comment is deleted immediately.
  - If child comments exist, the comment is **soft-deleted (marked as deleted)**.
- The design strategy differs depending on the maximum allowed depth.
  - Max 2 depth → **Adjacency List**
  - Infinite depth → **Path Enumeration**

---

### ☑ Max 2 Depth: Design

- In hierarchical comments, the parent-child relationship must be represented.
- Each comment holds a reference to its parent comment ID (`parent_comment_id`).
- The primary key uses **Snowflake**, same as the Article service.
- To ensure that all comments for a given article are queried from a single shard,
  **sharding is based on `article_id`**.

---

### ☑ Max 2 Depth: Query

- Sorting solely by creation time (`comment_id`) is insufficient due to hierarchy.
- The following rules are applied:
  - A parent comment is always created before its child comments.
  - Child comments sharing the same parent are ordered by creation time.
  - Therefore, sorting is based on:
    - `(parent_comment_id ASC, comment_id ASC)`
  - An index can be created as:
    - `article_id ASC, parent_comment_id ASC, comment_id ASC`

~~~sql
-- Page 1
SELECT *
FROM comment
WHERE article_id = {article_id}
ORDER BY parent_comment_id ASC, comment_id ASC
LIMIT {limit};

-- Page 2 and beyond
-- Cursor = last_parent_comment_id, last_comment_id
SELECT *
FROM comment
WHERE article_id = {article_id}
  AND (
    parent_comment_id > {last_parent_comment_id}
    OR (parent_comment_id = {last_parent_comment_id} AND comment_id > {last_comment_id})
  )
ORDER BY parent_comment_id ASC, comment_id ASC
LIMIT {limit};
~~~

---

### ☑ Infinite Depth: Design

- For infinite depth, sorting requires knowledge of the **entire ancestor hierarchy**.
- Representing each depth as a separate column leads to excessive schema complexity.
- Instead, **Path Enumeration** is adopted, storing the hierarchy in a single string column.
- Path encoding rules:
  - Each depth is represented by **5 characters**.
  - Depth 1 → 5 characters
  - Depth 2 → 10 characters
  - Depth 3 → 15 characters
  - N depth → `(N * 5)` characters
- Database collation uses `utf8mb4_bin` to preserve case-sensitive ordering,
  allowing a richer path space.
- A `path` column is introduced to represent hierarchy.
- `parent_comment_id` is removed.
- Although the structure supports infinite depth,
  **depth is limited to 5 levels** for implementation simplicity and service constraints.

---

### ☑ Infinite Depth: Query

- How is the path of a new child comment determined?
- The new path is derived by:
  - Finding the **largest existing path** among descendants (`descendantsTopPath`)
  - Incrementing it by one at the child level (`childrenTopPath`)
- Steps:
  - Query all descendants that share the same `parentPath`
  - Find the maximum path (`descendantsTopPath`)
  - Trim it to `(newDepth * 5)` characters
- Query to find `descendantsTopPath`:

~~~sql
SELECT path
FROM comment_v2
WHERE article_id = {article_id}
  AND path > {parentPath}
  AND path LIKE CONCAT({parentPath}, '%')
ORDER BY path DESC
LIMIT 1;
~~~

- Query performance remains consistent regardless of ascending or descending order
  due to **Backward Index Scan**.
- Backward index scan traverses the index in reverse order,
  leveraging bidirectional pointers in the B+ Tree leaf nodes.
- Path incrementation is performed by:
  - Converting the base-62 path segment to a decimal number
  - Adding `1`
  - Converting it back to a base-62 string

~~~sql
-- Page 1
SELECT *
FROM comment_v2
WHERE article_id = {article_id}
ORDER BY path ASC
LIMIT {limit};

-- Page 2 and beyond
SELECT *
FROM comment_v2
WHERE article_id = {article_id}
  AND path > {last_path}
ORDER BY path ASC
LIMIT {limit};
~~~

</details>

