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
