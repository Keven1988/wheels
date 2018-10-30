# API

- ***[wheels-spark](#wheels-spark)***
  - [创建核心功能对象](#com.zhjy.wheel.spark.Core.apply)
  - [是否支持sql模块功能](#com.zhjy.wheel.spark.Core.support_sql)
  - [获取 catalog 对象](#com.zhjy.wheel.spark.Core.catalog)
  - [释放资源](#com.zhjy.wheel.spark.Core.stop)
  - [使用sql进行数据处理](#com.zhjy.wheel.spark.SQL.==>)
  - [将视图写入hive](#com.zhjy.wheel.spark.SQL.<==)


## <a name='wheels-spark'>wheels-spark</a>

### <a name='com.zhjy.wheel.spark.Core.apply'>创建核心功能对象</a>

```
com.zhjy.wheel.spark.Core.apply
  /**
    * 创建核心功能对象
    * @param name app名称
    * @param conf runtime 配置信息
    * @param hive_support 是否开启hive支持
    * @param database database名称
    * @param log_less 是否需要少量的日志输出
    * @return 核心功能对象
    */
  def apply(name: String = s"run spark @ ${Time.now}",
            conf: Map[String, Any] = Map(),
            hive_support: Boolean = true,
            database: String = null,
            log_less: Boolean = true
           ): Core
```

### <a name='com.zhjy.wheel.spark.Core.support_sql'>是否支持sql模块功能</a>

```
com.zhjy.wheel.spark.Core.support_sql
  /**
    * 是否支持sql模块功能
    * @return sql对象
    */
  def support_sql: SQL
```

### <a name='com.zhjy.wheel.spark.Core.catalog'>获取 catalog 对象</a>

```
com.zhjy.wheel.spark.Core.catalog
  /**
    * 获取 catalog 对象
    * @return catalog
    */
  def catalog: Catalog
```

### <a name='com.zhjy.wheel.spark.Core.stop'>释放资源</a>

```
com.zhjy.wheel.spark.Core.stop
  /**
    * 释放资源
    */
  def stop(): Unit
```

### <a name='com.zhjy.wheel.spark.SQL.==>'>使用sql进行数据处理</a>

```
com.zhjy.wheel.spark.SQL.==>
   /**
    * 使用sql进行数据处理
    * @param sql 待执行的sql字符串
    * @param view 执行结果的视图名称，若未填入则不注册视图
    * @param cache 是否写入缓存
    * @param level 写入缓存的级别
    * @return dataframe对象
    */
  def ==>(sql: String, view: String = null,
          cache: Boolean = false,
          level: StorageLevel = StorageLevel.MEMORY_AND_DISK): DataFrame
```

### <a name='com.zhjy.wheel.spark.SQL.<=='>使用sql进行数据处理</a>

```
com.zhjy.wheel.spark.SQL.<==
/**
    * 将视图写入hive
    * @param view 视图名称
    * @param table 待写入hive表名称，默认为视图名称
    * @param p 分区表配置对象，默认为写入非分区表
    * @param save_mode 数据入库模式(overwrite:覆盖，append：追加，ignore：若存在则跳过写入，error：若存在则报错)
    * @param format_source 写入数据格式(parquet,orc,csv,json)
    * @param coalesce_limit 写入文件最大行数限制，用于预防小文件产生
    * @param refresh_view 数据写入后是否刷新视图
    * @return 写入数据的行数
    */
  def <==(view: String, table: String = null,
          p: partition = null,
          save_mode: SaveMode = save_mode,
          format_source: String = format_source,
          coalesce_limit: Long = coalesce_limit,
          refresh_view: Boolean = refresh_view): Long
```