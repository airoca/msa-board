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

<details>
<summary>

## ✅ Like Service

</summary>

---

### ☑ Like Design

- Each user can like a specific article.
- A like can be canceled.
- Each user can perform the like action **only once per article**.
- Therefore, only **one unique record** must exist per `(article_id, user_id)` pair.

- A simple table can be created with a **unique index on `(article_id, user_id)`**.

---

### ☑ Like Count Query Design

- In large-scale datasets, `COUNT(*)` queries can cause performance issues.
- Unlike articles, like counts cannot be partially displayed.
- Like counts must be displayed **accurately and quickly in real time**.
- If real-time aggregation becomes expensive, an alternative approach is:
  - **Pre-aggregating the like count** whenever a like is created or removed.
- This is achieved by **denormalizing** the per-article like count into a separate table.

---

### ☑ Characteristics of Like Data

- First, consider the characteristics of like count data.
- Write traffic is relatively low.
  - Users browse articles.
  - Users explicitly perform the like action.
- Data consistency is relatively important.
  - Example inconsistency:
    - 15 users liked the article, but the displayed count is 10.
    - The list of users who liked the article does not match the count.
- **When write traffic is moderate and consistency is important:**
  - Relational database transactions can be utilized.
  - Like creation/deletion and like count updates can be handled in a **single transaction**.
  - Both tables use the same MySQL database.
- However, this approach must consider the following constraints:
  - Record Lock
  - Distributed Transaction

---

### ☑ Record Lock

- **Record (= Row)**
  - A single row of data in a table
- **Lock**
  - A mechanism to prevent race conditions when multiple processes or threads
    attempt to access shared resources concurrently
- **Record Lock (= Row Lock)**
  - A lock applied to a specific record
  - Ensures data integrity and prevents race conditions during concurrent access
- Article writes are typically performed by the author and occur infrequently.
- Like count updates are performed by many users and occur more frequently.
  - As a result, multiple independent users may contend for the same record.
  - Article creation and like actions are logically independent from a user perspective.
  - However, they may interfere at the database level.
    - Example: article update fails due to like count contention
- To avoid this issue, article data and like count data are separated
  into independent tables via **denormalization**.

---

### ☑ Distributed Transaction

- The system adopts a microservices architecture (MSA).
- Each service owns an independent database.
- The database is horizontally sharded.
- For strong consistency between likes and like counts,
  relational database transactions are considered.
- However, distributed transactions are:
  - Complex
  - Slow
  - Difficult to operate (covered in more detail in the Popular Article section)
- To avoid distributed transactions:
  - Like count tables are managed within the **Like Service database**.
  - The shard key of the like count table is set to `article_id`,
    identical to the like table.

---

### ☑ Concurrency Considerations

- Under high traffic, concurrency issues are inevitable.
- Multiple requests attempt to update the same like count record.
- Therefore, concurrency control mechanisms are required.
- The following approaches are considered:
  - Pessimistic Lock
  - Optimistic Lock
  - Asynchronous Sequential Processing

---

### ☑ Lock Strategies

- **Pessimistic Lock**
  - Assumes conflicts are likely to occur.
  - Uses record-level locking.

~~~sql
-- Method 1: Atomic update based on stored value
UPDATE article_like_count
SET like_count = like_count + 1
WHERE article_id = {article_id};

-- Method 2: Explicitly acquire lock using FOR UPDATE
SELECT *
FROM article_like_count
WHERE article_id = {article_id}
FOR UPDATE;
~~~

- Method 1 (UPDATE only) vs Method 2 (SELECT FOR UPDATE + UPDATE)
  - Lock duration:
    - Method 1: Lock acquired only during UPDATE (shorter duration)
    - Method 2: Lock acquired from SELECT (longer duration)
  - Application development:
    - Method 1: Direct SQL-based increment
    - Method 2: More object-oriented when using JPA entities

- **Optimistic Lock**
  - Assumes conflicts are rare.
  - Uses a `version` column to track modifications
    (supported via `@Version` in JPA).
  - Conflict detection flow:
    - Read data along with version
    - Update with `WHERE version = ?` and increment version
    - If update fails, a conflict occurred
    - Rollback is handled at the application level

- **Pessimistic vs Optimistic Lock**
  - Pessimistic locking explicitly blocks concurrent access
    but short locks on a single row may be acceptable.
  - Optimistic locking avoids locks and reduces latency,
    but requires retry logic in the application.

