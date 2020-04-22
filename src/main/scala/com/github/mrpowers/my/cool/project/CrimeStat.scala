package com.github.mrpowers.my.cool.project

import org.apache.log4j.{Level,Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window

//С помощью Spark соберите агрегат по районам (поле district) со следующими метриками:
//crimes_total - общее количество преступлений в этом районе
//crimes_monthly - медиана числа преступлений в месяц в этом районе
//frequent_crime_types - три самых частых crime_type за всю историю наблюдений в этом районе, объединенных через запятую с одним пробелом “, ” , расположенных в порядке убывания частоты
//crime_type - первая часть NAME из таблицы offense_codes, разбитого по разделителю “-” (например, если NAME “BURGLARY - COMMERICAL - ATTEMPT”, то crime_type “BURGLARY”)
//lat - широта координаты района, расчитанная как среднее по всем широтам инцидентов
//lng - долгота координаты района, расчитанная как среднее по всем долготам инцидентов



object CrimeStat{
	
	def main(args: Array[String]) {
		 val crime_path = args(0)
		 val code_path = args(1)
		 val out = args(2)

		//создаем сессию
		val ses = SparkSession
		  .builder()
		  .appName("crimestat")
		  .master("local[2]")
		  .getOrCreate()
		import ses.implicits._

		    val dataFrameReader = ses.read

		val crimeDF = dataFrameReader
		.option("header", "true")
		.option("inferSchema", value = true)
		.csv(crime_path)

		val codesDF = dataFrameReader
		.option("header", "true")
		.option("inferSchema", value = true)
		.csv(code_path)

		//crimeDF.show()
		//codesDF.show()

		crimeDF.createOrReplaceTempView("crime")
		codesDF.createOrReplaceTempView("codes")

    	val crimesTotalX = ses.sql("SELECT distinct code from codes")
		
		crimesTotalX.createOrReplaceTempView("codes1")
		//
		 val crimesTotal = ses.sql("SELECT /*+ BROADCAST (co) */ nvl(DISTRICT,'NA') as DISTRICT,"+ 
		 "COUNT(*) as CrimesTotal,avg(Lat) as Lat, avg(Long) as Long " +
      "FROM crime cr inner join codes1 co on (cr.OFFENSE_CODE=co.CODE) group by nvl(DISTRICT,'NA') ")

    crimesTotal.createOrReplaceTempView("crimesTotal")
    val crimesTotal2 = ses.sql("SELECT sum(CrimesTotal) from crimesTotal")

    crimesTotal2.show()

    val crimesTotal1 = ses.sql("SELECT /*+ BROADCAST (co) */ COUNT(*) as CrimesTotal " +
      "FROM crime cr inner join codes1 co on (cr.OFFENSE_CODE=co.CODE) ")

    crimesTotal1.show()

    val crimesMonthly1 = ses.sql("SELECT count(*) as x,YEAR,MONTH, nvl(DISTRICT,'NA') as DISTRICT FROM crime cr group by YEAR,MONTH,DISTRICT")

    crimesMonthly1.createOrReplaceTempView("crimesmonthly")

    val crimesMonthly = ses.sql("SELECT percentile_approx(x,0.5) as CrimesMonthly, nvl(DISTRICT,'NA') as DISTRICT"+ 
	" FROM crimesmonthly group by nvl(DISTRICT,'NA')")


    crimesMonthly.createOrReplaceTempView("crimesmonthly1")
    val crimesMonthly2 = ses.sql("SELECT sum(CrimesMonthly) FROM crimesmonthly1")
    crimesMonthly2.show()


    val frequent_crime_types1  = ses.sql("SELECT /*+ BROADCAST (co) */ nvl(DISTRICT,'NA') as" +
	" DISTRICT, split(co.NAME, ' - ')[0] NAME, COUNT(*) as CrimesbyNameDistrict " +
      "FROM crime cr inner join codes co on (cr.OFFENSE_CODE=co.CODE) group by nvl(DISTRICT,'NA'), split(co.NAME, ' - ')[0] order by DISTRICT, CrimesbyNameDistrict desc")


    val w= Window.partitionBy("DISTRICT").orderBy(col("CrimesbyNameDistrict").desc)

    val frequent_crime_types2 = frequent_crime_types1
      .withColumn("rn", row_number.over(w))
      .filter(col("rn") < 4)
      .orderBy(col("DISTRICT"),col("CrimesbyNameDistrict").desc)
      .drop("CrimesbyNameDistrict")
      .drop("rn")

    val frequent_crime_types = frequent_crime_types2
      .groupBy("DISTRICT")
      .agg(collect_list("NAME").alias("frequency_crime_types"))
    frequent_crime_types.show(20,false)

    val res = crimesTotal.join(crimesMonthly,"DISTRICT").join(frequent_crime_types,"DISTRICT")
    res.write.parquet(out + "/out.parquet")

	
	}
}
