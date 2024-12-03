# E-commerce Recommender System

This project implements a recommender system with comprehensive features tailored for an online e-commerce platform:

### Key Features

- **Statistical Recommendations:** Generates recommendations using historical statistical data across all users.
- **Offline Recommendations:** Provides personalized suggestions for users based on an item-based Collaborative Filtering (CF) algorithm, implemented with the ALS (Alternating Least Squares) method.
- **Online Recommendations:** Delivers real-time recommendations for users by utilizing an item similarity matrix and the user's recent rating history, stored in Redis for quick access.

### Tech Stack

- **Programming Language:** Scala

- **Computing Framework:**

  Apache Spark

  - *SparkCore* for offline processing
  - *SparkSQL* for statistical analysis
  - *SparkStreaming* for real-time recommendations

- **Databases:** MongoDB and Redis

- **Data Ingestion:** Flume

- **Message Queue:** Kafka