---

### ☑ Asynchronous Sequential Processing

- Not all operations must be processed synchronously.
- Requests can be enqueued and processed asynchronously.
- If each article is processed sequentially by a single worker:
  - Concurrency issues are eliminated.
  - Lock contention and failure cases are minimized.
- However, this approach has significant costs:
  - Infrastructure for asynchronous processing
  - Additional client-side handling due to delayed responses
  - User experience considerations (optimistic UI, failure notifications)
  - Ensuring exactly-once processing without duplication or loss
- Due to these costs, this approach is **not adopted**
  since like write traffic is not expected to be extremely high.

---

### ☑ Article Count & Comment Count Tables

- For the Popular Article service,
  article count and comment count tables are created
  using the same design approach.
- These tables support efficient ranking and aggregation.

</details>

<details>
<summary>

## ✅ View Service

</summary>

---

### ☑ View Count Design

- View count abuse prevention policy
    - Each user’s view for a single article is counted **once every 10 minutes**.
    - Even if a user views the same article 100 times within 10 minutes, it is counted only once.
- The view count only needs to store how many times an article has been viewed.
    - Unlike like count, article count, or comment count, it is not derived from other data.
    - Users cannot view detailed view history.
    - Only the total view count needs to be displayed.
    - Therefore, storing the total view count directly in a **single denormalized record** is sufficient.
- Data consistency and write traffic considerations
    - View count data has **relatively low consistency requirements**.
    - Write traffic is **relatively high**.
    - Therefore, using a relational database is not ideal:
        - Transaction requirements are minimal.
        - Disk access cost is expensive.
- For managing view counts:
    - An **in-memory database** can be used.
    - We use **Redis**, which is widely adopted.

---

### ☑ Redis

- **In-memory Database**
    - High performance
- **NoSQL (Not Only SQL) Database, Key-Value Store**
    - Unlike relational databases, it has no fixed schema
    - Allows flexible data models
- **Supports Various Data Structures**
    - String, List, Set, Sorted Set, Hash, etc.
- **TTL (Time To Live) Support**
    - Data is automatically deleted after a specified time
- **Single Thread**
    - Redis processes commands sequentially on a single thread.
    - Since commands are executed one by one, it is advantageous for handling concurrency issues.
- **Data Persistence Support**
    - Although memory is volatile, Redis provides ways to persist data to disk
      (AOF, RDB)
- **Redis Cluster**
    - A feature that enables horizontal scaling of Redis
    - Supports logical sharding for scalability (16,384 slots)
    - The hash value of a key determines the slot (logical shard),
      and the slot determines the physical shard
    - Provides distributed system capabilities for:
        - Scalability
        - Load balancing
        - High availability
    - Redis Cluster also supports data replication.
    - This allows flexible 대응 during failures and provides high availability.
    - Although we do not build Redis Cluster ourselves,
      we assume an environment where Redis Cluster is already available.
      In practice, we run a single Redis instance using Docker.

---

### ☑ Data Persistence

- Although view count data has relatively low consistency requirements,
  it **must not be completely lost**.
- A certain level of persistence is required.
- **AOF (Append Only File)**
    - Records executed commands in a log file
    - Data is restored by replaying the log
- **RDB (Snapshot)**
    - Periodically saves the in-memory data to disk
- In addition, we build a **simple custom backup system**.
- View count data can be backed up to **MySQL**, which is used as the primary database.
- Time-based backup
- Back up Redis data to MySQL every **N minutes**.
    - Requires a batch or scheduling system
    - Data may be lost if a failure occurs before backup
- Count-based backup
- Back up Redis data to MySQL every **N increments**.
    - Can be handled easily at read time
    - Data may be lost if a failure occurs before backup
    - If the threshold is not reached, data may be lost
    - A combination of both approaches is possible,
      but for simplicity, we adopt the **count-based backup approach**.

---

### ☑ View Count Abuse Prevention Policy Design

- Why abuse prevention is necessary
    - Viewing an article increases its view count.
    - Abusers may repeatedly view a specific article to manipulate data.
    - If popular articles are selected based on view count,
      manipulated data can lead to incorrect rankings.
- How can we identify whether a view should be counted?
    - Logged-in users: can be identified per user.
    - Non-logged-in users: can be identified using IP, User-Agent,
      browser cookies, tokens, etc.
    - For simplicity, we only consider **logged-in users** and identify them using `userId`.
