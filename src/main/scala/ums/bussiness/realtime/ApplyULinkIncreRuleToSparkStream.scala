package ums.bussiness.realtime

import java.util.Properties

import com.typesafe.config._
import kafka.javaapi.producer.Producer
import kafka.producer.ProducerConfig
import org.apache.spark.api.java.StorageLevels
import org.apache.spark.sql.SQLContext
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.apache.spark.{Logging, SparkConf, SparkContext}
import org.codehaus.jettison.json.JSONObject
import org.kie.api.KieServices
import ums.bussiness.realtime.common.{HbaseUtilCp, QueryRelatedPropertyInDF, SendMessage, SendToKafka, ParseTable}
import ums.bussiness.realtime.model.flow.UlinkIncre
import ums.bussiness.realtime.model.table._
import scala.collection.mutable.Map

/**
  * Created by zhaikaixuan on 27/07/2017.
  */
object ApplyULinkIncreRuleToSparkStream extends Logging{
  def main(args: Array[String]): Unit = {
    val setting:Config = ConfigFactory.load()
    println("the answer is: " + setting.getString("simple-app.answer"))

    //read data from kafka
    val kafkaZkHost = setting.getString("kafkaZkHost")
    val appName = setting.getString("appName")
    val processInterval = setting.getInt("processInterval")
    val logLevel = setting.getString("logLevel")
    val kafkaTopics = setting.getString("kafkaTopics")
    val kafkaReceiverNum = setting.getInt("kafkaReceiverNum")
    val kafkaGroup = setting.getString("kafkaGroup")
    val kafkaThread = setting.getInt("kafkaThread")
    val conf = new SparkConf().setMaster("local").setAppName(appName)
    //val sc = new SparkContext(conf)
    //val sqlContext = new SQLContext(conf)
    val streamContext = new StreamingContext(conf, Milliseconds(processInterval))
    streamContext.sparkContext.setLogLevel(logLevel)
    val sc = streamContext.sparkContext

    //初始化一个用于汇总的累加器
    //val sumMapAccum = sc.accumulator(Map[String, Double]())(HashMapAccumalatorParam)
    //    val sumMapAccum = sc.accumulator(Map[String, Double]())(HashMapAccumalatorParam)
    val sqlContext = new SQLContext(sc)
    //val topicMapUlinkIncremental = {}
    //val topicMapULinkTraditional = {}
    //val topicMap = kafkaTopics.split(",").map((_, kafkaThread)).toMap
    //ulink增量对应的topic数据读入
    val ulinkIncreTopicMap = scala.collection.immutable.Map("ulink_incremental" -> 1)
    val ulinkIncreKafkaStreams = (1 to kafkaReceiverNum).map { _ =>
      KafkaUtils.createStream(streamContext, kafkaZkHost, kafkaGroup, ulinkIncreTopicMap, StorageLevels.MEMORY_AND_DISK_SER)
    }
    val increLines = streamContext.union(ulinkIncreKafkaStreams)
    //ulink传统对应的topic数据读入
    val ulinkTraTopicMap = scala.collection.immutable.Map("ulink_incremental" -> 1)
    val ulinkTraKafkaStreams = (1 to kafkaReceiverNum).map { _ =>
      KafkaUtils.createStream(streamContext, kafkaZkHost, kafkaGroup, ulinkIncreTopicMap, StorageLevels.MEMORY_AND_DISK_SER)
    }
    val tradLines = streamContext.union(ulinkIncreKafkaStreams)

    //导入需要找相应字段的外部表
    val (sysTxnCodeDf, sysGroupItemInfoDf, sysMapItemDf, bmsStlInfoDf) = ParseTable.parseTable(sc, sqlContext, setting)
    val sysTxnCodeDF = new QueryRelatedPropertyInDF(sqlContext, sysTxnCodeDf)
    val sysGroupItemDF = new QueryRelatedPropertyInDF(sqlContext, sysGroupItemInfoDf)
    val sysMapItemDF = new QueryRelatedPropertyInDF(sqlContext, sysMapItemDf)
    val bmsStlInfoDF = new QueryRelatedPropertyInDF(sqlContext, bmsStlInfoDf)

    //val sys_group_item_info_df = sqlContext.read.parquet("table path").select("Mchnt_Id_Pay", "category")

    //read key-value from table sys_group_item_info
    /*
    val sys_group_item_info_rdd = sqlContext.read.parquet("table path").select("Mchnt_Id_Pay", "category").map(
      row => (row.getAs[String]("Mchnt_Id_Pay"), row.getAs[String]("category"))
    ).rdd

    //put key-value into cache, use igniteRDD for cache
    val igniteContext = new IgniteContext(sc, "resouces/sharedRDD.xml")
    val sharedRDD:IgniteRDD[String, String] = igniteContext.fromCache[String, String]("sys_group_item_info_rdd")
    sharedRDD.savePairs(sys_group_item_info_rdd)
    //igniteRDD for other table
    */

    //kafka setting for output
    /*
    val props: Properties = new Properties()
    props.setProperty("metadata.broker.list", "localhost:2128")
    props.setProperty("serializer.class", "kafka.serializer.StringEncoder")
    props.put("request.required.acks", "1")
    props.put("producer.type", "async")
    val config = new ProducerConfig(props)
    val kafkaProducer = new Producer[String, String](config)
    val sendToKafkaInc:SendMessage = new SendToKafka(kafkaProducer, "ulink_incremental")
    //val sendToKafkaTra:SendMessage = new SendToKafka(kafkaProducer, "ulink_traditional")
    */

    //hbase initialization
    val hbaseUtils = HbaseUtilCp("localhost:2128")
    val summaryName = "summary"
    hbaseUtils.createTable(summaryName)
    //无需从远程服务器上pull，rule包直接放置在本地
    val ks = KieServices.Factory.get()
    val kieContainer = ks.newKieClasspathContainer()
    val kieSession = kieContainer.newKieSession()
    //apply drools rules to each item in rdd
    /*
    val ks = KieServices.Factory.get()
    val releaseId = ks.newReleaseId("com.ums", "ruleEngine", "1.0.0")
    val kieContainer = ks.newKieContainer(releaseId)
    val scanner = ks.newKieScanner(kieContainer)
    //val broadcastKieContainer = sc.broadcast(kieContainer)
    val broadcastKieScanner = sc.broadcast(scanner)
    broadcastKieScanner.value.start(10000L)
    val kieSession = kieContainer.newKieSession("filterSession")
    */
    //kieSession.setGlobal("producer", sendToKafkaInc)

    //val queryServiceImp:QueryRelatedProperty = new QueryRelatedPropertyInDF(sys_group_item_info_df)
    //kieSession.setGlobal("queryService", queryServiceImp)
    val kSession = sc.broadcast(kieSession)
    val clearFlagList = List("1", "2", "3", "4", "5", "6")

    /*
    val items = lines.foreachRDD{
      DiscretizedRdd =>
        DiscretizedRdd.foreachPartition{
    */
    val incrementalResult = increLines.transform{
      rdd => rdd.mapPartitions {
        partition => {
          val newPartition = partition.filter {
            //假设输入的数据的每行交易清单是符合以json字符串格式的 string
            item =>
              val JItem = new JSONObject(item.toString())
              val itemAfterParsing = new UlinkIncre(JItem.getString("TRANS_CD_PAY"),
                JItem.getString("PAY_ST"),
                JItem.getString("TRANS_ST_RSVL"),
                JItem.getString("ROUT_INST_ID_CD"),
                JItem.getString("TRANS_ST"),
                JItem.getString("PROD_STYLE"),
                JItem.getInt("RSVD6"),
                JItem.getString("MCHNT_ID_PAY"),
                JItem.getString("TERM_ID_PAY"),
                false,
                0,
                0)

              //var extendedItem = extendProperty(itemAfterParsing)
              // val cacheRDD = igniteContext.fromCache("sys_group_item_info_rdd")
              //val result = cacheRDD.sql("select ")
              //itemAfterParsing
              //val message = new KeyedMessage[String, String]("topic", "message")
              //kafkaProducer.send(message) //moved to rules
              kSession.value.insert(itemAfterParsing)
              kSession.value.fireAllRules()
              itemAfterParsing.getFilterFlag
          }.map {
            item =>
              val JItem = new JSONObject(item.toString())
              val itemAfterParsing = new UlinkIncre(JItem.getString("TRANS_CD_PAY"),
                JItem.getString("PAY_ST"),
                JItem.getString("TRANS_ST_RSVL"),
                JItem.getString("ROUT_INST_ID_CD"),
                JItem.getString("TRANS_ST"),
                JItem.getString("PROD_STYLE"),
                JItem.getInt("RSVD6"),
                JItem.getString("MCHNT_ID_PAY"),
                JItem.getString("TERM_ID_PAY"),
                false,
                0,
                0)
              //parsing
              //query properties
              //根据TRANS_CD_PAY去SYS_TXN_CODE_INFO表中找到相应的是否纳入清算
              val settleFlag = sysTxnCodeDF.queryProperty("settleFlag", "txn_key", JItem.getString("TRANS_CD_PAY"), "=")
              itemAfterParsing.setClearingFlag(settleFlag)
              //根据TRANS_CD_PAY去SYS_TXN_CODE_INFO表中找到相应的借贷
              val dcFlag = sysTxnCodeDF.queryProperty("dcFlag", "txn_key", JItem.getString("TRANS_CD_PAY"), "=")
              itemAfterParsing.setDcFlag(dcFlag.toInt)
              //根据 Mchnt_Id_Pay 字段去 sys_group_item_info 里获取分组信息
              val groupId = sysGroupItemDF.queryProperty("groupId", "item", JItem.getString("Mchnt_Id_Pay"), "=")
              itemAfterParsing.setGroupId(groupId)
              //TODO,sys_map_item_info的表结构,商户编号对应于哪个字段
              //源字段为 Mchnt_Id_Pay+ Term_Id_Pay，根据源字段去清分映射表 sys_map_item_info 中查找结果字段，并将结果字段作为入账商户编号
              val merNo: String ={
                val merNoByTer = sysMapItemDF.queryMerNo("map_result", "src_item", JItem.getString("MCHNT_ID_PAY")+JItem.getString("TERM_ID_PAY"), "=", 1)
                val merNoByUlink = if (JItem.getString("RSVD1").substring(0,3).equals("SML")){
                  sysMapItemDF.queryMerNo("map_result", "src_item", JItem.getString("RSVD1").substring(50, 57), "=", 1068)
                }else{
                  ""
                }
                val merNoByQuery=if (merNoByUlink.equals("")){
                  merNoByTer
                }else{
                  merNoByUlink
                }
                if(merNoByQuery.equals("")){
                  JItem.getString("MCHNT_ID_PAY")
                }else{
                  merNoByQuery
                }
              }
              /*            val merNo = sysMapItemDF.queryMerNo("map_result", "src_item", JItem.getString("MCHNT_ID_PAY")+JItem.getString("TERM_ID_PAY"), "=", 1)
                          if (JItem.getString("RSVD1").substring(0,3).equals("SML")){
                            val merNo = sysMapItemDF.queryMerNo("map_result", "src_item", JItem.getString("RSVD1").substring(50, 57), "=", 1068)
                          }*/

              itemAfterParsing.setMerNo(merNo)
              //TODO,do not need merid anymore
              /*            val merId = bmsStlInfoDF.queryProperty("mer_id", "mer_no", merNo, "=")
                          itemAfterParsing.setMerId(merId.toInt)*/
              //查看交易金额
              itemAfterParsing.setTransAmt(JItem.getDouble("TRANS_AMT"))
              //val jo = new JSONObject(itemAfterParsing)
              //jo.toString
              itemAfterParsing

          }.filter {   //根据清算标志位判断是否纳入清算
            item =>
              clearFlagList.contains(item.getClearingFlag)
          }.map {   //计算手续费，
            //TODO, 按商户编号汇总
            item =>
              val creditMinAmt = bmsStlInfoDF.queryPropertyInBMS_STL_INFODF("credit_min_amt", "mer_no", item.getMerNo).getOrElse("-1")
              val creditMaxAmt = bmsStlInfoDF.queryPropertyInBMS_STL_INFODF("creditMaxAmt", "mer_no", item.getMerNo).getOrElse("-1")
              bmsStlInfoDF.queryPropertyInBMS_STL_INFODF("credit_calc_type", "mer_no", item.getMerNo) match {
                case Some(calType) => {
                  if (calType == "10"){
                    val creditCalcRate = bmsStlInfoDF.queryPropertyInBMS_STL_INFODF("credit_calc_rate", "mer_no", item.getMerNo).getOrElse("-1")
                    if (creditCalcRate.toDouble * item.getTransAmt < creditMinAmt.toDouble) item.setExchange(creditMinAmt.toDouble)
                    else if (creditCalcRate.toDouble * item.getTransAmt > creditMaxAmt.toDouble) item.setExchange(creditMaxAmt.toDouble)
                    else item.setExchange(creditCalcRate.toDouble * item.getTransAmt)
                  }

                }
                case Some("11") => {
                  val creditCalcAmt = bmsStlInfoDF.queryPropertyInBMS_STL_INFODF("credit_calc_amt", "mer_no", item.getMerNo).getOrElse("-1")
                  if (creditCalcAmt < creditMinAmt) item.setExchange(creditMinAmt.toDouble)
                  else if (creditCalcAmt > creditMaxAmt) item.setExchange(creditMaxAmt.toDouble)
                  else item.setExchange(creditCalcAmt.toDouble)
                }
                case None => {
                  item.setNoBmsStlInfo(true)
                  item.setExchange(0)
                }
              }
              //sumMapAccum += Map(item.getMerId.toString -> (item.getTransAmt - item.getExchange))
              //            sumMapAccum += Map(item.getMerId.toString -> (item.getTransAmt - item.getExchange))
              item
          }.toList
          newPartition.iterator
        }
      }
    }
    //保存汇总信息到HBase
    /*
    val columnName = "sum"
    for ((k, v) <- sumMapAccum.value){
      hbaseUtils.writeTable(summaryName, k, columnName, v.toString)
    }
    */
    //    val columnName = "sum"
    //    for ((k, v) <- sumMapAccum.value){
          //hbaseUtils.writeTable(summaryName, k, columnName, v.toString)
         hbaseUtils.writeTable(summaryName, k, columnName, v.toString)
    //    }
    //sumMapAccum.toString()
    incrementalResult.saveAsObjectFiles("ums_poc", ".obj")
    //汇总
    val sum = Map[String, Double]()
    incrementalResult.foreachRDD{
      rdd =>
        val sumMapAccum = sc.accumulator(Map[String, Double]())(HashMapAccumalatorParam)
        rdd.map{
          item =>
            sumMapAccum += Map(item.getMerId.toString -> (item.getTransAmt - item.getExchange))
      }
        sum ++= sumMapAccum.value.map{ case (k, v) => k -> (v + sum.getOrElse(k, 0.toDouble))}
    }

    streamContext.start()
    streamContext.awaitTermination()
    streamContext.stop(true, true)

  }

  //def extendProperty(propertyValueMatched:String, propertyNameMatched:String, extendProperties:List[String], targetCacheRDD:IgniteRDD[])
}
