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