- View count abuse prevention design
    - **Using MySQL (checking view history within 10 minutes)**
        - However, Redis was chosen for performance reasons.
        - Reintroducing MySQL would defeat the purpose.
        - Concurrency issues may occur:
            - MySQL requires locks.
            - Redis operates as a single-threaded system, which is advantageous.
        - MySQL does not support automatic data expiration.
        - Redis supports TTL (Time To Live).
    - **Using Redis**
        - When a view request arrives, store data in Redis with TTL = 10 minutes.
        - Since views are identified per user, the key becomes:
          `key = (articleId + userId)`
        - Use a command that fails if the key already exists.
        - `setIfAbsent`: stores data only if it does not already exist.
        - If successful:
            - No previous view exists → increment the view count.
        - If failed:
            - A previous view exists → do not increment the view count.
- This process can be considered as acquiring a **lock** for view count increment.
    - The system is a scalable distributed system.
    - Scale-out is assumed.
    - The View Service consists of multiple server applications.
    - Acquiring a lock in such a distributed environment is called a
      **Distributed Lock**.

</details>

<details>
<summary>

## ✅ Hot Article Service **- Design**

</summary>

---

### ☑ Hot Article Policy

- Select **Top 10 hot articles per day**
    - Updated **daily at 1:00 AM**
    - Score is calculated based on:
        - Number of likes
        - Number of comments
        - Number of views
    - Score formula:
        - `score = (likes * 3) + (comments * 2) + (views * 1)`
    - Supports querying hot articles for the **last 7 days**

- A batch-based approach can be considered.
    - At midnight, iterate over all articles created on the previous day.
    - For each article:
        - Query like count, view count, and comment count.
        - Calculate the article score.
    - Select the top 10 articles based on the calculated scores.

- Limitations of batch processing
    - The number of articles to process can be extremely large,
      resulting in long execution times.
        - What if hot articles must be updated immediately at 00:00?
    - Parallel processing may be introduced to improve performance.
        - However, this significantly increases design and implementation complexity.
        - Even with parallelism, fundamental issues remain.
    - Hot article selection requires a massive number of queries
      across multiple services.
        - This can cause traffic spikes and overload other services.
        - The hot article job itself may negatively impact unrelated services.

---

### ☑ Stream Processing

- **Stream**
    - A continuous flow of data
    - Examples include real-time logs or continuously generated events

- **Stream Processing**
    - Processing data as it continuously arrives
    - Enables real-time or near real-time computation

- A stream processing application can be built by consuming
  the following real-time events:
    - Article created / updated / deleted events
    - Comment created / deleted events
    - Like created / deleted events
    - View count increment events

---

### ☑ Hot Article Architecture

- Hot article processing flow:
    1. Receive events required for hot article selection as streams.
    2. Continuously calculate scores for each article in real time.
    3. Maintain a real-time **Top 10 hot article list**.
    4. Clients query the hot article list.

- The Hot Article Service consumes events from:
    - Article Service
    - Comment Service
    - Like Service
    - View Service

- The Hot Article Service acts as a **data processor** that
  transforms incoming events into hot article data.

- Hot article data is managed using **Redis Sorted Set (ZSET)**.
    - Maintains articles in sorted order by score.
    - Efficiently retrieves the top 10 articles.

---

### ☑ Hot Article Event Design

- **Direct API-based approach**
    - Each service sends events directly to the Hot Article Service via API.
    - Easy to implement.
    - Increases coupling between services.

- **Message Broker-based approach**
    - Article / Like / Comment / View services publish events
      to a message broker.
    - The Hot Article Service consumes and processes these events.
    - More complex to implement.
    - Significantly reduces service-to-service coupling.

---

### ☑ Hot Article Score Calculation

- The Hot Article Service receives events **independently**:
    - Comment events do not include current like or view counts.
    - Like events do not include comment counts.
    - View events only indicate a single increment.

- To calculate scores, the current state of all required metrics
  must be known.

- One possible approach is to query other services via API.
    - However, this tightly couples the Hot Article Service
      to other services.
    - Hot article processing traffic would propagate load
      to other services.

- Current score calculation uses:
    - Like count
    - Comment count
    - View count

- These values are originally owned by their respective services.
    - However, consider a future requirement:
        - Example: score depends on the number of **unique commenters**.
        - This data may not exist in the Comment Service.
        - The Comment Service should not be responsible
          for producing data solely for hot article computation.

