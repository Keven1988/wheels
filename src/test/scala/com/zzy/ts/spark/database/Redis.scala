package com.zzy.ts.spark.database

import com.wheels.spark.database.DB
import com.wheels.spark.{Core, SQL}
import com.zzy.ts.spark.DBS
import org.junit.jupiter.api._
import org.junit.jupiter.api.TestInstance.Lifecycle
import com.wheels.common.Conf.WHEEL_SPARK_SQL_JDBC_SAVE_MODE


@TestInstance(Lifecycle.PER_CLASS)
@DisplayName("测试Spark-DB-redis模块")
class Redis {

  var sql: SQL = _
  var database: DB = _

  @BeforeAll
  def init_all(): Unit = {
    val conf = Map(
      "spark.master" -> "local[*]",
      "zzy.param" -> "fk",
      WHEEL_SPARK_SQL_JDBC_SAVE_MODE -> "append"
    )

    sql = Core(
      conf = conf,
      hive_support = false
    ).support_sql

    database = sql.support_database

  }

  @BeforeEach
  def init(): Unit = {}


  @Test
  @DisplayName("测试写集群模式的redis功能(注意开启redis-server)")
  def ts_redis(): Unit = {
    DBS.emp(sql)
    sql ==> (
      """
        |select
        |user_id k,height v
        |from emp
      """.stripMargin, "w2redis")

    sql show "w2redis"

    val redis = database.redis_cluster(
      Seq(("127.0.0.1", 6379), ("127.0.0.1", 6381), ("127.0.0.1", 6382)),
      life_seconds = 100 * 60
    )

    redis <== "w2redis"


    val df = sql ==>
      """
        |select
        |user_id v,height k
        |from emp
      """.stripMargin

    database.redis_cluster(
      Seq(("127.0.0.1", 6379), ("127.0.0.1", 6381)),
      life_seconds = 10
    ) dataframe df


  }

  @AfterEach
  def after(): Unit = {}

  @AfterAll
  def after_all(): Unit = {
    sql.uncache_all()
    sql.stop()
  }
}