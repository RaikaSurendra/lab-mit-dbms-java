# Assignments: Final Project Assignment and Ideas

A large portion (20%) of your grade in 6.830 consists of a final project. This project is meant to be a substantial independent research or engineering effort related to material we have studied in class. Your project may involve a comparison of systems we have read about, an application of database techniques to a system you are familiar with, or be a database-related project in your research area.

This document describes what is expected of a final project and proposes some possible project ideas.

---

## What Is Expected

Good class projects can vary dramatically in complexity, scope, and topic. The only requirement is that they be related to something we have studied in this class and that they contain some element of research — e.g., that you do more than simply engineer a piece of software that someone else has described or architected. To help you determine if your idea is of reasonable scope, we will arrange to meet with each group several times throughout the semester.

---

## What to Hand In

There are two written deliverables: a **project proposal** and a **final report**.

### 1. Project Proposal
The proposal should consist of 1-2 pages describing the problem you plan to solve, outlining how you plan to solve it, and describing what you will "deliver" for the final project. We will arrange short meetings with every group before the project proposal to help you refine your topic and would be happy to provide feedback on a draft of your proposal before it is due.

### 2. Final Report
You should prepare a conference-style report on your project with a maximum length of 15 pages (10 pt font or larger, one or two columns, 1-inch margins, single or double-spaced — more is not better). 

Your report should:
- Introduce and motivate the problem your project addresses.
- Describe related work in the area.
- Discuss the elements of your solution.
- Present results that measure the behavior, performance, or functionality of your system (with comparisons to other related systems as appropriate).

> [!IMPORTANT]
> Because this report is the primary deliverable upon which you will be graded, do not treat it as an afterthought. Plan to leave at least a week to do the writing, and make sure you proofread and edit carefully!

Please submit a paper copy of your report. You will also be expected to give a presentation on your project in class that will provide an opportunity for you to present a short demo of your work and show what you have done to other students in the class. Details about the format of the presentation will be posted as the date gets closer.

---

## Project Ideas

The following is a list of possible project ideas; you are not required to choose from this list — in fact, we encourage you to try to solve a problem of your own choosing! 

If you are interested in working on one of these projects, contact the instructors and we can put you in touch with students and others around MIT working on these ideas. Note that these are not meant to be complete project proposals, but just suggestions for areas to explore — you will need to flesh them out into complete projects by talking with your group members, the course staff, and graduate students working on these projects.

### 1. Real-World Wikipedia Benchmark
Being able to compare performance of different DBMSs and different storage and access techniques is vital for the database community. To this purpose several synthetic benchmark has been designed and adopted over time (see TPC-C, TPC-H etc…). Wikipedia open source application, and publicly available data (several TB!!), provide a great starting point to develop a benchmark based on real-world data. Moreover, we obtained from the Wikimedia foundation 10% of 4 months of Wikipedia accesses (roughly 20 billion HTTP requests!). The project will consists in using this real-world data, queries and access patterns to design one of the first benchmarks based on real-world data.

### 2. Amazon RDS Performance and Scalability
Amazon RDS is a database service provided within the EC2 cloud. An interesting project consists in investigating performance and scalability characteristics of Amazon RDS. Also since RDS services run in a virtualized environment, studying the “stability” and “isolation” of the performance offered is interesting.

### 3. Impact of Hosted Database Services
Hosted database services such as Amazon RDS, Microsoft SQL Azure are starting to become popular. It is still unclear what is the performance impact of running applications on a local (non-hosted) platform, such as a local enterprise datacenter, while having the data hosted “in the cloud”. An interesting project aim at investigating the performance impact for different classes of applications e.g., OLAP, OLTP, Web.

### 4. Database Performance Monitoring Interface
Performance monitoring is an important portion of data-center and database management. An interesting project consists in developing a monitoring interface for MySQL, capable of monitoring multiple nodes, reporting both DBMS internal statistics, and OS-level statistics (CPU, RAM, Disk), potentially automating the detection of saturation of resources.

### 5. Machine Learning Load Prediction for DB Consolidation
Being able to predict cpu/mem/disk load of database machines can enable “consolidation”, i.e., the co-location of multiple DB within a smaller set of physical servers. We have an interesting set of data from real-world data-centers, the project would consist in investigating machine-learning and other predictive techniques on such real-world data.

### 6. Database Performance on Flash Memory
Flash memories are very promising technologies, providing lower latency for random operations. However, they have a series of unusual restrictions and performance. An interesting project investigates the performance impact of using flash memories for DB applications.