- Therefore, the Hot Article Service maintains its **own derived data**.
    - Raw events are consumed and transformed into
      hot-article-specific metrics.
    - The service independently manages and updates
      all data required for score calculation.
    - No synchronous dependency on other services is required.

- Since hot article data only needs to be retained **per day**:
    - Data volume is limited.
    - Fast access is critical.
    - Data can be volatile.
- Redis is chosen as the storage:
    - In-memory
    - High performance
    - Suitable for short-lived, frequently updated data

</details>

<details>
<summary>

## ✅ Hot Article Service - Producer

</summary>

---

### ☑ Transactional Messaging

- Article / Comment / Like / View services act as **Producers** that publish events to Kafka.
    - A Producer’s responsibility is to reliably deliver defined events to Kafka.
    - But can event delivery always be handled safely?
    - To answer this, we need to understand **Transactional Messaging**.

- Transaction management is critical for ensuring **data consistency and atomicity**.
- Kafka is assumed to be a reliable system.
    - If a Consumer commits its offset **after successfully processing events**,
      data loss can be avoided.
    - But what happens if a failure occurs **while a Producer is sending events to Kafka**?
        - Since the data has not yet been delivered to Kafka,
          the event may be permanently lost.
        - This leads to **data inconsistency across services**.

- To prevent this, **business logic execution and event publishing**
  must be treated as a **single transactional unit**.
    - This does not need to be strictly synchronous.
    - **Eventual consistency** is acceptable.
    - However, MySQL state changes and Kafka message publishing
      cannot be wrapped in a single MySQL transaction
      because they are **different systems**.

- Kafka publishing can be placed inside a MySQL transaction block:

    ```sql
    1. Transaction start
    2. Execute business logic
    3. publishEvent()
    4. commit or rollback
    ```

- But this approach introduces serious problems:
    - If Kafka is temporarily unavailable and `publishEvent()` blocks for 3 seconds:
        - The database transaction is held for 3 seconds.
        - Kafka failure can propagate to the application and MySQL.
    - The database transaction may fail after
      the Kafka event has already been published.
    - If `publishEvent()` is asynchronous,
      rollback becomes even harder when event delivery fails.

- So how can two different systems be treated as one transaction?
- **Distributed transaction management** is required.

---

### ☑ Two Phase Commit

- Two Phase Commit (2PC) is a protocol used
  when multiple distributed systems participate in a single transaction.
- If all participants succeed → commit
- If any participant fails → rollback
- As the name suggests, it consists of two phases:
    - **Prepare phase**
    - **Commit phase**

- **Prepare Phase**
    - The Coordinator asks all participants
      whether they are ready to commit.
    - Each participant responds with its readiness status.

- **Commit Phase**
    - If all participants respond positively,
      the Coordinator instructs all participants to commit.
    - Each participant commits its transaction.

- However, several critical issues remain:
    - Overall latency increases because all participants must wait.
    - If the Coordinator or a participant fails,
      others may remain blocked, unaware of the final state.
    - Kafka and MySQL do not natively support
      such tightly coupled transactional integration.

---

### ☑ Transactional Outbox

- Event publishing cannot be directly included
  in a typical database transaction.
- However, **event metadata can be stored inside the database transaction**.
    - An **Outbox table** is created in a transactional database.
    - Business logic execution and event record insertion
      are wrapped in a **single database transaction**.

- A **Message Relay** reads unpublished events from the Outbox table.
    - The Message Relay is responsible for publishing events
      to the Message Broker.
    - This component is implemented directly within the system.

---

### ☑ Transaction Log Tailing

- A technique that tracks and analyzes database transaction logs.
    - Databases record changes in transaction logs:
        - MySQL binlog
        - PostgreSQL WAL
        - SQL Server Transaction Log

- These logs can be read to publish events to a Message Broker.
    - This approach leverages **CDC (Change Data Capture)**.
    - CDC tracks data changes and propagates them to other systems.

- A **Transaction Log Miner**:
    - Reads transaction logs
    - Publishes events to the Message Broker

- If tracking changes in data tables alone is sufficient,
  the Outbox table may be omitted.

---

### ☑ Transactional Outbox vs Transaction Log Tailing

- We adopt **Transactional Outbox**.

