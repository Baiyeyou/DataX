# DataX HiveReader 插件文档


------------

## 1 快速介绍

HiveReader提供了基于SQL进行数据获取的能力。在底层实现上，HiveReader获取HIVE数据仓库上的原始表数据，并新建一个临时表，存储读取的数据，格式为TEXTFILE，然后通过HDFS文件系统读取其数据存储目录，进行数据获取，并转换为DataX传输协议传递给Writer。



## 2 功能


1. 支持Hive JDBC的方式进行数据读取。
1. 支持以SQL的方式对HIVE数据进行筛选。


## 3 功能说明


### 3.1 配置样例

```json
{
    "job": {
        "setting": {
            "speed": {
                "channel": 1
            }
        },
        "content": [
            {
                "reader": {
				  "name": "hivereader",
				  "parameter": {
						"defaultFS": "hdfs://nameservice1",
						"hiveJdbcUrl": "jdbc:hive2://cdh002:10000/;principal=hive/cdh002@DYKJ.COM",
						"hiveSql": [
						  "select * from ods.ods_ekt_transaction_1;"
						],
						"haveKerberos": true,
						"kerberosPrincipal": "hive@DYKJ.COM",
						"kerberosKeytabFilePath": "/data/dolphinscheduler/keytabs/hive.keytab",
						"hadoopConfig": {
						  "dfs.nameservices": "nameservice1",
						  "dfs.ha.namenodes.nameservice1": "namenode176,namenode224",
						  "dfs.namenode.rpc-address.nameservice1.namenode176": "cdh001:8020",
						  "dfs.namenode.rpc-address.nameservice1.namenode224": "cdh002:8020",
						  "dfs.client.failover.proxy.provider.nameservice1": "org.apache.hadoop.hdfs.server.namenode.ha.ConfiguredFailoverProxyProvider"
						}
				  }
                },
                "writer": {
                    "name": "streamwriter",
                    "parameter": {
                        "print": true
                    }
                }
            }
        ]
    }
}
```

### 3.2 参数说明（各个配置项值前后不允许有空格）

* **defaultFS**

	* 描述：Hadoop hdfs文件系统namenode节点地址。 <br />


		**目前HdfsReader已经支持Kerberos认证，如果需要权限认证，则需要用户配置kerberos参数，见下面**


	* 必选：是 <br />

	* 默认值：无 <br />



* **fieldDelimiter**

	* 描述：设置临时表的字段分隔符 <br />

	**另外需要注意的是，HiveReader在读取textfile数据时，需要指定字段分割符，例如指定为"\\u0001"**

	* 必选：否 <br />

	* 默认值：\u0001 <br />



* **nullFormat**

	* 描述：文本文件中无法使用标准字符串定义null(空指针)，DataX提供nullFormat定义哪些字符串可以表示为null。<br />

		 例如如果用户配置: nullFormat:"\\N"，那么如果源头数据是"\N"，DataX视作null字段。

 	* 必选：否 <br />

 	* 默认值：\N <br />

* **haveKerberos**

	* 描述：是否有Kerberos认证，默认false<br />
 
		 例如如果用户配置true，则配置项kerberosKeytabFilePath，kerberosPrincipal为必填。

 	* 必选：haveKerberos 为true必选 <br />
 
 	* 默认值：false <br />

* **kerberosKeytabFilePath**

	* 描述：Kerberos认证 keytab文件路径，绝对路径<br />

 	* 必选：否 <br />
 
 	* 默认值：无 <br />

* **kerberosPrincipal**

	* 描述：Kerberos认证Principal名，如xxxx/hadoopclient@xxx.xxx <br />

 	* 必选：haveKerberos 为true必选 <br />
 
 	* 默认值：无 <br />

	
* **hadoopConfig**

	* 描述：hadoopConfig里可以配置与Hadoop相关的一些高级参数，比如HA的配置。<br />

		```json
		"hadoopConfig":{
		        "dfs.nameservices": "testDfs",
		        "dfs.ha.namenodes.testDfs": "namenode1,namenode2",
		        "dfs.namenode.rpc-address.aliDfs.namenode1": "",
		        "dfs.namenode.rpc-address.aliDfs.namenode2": "",
		        "dfs.client.failover.proxy.provider.testDfs": "org.apache.hadoop.hdfs.server.namenode.ha.ConfiguredFailoverProxyProvider"
		}
		```

	* 必选：否 <br />
 
 	* 默认值：无 <br />


### 3.3 类型转换

由于textfile文件表的元数据信息由Hive维护并存放在Hive自己维护的数据库（如mysql）中，目前HiveReader不支持对Hive元数

据数据库进行访问查询，因此用户在进行类型转换的时候，必须指定数据类型，如果用户配置的column为"*"，则所有column默认转换为

string类型。HdfsReader提供了类型转换的建议表如下：

| DataX 内部类型| Hive表 数据类型    |
| -------- | -----  |
| Long     |TINYINT,SMALLINT,INT,BIGINT|
| Double   |FLOAT,DOUBLE|
| String   |String,CHAR,VARCHAR,STRUCT,MAP,ARRAY,UNION,BINARY|
| Boolean  |BOOLEAN|
| Date     |Date,TIMESTAMP|

其中：

* Long是指Hdfs文件文本中使用整形的字符串表示形式，例如"123456789"。
* Double是指Hdfs文件文本中使用Double的字符串表示形式，例如"3.1415"。
* Boolean是指Hdfs文件文本中使用Boolean的字符串表示形式，例如"true"、"false"。不区分大小写。
* Date是指Hdfs文件文本中使用Date的字符串表示形式，例如"2014-12-31"。

特别提醒：

* Hive支持的数据类型TIMESTAMP可以精确到纳秒级别，所以textfileTIMESTAMP存放的数据类似于"2015-08-21 22:40:47.397898389"，如果转换的类型配置为DataX的Date，转换之后会导致纳秒部分丢失，所以如果需要保留纳秒部分的数据，请配置转换类型为DataX的String类型。
