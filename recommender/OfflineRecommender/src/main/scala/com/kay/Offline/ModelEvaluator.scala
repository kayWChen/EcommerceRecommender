package com.kay.Offline

import breeze.numerics.sqrt
import org.apache.spark.SparkConf
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

// case class MongoConfig(uri: String, db: String)
// case class ProductRating(userId: Int, productId: Int, score: Double, timestamp: Int)
object ModelEvaluator {
  val MONGODB_RATING_COLLECTION = "rating"

  def getRMSE(model: MatrixFactorizationModel, testData: RDD[Rating]) = {
    // get prediction input userProduct
    val userProducts = testData.map(rating => (rating.user, rating.product))
    // make the prediction
    val prediction = model.predict(userProducts).map(rating => ((rating.user, rating.product), rating.rating))
    val real = testData.map(rating => ((rating.user, rating.product), rating.rating))
    // calculate the RMSE
    sqrt{
      real.join(prediction).map{
        case((user, product), (real, predict)) =>
          var error = real -predict
          error * error
      }.mean()
    }

  }

  def adjustALSParams(trainRDD: RDD[Rating], testRDD: RDD[Rating]) = {
    val result = for (rank <- Array(100,200,250); lambda <- Array(1, 0.1, 0.01, 0.001))
      yield {
        val model = ALS.train(trainRDD, rank, 10, lambda)
        val rmse = getRMSE(model, testRDD)
        // output
        (rank,lambda,rmse)
      }
    //(rank,lambda,rmse)
    println("------------------------------------------------------------------------------")
    println(result.sortBy(_._3).head)

  }

  def main(args: Array[String]): Unit = {
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://192.168.91.1:27017/recommender",
      "mongo.db" -> "recommender"
    )
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("evaluator")
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()
    implicit val mongoConfig = MongoConfig(config("mongo.uri"), config("mongo.db"))

    import spark.implicits._

    // read data from Mongo
    val ratingRDD = spark.read.option("uri", mongoConfig.uri)
      .option("db", mongoConfig.db)
      .option("collection", MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[ProductRating]
      .rdd
      .map(ele => Rating(ele.userId, ele.productId, ele.score))
      .cache()

    // split the dataset: scala.Array[org.apache.spark.rdd.RDD[T]]
    val splitArray = ratingRDD.randomSplit(Array(0.8, 0.2))
    val trainRDD = splitArray(0)
    val testRDD = splitArray(1)

    // user train set to train a model, and fine-tuning with the best RMSE
    adjustALSParams(trainRDD, testRDD)

    spark.close()

  }
}
