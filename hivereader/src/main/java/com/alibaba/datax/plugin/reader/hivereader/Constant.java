package com.alibaba.datax.plugin.reader.hivereader;

public class Constant {

    public final static String TEMP_DATABASE_DEFAULT = "default"; // 参考CDH的default库
    public static final String HIVE_JDBC_USERNAME="hive";
    public static final String TEMP_DATABSE_HDFS_LOCATION_DEFAULT = "/user/{username}/warehouse/";// 参考CDH的default库的路径
//    public static final String TEMP_DATABSE_HDFS_LOCATION_DEFAULT = "/user/hive/warehouse/";// 参考CDH的default库的路径
    public static final String TEMP_TABLE_NAME_PREFIX="tmp_datax_hivereader_";
    //    public final static String HIVE_CMD_DEFAULT = "hive";
    public final static String HIVE_SQL_SET_DEFAULT = "";
    public final static String FIELDDELIMITER_DEFAULT = "\\u0001";
    public final static String NULL_FORMAT_DEFAULT="\\N" ;
    public static final String TEXT = "TEXT";
    public static final String CSV = "CSV";
}