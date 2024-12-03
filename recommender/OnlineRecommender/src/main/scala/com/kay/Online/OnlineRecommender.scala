package com.kay.Online

import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClient, MongoClientURI}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import redis.clients.jedis.Jedis

//todo STEP1 prepare for the DB connection and output tables
object CompilerHelper extends Serializable {
  // lazy variables, initialized only when used
  // connect to jedis
  lazy val jedis = new Jedis("hadoop102")
  // connect to mongodb: most complex operations: delete, index
  lazy val mongoClient = MongoClient(MongoClientURI("mongodb://192.168.91.1:27017/recommender"))

}
case class MongConfig(uri:String,db:String)

case class Recommendation(productId:Int, score:Double)

case class UserRecs(userId:Int, recs:Seq[Recommendation])

case class ProductRecs(productId:Int, recs:Seq[Recommendation])

object OnlineRecommender{
  // define constants and tables
  val STREAM_RECS = "StreamRecs"
  // read sim_matrix from productRecs table
  val PRODUCT_RECS = "ProductRecs"

  val MONGODB_RATING_COLLECTION = "Rating"

  val MAX_USER_RATING = 20
  val MAX_SIM_PRODUCTS_NUM = 20

  import scala.collection.JavaConversions._
  def getUserRencentlyRatings(num: Int, userId: Int, jedis: Jedis): Array[(Int, Double)] = {
    // get scores from redis
    jedis.lrange( "userId:" + userId.toString, 0, num)
      .map{item=>
        val attr = item.split("\\:")
        ( attr(0).trim.toInt, attr(1).trim.toDouble)
      }.toArray   //Array[(productId,score)]

  }

  def getTopSimProducts(num: Int,
                        productId: Int,
                        userId: Int,
                        simProducts: collection.Map[Int, collection.immutable.Map[Int, Double]])
                       (implicit mongConfig: MongConfig): Array[Int] = {
    // from sim_matrix get the sim_list of the current product
    val allSimProducts = simProducts(productId).toArray //Array[(productId, score)]

    // filter out the rated products
    val ratingCollection = CompilerHelper.mongoClient(mongConfig.db)( MONGODB_RATING_COLLECTION )
    val ratingExisted = ratingCollection.find( MongoDBObject("userId" -> userId))
      .toArray
      .map{item =>
        item.get("productId").toString.toInt
      }
    allSimProducts.filter( x => ! ratingExisted.contains(x._1))
      .sortWith(_._2 > _._2)
      .take(num)
      .map(x=>x._1)  //Array[productId]
    
  }

  def getProductsSimScore(product1: Int,
                          product2: Int,
                          simProducts: collection.Map[Int, collection.immutable.Map[Int, Double]]): Double = {
    simProducts.get(product1) match {
      case Some(sims) => sims.get(product2) match {
        case Some(score) => score
        case None => 0.0
      }
      case None => 0.0
    }
  }

  def log(m: Int): Double ={
    val N = 10
    math.log(m)/math.log(N)
  }

  def computeProductScores(candidateProducts: Array[Int],
                           userRencentlyRatings: Array[(Int, Double)],
                           simProducts: collection.Map[Int, collection.immutable.Map[Int, Double]])
  :Array[(Int, Double)] ={
    //ArrayBuffer to save the candidate scores
    val score = scala.collection.mutable.ArrayBuffer[(Int, Double)]()
    // two maps for each product: productId -> (increaseCount, decreaseCount)
    val increMap = scala.collection.mutable.HashMap[Int,Int]()
    val decreMap = scala.collection.mutable.HashMap[Int,Int]()

    for (candidate <- candidateProducts; userRencentlyRating <- userRencentlyRatings){
      // get the (candidate, userRencentlyRating) from sim_matrix
      val simScore = getProductsSimScore( candidate, userRencentlyRating._1, simProducts)
      if (simScore > 0.4){
        score += ((candidate, simScore * userRencentlyRating._2))
        if (userRencentlyRating._2 > 3){
          increMap(candidate) = increMap.getOrDefault(candidate, 0) + 1
        } else {
          decreMap(candidate) = decreMap.getOrDefault(candidate, 0) + 1
        }
      }
    }
    score.groupBy(_._1).map{
      case (productId, scoreList) =>
        (productId, scoreList.map(_._2).sum/scoreList.length + log(increMap.getOrDefault(productId, 1))
          + log(decreMap.getOrDefault(productId, 1)))
    }.toArray
      .sortWith(_._2 > _._2)

  }

