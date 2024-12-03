package com.kay.statistics

import java.text.SimpleDateFormat
import java.util.Date

import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}

case class Rating(userId: Int, productId: Int, score: Double, timestamp: Int)
case class MongoConfig(uri: String, db: String)
object StatisticsRecommender {

  val MONGODB_RATING_COLLECTION = "rating"

  //统计的表的名称
  val RATE_MORE_PRODUCTS = "RateMoreProducts"
  val RATE_MORE_RECENTLY_PRODUCTS = "RateMoreRecentlyProducts"
  val AVERAGE_PRODUCTS = "AverageProducts"

  def main(args: Array[String]): Unit = {

    // build spark env
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://192.168.91.1:27017/recommender",
      "mongo.db" -> "recommender"
    )

    val conf = new SparkConf().setMaster(config("spark.cores")).setAppName("StatisticsRecommender")
    val spark = SparkSession.builder().config(conf).getOrCreate()
    val mongoConfig = MongoConfig(config("mongo.uri"),config("mongo.db"))

    // load data
    import spark.implicits._
    val data = spark.read.option("uri", mongoConfig.uri)
      .option("db", mongoConfig.db)
      .option("collection", MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Rating].toDF()

    data.createOrReplaceTempView("ratings")
    // historical rating ranks: according to numbers of reviews
    val rateMoreProductsDF = spark.sql("select productId, count(productId) as count from ratings group by productId order by count desc")
    storeDFInMongo(rateMoreProductsDF, RATE_MORE_PRODUCTS)(mongoConfig)

    // recent rating ranks: according timestamps
    val simpleDateFormat = new SimpleDateFormat("yyyyMM")
    spark.udf.register("changeDate", (x : Int) => simpleDateFormat.format(new Date(x * 1000L)).toInt)
    val ratingOfYearMonth = spark.sql("select productId, score, changeDate(timestamp) yearmonth from ratings")
    ratingOfYearMonth.createOrReplaceTempView("ratingOfMonth")
    val rateRecentlyProductsDF = spark.sql("select productId, count(productId) count, yearmonth from ratingOfMonth group by yearmonth, productId order by yearmonth desc, count desc")
    storeDFInMongo(rateRecentlyProductsDF, RATE_MORE_RECENTLY_PRODUCTS)(mongoConfig)

    //ranking by average score
    val averageProductsDF = spark.sql("select productId, avg(score) as avg from ratings group by productId order by avg")
    storeDFInMongo(averageProductsDF, AVERAGE_PRODUCTS)(mongoConfig)

    spark.stop()
  }
  def storeDFInMongo(df : DataFrame, collection: String)(implicit mongoConfig: MongoConfig): Unit ={
    df.write.option("uri",mongoConfig.uri)
      .option("db",mongoConfig.db)
      .option("collection", collection)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

  }

}