- Transaction Log Tailing:
    - Requires CDC infrastructure.
    - Directly couples message semantics
      to low-level database change logs.

- Transactional Outbox:
    - Introduces additional tables and operational overhead.
    - But allows **explicit, well-defined event models**
      independent of database internals.
    - Provides clearer intent and better long-term maintainability.

---

### ☑ Transactional Outbox – Detailed Design

- **Message Relay**
    - Periodically polls the Outbox table
      for unpublished events and sends them to Kafka.
        - If polling is too frequent → database overload
        - If polling is too slow → event delivery latency
        - Polling interval is set to **10 seconds**

    - Even with polling, latency can still be large.
        - After a service transaction commits,
          the event can be immediately delivered to the Message Relay.
        - The Message Relay asynchronously publishes it to Kafka.
        - Polling is only required for failed transmissions.

    - However, this introduces multiple event delivery paths:
        - Events may be duplicated.
        - Duplicate delivery is acceptable.

    - Consumers must be **idempotent**.
        - The Hot Article Service stores data in Redis Key-Value form.
        - Idempotent processing is guaranteed by design.

    - Successfully delivered events are deleted from the Outbox table.

- **Coordinator**
    - The system is distributed:
        - Multiple application instances
        - Horizontally sharded databases

    - How should Message Relay polling be handled?
        - Without coordination, every instance polls every shard.
        - This leads to:
            - Duplicate processing
            - Increased latency

    - A Coordinator inside Message Relay assigns shards
      so that each instance processes **only a subset**.

    - Coordinator behavior:
        - Sends a ping every 3 seconds
          containing:
            - Application identifier
            - Current timestamp
        - Maintains active application list
          in a central storage.
        - If no ping is received for 9 seconds,
          the instance is considered dead and removed.
        - Central storage is implemented using **Redis Sorted Set**.
            - Application identifier + last ping time
            - Automatically ordered by timestamp

- Message Relay is implemented as a **shared module**.
- The Coordinator is also implemented within the Message Relay.

</details>

<details>
<summary>

## ✅ Article Read Optimization

</summary>

---

### ☑ Article Read Requirements

- **Article read optimization goals**
    - Optimize single-article read performance
    - Optimize article list retrieval
    - Define an effective cache strategy
    - Design a read-optimized caching architecture

- **Characteristics of a board service**
    - Read traffic overwhelmingly exceeds write traffic.
    - Articles are never shown alone.
        - Article content
        - Like count
        - Comment count
        - View count
        - Author information
    - With data distributed across multiple services,
      how should the client retrieve everything efficiently?

- A naive approach:
    - Client requests article data from the Article Service.
    - Article Service synchronously requests data from:
        - Comment Service
        - Like Service
        - View Service
    - The Article Service aggregates the data and returns the response.

---

### ☑ Limitations of Direct Article Read

- In board services, read traffic can far exceed write traffic.
    - What if servers must be scaled solely due to read load?
    - Scaling read capacity also unnecessarily scales write capacity.

- Service dependency and circular reference issues
    - Previously, the Article Service had no dependency
      on Comment / Like / View services.
    - Article read requirements introduce new dependencies.
    - Bidirectional references are created:
        - Comment / Like / View services store `articleId`
          and must validate article existence.
        - Article Service must query other services
          to assemble read responses.
    - This leads to **circular dependencies**.

- Consequences of circular dependencies:
    - Services cannot be independently deployed or maintained.
    - Failures propagate across services.
    - Testing becomes more complex and fragile.

---

### ☑ Article Read Service

- A dedicated **Article Read Service** can be introduced.
- Client requests data from the Article Read Service.
- The Article Read Service:
    - Fetches data from each microservice
    - Aggregates and returns the response

- By isolating read responsibilities:
    - Circular dependencies are eliminated.
    - Each microservice regains independence.
    - Read services can be scaled independently
      without affecting write workloads.

- However, inefficiencies still remain:
    - Network overhead from multiple service calls
    - Load propagation to upstream services
    - Increased aggregation and query cost

- To address these issues, **CQRS** is applied.

---

### ☑ CQRS (Command Query Responsibility Segregation)

- Separates **Command** (write) and **Query** (read) responsibilities.
- Data modification and data retrieval are handled independently.

- How does the Article Read Service obtain data?
    - Pulling data synchronously from Command services
      still propagates load.
    - Instead, the Article Read Service maintains
      its own **Query Database**.

