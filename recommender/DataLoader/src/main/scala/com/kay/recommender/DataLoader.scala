package com.kay.recommender

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import org.apache.spark.SparkConf
import org.apache.spark.sql.{DataFrame, SparkSession}


object DataLoader {

  val PRODUCT_DATA_PATH = "D:\\MyProjects-java\\EcommerceRecommender\\recommender\\DataLoader\\src\\main\\resources\\products.csv"
  val RATING_DATA_PATH = "D:\\MyProjects-java\\EcommerceRecommender\\recommender\\DataLoader\\src\\main\\resources\\ratings.csv"
  val MONGODB_PRODUCT_COLLECTION = "product"
  val MONGODB_RATING_COLLECTION = "rating"

  def main(args: Array[String]): Unit = {
   // build spark env
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://192.168.91.1:27017/recommender",
      "mongo.db" -> "recommender"
    )

    val conf = new SparkConf().setMaster(config("spark.cores")).setAppName("DataLoader")
    val spark = SparkSession.builder().config(conf).getOrCreate()

    // load data
    import spark.implicits._

    val productRDD = spark.sparkContext.textFile(PRODUCT_DATA_PATH)
    val ratingRDD = spark.sparkContext.textFile(RATING_DATA_PATH)
    val productDF = productRDD.map{
      line => {
        val list = line.split("\\^")
        Product(list(0).toInt, list(1).trim, list(4).trim, list(5).trim,list(6).trim)
      }
    }.toDF()

    val ratingDF = ratingRDD.map{
      line => {
        val list = line.split(",")
        Rating(list(0).toInt, list(1).toInt, list(2).toDouble, list(3).toInt)
      }
    }.toDF()

    implicit val mongoConfig = MongoConfig( config("mongo.uri"), config("mongo.db"))
    storeDataInMangoDB(productDF, ratingDF)

    spark.stop()
  }
  def storeDataInMangoDB(productDF: DataFrame, ratingDF: DataFrame)(implicit mongoConfig: MongoConfig) = {
    // set a connection
    val mongoClient = MongoClient( MongoClientURI(mongoConfig.uri))
    // select table to manage database -> table
    val productCollection = mongoClient(mongoConfig.db)(MONGODB_PRODUCT_COLLECTION)
    val ratingCollection = mongoClient(mongoConfig.db)(MONGODB_RATING_COLLECTION)

    // drop all tables
    productCollection.dropCollection()
    ratingCollection.dropCollection()
    // store the data into table
    productDF.write
      .option("uri",mongoConfig.uri)
      .option("collection", MONGODB_PRODUCT_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()
    ratingDF.write
      .option("uri",mongoConfig.uri)
      .option("collection", MONGODB_RATING_COLLECTION)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()
    // set index
    productCollection.createIndex(MongoDBObject("productId" -> 1)) // asc
    ratingCollection.createIndex(MongoDBObject("useId" -> 1))
    ratingCollection.createIndex(MongoDBObject("productId" -> 1))

    mongoClient.close()
  }






}
// Table Mapping
//  3982
//  Fuhlen 富勒 M8眩光舞者时尚节能无线鼠标(草绿)(眩光.悦动.时尚炫舞鼠标 12个月免换电池 高精度光学寻迹引擎 超细微接收器10米传输距离)
//  1057,439,736
//  B009EJN4T2
//  https://images-cn-4.ssl-images-amazon.com/images/I/31QPvUDNavL._SY300_QL70_.jpg
//  外设产品|鼠标|电脑/办公
//  富勒|鼠标|电子产品|好用|外观漂亮
case class Product(productId: Int, name: String, imageURL: String, categories: String, tags: String)

// 4867,
// 457976,
// 5.0,
// 1395676800
case class Rating(userId: Int, productId: Int, score: Double, timestamp: Int)
// MongoDB connection config
case class MongoConfig(uri: String, db: String)