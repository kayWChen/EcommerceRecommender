package com.kay.Offline

import org.apache.spark.SparkConf
import org.apache.spark.mllib.recommendation.{ALS, Rating}
import org.apache.spark.sql.SparkSession
import org.jblas.DoubleMatrix

// Todo Hint: use a case class to encapsule an interim or final result (DF need a structured collection)
case class ProductRating(userId: Int, productId: Int, score: Double, timestamp: Int)
case class MongoConfig(uri: String, db: String)
case class Recommendation(productId: Int, score: Double)
// recommendation list for users
case class UserRecs(userId: Int, recs: Seq[Recommendation])
// similarity list for products
case class ProductRecs(productId: Int, recs: Seq[Recommendation])

object OfflineRecommender {
  // Todo Hint: where to write constants
  // read table
  val MONGODB_RATING_COLLECTION = "rating"

  // add tables
  val USER_RECS = "UserRecs"
  val PRODUCT_RECS = "ProductRecs"

  val USER_MAX_RECOMMENDATION = 20

  def main(args: Array[String]): Unit = {
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://192.168.91.1:27017/recommender",
      "mongo.db" -> "recommender"
    )

    // 创建spark session
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("OfflineRecommender")
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    implicit val mongoConfig = MongoConfig(config("mongo.uri"),config("mongo.db"))

    import spark.implicits._

    //Read from MongoDB
    val ratingRDD = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[ProductRating]
      .rdd  //rdd required for spark mlib input
      .map(rating=> (rating.userId, rating.productId, rating.score))
      .cache() // to avoid repeated data loading for each job

    // prepare the user data and product data
    val userRDD = ratingRDD.map(_._1).distinct()
    val productRDD = ratingRDD.map(_._2).distinct()

    // major computation logics
    // 1. train an als model
    val trainRDD = ratingRDD.map(ele => Rating(ele._1, ele._2, ele._3))  // encapsule the data with required format
    val (rank, iterations, lambda) = (5, 10, 0.01) // rank for K-dimension
    val model = ALS.train(trainRDD, rank, iterations, lambda)

    // 2. acquire a prediction matrix and userRec list
    // can use a user-product matrix as input
    val userProducts = userRDD.cartesian(productRDD) // RDD[scala.Tuple2[T, U]]
    val prediction = model.predict(userProducts) //usersProducts: RDD[(Int, Int)] -> RDD[Rating]

    val userRecs = prediction.filter(_.rating > 0)
        .map(
          rating => (rating.user,(rating.product, rating.rating))
        )
        .groupByKey()
        .map {
          case (userId, recs) => UserRecs(userId, recs.toList.sortWith(_._2 > _._2).take(USER_MAX_RECOMMENDATION).map(x => Recommendation(x._1, x._2)))
        }.toDF()
    // write a collection to MongoDB
    userRecs.write.option("uri", mongoConfig.uri)
        .option("db", mongoConfig.db)
        .option("collection", USER_RECS)
        .mode("overwrite")
        .format("com.mongodb.spark.sql")
        .save()

    // 3. acquire similarity list for each product
    // RDD[scala.Tuple2[scala.Int, scala.Array[scala.Double]]]
    val productFeatures = model.productFeatures.map{
      case (productId, features) => (productId, new DoubleMatrix(features))
    }

    val productRecs = productFeatures.cartesian(productFeatures).filter {
      case (a, b) => a._1 != b._1
    }.map{
      case(a, b) =>
        val simScore = cosinSim(a._2, b._2)
        (a._1, (b._1, simScore))
    }
      .filter(_._2._2 > 0.4)
      .groupByKey()
      .map {
        case (productId, recs) => ProductRecs(productId, recs.toList.sortWith(_._2 > _._2).map(x => Recommendation(x._1, x._2)))
      }.toDF()

    productRecs.write.option("uri", mongoConfig.uri)
      .option("db", mongoConfig.db)
      .option("collection", PRODUCT_RECS)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    spark.stop()

  }
  def cosinSim(product1: DoubleMatrix, product2: DoubleMatrix) : Double = {
    product1.dot(product2)/(product1.norm2()* product2.norm2())

  }

}
