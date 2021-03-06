package ums.bussiness.realtime.common

import org.apache.spark.sql.{DataFrame, SQLContext}

/**
  * Created by zhaikaixuan on 28/07/2017.
  */

trait QueryRelatedProperty{
  def queryProperty(targetCol:String, sourceColName:String, sourceColValue:String, operator:String):String
}

class QueryRelatedPropertyInDF(sqlContext: SQLContext,df:DataFrame) extends QueryRelatedProperty with Serializable{ 
  //private lazy val df = sqlContext.load(tableName)
  override def queryProperty(targetCol: String, sourceColName: String, sourceColValue:String, operator:String): String = {
    val value = df.where(s"$sourceColName $operator $sourceColValue").select(targetCol).first()
    value.toString
  }

  def queryMerNoByUNormalSpeci(targetCol: String, sourceColName1: String, sourceColValue1:String, operator1:String,
                               sourceColName2: String, sourceColValue2:String, operator2:String): String = {
    val value = df.where(s"$sourceColName1 $operator1 $sourceColValue1 and $sourceColName2 $operator2 $sourceColValue2").select(targetCol).first()
    value.toString
  }

  def queryMerNo(targetCol: String, sourceColName: String, sourceColValue:String, operator:String, typeValue: Int):String={
    val value = df.where(s"$sourceColName $operator $sourceColValue and type_id = ${typeValue.toString}").select(targetCol).first()
    value.toString
  }

  def queryPropertyInBMS_STL_INFODF(targetCol: String, sourceColName: String, sourceColValue:String): Option[String] = {
    val df2 = df.where(s"$sourceColName = $sourceColValue")
    if (df2.count() != 0){
      df2.registerTempTable("BMS_STL_INFO")
      val sqlStatement = s"select $targetCol from BMS_STL_INFO order by decode(trim(mapp_main), '1', 1, 2), " +
        s"decode(apptype_id, 1,1,86,2,74,3,18,4,39,5,40,6,68,7)"
      Some(sqlContext.sql(sqlStatement).first().toString())
    }
    else
      None
  }
}
