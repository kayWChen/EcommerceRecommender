package com.kay.ItemCF


import org.apache.spark.SparkConf

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.jblas.DoubleMatrix

// This recommendation algorithm is similar to cos_similarity based on ALS model.
// It utilizes the counts of users action to compute the similarity of two products.

case class ProductRating(userId: Int, productId: Int, score: Double, timestamp: Int)
case class MongoConfig(uri: String, db: String)
case class Recommendation(productId: Int, score: Double)
// similarity list for products
case class ProductRecs(productId: Int, recs: Seq[Recommendation])

object ItemCFRecommender {
  val MONGODB_RATING_COLLECTION = "rating"
  // add tables
  val ITEM_CF_RECS = "ItemCFProductRecs"

  val MAX_RECOMMENDATION = 20

  def cooccurrenceSim(coCount: Long, count1: Long, count2: Long): Double = {
    coCount / math.sqrt( count1 * count2)
    }

  def main(args: Array[String]): Unit = {
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://192.168.91.1:27017/recommender",
      "mongo.db" -> "recommender"
    )

    // 创建spark session
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("ItemCFRecommendation")
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()

    implicit val mongoConfig = MongoConfig(config("mongo.uri"),config("mongo.db"))

    import spark.implicits._

    //Read from MongoDB
    val ratingDF = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_RATING_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[ProductRating]
      .map{
        rating=> (rating.userId, rating.productId, rating.score)
      }
      .toDF("userId", "productId", "rating")
      .cache() // to avoid repeated data loading for each job

    // count rating num for each product
    val productCountDf = ratingDF.groupBy("productId").count()
    val ratingWithCount = ratingDF.join(productCountDf, "productId")

    val jointDF = ratingWithCount.join(ratingWithCount, "userId")
        .toDF("userId", "product1", "score1", "count1", "product2", "score2", "count2")
        .select("userId", "product1", "count1", "product2", "count2")
    jointDF.createOrReplaceTempView("joint")

    val coocurenceDF: DataFrame = spark
      .sql("select product1, product2, count(userId) as coocount, first(count1) as count1, first(count2) as count2 from joint where product1 != product2 group by product1, product2")
        .cache()
    val productRecs = coocurenceDF.map{
      row =>
        // Using the co-occurrence count and individual counts to calculate the co-occurrence similarity.
        val coocSim = cooccurrenceSim( row.getAs[Long]("coocount"), row.getAs[Long]("count1"), row.getAs[Long]("count2") )
        ( row.getAs[Int]("product1"), ( row.getAs[Int]("product2"), coocSim ) )
    }
      .rdd
        .groupByKey()
        .map{
          case (productId, recs) =>
            ProductRecs(productId, recs.toList.sortWith(_._2 > _._2)
              .map(x => Recommendation(x._1, x._2))
              .take(MAX_RECOMMENDATION)
            )
        }
        .toDF()

    productRecs.write.option("uri", mongoConfig.uri)
      .option("db", mongoConfig.db)
      .option("collection", ITEM_CF_RECS)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    spark.stop()

  }

}
