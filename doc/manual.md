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
- ***[Database 模块](#db-model)***
  - [开启方式](#db-model-open)
  - [hbase](#hbase)
  - [redis](#redis)
- ***[ML 模块](#ml-model)***
  - [开启方式](#ml-model-open)
  - [联合加权](#union-weighing)
  
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

val tmp_country_agg = emp
  .groupBy("country")
  .count()
  .as("country_count")

val tmp_org_agg = emp
  .groupBy("org_id")
  .count()
  .as("org_count")

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
      save_mode = SaveMode.Append,//写入模式为追加写入
      format_source = "orc",//文件格式设置为orc
      coalesce_limit = 100*10000//单文件行数上限为100W
    )
```

#### <a name='data-save-p'>分区表</a>

写人分区表需配置partition属性

```
import com.wheels.spark.SQL.partition

// 设置分区字段为y,m,d
val p = partition("y", "m", "d")

// 在hive中写入名为tb_login_log，以y,m,d字段作为分区的表
sql <== ("tb_login_log", p = pt)

```

在已知数据集中分区值的情况

+ 已知数据集中的y=2018，m=11，d=11

```
import com.wheels.spark.SQL.partition

// 设置分区字段为y,m,d
val p = partition("y", "m", "d") + ("2018", "11", "11")

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
val p = partition("y", "m", "d") ++ days

// 在hive中写入名为tb_login_log，以y,m,d字段作为分区的表
sql <== ("tb_login_log", p = pt)

```

默认状态下，程序会认为待写入的分区表是存在的，若需要建表或者刷新表，可以设置如下参数即可：

```
val p = partition("y", "m", "d").table_init
```

## <a name='db-model'>Database 模块</a>

该模块的主要功能是完成 dataframe/view <-> database 操作 

### <a name='db-model-open'>开启方式</a>

```
val database: DB = sql.support_database
```

### <a name='hbase'>hbase</a>

view的数据为:

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
|hbase_zookeeper_quorum | zk地址串，多个地址使用英文逗号分隔|
|port | zk端口号|
|rk_col | row key 所对应的列名，默认为rk|
|family_name | 列族名称，默认为cf|
|split_keys | 预分区字母，默认为0～9，a～f|
|overwrite | 是否采用完全覆盖写入方式（每次写入前重建表），默认为false|

### <a name='redis'>redis</a>

集群模式写入

```

```