### 7. Local vs. Networked Storage query Performance
Often database assume data to be stored on a local disk, however data stored on network file systems can allow for easier administration, and is rather common in enterprises using SAN or NAS storage systems. The project will investigate the impact of local-vs-networked storage on query performance.

### 8. Partition-Aware Object-Relational Mapping (ORM)
Many programmers seem to prefer object-relational mapping (ORM) layers such as like Ruby on Rails or Hibernate to a traditional ODBC/JDBC interface to a database. In the H-store Project we have been studying performance benefits that can be obtained in a “partitonable” database, where the tables can be cleanly partitioned according to some key attribute (for example, customer-id), and queries are generally run over just one partition. The goal of this project would be to study how to exploit partitioning to improve the performance of a distributed ORM layer.

### 9. Stream Processing over Social Streams (Twitter Firehose)
Twitter provides a fire hose of data. Automatically filtering, aggregating, analyzing such data can allow a way to harness the full value of the data, extracting valuable information. The idea of this project is investigating stream processing technology to operate on social streams.

### 10. Client-Side Database Library
Build a Javascript library that client-side Web applications can use to access a database; the idea is to avoid the painful way in which current client-side application have to use the XMLHttpRequest interface to access server-side objects asynchronously. This layer should cache objects on the client side whenever possible, but be backed by a shared, server-side database system.

As a related project, HTML5 browsers (including WebKit, used by Safari and Chrome), include a client-side SQL API in JavaScript. This project would involve investigating how to user such a database to improve client performance, offload work from the server, etc.

### 11. Preventing Database Denial-of-Service (DoS) Attacks
Databases are a vulnerable point in many Web sites, because it is often possible for attackers to make some simple request that causes the Web site to issue queries asking the database to do a lot of work. By issuing a large number of such requests, and attacker can effectively issue a denial of service attack against the Web site by disabling the database. The goal of this project would be to develop a set of techniques to counter this problem — for example, one approach might be to modify the database scheduler so that it doesn’t run the same expensive queries over and over.

### 12. Auto-Admin Tools for Recommendations
Design a tool that recommends a set of indices to build given a particular workload and a set of statistics in a database. Alternatively investigate the question of which materialized views to create in a data-warehousing system.

### 13. Scientific Data Management: SciDB Performance
Scientific community data management requirements significantly differ from regular web/enterprise ones. To this purpose a specialized DB is currently being developed named: SciDB. Studying performance of SciDB on dedicated servers vs. on virtualized environment such as EC2 is an intriguing topic. Another interesting investigation would cover the impact on SciDB performance of storing the data over the network (e.g., network file system). A third interesting project would explore the performance of clustering algorithms on SciDB vs. MapReduce.

### 14. Asynchronous Database Access
Client software interacts with standard SQL databases via a blocking interface like ODBC or JDBC; the client sends SQL, waits for the database to process the query, and receives an answer. A non-blocking interface would allow a single client thread to issue many parallel queries from the same thread, with potential for some impressive performance gains. This project would investigate how this would work (do the queries have to be in different transactions? what kind of modification would need to be made to the database) and would look at the possible performance gains in some typical database benchmarks or applications.

### 15. SimpleDB Extensions
SimpleDB is very simple. There are a number of ways you might extend it to explore some of the research ideas we have studied in this class. For example, you could add support for optimistic concurrency control and compare its performance to the basic concurrency control scheme you will implement in Problem Set 3. There are a number of other possible projects of this type; we would be happy to discuss these in more detail.

### 16. CarTel Project
In the CarTel project, we are building a system for collecting and managing data from automobiles. There are several possible CarTel related projects:
- **Geo-Spatial Data GUI:** One of the features of CarTel is a GUI for browsing geo-spatial data collected from cars. We currently have a primitive interface for retrieving parts of the data that are of interest, but developing a more sophisticated interface or query language for browsing and exploring this data would make a great project.
- **Privacy-Preserving Aggregate Queries:** One of the dangers with building a system like CarTel is that it collects relatively sensitive personal information about users location and driving habits. Protecting this information from casual browsers, insurance companies, or other undesired users is important. However, it is also important to be able to combine different users data together to do things like intelligent route planning or vehicle anomaly detection. The goal of this project would be to find a way to securely perform certain types of aggregate queries over CarTel data without exposing personally identifiable information.
- **Taxi Data Analysis:** We have speed and position data from the last year for 30 taxi cabs on the Boston streets. Think of something exciting you could do with this.

### 17. Rollback of Committed Transactions
Database systems typically only support UNDO of committed transactions, but there are cases where it might be important to rollback already committed transactions. One approach is to use user-supplied compensating actions, but there may be other models that are possible, or it may be possible to automatically derive such compensating action for certain classes of transactions.