  def saveDataToMongoDB(userId: Int, streamRecs: Array[(Int, Double)])(implicit mongConfig: MongConfig): Unit = {
    //Connect to StreamRecs
    val streaRecsCollection = CompilerHelper.mongoClient(mongConfig.db)(STREAM_RECS)

    streaRecsCollection.findAndRemove(MongoDBObject("userId" -> userId))
    streaRecsCollection.insert(MongoDBObject("userId" -> userId, "recs" ->
      streamRecs.map( x => MongoDBObject("productId"->x._1,"score"->x._2)) ))
  }

  def main(args: Array[String]): Unit = {
    val config = Map(
      "spark.cores" -> "local[*]",
      "mongo.uri" -> "mongodb://192.168.91.1:27017/recommender",
      "mongo.db" -> "recommender",
      "kafka.topic" -> "recommender"
    )
    val sparkConf = new SparkConf().setMaster(config("spark.cores")).setAppName("OnlineRecommender")
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()
    val sc = spark.sparkContext
    val ssc = new StreamingContext(sc, Seconds(2))
    // for dataset and dataframe
    import spark.implicits._
    implicit val mongConfig = MongConfig(config("mongo.uri"), config("mongo.db"))

    // todo STEP2: prepare the data: sim_matrix(broadcast) --> this is the core data(1, Mongodb) in online recommendation
    // todo another core data is the recent ratings cached in Redis
    val simProductMatrix = spark.read
      .option("uri", mongConfig.uri)
      .option("db", mongConfig.db)
      .option("collection", PRODUCT_RECS)
      .format("com.mongodb.spark.sql")
      .load()
      .as[ProductRecs].rdd
      .map{
        item => (item.productId, item.recs.map(x => (x.productId, x.score)).toMap)
      }
      .collectAsMap() //map(key, map(k, v))
    // broadcast the map to all nodemanger nodes
    val simProductMatrixBC = sc.broadcast(simProductMatrix)

    // create kafka config
    val kafkaPara = Map(
      "bootstrap.servers" -> "hadoop102:9092",
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "recommender",
      "auto.offset.reset" -> "latest"
    )
    // create a kafka directed stream
    val kafkaStream = KafkaUtils.createDirectStream[String, String](ssc,
      LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String,String](Array(config("kafka.topic")),
        kafkaPara))

    // pre-processing: streaming from incoming rating
    val ratingStream = kafkaStream.map{
      msg => var attr = msg.value().split("\\|")
        (attr(0).toInt, attr(1).toInt, attr(2).toDouble, attr(3).toInt)
    }

    // process streaming data
    ratingStream.foreachRDD{
      rdds => rdds.foreach{
        case (userId, productId, score, timestamp) =>
          println("rating data coming >>>>>>>>>>>>>>>>")
          // 1. form redis get the recent scores from the current customer as Array[(productId,score)]
        val userRencentlyRatings = getUserRencentlyRatings(MAX_USER_RATING, userId, CompilerHelper.jedis)


        // 2. from the sim_matrix get the most similar product list to the current product
          // filter out the products that have been rated by the current user (not recommended again)
          // save as an Array[productId]
        val candidateProducts = getTopSimProducts(MAX_SIM_PRODUCTS_NUM, productId, userId, simProductMatrixBC.value)


        // 3. calculate the rank of each products to be chosen, get the real-time recommendation list
        // save as Array[(productId, score)]
        val streamRecs = computeProductScores(candidateProducts, userRencentlyRatings, simProductMatrixBC.value)

        // 4. store the list to mongodb
        saveDataToMongoDB(userId, streamRecs)

      }
    }
    ssc.start()
    println("streaming starting >>>>>>>>>>>>>")
    ssc.awaitTermination()







  }


}

