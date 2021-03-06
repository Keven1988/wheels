# 使用手册

- ***[简介](#introduction)***
  - [编程风格](#programming-model)
  - [常用符号](#symbols)
- ***[基础模块](#base-model)***
  - [开启方式](#base-model-open)
  - [视图与dataframe](#view-df)
    - [创建视图](#view-create)
    - [创建dataframe](#df-create)
    - [互相转换](#dv-conversion)
  - [数据处理](#data-processing)
      - [sql方式（推荐）](#processing-sql)
      - [dataframe方式](#processing-df)
  - [数据存储](#data-save)
    - [全量表](#data-save-f)
    - [分区表](#data-save-p)
- ***[SQL增强](#sql-ex)***
  - [开启方式](#sql-ex-open)
  - [聚合为json字符串](#sql-ex-collect-json)
  - [多列转向量](#sql-ex-to-vector)  
  - [super-join](#sql-ex-super-join)  
- ***[Database 模块](#db-model)***
  - [开启方式](#db-model-open)
  - [hbase](#db-model-hbase)
  - [redis](#db-model-redis)
  - [kafka](#db-model-kafka)
  - [jdbc](#db-model-jdbc)
  - [elasticsearch](#db-model-es)
- ***[ML 模块](#ml-model)***
  - [开启方式](#ml-model-open)
  - [特征处理](#ml-model-f)
    - [索引化](#ml-model-f-indexer)
    - [标准化](#ml-model-f-scaler)
    - [联合加权](#ml-model-f-union-weighing)
  
## <a name='introduction'>简介</a>

Wheels主要对大数据主流框架及常用算法库进行统一封装，优化及实现，对外提供简洁且高效的API。<br>
从而达到降低从事大数据场景下开发人员的编程技术门槛及提高整体项目质量的目的。

### <a name='programming-model'>编程风格</a>

使用Wheels编程，每行程序主要有三个区域组成：
<br>
【对象区域】 【符号区域】 【参数区域】

+ 对象区域

当前所操作的对象

+ 符号区域

该对象的操作符号或函数

+ 参数区域

当前操作所对应的配置参数

以写hive为例:

```
import com.wheels.spark.Core

val sql = Core(database = "my_hive_db").support_sql

//【对象区域】sql 
//【符号区域】==> 
//【参数区域】(...)
sql ==> ("""
            select
            country,count(1) country_count
            from emp
            group by country
         """, "tmp_country_agg") //将emp表通过sql转换为tmp_country_agg临时视图

//【对象区域】sql 
//【符号区域】<== 
//【参数区域】"tmp_country_agg"
sql <== "tmp_country_agg" //将数据写入hive的tmp_country_agg表      

```

### <a name='symbols'>常用符号</a>

| 符号 | 含义 |
| -------- | -------- |
| ==> | 数据输入 |
| <== | 数据输出 |

## <a name='base-model'>基础模块</a>

该模块提供用于数据操作的基础功能

### <a name='base-model-open'>开启方式</a>

```
import com.wheels.spark.Core
val sql = Core().support_sql
```

可选参数

```
import com.wheels.spark.Core
val sql: SQL = Core(
  name = "my app name",//app 名称
  database = "my_hive_db",// hive 库名称
  // runtime 配置
  conf = Map(
    "wheel.spark.sql.hive.save.mode" -> "overwrite",
    "spark.sql.broadcastTimeout" -> "3000"
  )
).support_sql
```

### <a name='view-df'>视图与dataframe</a>

+ 视图

通过数据变换生成的临时视图

+ dataframe

spark 的 DataFrame 类型的数据集

#### <a name='view-create'>创建视图</a>

当前database下hive中的所有表会自动注册为与表明同名的视图

```
//创建名为org_ct的视图
sql ==> ("select org_id,count(1) ct from emp group by emp",
      view = "org_ct")
```

#### <a name='df-create'>创建dataframe</a>

```
val my_df = sql ==> "select org_id,count(1) ct from emp group by emp" //将数据转换结果作为dataframe

val table_df = sql read "emp" //读取hive中的emp表，作为dataframe

```

#### <a name='dv-conversion'>互相转换</a>

视图 -> dataframe

```
//将org_ct视图 转换为 my_df
val my_df = sql view "org_ct"
```

dataframe -> 视图

```
//把my_df注册为my_view视图
sql register(my_df, "my_view")
```

### <a name='data-processing'>数据处理</a>

Wheels支持两种风格的数据处理api，并且两种可以无缝的混合使用。下面将以一个例子介绍。<br>
+ 数据源
database为my_hive_db,表名为emp，emp表结构及数据如下：
```
+-------+------+-------+------+
|user_id|height|country|org_id|
+-------+------+-------+------+
|u-001  |175   |CN     |o-001 |
|u-002  |188   |CN     |o-002 |
|u-003  |190   |US     |o-001 |      
|u-004  |175   |CN     |o-001 |
|u-005  |155   |JP     |o-002 |
|u-006  |145   |JP     |o-002 |
|u-007  |166   |JP     |o-002 |
|u-008  |148   |CN     |o-002 |
|u-009  |172   |CN     |o-003 |
|u-010  |167   |US     |o-003 |
+-------+------+-------+------+
``` 

+ 数据处理目标

height>156的全员用户，并且标注出该用户所在国家和组织的总人数，结果数据结构及内容如下：

```
+-------+------+-------+------+-------------+---------+
|user_id|height|country|org_id|country_count|org_count|
+-------+------+-------+------+-------------+---------+
|u-001  |175   |CN     |o-001 |5            |3        |
|u-002  |188   |CN     |o-002 |5            |5        |
|u-003  |190   |US     |o-001 |2            |3        |
|u-004  |175   |CN     |o-001 |5            |3        |
|u-007  |166   |JP     |o-002 |3            |5        |
|u-009  |172   |CN     |o-003 |5            |2        |
|u-010  |167   |US     |o-003 |2            |2        |
+-------+------+-------+------+-------------+---------+
```


#### <a name='processing-sql'>sql方式（推荐）</a>

支持绝大多数hive语法，详情参照[hive官网介绍](https://cwiki.apache.org/confluence/display/Hive/LanguageManual)。

```
import com.wheels.spark._

val sql = Core(database = "my_hive_db").support_sql //创建sql对象

//统计每个国家的人数，结果注册为视图tmp_country_agg
sql ==> (
  """
    select
    country,count(1) country_count
    from emp
    group by country
  """, "tmp_country_agg")

//统计每个组织的人数，结果注册为视图tmp_org_agg
sql ==> (
  """
    select
    org_id,count(1) org_count
    from emp
    group by org_id
  """, "tmp_org_agg")

//获取最终结果
sql ==> (
  """
    select
    e.*,c.country_count,o.org_count
    from emp e,tmp_country_agg c,tmp_org_agg o
    where
    e.country = c.country and
    o.org_id = e.org_id and
    e.height > 156
  """, "emp_res")

//预览结果
sql show "emp_res"
```

#### <a name='processing-df'>dataframe方式</a>

```
import com.wheels.spark._

val sql = Core(database = "my_hive_db").support_sql //创建sql对象

val emp = sql view "emp"

//统计每个国家的人数
val tmp_country_agg = emp
  .groupBy("country")
  .count()
  .as("country_count")

//统计每个组织的人数
val tmp_org_agg = emp
  .groupBy("org_id")
  .count()
  .as("org_count")

//获取最终结果
val emp_res = emp
  .join(tmp_country_agg, "country")
  .join(tmp_org_agg, "org_id")
  .where("height > 156")

emp_res.show(truncate = false)
```

### <a name='data-save'>数据存储</a>

Wheel使用<==符号会将视图写入默认的文件系统

#### <a name='data-save-f'>全量表</a>

```
//将emp_res写入hive表，hive表名为emp_res
sql <== "emp_res"
```

可选配置

```
import org.apache.spark.sql.SaveMode

sql <== ("emp_res",//视图名称
      "tb_emp_res",//待写入hive表名称
      save_mode = SaveMode.Overwrite,//写入模式为追加写入
      format_source = "orc",//文件格式设置为orc
      coalesce_limit = 100*10000//单文件行数上限为100W
    )
```

#### <a name='data-save-p'>分区表</a>

写人分区表需配置partition属性

```
import com.wheels.spark.SQL.partition

// 设置分区字段为y,m,d
val pt = partition("y", "m", "d")

// 在hive中写入名为tb_login_log，以y,m,d字段作为分区的表
sql <== ("tb_login_log", p = pt)

```

在已知数据集中分区值的情况

+ 已知数据集中的y=2018，m=11，d=11

```
import com.wheels.spark.SQL.partition

// 设置分区字段为y,m,d
val pt = partition("y", "m", "d") + ("2018", "11", "11")

// 在hive中写入名为tb_login_log，以y,m,d字段作为分区的表
sql <== ("tb_login_log", p = pt)

```

+ 已知数据集中的y=2018，m=11，d为02，15，30

```
import com.wheels.spark.SQL.partition

// 设置分区字段为y,m,d
val days = ++ Seq(Seq("2018", "11", "02"),
                  Seq("2018", "11", "15"),
                  Seq("2018", "11", "30"))
val pt = partition("y", "m", "d") ++ days

// 在hive中写入名为tb_login_log，以y,m,d字段作为分区的表
sql <== ("tb_login_log", p = pt)

```

默认状态下，程序会认为待写入的分区表是存在的，若需要建表或者刷新表，可以设置如下参数即可：

```
val pt = partition("y", "m", "d").table_init
```

## <a name='sql-ex'>SQL增强</a>

对原有的sql进行功能扩展，并且支持关键字的转义。

### <a name='sql-ex-open'>开启方式</a>

```scala
import com.wheels.spark.Core
import com.wheels.spark.SQL._
val sql = Core().support_sql
```

### <a name='sql-ex-collect-json'>聚合为json字符串</a>

可以在数据聚合时，将一列或多列的数据聚合为json字符串，同时支持非法字符自动转义，例如：

原始数据（emp）为：

```
+-------+------+-------+------+
|user_id|height|country|org_id|
+-------+------+-------+------+
|u-001  |175   |CN     |o-001 |
|u-002  |188   |CN     |o-002 |
|u-003  |190   |US     |o-001 |
|u-004  |175   |{""}   |o-001 |
|u-005  |155   |JP     |o-002 |
|u-006  |145   |JP     |o-002 |
|u-007  |166   |JP     |o-002 |
|u-008  |148   |CN     |o-002 |
|u-009  |172   |CN     |o-003 |
|u-010  |167   |US     |o-003 |
+-------+------+-------+------+
```

执行程序：

```
sql ==> (
    s"""
       |select
       |org_id,${collect_json(Seq("height", "country", "user_id"),"from")}
       |from emp
       |group by org_id
    """.stripMargin, "res_tb")
    
sql show "res_tb"
```

输出：

```
+------+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
|org_id|from                                                                                                                                                                                                                                             |
+------+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
|o-002 |[{"height":148,"country":"CN","user_id":"u-008"},{"height":166,"country":"JP","user_id":"u-007"},{"height":155,"country":"JP","user_id":"u-005"},{"height":188,"country":"CN","user_id":"u-002"},{"height":145,"country":"JP","user_id":"u-006"}]|
|o-001 |[{"height":190,"country":"US","user_id":"u-003"},{"height":175,"country":"CN","user_id":"u-001"},{"height":175,"country":"{\"\"}","user_id":"u-004"}]                                                                                            |
|o-003 |[{"height":167,"country":"US","user_id":"u-010"},{"height":172,"country":"CN","user_id":"u-009"}]                                                                                                                                                |
+------+-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+

```

### <a name='sql-ex-to-vector'>多列转向量</a>

可以将表或临时视图中的指定列转为Vectors类型，例如：

原始数据(emp)：
```
+-------+------+-------+------+---+---+---+
|user_id|height|country|org_id|nb1|nb2|nb3|
+-------+------+-------+------+---+---+---+
|u-001  |175   |CN     |o-001 |1.8|4  |0  |
|u-002  |188   |CN     |o-002 |1.8|4  |0  |
|u-003  |190   |US     |o-001 |1.8|4  |0  |
|u-004  |175   |{""}   |o-001 |1.8|4  |0  |
|u-005  |155   |JP     |o-002 |1.8|4  |0  |
|u-006  |145   |JP     |o-002 |1.8|4  |0  |
|u-007  |166   |JP     |o-002 |1.8|4  |0  |
|u-008  |148   |CN     |o-002 |1.8|4  |0  |
|u-009  |172   |CN     |o-003 |1.8|4  |0  |
|u-010  |167   |US     |o-003 |1.8|4  |0  |
+-------+------+-------+------+---+---+---+

```

执行程序：

```
sql ==> (s"select *,${to_vector(Seq("nb1", "nb2", "nb3", "height","user_id"),"vectors")} from emp", "emp_vs")

sql show "emp_vs"
```

输出：
```
+-------+------+-------+------+---+---+---+-----------------------+
|user_id|height|country|org_id|nb1|nb2|nb3|vectors                |
+-------+------+-------+------+---+---+---+-----------------------+
|u-001  |175   |CN     |o-001 |1.8|4  |0  |[1.8,4.0,0.0,175.0,0.0]|
|u-002  |188   |CN     |o-002 |1.8|4  |0  |[1.8,4.0,0.0,188.0,0.0]|
|u-003  |190   |US     |o-001 |1.8|4  |0  |[1.8,4.0,0.0,190.0,0.0]|
|u-004  |175   |{""}   |o-001 |1.8|4  |0  |[1.8,4.0,0.0,175.0,0.0]|
|u-005  |155   |JP     |o-002 |1.8|4  |0  |[1.8,4.0,0.0,155.0,0.0]|
|u-006  |145   |JP     |o-002 |1.8|4  |0  |[1.8,4.0,0.0,145.0,0.0]|
|u-007  |166   |JP     |o-002 |1.8|4  |0  |[1.8,4.0,0.0,166.0,0.0]|
|u-008  |148   |CN     |o-002 |1.8|4  |0  |[1.8,4.0,0.0,148.0,0.0]|
|u-009  |172   |CN     |o-003 |1.8|4  |0  |[1.8,4.0,0.0,172.0,0.0]|
|u-010  |167   |US     |o-003 |1.8|4  |0  |[1.8,4.0,0.0,167.0,0.0]|
+-------+------+-------+------+---+---+---+-----------------------+

```
### <a name='sql-ex-super-join'>super-join</a>
可以自动解决两个数据集left，inner，full的join操作产生的数据倾斜

使用方式：

```
//my_table_1: 较大的视图名称
//my_table_2: 较小的视图名称
//Seq("join_key_1","join_key_2"): join的列
//result_view: 输出视图名称

sql super_join("my_table_1", "my_table_2", Seq("join_key_1","join_key_2"),
      output_view = "result_view")
```

更多配置：

```
/**
    * super-join 功能（可以自动解决两个数据集left，inner，full的join操作产生的数据倾斜）
    * @param bigger_view 较大的视图名称
    * @param smaller_view 较小的视图名称
    * @param join_cols join的列
    * @param join_type join的类型，暂时仅支持left，inner，full。默认为inner
    * @param output_view 输出视图名称，默认为wheels_super_join_res
    * @param deal_ct 判定为倾斜的阀值，默认为10000
    * @param deal_limit 做特殊处理数据量的上限，默认为1000
    * @param bigger_clv 较大视图的缓存级别，默认为MEMORY_AND_DISK
    */
  def super_join(bigger_view: String, smaller_view: String, join_cols: Seq[String],
                 join_type: String = "inner",
                 output_view: String = "wheels_super_join_res",
                 deal_ct: Int = 10000,
                 deal_limit: Int = 1000,
                 bigger_clv: StorageLevel = StorageLevel.MEMORY_AND_DISK): DataFrame
```

## <a name='db-model'>Database 模块</a>

该模块的主要功能是完成 dataframe/view <-> database 操作 

### <a name='db-model-open'>开启方式</a>

```
val database: DB = sql.support_database
```

### <a name='db-model-hbase'>hbase</a>

在默认情况下，会将视图中的数据写入所指定的hbase表中。<br>
其中会把视图中名为“rk”的列作为row key，列族为cf。同时支持row key与视图列的对应关系、列族名称、预分区设置。

下面以一个视图写入hbase的例子进行说明：

视图的数据为:

```
+-----+------+-------+------+
|rk   |height|country|org_id|
+-----+------+-------+------+
|u-001|175   |CN     |o-001 |
|u-002|188   |CN     |o-002 |
|u-003|190   |US     |o-001 |
|u-004|175   |CN     |o-001 |
|u-005|155   |JP     |o-002 |
|u-006|145   |JP     |o-002 |
|u-007|166   |JP     |o-002 |
|u-008|148   |CN     |o-002 |
|u-009|172   |CN     |o-003 |
|u-010|167   |US     |o-003 |
+-----+------+-------+------+
```
视图名称为w2hbase

数据写入

```
val hbase = database.hbase("127.0.0.1")

hbase <== "w2hbase"
```

hbase shell 查询结果

```
ROW                   COLUMN+CELL                                               
 u-001                column=cf:country, timestamp=1541323286930, value=CN      
 u-001                column=cf:height, timestamp=1541323286930, value=175      
 u-001                column=cf:org_id, timestamp=1541323286930, value=o-001    
 u-001                column=cf:rk, timestamp=1541323286930, value=u-001        
 u-002                column=cf:country, timestamp=1541323286930, value=CN      
 u-002                column=cf:height, timestamp=1541323286930, value=188      
 u-002                column=cf:org_id, timestamp=1541323286930, value=o-002    
 u-002                column=cf:rk, timestamp=1541323286930, value=u-002        
 u-003                column=cf:country, timestamp=1541323286932, value=US      
 u-003                column=cf:height, timestamp=1541323286932, value=190      
 u-003                column=cf:org_id, timestamp=1541323286932, value=o-001    
 u-003                column=cf:rk, timestamp=1541323286932, value=u-003        
 u-004                column=cf:country, timestamp=1541323286932, value=CN      
 u-004                column=cf:height, timestamp=1541323286932, value=175      
 u-004                column=cf:org_id, timestamp=1541323286932, value=o-001    
 u-004                column=cf:rk, timestamp=1541323286932, value=u-004        
 u-005                column=cf:country, timestamp=1541323286932, value=JP      
 u-005                column=cf:height, timestamp=1541323286932, value=155      
 u-005                column=cf:org_id, timestamp=1541323286932, value=o-002    
 u-005                column=cf:rk, timestamp=1541323286932, value=u-005        
 u-006                column=cf:country, timestamp=1541323286931, value=JP      
 u-006                column=cf:height, timestamp=1541323286931, value=145      
 u-006                column=cf:org_id, timestamp=1541323286931, value=o-002    
 u-006                column=cf:rk, timestamp=1541323286931, value=u-006        
 u-007                column=cf:country, timestamp=1541323286931, value=JP      
 u-007                column=cf:height, timestamp=1541323286931, value=166      
 u-007                column=cf:org_id, timestamp=1541323286931, value=o-002    
 u-007                column=cf:rk, timestamp=1541323286931, value=u-007        
 u-008                column=cf:country, timestamp=1541323286932, value=CN      
 u-008                column=cf:height, timestamp=1541323286932, value=148      
 u-008                column=cf:org_id, timestamp=1541323286932, value=o-002    
 u-008                column=cf:rk, timestamp=1541323286932, value=u-008        
 u-009                column=cf:country, timestamp=1541323286932, value=CN      
 u-009                column=cf:height, timestamp=1541323286932, value=172      
 u-009                column=cf:org_id, timestamp=1541323286932, value=o-003    
 u-009                column=cf:rk, timestamp=1541323286932, value=u-009        
 u-010                column=cf:country, timestamp=1541323286932, value=US      
 u-010                column=cf:height, timestamp=1541323286932, value=167      
 u-010                column=cf:org_id, timestamp=1541323286932, value=o-003    
 u-010                column=cf:rk, timestamp=1541323286932, value=u-010        
10 row(s) in 0.2590 seconds
```

更多配置项

|配置参数|说明|
|---|---|
|hbase_zookeeper_quorum | zookeeper地址串，多个地址使用英文逗号分隔|
|port | zk端口号|
|rk_col | row key 所对应的列名，默认为rk|
|family_name | 列族名称，默认为cf|
|split_keys | 预分区字母，默认为0～9，a～f|
|overwrite | 是否采用完全覆盖写入方式（每次写入前重建表），默认为false|

### <a name='db-model-redis'>redis</a>

该功能只支持redis集群数据写入，默认情况下会把带有k，v两列的视图永久写入redis集群。k，v与视图类的对应关系及数据的存留时间可配置。


下面举例说明：

将视图w2redis中的数据写入redis，w2redis的结构及数据如下：

```
+-----+---+
|k    |v  |
+-----+---+
|u-001|175|
|u-002|188|
|u-003|190|
|u-004|175|
|u-005|155|
|u-006|145|
|u-007|166|
|u-008|148|
|u-009|172|
|u-010|167|
+-----+---+

```

数据写入：

```
val redis = database.redis_cluster(
     Seq(("127.0.0.1", 6379), ("127.0.0.1", 6381), ("127.0.0.1", 6382)),//redis集群地址及端口
     life_seconds = 100 * 60//写入的数据保留100分钟
   )

//写入redis
redis <== "w2redis"
```


更多配置项

|配置参数|说明|
|---|---|
|nodes | redis集群地址及端口|
|key_col | 待写入的key对应的列，默认为k|
|value_col | 待写入的value对应的列，默认为v|
|life_seconds | 待写入数据的生命周期，默认为不过期|
|timeout | 连接redis超时时间|
|max_attempts | 最大重试次数|
|pwd redis | 秘钥|
|batch | 写入数据批次，默认20|


### <a name='db-model-kafka'>kafka</a>
使用方式：

+ 版本0.10+

```
val kafka = database.kafka(servers = "yourhost0:port0,yourhost1:port1,yourhost2:port2", "your-topic")
kafka <== "your_view"
```

+ 版本低于 0.10

```
val kafka = database.kafka_low(servers = "yourhost0:port0,yourhost1:port1,yourhost2:port2", "your-topic")
kafka <== "your_view"
```

### <a name='db-model-jdbc'>jdbc</a>
使用方式(以mysql为例)：

```
val mysql = database.jdbc("com.mysql.cj.jdbc.Driver", "jdbc:mysql://localhost/yourdb", "username")
```

数据读取：

```
mysql ==> "your_mysql_table"
```

数据写入：

```
mysql <== "your_view"
```

DDL操作：

```
val admin = mysql.admin()
admin.exe("your_sql...")
```

### <a name='db-model-es'>elasticsearch</a>

使用方式：

```
val es = database.es("your-index/your-type")
es <== "your_view"
```

## <a name='ml-model'>ML 模块</a>

该模块提供算法有关的常用功能。

### <a name='ml-model-open'>开启方式</a>

```
val ml: ML = sql.support_ml
```

### <a name='ml-model-f'>特征处理</a>

创建特征处理对象：

```
val features = ml.features
```
#### <a name='ml-model-f-indexer'>索引化</a>

为数据集中某一字符串类型的列添加数字索引，或者将数字索引还原成原始数据。

##### 字符列->索引列

```
val indexer = features.indexer()

//view_name：视图名称
//col_name：待处理列的名称

indexer s2i("view_name", "col_name")

//默认输出列名为 col_name_index
```

更多配置项

```
/**
  * 字符列->索引列
  * @param view 视图名称
  * @param input_col 输入列
  * @param output_col 输出列，默认为input_col + "_index"
  * @param output_view 输出视图
  * @return 转化模型
  */
def s2i(view: String, input_col: String,
        output_col: String = null, output_view: String = null,
        handle_invalid: String = "error"): StringIndexerModel
```

##### 索引列->字符列

```
val indexer = features.indexer()

//view_name：视图名称
//col_name：待处理列的名称

indexer i2s("view_name", "col_name")

//默认输出列名为 col_name_str
```

更多配置项

```
/**
 * 索引列->字符列
 * @param view 视图名称
 * @param input_col 输入列
 * @param output_col 输出列，默认为input_col + "_str"
 * @param output_view 输出视图
 * @param labels 索引标签向量
 * @return 转换后的dataframe
 */
def i2s(view: String, input_col: String,
          output_col: String = null, output_view: String = null,
          labels: Array[String] = null): DataFrame
```
#### <a name='ml-model-f-scaler'>标准化</a>

对指定对列进行标准化处理，创建对象

```
//view_name: 视图名称
//Seq("col_name1", "col_name2", "col_name3")：输入列的名称

val scaler = features.scaler("view_name", Seq("col_name1", "col_name2", "col_name3"))
```

##### z-score

```
//返回值：处理后的dataframe
//建议直接使用输入视图名继续进行数据处理
scaler.z_score
```

获取mean和std,返回数据为Seq[Double]类型，顺序与输入列顺序一致

```
scaler.mean
scaler.std
```

更多配置：

```
/**
  * z-score
  *
  * @param with_std  是否将数据缩放到单位标准差
  * @param with_mean 缩放前是否以均值为中心
  * @return 处理后的dataframe
  */
def z_score(with_std: Boolean, with_mean: Boolean): DataFrame
```

##### MinMax

```
//返回值：处理后的dataframe
//建议直接使用输入视图名继续进行数据处理
scaler.mix_max
```

获取min和max,返回数据为Seq[Double]类型，顺序与输入列顺序一致

```
scaler.min
scaler.max
```

##### MaxAbs

```
//返回值：处理后的dataframe
//建议直接使用输入视图名继续进行数据处理
scaler.max_abs
```
获取绝对值的最大值,返回数据为Seq[Double]类型，顺序与输入列顺序一致
```
scaler.maxabs
```

#### <a name='ml-model-f-union-weighing'>联合加权</a>

根据数据类型及每种类型对应的权重值对指定数据集进行联合加权操作。默认合并方式是加权后进行求和，该操作支持自定义。

例如，视图recommend_res的格式及内容如下：

```
+-------+-------+------+----+
|user_id|item_id|degree|type|
+-------+-------+------+----+
|u-001  |i-003  |12.886|t1  |
|u-002  |i-002  |33.886|t1  |
|u-003  |i-001  |77.886|t1  |
|u-004  |i-001  |54.886|t1  |
|u-002  |i-002  |99.886|t2  |
|u-004  |i-001  |22.886|t2  |
|u-001  |i-003  |45.886|t2  |
|u-002  |i-001  |66.886|t3  |
|u-003  |i-003  |0.886 |t3  |
|u-004  |i-001  |2.886 |t3  |
+-------+-------+------+----+
```
已知每种类型的对应的权重值如下：
```
t1 -> 0.33
t2 -> 0.22
t3 -> 0.45
```

代码实现如下：

```
//创建联合加权器
val uw = ml.union_weighing(
      //类型&权重值关系
      Map(
        "t1" -> 0.33,
        "t2" -> 0.22,
        "t3" -> 0.45),
      //输出视图名称
      output = "recommend_weighing_res"
    )
    
//进行联合加权
uw ==> "recommend_res"

//预览结果数据
sql show "recommend_weighing_res"
```
结果输出：
```
+-------+-------+------------------+
|user_id|item_id|degree            |
+-------+-------+------------------+
|u-004  |i-001  |24.446            |
|u-002  |i-002  |33.157300000000006|
|u-002  |i-001  |30.098699999999997|
|u-001  |i-003  |14.3473           |
|u-003  |i-001  |25.70238          |
|u-003  |i-003  |0.3987            |
+-------+-------+------------------+

```

由于默认情况下聚会操作为求和，若需要自定义（以求平均为例）可对union_weighing进行如下配置：

```

val uw = ml.union_weighing(Map(
               "t1" -> 0.33,
               "t2" -> 0.22,
               "t3" -> 0.45
             ),
               //自定义聚合方式
               udf = (degrees: Seq[Double]) => {
                 val ct = degrees.length
                 degrees.sum / ct
               },
               output = "recommend_weighing_res")
               
//进行联合加权
uw ==> "recommend_res"

//预览结果数据
sql show "recommend_weighing_res"

```
结果输出：
```
+-------+-------+------------------+
|user_id|item_id|degree            |
+-------+-------+------------------+
|u-004  |i-001  |8.148666666666667 |
|u-002  |i-002  |16.578650000000003|
|u-002  |i-001  |30.098699999999997|
|u-001  |i-003  |7.17365           |
|u-003  |i-001  |25.70238          |
|u-003  |i-003  |0.3987            |
+-------+-------+------------------+
```

更多配置项：

|配置参数|说明|
|---|---|
|weight_info | 类型与权重的对应关系|
|type_col | 类型列名，默认为type|
|keys | 唯一标示，默认为 Seq("user_id", "item_id")|
|degree_col | 评分列名，默认为degree|
|udf | 自定义聚合函数，默认为求和|
|output | 输出视图名称，默认无输出视图|
