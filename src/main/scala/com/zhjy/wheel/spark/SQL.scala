package com.zhjy.wheel.spark

import java.util.Locale

import com.zhjy.wheel.common.Log
import com.zhjy.wheel.exception._
import org.apache.log4j.Logger
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.ListBuffer

/**
  * Created by zzy on 2018/10/25.
  */
class SQL(spark: SparkSession) extends Core(spark) {

  import SQL._

  private def save_mode: SaveMode = {
    val mode = spark.conf.get("wheel.spark.sql.hive.save.mode")
    mode.toLowerCase(Locale.ROOT) match {
      case "overwrite" => SaveMode.Overwrite
      case "append" => SaveMode.Append
      case "ignore" => SaveMode.Ignore
      case "error" | "default" => SaveMode.ErrorIfExists
      case _ => throw IllegalConfException(s"unknown save mode: $mode." +
        s"accepted save modes are 'overwrite', 'append', 'ignore', 'error'.")
    }
  }
  private def format_source: String = spark.conf.get("wheel.spark.sql.hive.save.format")
  private def coalesce_limit: Long = spark.conf.get("wheel.spark.sql.hive.save.file.lines.limit").toLong
  private def refresh_view: Boolean = spark.conf.get("wheel.spark.sql.hive.save.refresh.view").toBoolean

  def ==>(sql: String, view: String = null,
          cache: Boolean = false,
          level: StorageLevel = StorageLevel.MEMORY_AND_DISK): DataFrame = {
    log.info(s"register ${
      if (view eq null) "no view" else s"view[$view]"
    } sql:$sql")
    val df = spark.sql(sql)
    if (cache) df.persist(level)
    if (view ne null) df.createOrReplaceTempView(view)
    df
  }

  def <==(view: String, table: String = null,
          p: partition = null,
          save_mode: SaveMode = save_mode,
          format_source: String = format_source,
          coalesce_limit: Long = coalesce_limit,
          refresh_view: Boolean = refresh_view): Long = {
    val df = this view view
    val tb = if (table ne null) table else view
    save(df, tb, p, save_mode, format_source, coalesce_limit, refresh_view)
  }

  def register(df: DataFrame, view: String,
               cache: Boolean = false,
               level: StorageLevel = StorageLevel.MEMORY_AND_DISK): DataFrame = {
    if (cache) df.persist(level)
    df.createOrReplaceTempView(view)
    log.info(s"dataframe register view[$view]")
    df
  }

  def read(table: String,
           reality: Boolean = true,
           cache: Boolean = false,
           level: StorageLevel = StorageLevel.MEMORY_AND_DISK): DataFrame = {
    if (reality) catalog.dropTempView(table)
    val df = {
      var df: DataFrame = null
      try {
        df = spark.table(table)
      } catch {
        case _: org.apache.spark.sql.AnalysisException =>
          throw RealityTableNotFoundException(s"reality table not found: $table")
      }
      df
    }
    if (cache) df.persist(level)
    df.createOrReplaceTempView(table)
    df
  }

  def view(view: String): DataFrame = spark.table(view)

  def count(table: String, reality: Boolean = false): Long = {
    if (reality) this.read(table).count
    else spark.table(table).count
  }

  def show(view: String, limit: Int = 20, truncate: Boolean = false,
           reality: Boolean = false): Unit = {
    val df = if (reality) this.read(view) else spark.table(view)
    df.show(limit, truncate)
  }

  def save(df: DataFrame, table: String,
           p: partition = null,
           save_mode: SaveMode = save_mode,
           format_source: String = format_source,
           coalesce_limit: Long = coalesce_limit,
           refresh_view: Boolean = refresh_view): Long = {
    catalog.dropTempView(table)
    log.info(s"$table[save mode:$save_mode,format source:$format_source] will be save")
    log.info(s"schema is:${df.schema}")
    if (df.storageLevel eq StorageLevel.NONE) df.cache
    val ct = df.count
    ct match {
      case 0l =>
        log.warn(s"$table is empty,skip save")
      case _ =>
        log.info(s"$table length is $ct,begin save")
        if (p eq null) {
          val coalesce_num = (1 + ct / coalesce_limit).toInt
          val writer = df.coalesce(coalesce_num).write
          save_mode match {
            case SaveMode.Append =>
              writer.insertInto(table)
            case _ =>
              writer
                .mode(save_mode).format(format_source)
                .saveAsTable(table)
          }
          log.info(s"$table[$coalesce_num flies] is saved")
        } else {
          import org.apache.spark.sql.functions.col
          if (p.values.isEmpty) {
            p ++ df.select(p.col.map(col): _*).distinct.collect
              .map(r => p.col.map(r.getAs[String]))
          }
          val cols = (df.columns.filterNot(p.col.contains) ++ p.col).map(col)
          val pdf = df.select(cols: _*)
          var is_init = p.is_init
          log.info(s"$table is partition table[init:$is_init],will run ${p.values.length} batch")
          p.values.map(v => v.map(s => s"'$s'")).map(v => v.zip(p.col)
            .map(s => s"${s._2}=${s._1}")).foreach(ps => {
            val pdf_ = pdf.where(ps.mkString(" and ")).cache
            val ct_ = pdf_.count
            val coalesce_num = (1 + ct_ / coalesce_limit).toInt
            val writer = pdf_.coalesce(coalesce_num).write
            if (is_init) {
              writer.mode(save_mode)
                .format(format_source)
                .partitionBy(p.col: _*)
                .saveAsTable(table)
              is_init = false
            }
            else {
              spark.sql(s"alter table $table drop if exists partition (${ps.mkString(",")})")
              writer.insertInto(table)
            }
            log.info(s"$table's partition[$ps] is saved,count:$ct_,file number:$coalesce_num")
            pdf_.unpersist
          })
        }
    }
    df.unpersist
    if (refresh_view && ct > 0l) this read table
    this register(df, table)
    ct
  }

  def cache_many(df: DataFrame*): Unit = {
    log.info(s"${df.length} dataframe will be cache")
    df.foreach(_.cache)
  }

  def cache(df: DataFrame): DataFrame = {
    log.info("1 dataframe will be cache")
    df.cache
  }

  def cache(view: String*): Unit = {
    view.foreach(v => {
      log.info(s"$view will be cache")
      catalog.cacheTable(v)
    })
  }

  def uncache(df: DataFrame*): Unit = {
    log.info(s"${df.length} dataframe will be cleared")
    df.foreach(_.unpersist)
    log.info(s"${df.length} dataframe is cleared")
  }

  def uncache_view(view: String*): Unit = {
    view.foreach(v => {
      log.info(s"$view will be cleared")
      catalog.uncacheTable(v)
      log.info(s"$view is cleared")
    })
  }

  def uncache_all(): Unit = {
    log.info("all cache will be cleared")
    catalog.clearCache
    log.info("all cache is cleared")
  }

}

object SQL {

  lazy val log: Logger = Log.get("wheel>spark>sql")

  case class partition(col: String*) {

    private var vals = new ListBuffer[Seq[String]]

    var is_init = false

    def table_init: this.type = {
      is_init = true
      this
    }

    def values: Seq[Seq[String]] = {
      val cl = col.length
      vals.result().map(v => {
        param_verify(cl, v.length)
        v
      }).distinct
    }

    def +(value: String*): this.type = {
      vals += value
      this
    }

    def ++(values: Seq[Seq[String]]): this.type = {
      vals ++= values
      this
    }

    private def param_verify(cl: Int, vl: Int): Unit = {
      if (cl != vl) throw IllegalParamException("partition column length not equal value length")
    }
  }

}
