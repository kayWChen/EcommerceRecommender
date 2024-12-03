package com.kay.ItemCF


import org.apache.spark.SparkConf
import org.apache.spark.ml.feature.{HashingTF, IDF, Tokenizer}
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.jblas.DoubleMatrix

// This approach utilizes the cos_similarity between two products based on the features extracted from the contents.

case class MongoConfig(uri: String, db: String)
case class Product(productId: Int, name: String, imageURL: String, categories: String, tags: String)
case class Recommendation(productId: Int, score: Double)
// similarity list for products
case class ProductRecs(productId: Int, recs: Seq[Recommendation])

object ContentRecommender {
  val MONGODB_RRODUCT_COLLECTION = "product"
  // add tables
  val CONTENT_PRODUCT_RECS = "ContentBasedProductRecs"

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
    val productDF = spark
      .read
      .option("uri",mongoConfig.uri)
      .option("collection",MONGODB_RRODUCT_COLLECTION)
      .format("com.mongodb.spark.sql")
      .load()
      .as[Product]
      .map{
        product=> (product.productId, product.name, product.tags.map(c => if ( c == '|') ' ' else c))
      }
      .toDF("productId","productName", "productTags")
      .cache() // to avoid repeated data loading for each job

    // feature extracting with TF-IDF
    val tokenizer = new Tokenizer().setInputCol("productTags").setOutputCol("words")
    val wordsDataDF: DataFrame = tokenizer.transform(productDF)

    val hashingTF = new HashingTF().setInputCol("words").setOutputCol("rawFeature").setNumFeatures(800)
    val featurizedDF = hashingTF.transform(wordsDataDF)

    val idf = new IDF().setInputCol("rawFeature").setOutputCol("features")
    val rescaledData = idf.fit(featurizedDF).transform(featurizedDF)

    val productFeatures = rescaledData.map{
          //(productId, new DoubleMatrix(features))
          // Spark SQL and DataFrame APIs to represent a single row of data within a DataFrame or Dataset
      row => (row.getAs[Int]("productId"), row.getAs[SparseVector]("features").toArray)
    }.rdd
      .map{
        case (productId, features) => (productId, new DoubleMatrix(features))
      }

    // computing similarity matrix : ONLY TAKE rdd
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
      .option("collection", CONTENT_PRODUCT_RECS)
      .mode("overwrite")
      .format("com.mongodb.spark.sql")
      .save()

    spark.stop()

  }
  def cosinSim(product1: DoubleMatrix, product2: DoubleMatrix) : Double = {
    product1.dot(product2)/(product1.norm2()* product2.norm2())
  }

}
