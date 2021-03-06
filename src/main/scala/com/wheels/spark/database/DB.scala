package com.wheels.spark.database

import com.wheels.exception.IllegalParamException
import com.wheels.spark.SQL
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.apache.spark.sql.functions.rand
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.ArrayBuffer

class DB(sql: SQL) extends Serializable {

  val spark: SparkSession = sql.spark

  case class es(resource: String, conf: Map[String, String] = Map.empty) {

    import org.elasticsearch.spark.sql.EsSparkSQL

    def <==(view: String, r: String = resource,
            cf: Map[String, String] = Map.empty,
            partition_num: Int = sql.DOP): Long = dataframe(sql view view, resource, cf)

    def dataframe(df: DataFrame, r: String = resource,
                  cf: Map[String, String] = Map.empty,
                  partition_num: Int = sql.DOP): Long = {
      val mid = "es.mapping.id"
      val is_uncache = df.storageLevel eq StorageLevel.NONE
      if (is_uncache) df.cache
      val ct = df.count
      val cf_ = Map("es.index.auto.create" -> "true") ++ conf ++ Map("es.resource.write" -> r) ++ cf
      EsSparkSQL.saveToEs(df.repartition(partition_num, rand), {
        if (cf_.exists(_._1 == mid)) cf_
        else cf_ ++ Map(mid -> df.columns.head)
      })
      if (is_uncache) df.unpersist
      ct
    }
  }

