import org.apache.spark.rdd._
import org.apache.log4j.Logger
import org.apache.log4j.Level
import org.apache.spark.sql.SparkSession


object Final {

  def dfToIdf(df: Int, totalDocs: Long): Double = {
    math.log(totalDocs.toDouble / df)
  }

  def calc_magnitude(v: List[Double]) = {
    math.sqrt(v.map(x => x * x).sum)
  }

  def calc_cosign(doc_map_1: Map[Long, Double], doc_map_2: Map[Long, Double]) = {
    val dot_product = doc_map_1
      .map({case (k, v) => (k, (v, doc_map_2.get(k)))})
      .map({
        case (k, (v1, Some(v2))) => (v1 * v2)
        case (k, (v1, None)) => (0)
      }).sum

    val v1 = doc_map_1.map({case (k, v) => v}).toList
    val v2 = doc_map_2.map({case (k, v) => v}).toList

    dot_product / (calc_magnitude(v1) * calc_magnitude(v2))
  }

  def cosineBetweenDocs(doc1: Int, doc2: Int, tfIdfs: RDD[(Long, Map[Long, Double])]): Double = {
    val doc1TfIdfs = tfIdfs.filter(_._1 == doc1).collect.head._2
    val doc2TfIdfs = tfIdfs.filter(_._1 == doc2).collect.head._2

    calc_cosign(doc1TfIdfs, doc2TfIdfs)
  }

  def main(args: Array[String]): Unit = {
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)

    val spark = SparkSession.builder.appName("Final").master("local[4]").getOrCreate()

//    val conf = new SparkConf().setAppName("Final").setMaster("local[4]")
//    val sc = new SparkContext(conf)

    val moviePath = "./movies.csv"
    val stopWordsPath = "./stopwords.txt"
    val lemmatizationPath = "./lemmatization.txt"


    val moviesRaw = spark.read
      .option("header", "true")
      .option("multiline", "true")
      .option("escape", "\"")
      .csv(moviePath)
      .rdd
      .zipWithIndex
      .map(x => (x._2, x._1.getString(0), x._1.getString(1), x._1.getString(7))) // (id, title, genres)

    //moviesRaw.take(5).foreach(println)

    val stopWords = spark.read
      .textFile(stopWordsPath)
      .rdd
      .map(_.trim.toLowerCase.replaceAll("[^a-z]", ""))
      .collect()
      .toSet

    val lemmatization = spark.read
      .textFile(lemmatizationPath)
      .rdd
      .map(_.split("\\s+"))
      .map(x => (x(1), x(0)))
      .collect()
      .toMap

    val movies = moviesRaw.map { case (id, year, title, plot) =>
      val processedPlot = plot.toLowerCase
        .replaceAll("[^a-z ]", " ")
        .split(" ")
        .filterNot(stopWords.contains)
        .filterNot(_.isEmpty)
        .map(token => lemmatization.getOrElse(token, token))

      (id, year, title, processedPlot)
    }

    val vocabulary = movies
      .flatMap { case (_, _, _, cleanedPlot) => cleanedPlot }
      .distinct

    val vocabularyIndexed = vocabulary
      .zipWithIndex
      .map(_.swap)

    val numDocs = movies.count
    val vocabularySize = vocabularyIndexed.count.toInt

    val vocabIdfs = movies
      .flatMap { case (_, _, _, cleanedPlot) => cleanedPlot }
      .map { word => (word, 1) }
      .reduceByKey(_ + _)
      .zipWithIndex
      .map { case ((word, count), id) => (word, id, count) }
      .map { case (word, id, count) => (word, id, dfToIdf(count, numDocs)) }


    val tokenCounts = movies
      .flatMap { case (id, title, genres, plotTokens) => {
        val tfs = plotTokens
          .groupBy(identity)
          .mapValues(_.size)
          .map { case (token, count) => (token, id, count) }
          .toList
        tfs
      }}

      val word_key = vocabIdfs.map { case (word, wordId, idf) => (word, (wordId, idf)) }

      val tfIdfs = tokenCounts
        .map { case (token, docId, tf) => (token, (docId, tf)) }
        .join(word_key)
        .map { case (token, ((docId, tf), (wordId, idf))) => (docId, (wordId, tf * idf)) }
        .groupByKey
        .mapValues(_.toMap)
        .persist


        val TEST = cosineBetweenDocs(0, 1, tfIdfs)
        println(TEST)


        val SAME = cosineBetweenDocs(1, 1, tfIdfs)
        println(SAME)

  }

}