- How does the Query Database stay up to date?
    - Polling APIs periodically
    - Consuming events from a Message Broker
    - API polling reintroduces coupling and load propagation.
    - Since a Message Broker already exists,
      event-based synchronization is preferred.

- Query Model design:
    - Query models do not mirror Command data models.
    - They are optimized for read efficiency.
    - Denormalized structure:
        - Article
        - Comment count
        - Like count
    - A single Query Model fetch satisfies most read requests.

---

### ☑ Query Database Design

- **Redis** is chosen as the Query Database.
    - In-memory storage
    - Extremely fast read performance
    - Higher cost per capacity compared to disk-based storage

- Board service access patterns:
    - Most reads target **recent articles**.
    - Older articles are accessed far less frequently.

- Strategy:
    - Store only recent articles in Redis.
    - Apply TTL of **1 day**.
    - Redis retains only articles created
      within the last 24 hours.

- Cache miss handling:
    - When data expires, the Query Model is rebuilt
      by requesting original data from Command services.
    - This happens infrequently and incurs low overhead.
    - Improves resilience against:
        - Redis failures
        - Event loss
        - Partial data inconsistency
    - However, failure propagation risk must still be considered.

---

### ☑ Why View Count Is Not Denormalized

- View count characteristics:
    - View count increases with read traffic.
    - Reads trigger writes.

- Problems with denormalizing view count:
    - Every view would require rebuilding the Query Model.
    - This is inefficient and unnecessary.

- View count already:
    - Is stored in Redis within the View Service.
    - Is optimized for frequent updates.

- View count events:
    - Are not emitted in real time.
    - Are sent only during periodic backups
      (e.g., every 100 increments).

- Strategy:
    - Fetch view count directly from the View Service.
    - Apply short-lived caching in the Article Read Service
      to reduce load.
    - Further optimized in the cache optimization section.

---

### ☑ Article List Read Optimization

- The Article Service’s MySQL database
  handles all article list queries.
    - Queries are already index-optimized.
    - But database load remains high.

- Can Redis be used here as well?
    - Reduce load on MySQL
    - Improve read latency

- Challenges:
    - Article lists change frequently due to:
        - Article creation
        - Article deletion
    - Traditional page-based caching
      becomes stale immediately.

- Standard cache approach (`@Cacheable`):
    - Check cache by key
    - If cache hit → return cached data
    - If cache miss → query DB, cache result, return
    - But page-level caching quickly becomes outdated.

- Precompute cache strategy:
    - Update cached article lists proactively
      on article create/delete events.
    - Article Read Service already consumes these events.

- Memory constraints:
    - Memory is more expensive than disk.
    - Storing all list data is impractical.

- Access pattern optimization:
    - Users usually land on the **first page**.
    - Recent articles receive most of the traffic.
    - Older pages are rarely accessed.

- Strategy:
    - Cache only the **latest 1000 articles** per board in Redis.
    - When client requests:
        - If within latest 1000 → Redis
        - Otherwise → Article Service

---

### ☑ Cache Optimization

- Short-lived caching is applied to view count retrieval.
- Standard `@Cacheable` flow:
    1. Check cache
    2. If exists → return cached data
    3. If missing → fetch original data
    4. Store in cache and return

- Problem under high concurrency:
    - Cache expiration causes multiple concurrent cache misses.
    - Multiple requests hit the original data source.
    - Cache is rebuilt multiple times redundantly.

- Using distributed locks?
    - Only one request refreshes cache.
    - Others wait.
    - This introduces latency and inefficiency.

- **Logical TTL vs Physical TTL strategy**
    - Logical TTL: determines when refresh should occur
    - Physical TTL: determines actual data expiration
    - Condition:
        - Logical TTL < Physical TTL

- Example:
    - Logical TTL = 10 seconds
    - Physical TTL = 15 seconds

- Behavior:
    - Request 1 detects Logical TTL expiration.
    - Acquires distributed lock.
    - Refreshes cache.
    - Request 2 arrives during refresh.
    - Fails to acquire lock.
    - Returns existing cached data
      (still valid due to Physical TTL).

- Distributed lock implementation:
    - Redis `setIfAbsent` is sufficient.

- Trade-off:
    - Slightly stale data may be served temporarily.
    - Not suitable for all use cases.

- This technique collapses multiple identical requests
  into a single refresh operation.
- This pattern is known as **Request Collapsing**.

</details>