  case class jdbc(driver: String, url: String,
                  user: String = null, pwd: String = null,
                  global_conf: Map[String, String] = Map.empty) {

    import java.sql.{Connection, DriverManager, Statement}
    import com.wheels.common.Conf.WHEEL_SPARK_SQL_JDBC_SAVE_MODE

    object DBP {
      lazy val MYSQL = "mysql"
      lazy val POSTGRESQL = "postgresql"
      lazy val ORACLE = "oracle"
      lazy val WHEELS_PART_COL = "wheels_part_col"
      lazy val ORACLE_PART_NUM = "1000"
      lazy val TABLE_ALIAS = "wheels_jdbc_tb"

    }

    import DBP._

    def ==>(table: String, alias: String = null, conf: Map[String, String] = Map.empty): DataFrame = {
      val is_sql = if (table.contains(" ")) {
        if (alias eq null)
          throw IllegalParamException("when use sql table,must have a alias.")
        if (conf.getOrElse("partitionColumn", null) eq null)
          throw IllegalParamException("when use sql table,must have a partitionColumn.")
        true
      } else false
      val df = spark.read.format("jdbc")
        .options(read_ops(if (is_sql) s"($table) $TABLE_ALIAS" else table, conf)).load()
      val r = if (driver.contains(ORACLE)) df.drop(WHEELS_PART_COL) else df
      sql register(r, if (alias ne null) alias else table)
    }

    def <==(view: String, partition_num: Int = sql.DOP, table: String = null,
            save_mode: SaveMode = sql.get_save_mode(WHEEL_SPARK_SQL_JDBC_SAVE_MODE),
            conf: Map[String, String] = Map.empty): Long = {
      val tbn = if (table ne null) table else view
      dataframe(sql view view, tbn, partition_num, save_mode, conf)
    }

    def dataframe(df: DataFrame, table: String, partition_num: Int = sql.DOP,
                  save_mode: SaveMode = sql.get_save_mode(WHEEL_SPARK_SQL_JDBC_SAVE_MODE),
                  conf: Map[String, String] = Map.empty): Long = {
      val is_uncache = df.storageLevel eq StorageLevel.NONE
      if (is_uncache) df.cache
      val ct = df.count

      df.repartition(partition_num, rand)
        .write
        .format("jdbc")
        .options(save_ops(table, conf))
        .mode(save_mode)
        .save()
      if (is_uncache) df.unpersist
      ct
    }

    def get_cols(table: String): Seq[String] = {
      val conn = admin().conn
      val rs = conn.getMetaData.getColumns(conn.getCatalog, null, table, "%")
      val cols = ArrayBuffer[String]()
      while (rs.next()) cols.append(rs.getString("COLUMN_NAME").toLowerCase)
      conn.close()
      cols
    }

    def get_first_col(table: String): String = {
      val conn = admin().conn
      val rs = conn.getMetaData.getColumns(conn.getCatalog, null, table, "%")
      rs.next()
      val first_col = rs.getString("COLUMN_NAME").toLowerCase
      conn.close()
      first_col
    }

    private def read_ops(table: String, conf: Map[String, String]): Map[String, String] = {
      (Map(
        "driver" -> driver,
        "url" -> url,
        "dbtable" -> {
          if (driver.contains(ORACLE))
            s"(select t.*,mod(rownum,$ORACLE_PART_NUM)+1 $WHEELS_PART_COL from $table ${
              if (table.contains(" ")) "" else "t"
            }) tb"
          else table
        },
        "user" -> user,
        "password" -> pwd,
        "fetchsize" -> "5000"
      ) ++ {
        val pn = {
          if (sql.DOP > 10) 10 else sql.DOP
        }.toString
        val pc = {
          if (driver.contains(MYSQL) || driver.contains(POSTGRESQL)) {
            if (table.contains(" ") || (conf.getOrElse("partitionColumn", null) ne null)) ""
            else {
              val first_col = get_first_col(table)
              s"((ascii(md5($first_col)) + $pn) % $pn)"
            }
          }
          else if (driver.contains(ORACLE)) WHEELS_PART_COL
          else null
        }
        if (pc ne null) {
          Map(
            "numPartitions" -> pn,
            "lowerBound" -> "0",
            "upperBound" -> {
              if (driver.contains(ORACLE)) ORACLE_PART_NUM
              else pn
            },
            "partitionColumn" -> pc
          )
        } else Map.empty[String, String]
      } ++ {
        if (conf.isEmpty) global_conf else conf
      }).filter(_._2 ne null)
    }

    private def save_ops(table: String, conf: Map[String, String]): Map[String, String] = {
      (Map(
        "driver" -> driver,
        "url" -> url,
        "dbtable" -> table,
        "user" -> user,
        "password" -> pwd,
        "batchsize" -> "1000"
      ) ++ {
        if (conf.isEmpty) global_conf else conf
      }).filter(_._2 ne null)
    }

    case class admin() {

      Class.forName(driver)

      val conn: Connection = DriverManager.getConnection(url, user, pwd)

      def exe(sql_str: String): Boolean = {
        var r = false
        var st: Statement = null
        try {
          st = conn.createStatement()
          r = st.execute(sql_str)
          if (!conn.getAutoCommit) conn.commit()
        }
        catch {
          case e: Exception =>
            throw e
        } finally if (st ne null) st.close()
        r
      }

      def close(): Unit = conn.close()
    }

  }

  /**
    * redis 配置项
    *
    * @param nodes        redis集群地址及端口
    * @param key_col      待写入的key对应的列，默认为k
    * @param value_col    待写入的value对应的列，默认为v
    * @param life_seconds 待写入数据的生命周期，默认为不过期
    * @param timeout      连接redis超时时间
    * @param max_attempts 最大重试次数
    * @param pwd          redis 秘钥
    * @param batch        写入数据批次，默认20
    */
  case class redis_cluster(
                            nodes: Seq[(String, Int)],
                            key_col: String = "k",
                            value_col: String = "v",
                            life_seconds: Int = -1,
                            timeout: Int = 10000,
                            max_attempts: Int = 3,
                            pwd: String = null,
                            batch: Int = 20
                          ) {

    import redis.clients.jedis.{HostAndPort, JedisCluster}
    import org.apache.commons.pool2.impl.GenericObjectPoolConfig

    def <==(input: String): Unit = dataframe(sql view input)

    def dataframe(df: DataFrame): Unit = {
      val nodes_ = new java.util.HashSet[HostAndPort]()
      val timeout_ = timeout
      val max_attempts_ = max_attempts
      val pwd_ = pwd
      val life_seconds_ = life_seconds
      nodes.map(kv => new HostAndPort(kv._1, kv._2)).foreach(nodes_.add)
      df.select(key_col, value_col).coalesce(batch).foreachPartition(rs => {
        var jedis: JedisCluster = null
        val is_forever = life_seconds_ <= 0
        try {
          jedis = new JedisCluster(nodes_, timeout_, timeout_, max_attempts_, pwd_, new GenericObjectPoolConfig())
          while (rs.hasNext) {
            val r = rs.next()
            val k = r.get(0).toString
            val v = r.get(1).toString
            if (is_forever) jedis.set(k, v)
            else jedis.setex(k, life_seconds_, v)
          }
        } catch {
          case e: Exception =>
            throw e
        } finally {
          if (jedis ne null) jedis.close()
        }
      })

    }
  }

  /** *
    * hbase 配置项
    *
    * @param hbase_zookeeper_quorum zk地址串，多个地址使用英文逗号分隔
    * @param port                   zk端口好
    * @param rk_col                 row key 所对应的列名，默认为rk
    * @param family_name            列族名称，默认为cf
    * @param split_keys             预分区字母，默认为0～9，a～f
    * @param overwrite              是否采用完全覆盖写入方式（每次写入前重建表），默认为false
    */
  case class hbase(hbase_zookeeper_quorum: String,
                   port: Int = 2181,
                   rk_col: String = "rk",
                   family_name: String = "cf",
                   split_keys: Seq[String] =
                   Seq("0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                     "a", "b", "c", "d", "e", "f"),
                   ex_save: Boolean = false,
                   ex_hdfs_site: String = null,
                   ex_hdfs_uri: String = null,
                   ex_save_dir: String = "wheels-database-hbase-temp",
                   overwrite: Boolean = false) {

    import org.apache.hadoop.conf.Configuration
    import org.apache.hadoop.hbase.{HBaseConfiguration, HColumnDescriptor, HTableDescriptor, KeyValue, TableName}
    import org.apache.hadoop.hbase.client.{Connection, Admin, ConnectionFactory, Put}
    import org.apache.hadoop.hbase.io.ImmutableBytesWritable
    import org.apache.hadoop.hbase.mapreduce.{HFileOutputFormat2, LoadIncrementalHFiles, TableOutputFormat}
    import org.apache.hadoop.hbase.util.Bytes
    import org.apache.hadoop.mapreduce.{Job, MRJobConfig, OutputFormat}
    import org.apache.hadoop.fs.{FileSystem, Path}

    private lazy val sks: Array[Array[Byte]] = split_keys.map(Bytes.toBytes).toArray

    private lazy val family: Array[Byte] = Bytes.toBytes(family_name)

    private def save_job(table: String): Configuration = {
      val conf = get_conf
      conf.setClass(MRJobConfig.OUTPUT_FORMAT_CLASS_ATTR,
        classOf[TableOutputFormat[_]], classOf[OutputFormat[_, _]])
      conf.set(TableOutputFormat.OUTPUT_TABLE, table)
      conf
    }

    private def save_job_ex(table: String): Job = {
      val conf = get_conf
      conf.set(TableOutputFormat.OUTPUT_TABLE, table)
      conf.setInt("hbase.mapreduce.bulkload.max.hfiles.perRegion.perFamily", 10010)
      if (ex_hdfs_site ne null) {
        if (ex_hdfs_uri ne null) conf.set(FileSystem.FS_DEFAULT_NAME_KEY, ex_hdfs_uri)
        conf.addResource(ex_hdfs_site)
      }
      Job.getInstance(conf)
    }

    private def init(table: String): Unit = {
      var conn: Connection = null
      var admin: Admin = null
      try {
        conn = create_conn
        admin = conn.getAdmin
        val tn = TableName.valueOf(table)
        val desc = new HTableDescriptor(tn)
        desc.addFamily(new HColumnDescriptor(family))
        val is_exist = admin.tableExists(tn)
        if (overwrite && is_exist) {
          admin.disableTable(tn)
          admin.deleteTable(tn)
        }
        if (overwrite || (!is_exist)) admin.createTable(desc, sks)
      } catch {
        case e: Exception =>
          throw e
      } finally {
        if (admin ne null) admin.close()
        if (conn ne null) conn.close()
      }
    }

    private def get_conf: Configuration = {
      val conf = HBaseConfiguration.create()
      conf.set("hbase.zookeeper.property.clientPort", port.toString)
      conf.set("hbase.zookeeper.quorum", hbase_zookeeper_quorum)
      conf
    }

    private def create_conn: Connection =
      ConnectionFactory.createConnection(get_conf)

    def <==(view: String, table: String = null): Unit = {
      val tb = if (table ne null) table else view
      val df = sql view view
      dataframe(df, tb)
    }

    def dataframe(df: DataFrame, table: String): Unit = {
      val rk_col_ = rk_col
      val family_ = family
      val cols = df.schema.map(_.name)
      if (!cols.contains(rk_col_)) throw IllegalParamException(s"your dataframe has no rk[$rk_col_] column")
      val cols_ = cols.filter(_ ne rk_col_)
      val input = df.where(s"$rk_col_ is not null")
      if (ex_save) {
        save_ex(input.sort(rk_col_), table, (r: Row) => {
          val rk = Bytes.toBytes(r.get(r.fieldIndex(rk_col_)).toString)
          cols_.sorted.map(col => {
            val value = r.get(r.fieldIndex(col))
            val kv = if (value == null) null else new KeyValue(rk, family_,
              Bytes.toBytes(col), Bytes.toBytes(value.toString))
            (new ImmutableBytesWritable(), kv)
          }).filterNot(_._2 == null)
        })
      } else {
        save(input
          , table, (r: Row) => {
            val put = new Put(Bytes.toBytes(r.get(r.fieldIndex(rk_col_)).toString))
            cols_.foreach(col => {
              val value = r.get(r.fieldIndex(col))
              if (value != null) put.addColumn(family_, Bytes.toBytes(col), Bytes.toBytes(value.toString))
            })
            (new ImmutableBytesWritable(), put)
          })
      }

    }

    private def save(df: DataFrame,
                     table: String,
                     f: Row => (ImmutableBytesWritable, Put)): Unit = {
      init(table)
      df.rdd.map(f).saveAsNewAPIHadoopDataset(save_job(table))
    }

    private def save_ex(df: DataFrame,
                        table: String,
                        f: Row => Seq[(ImmutableBytesWritable, KeyValue)]): Unit = {
      init(table)
      val job = save_job_ex(table)
      val conf = job.getConfiguration
      val dir = s"$ex_save_dir/$table"
      val path = new Path(dir)
      val fs = path.getFileSystem(conf)
      if (fs.exists(path)) fs.delete(path, true)
      job.getConfiguration.set("mapred.output.dir", dir)
      job.setMapOutputKeyClass(classOf[ImmutableBytesWritable])
      job.setMapOutputValueClass(classOf[KeyValue])
      val conn = create_conn
      val htb = conn.getTable(TableName.valueOf(table))
      HFileOutputFormat2.configureIncrementalLoadMap(job, htb)
      df.rdd.flatMap(f).saveAsNewAPIHadoopDataset(job.getConfiguration)
      new LoadIncrementalHFiles(conf).run(Array(dir, table))
      htb.close()
      conn.close()
    }
  }

  /**
    * 用于0.10+版本的kafka
    *
    * @param servers broker地址，多个用逗号分隔
    * @param topic   topic名称
    */
  case class kafka(servers: String, topic: String) {

    def <==(view: String): Unit = {
      val df = spark.table(view)
      dataframe(df)
    }

    def dataframe(df: DataFrame): Unit = {
      df.toJSON.toDF("value")
        .write.format("kafka")
        .option("kafka.bootstrap.servers", servers)
        .option("topic", topic)
        .save()
    }
  }

  /**
    * 用于低于0.10版本的kafka
    *
    * @param servers broker地址，多个用逗号分隔
    * @param topic   topic名称
    * @param batch   批次
    */
  case class kafka_low(servers: String, topic: String, batch: Int = 10) {

    import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
    import org.apache.kafka.common.serialization.StringSerializer

    def <==(view: String): Unit = {
      val df = spark.table(view)
      dataframe(df)
    }

    def dataframe(df: DataFrame): Unit = {
      val output = df.toJSON.cache
      val servers_ = servers
      val topic_ = topic
      output.count
      output.coalesce(batch).foreachPartition(values => {
        val props = new java.util.Properties()
        props.put("metadata.broker.list", servers_)
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers_)
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
        var producer: KafkaProducer[String, String] = null
        try {
          producer = new KafkaProducer[String, String](props)
          while (values.hasNext) {
            val value = values.next
            producer.send(new ProducerRecord[String, String](topic_, value))
          }
        } catch {
          case e: Exception =>
            throw e
        } finally {
          if (producer ne null) producer.close()
        }

      })
      output.unpersist

    }
  }

}


