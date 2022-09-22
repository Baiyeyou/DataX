package com.alibaba.datax.plugin.reader.hdfsreader;

import com.alibaba.datax.common.element.*;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordSender;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.plugin.unstructuredstorage.reader.ColumnEntry;
import com.alibaba.datax.plugin.unstructuredstorage.reader.UnstructuredStorageReaderErrorCode;
import com.alibaba.datax.plugin.unstructuredstorage.reader.UnstructuredStorageReaderUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.apache.avro.Conversions;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.util.Utf8;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.RCFile;
import org.apache.hadoop.hive.ql.io.RCFileRecordReader;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.ql.io.orc.Reader;
import org.apache.hadoop.hive.serde2.columnar.BytesRefArrayWritable;
import org.apache.hadoop.hive.serde2.columnar.BytesRefWritable;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Created by mingya.wmy on 2015/8/12.
 */
public class DFSUtil {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsReader.Job.class);
    private static final int JULIAN_EPOCH_OFFSET_DAYS = 2440588;
    private static final long MILLIS_IN_DAY = TimeUnit.DAYS.toMillis(1);
    private static final long NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);

    private org.apache.hadoop.conf.Configuration hadoopConf = null;
    private String specifiedFileType = null;
    private Boolean haveKerberos = false;
    private String kerberosKeytabFilePath;
    private String kerberosPrincipal;


    private static final int DIRECTORY_SIZE_GUESS = 16 * 1024;

    public static final String HDFS_DEFAULTFS_KEY = "fs.defaultFS";
    public static final String HADOOP_SECURITY_AUTHENTICATION_KEY = "hadoop.security.authentication";


    public DFSUtil(Configuration taskConfig) {
        hadoopConf = new org.apache.hadoop.conf.Configuration();
        //io.file.buffer.size 性能参数
        //http://blog.csdn.net/yangjl38/article/details/7583374
        Configuration hadoopSiteParams = taskConfig.getConfiguration(Key.HADOOP_CONFIG);
        JSONObject hadoopSiteParamsAsJsonObject = JSON.parseObject(taskConfig.getString(Key.HADOOP_CONFIG));
        if (null != hadoopSiteParams) {
            Set<String> paramKeys = hadoopSiteParams.getKeys();
            for (String each : paramKeys) {
                hadoopConf.set(each, hadoopSiteParamsAsJsonObject.getString(each));
            }
        }
        hadoopConf.set(HDFS_DEFAULTFS_KEY, taskConfig.getString(Key.DEFAULT_FS));

        //是否有Kerberos认证
        this.haveKerberos = taskConfig.getBool(Key.HAVE_KERBEROS, false);
        if (haveKerberos) {
            this.kerberosKeytabFilePath = taskConfig.getString(Key.KERBEROS_KEYTAB_FILE_PATH);
            this.kerberosPrincipal = taskConfig.getString(Key.KERBEROS_PRINCIPAL);
            this.hadoopConf.set(HADOOP_SECURITY_AUTHENTICATION_KEY, "kerberos");
        }
        this.kerberosAuthentication(this.kerberosPrincipal, this.kerberosKeytabFilePath);

        LOG.info(String.format("hadoopConfig details:%s", JSON.toJSONString(this.hadoopConf)));
    }

    /**
     * Kerberos信息认证
     * @param kerberosPrincipal         认证用户
     * @param kerberosKeytabFilePath    认证keytab
     */
    private void kerberosAuthentication(String kerberosPrincipal, String kerberosKeytabFilePath) {
        if (haveKerberos && StringUtils.isNotBlank(this.kerberosPrincipal) && StringUtils.isNotBlank(this.kerberosKeytabFilePath)) {
            UserGroupInformation.setConfiguration(this.hadoopConf);
            try {
                UserGroupInformation.loginUserFromKeytab(kerberosPrincipal, kerberosKeytabFilePath);
            } catch (Exception e) {
                String message = String.format("kerberos认证失败,请确定kerberosKeytabFilePath[%s]和kerberosPrincipal[%s]填写正确",
                        kerberosKeytabFilePath, kerberosPrincipal);
                throw DataXException.asDataXException(HdfsReaderErrorCode.KERBEROS_LOGIN_ERROR, message, e);
            }
        }
    }

    /**
     * 获取指定路径列表下符合条件的所有文件的绝对路径
     *
     * @param srcPaths          路径列表
     * @param specifiedFileType 指定文件类型
     */
    public HashSet<String> getAllFiles(List<String> srcPaths, String specifiedFileType) {

        this.specifiedFileType = specifiedFileType;

        if (!srcPaths.isEmpty()) {
            for (String eachPath : srcPaths) {
                LOG.info(String.format("get HDFS all files in path = [%s]", eachPath));
                getHDFSAllFiles(eachPath);
            }
        }
        return sourceHDFSAllFilesList;
    }

    private HashSet<String> sourceHDFSAllFilesList = new HashSet<String>();

    public HashSet<String> getHDFSAllFiles(String hdfsPath) {

        try {
            FileSystem hdfs = FileSystem.get(hadoopConf);
            //判断hdfsPath是否包含正则符号
            if (hdfsPath.contains("*") || hdfsPath.contains("?")) {
                Path path = new Path(hdfsPath);
                FileStatus stats[] = hdfs.globStatus(path);
                for (FileStatus f : stats) {
                    if (f.isFile()) {
                        if (f.getLen() == 0) {
                            String message = String.format("文件[%s]长度为0，将会跳过不作处理！", hdfsPath);
                            LOG.warn(message);
                        } else {
                            addSourceFileByType(f.getPath().toString());
                        }
                    } else if (f.isDirectory()) {
                        getHDFSAllFilesNORegex(f.getPath().toString(), hdfs);
                    }
                }
            } else {
                getHDFSAllFilesNORegex(hdfsPath, hdfs);
            }

            return sourceHDFSAllFilesList;

        } catch (IOException e) {
            String message = String.format("无法读取路径[%s]下的所有文件,请确认您的配置项fs.defaultFS, path的值是否正确，" +
                    "是否有读写权限，网络是否已断开！", hdfsPath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.PATH_CONFIG_ERROR, e);
        }
    }

    private HashSet<String> getHDFSAllFilesNORegex(String path, FileSystem hdfs) throws IOException {

        // 获取要读取的文件的根目录
        Path listFiles = new Path(path);

        // If the network disconnected, this method will retry 45 times
        // each time the retry interval for 20 seconds
        // 获取要读取的文件的根目录的所有二级子文件目录
        FileStatus stats[] = hdfs.listStatus(listFiles);

        for (FileStatus f : stats) {
            // 判断是不是目录，如果是目录，递归调用
            if (f.isDirectory()) {
                LOG.info(String.format("[%s] 是目录, 递归获取该目录下的文件", f.getPath().toString()));
                getHDFSAllFilesNORegex(f.getPath().toString(), hdfs);
            } else if (f.isFile()) {
                if (f.getLen()==0){
                    String message = String.format("文件[%s]长度为0，将会跳过不作处理！", path);
                    LOG.warn(message);
                }else {
                    addSourceFileByType(f.getPath().toString());
                }
            } else {
                String message = String.format("该路径[%s]文件类型既不是目录也不是文件，插件自动忽略。",
                        f.getPath().toString());
                LOG.info(message);
            }
        }
        return sourceHDFSAllFilesList;
    }

    // 根据用户指定的文件类型，将指定的文件类型的路径加入sourceHDFSAllFilesList
    private void addSourceFileByType(String filePath) {
        // 检查file的类型和用户配置的fileType类型是否一致
        boolean isMatchedFileType = checkHdfsFileType(filePath, this.specifiedFileType);

        if (isMatchedFileType) {// 文件类型一致
            LOG.info(String.format("[%s]是[%s]类型的文件, 将该文件加入source files列表", filePath, this.specifiedFileType));
            sourceHDFSAllFilesList.add(filePath);
        } else {                // 文件类型不一致提示告警
            String message = String.format("文件[%s]的类型与用户配置的fileType类型不一致，" +
                            "请确认您配置的目录下面所有文件的类型均为[%s]"
                    , filePath, this.specifiedFileType);
            LOG.error(message);
            throw DataXException.asDataXException(
                    HdfsReaderErrorCode.FILE_TYPE_UNSUPPORT, message);
        }
    }

    public InputStream getInputStream(String filepath) {
        InputStream inputStream;
        Path path = new Path(filepath);
        try {
            FileSystem fs = FileSystem.get(hadoopConf);
            //If the network disconnected, this method will retry 45 times
            //each time the retry interval for 20 seconds
            inputStream = fs.open(path);
            return inputStream;
        } catch (IOException e) {
            String message = String.format("读取文件 : [%s] 时出错,请确认文件：[%s]存在且配置的用户有权限读取", filepath, filepath);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message, e);
        }
    }

    // sequence 文件类型读取
    public void sequenceFileStartRead(String sourceSequenceFilePath, Configuration readerSliceConfig,
                                      RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read sequence file [%s].", sourceSequenceFilePath));

        Path seqFilePath = new Path(sourceSequenceFilePath);
        SequenceFile.Reader reader = null;
        try {
            //获取SequenceFile.Reader实例
            reader = new SequenceFile.Reader(this.hadoopConf, SequenceFile.Reader.file(seqFilePath));
            //获取key 与 value
            Writable key = (Writable) ReflectionUtils.newInstance(reader.getKeyClass(), this.hadoopConf);
            Text value = new Text();
            while (reader.next(key, value)) {
                if (StringUtils.isNotBlank(value.toString())) {
                    UnstructuredStorageReaderUtil.transportOneRecord(recordSender,
                            readerSliceConfig, taskPluginCollector, value.toString());
                }
            }
        } catch (Exception e) {
            String message = String.format("SequenceFile.Reader读取文件[%s]时出错", sourceSequenceFilePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_SEQUENCEFILE_ERROR, message, e);
        } finally {
            IOUtils.closeStream(reader);
            LOG.info("Finally, Close stream SequenceFile.Reader.");
        }
    }

    // rc  文件类型读取
    public void rcFileStartRead(String sourceRcFilePath, Configuration readerSliceConfig,
                                RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read rcfile [%s].", sourceRcFilePath));
        List<ColumnEntry> column = UnstructuredStorageReaderUtil
                .getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        // warn: no default value '\N'
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);

        Path rcFilePath = new Path(sourceRcFilePath);
        FileSystem fs = null;
        RCFileRecordReader recordReader = null;
        try {
            fs = FileSystem.get(rcFilePath.toUri(), hadoopConf);
            long fileLen = fs.getFileStatus(rcFilePath).getLen();
            FileSplit split = new FileSplit(rcFilePath, 0, fileLen, (String[]) null);
            recordReader = new RCFileRecordReader(hadoopConf, split);
            LongWritable key = new LongWritable();
            BytesRefArrayWritable value = new BytesRefArrayWritable();
            Text txt = new Text();
            while (recordReader.next(key, value)) {
                String[] sourceLine = new String[value.size()];
                txt.clear();
                for (int i = 0; i < value.size(); i++) {
                    BytesRefWritable v = value.get(i);
                    txt.set(v.getData(), v.getStart(), v.getLength());
                    sourceLine[i] = txt.toString();
                }
                UnstructuredStorageReaderUtil.transportOneRecord(recordSender, column, sourceLine, nullFormat, taskPluginCollector);
            }

        } catch (IOException e) {
            String message = String.format("读取文件[%s]时出错", sourceRcFilePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_RCFILE_ERROR, message, e);
        } finally {
            try {
                if (recordReader != null) {
                    recordReader.close();
                    LOG.info("Finally, Close RCFileRecordReader.");
                }
            } catch (IOException e) {
                LOG.warn(String.format("finally: 关闭RCFileRecordReader失败, %s", e.getMessage()));
            }
        }

    }

    // orc 文件类型读取
    public void orcFileStartRead(String sourceOrcFilePath, Configuration readerSliceConfig,
                                 RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read orcfile [%s].", sourceOrcFilePath));
        List<ColumnEntry> column = UnstructuredStorageReaderUtil
                .getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);
        StringBuilder allColumns = new StringBuilder();
        StringBuilder allColumnTypes = new StringBuilder();
        boolean isReadAllColumns = false;
        int columnIndexMax = -1;
        // 判断是否读取所有列
        if (null == column || column.size() == 0) {
            int allColumnsCount = getAllColumnsCount(sourceOrcFilePath);
            columnIndexMax = allColumnsCount - 1;
            isReadAllColumns = true;
        } else {
            columnIndexMax = getMaxIndex(column);
        }
        for (int i = 0; i <= columnIndexMax; i++) {
            allColumns.append("col");
            allColumnTypes.append("string");
            if (i != columnIndexMax) {
                allColumns.append(",");
                allColumnTypes.append(":");
            }
        }
        if (columnIndexMax >= 0) {
            JobConf conf = new JobConf(hadoopConf);
            Path orcFilePath = new Path(sourceOrcFilePath);
            Properties p = new Properties();
            p.setProperty("columns", allColumns.toString());
            p.setProperty("columns.types", allColumnTypes.toString());
            try {
                OrcSerde serde = new OrcSerde();
                serde.initialize(conf, p);
                StructObjectInspector inspector = (StructObjectInspector) serde.getObjectInspector();
                InputFormat<?, ?> in = new OrcInputFormat();
                FileInputFormat.setInputPaths(conf, orcFilePath.toString());

                //If the network disconnected, will retry 45 times, each time the retry interval for 20 seconds
                //Each file as a split
                //TODO multy threads
                InputSplit[] splits = in.getSplits(conf, 1);

                RecordReader reader = in.getRecordReader(splits[0], conf, Reporter.NULL);
                Object key = reader.createKey();
                Object value = reader.createValue();
                // 获取列信息
                List<? extends StructField> fields = inspector.getAllStructFieldRefs();

                List<Object> recordFields;
                while (reader.next(key, value)) {
                    recordFields = new ArrayList<Object>();

                    for (int i = 0; i <= columnIndexMax; i++) {
                        Object field = inspector.getStructFieldData(value, fields.get(i));
                        recordFields.add(field);
                    }
                    transportOneRecord(column, recordFields, recordSender, taskPluginCollector, isReadAllColumns, nullFormat);
                }
                reader.close();
            } catch (Exception e) {
                String message = String.format("从orcfile文件路径[%s]中读取数据发生异常，请联系系统管理员。"
                        , sourceOrcFilePath);
                LOG.error(message);
                throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
            }
        } else {
            String message = String.format("请确认您所读取的列配置正确！columnIndexMax 小于0,column:%s", JSON.toJSONString(column));
            throw DataXException.asDataXException(HdfsReaderErrorCode.BAD_CONFIG_VALUE, message);
        }
    }


    public void parquetFileStartRead2(String sourceParquetFilePath, Configuration readerSliceConfig, RecordSender recordSender, TaskPluginCollector taskPluginCollector) {
        LOG.info(String.format("Start Read parquetfile [%s].", sourceParquetFilePath));
        List<ColumnEntry> column = UnstructuredStorageReaderUtil.getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);

        boolean isReadAllColumns = false;
        int columnIndexMax = -1;    // 字段个数最大索引
        // 判断是否读取所有列，赋值最大列长度
        if (null == column || column.size() == 0) {
            int allColumnsCount = getParquetAllColumnsCount(sourceParquetFilePath);
            LOG.info("获取parquet文件的列长:"+allColumnsCount);
            columnIndexMax = allColumnsCount - 1;
            isReadAllColumns = true;
        } else {
            columnIndexMax = getMaxIndex(column);
        }
        // 字段数>0
        if (columnIndexMax >= 0) {
            Path parquetFilePath = new Path(sourceParquetFilePath);

            //1.创建GroupReadSupport
            GroupReadSupport support = new GroupReadSupport();
            //2.使用ParquetReader.builder方法创建reader对象。
            ParquetReader.Builder<Group> builder = ParquetReader.builder(support, parquetFilePath);
//            LOG.info("使用ParquetReader.Builder对象.builder方法创建reader对象。");
            ParquetReader<Group> reader= null;
            try {
                reader = builder.build();
            } catch (IOException e) {
                e.printStackTrace();
                LOG.error("ParquetReader.Builder对象.builder方法创建reader对象创建失败。");
            }
            //3.读取一个数据，并输出group的schema列名
            if (reader!=null){
                Group line = null ;
                List<Object> recordFields;  // 数据记录的列字段集合
                try {
                    while((line = reader.read()) != null ){
                        recordFields = new ArrayList<Object>();
                        //从line中获取记录的每个字段
                        for (int i = 0; i <= columnIndexMax; i++) {
                            Object field = line.getValueToString(i, 0);
                            recordFields.add(field);
                        }
                        transportOneRecord(column, recordFields, recordSender, taskPluginCollector, isReadAllColumns, nullFormat);
                    }
                    reader.close();
                } catch (Exception e) {
                    String message = String.format("从parquetfile文件路径[%s]中读取数据发生异常，请联系系统管理员。", sourceParquetFilePath);
                    LOG.error(message);
                    throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
                }
            }
        } else {
            String message = String.format("请确认您所读取的列配置正确！columnIndexMax 小于0,column:%s", JSON.toJSONString(column));
            throw DataXException.asDataXException(HdfsReaderErrorCode.BAD_CONFIG_VALUE, message);
        }
    }

    public void parquetFileStartRead(String sourceParquetFilePath, Configuration readerSliceConfig, RecordSender recordSender, TaskPluginCollector taskPluginCollector)
    {
        LOG.info("Start Read parquet-file [{}].", sourceParquetFilePath);
        List<ColumnEntry> column = UnstructuredStorageReaderUtil.getListColumnEntry(readerSliceConfig, com.alibaba.datax.plugin.unstructuredstorage.reader.Key.COLUMN);
        String nullFormat = readerSliceConfig.getString(com.alibaba.datax.plugin.unstructuredstorage.reader.Key.NULL_FORMAT);
        Path parquetFilePath = new Path(sourceParquetFilePath);

        hadoopConf.set("parquet.avro.readInt96AsFixed", "true");
        JobConf conf = new JobConf(hadoopConf);

        GenericData decimalSupport = new GenericData();
        decimalSupport.addLogicalTypeConversion(new Conversions.DecimalConversion());

        try (ParquetReader<GenericData.Record> reader = AvroParquetReader.<GenericData.Record>builder(HadoopInputFile.fromPath(parquetFilePath, hadoopConf))
                .withDataModel(decimalSupport)
                .withConf(conf)
                .build()) {
            GenericData.Record gRecord = reader.read();
            Schema schema = gRecord.getSchema();
//            LOG.info("schema信息："+schema.toString());
            if (null == column || column.isEmpty()) {
                column = new ArrayList<>(schema.getFields().size());
                String sType;
                // 用户没有填写具体的字段信息，需要从parquet文件构建
                for (int i = 0; i < schema.getFields().size(); i++) {
                    ColumnEntry columnEntry = new ColumnEntry();
                    // 设置列索引
                    columnEntry.setIndex(i);
                    // 设置列名称
//                    columnEntry.setName(schema.getFields().get(i).name());
                    Schema type;
                    if (schema.getFields().get(i).schema().getType() == Schema.Type.UNION) {
                        type = schema.getFields().get(i).schema().getTypes().get(1);
                    } else {
                        type = schema.getFields().get(i).schema();
                    }

                    sType = type.getProp("logicalType") != null ? type.getProp("logicalType") : type.getType().getName();
                    // 数据类型设置
                    if (sType.startsWith("timestamp")) {
                        columnEntry.setType("timestamp");
                    } else {
                        columnEntry.setType(sType);
                    }
//                    LOG.info("列子墩信息："+columnEntry.toJSONString());
                    column.add(columnEntry);
                }
            }
            while (gRecord != null) {
                transportParquetRecord(column, gRecord, recordSender, taskPluginCollector, nullFormat,schema.getFields());
                gRecord = reader.read();
            }

        }
        catch (IOException e) {
            String message = String.format("从parquet file文件路径[%s]中读取数据发生异常，请联系系统管理员。", sourceParquetFilePath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
        }
    }

    /*
     * 记录值转换为parquet格式
     */
    private void transportParquetRecord(List<ColumnEntry> columnConfigs, GenericData.Record gRecord, RecordSender recordSender, TaskPluginCollector taskPluginCollector, String nullFormat,List<Schema.Field> fields)
    {
        Record record = recordSender.createRecord();
        Column columnGenerated;
        // Recoder 对象转换为 jsonobject
        JSONObject recordJson = JSONObject.parseObject(gRecord.toString());
        int scale = 10;
        // 遍历列集合信息
        try {
            for (ColumnEntry columnEntry : columnConfigs) {
//                LOG.info(columnEntry.toJSONString());
                String columnType = columnEntry.getType();      // parquet文件的列类型
                Integer columnIndex = columnEntry.getIndex();   // parquet文件的列索引
                String columnConst = columnEntry.getValue();    // parquet文件的列内容
                String columnValue = null;
                // 列非空，进行取值
                if (null != columnIndex) {
                    Object fieldValue = gRecord.get(columnIndex);
                    if (null != fieldValue) {
//                    if (null != cRecord) {
                        // 原始
//                        columnValue = StandardCharsets.UTF_8.decode((ByteBuffer)gRecord.get(columnIndex)).toString();
                        // json提取
//                        columnValue= new String(cRecord.getString(cRecord.getString(columnName)).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        if (fieldValue instanceof Utf8){
                            columnValue= recordJson.getString(fields.get(columnIndex).name());
                        }
                        if (fieldValue instanceof ByteBuffer){
                            // schema feilds集合提取
                            columnValue= new String(recordJson.getString(fields.get(columnIndex).name()).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        }
                    } else {
                        record.addColumn(new StringColumn(null));
                        continue;
                    }
                } else {
                    columnValue = columnConst;
                }

                // decimal类型转换
                if (columnType.startsWith("decimal(")) {
                    String ps = columnType.replace("decimal(", "").replace(")", "");
                    columnType = "decimal";
                    if (ps.contains(",")) {
                        scale = Integer.parseInt(ps.split(",")[1].trim());
                    }
                    else {
                        scale = 0;
                    }
                }
                Type type = Type.valueOf(columnType.toUpperCase());
//                LOG.info("获取的列类型为："+type.toString()+"，获取的列内容为："+columnValue);
                if (StringUtils.equals(columnValue, nullFormat)) {
                    columnValue = null;
                }
                try {
                    switch (type) {
                        case STRING:
                            columnGenerated = new StringColumn(columnValue);
                            break;
                        case INT:
                        case LONG:
                            columnGenerated = new LongColumn(columnValue);
                            break;
                        case DOUBLE:
                            columnGenerated = new DoubleColumn(columnValue);
                            break;
                        case DECIMAL:
                            if (null == columnValue) {
                                columnGenerated = new DoubleColumn((Double) null);
                            }
                            else {
                                columnGenerated = new DoubleColumn(new BigDecimal(columnValue).setScale(scale, RoundingMode.HALF_UP));
                            }
                            break;
                        case BOOLEAN:
                            columnGenerated = new BoolColumn(columnValue);
                            break;
                        case DATE:
                            if (columnValue == null) {
                                columnGenerated = new DateColumn((Date) null);
                            }
                            else {
                                String formatString = columnEntry.getFormat();
                                if (StringUtils.isNotBlank(formatString)) {
                                    // 用户自己配置的格式转换
                                    SimpleDateFormat format = new SimpleDateFormat(
                                            formatString);
                                    columnGenerated = new DateColumn(
                                            format.parse(columnValue));
                                }
                                else {
                                    // 框架尝试转换
                                    columnGenerated = new DateColumn(new StringColumn(columnValue).asDate());
                                }
                            }
                            break;
                        case TIMESTAMP:
                            if (null == columnValue) {
                                columnGenerated = new DateColumn();
                            }
                            else if (columnValue.startsWith("[")) {
                                // INT96 https://github.com/apache/parquet-mr/pull/901
                                GenericData.Fixed fixed = (GenericData.Fixed) gRecord.get(columnIndex);
                                Date date = new Date(getTimestampMills(fixed.bytes()));
                                columnGenerated = new DateColumn(date);
                            }
                            else {
                                columnGenerated = new DateColumn(Long.parseLong(columnValue) * 1000);
                            }
                            break;
                        case BINARY:
                        case BYTES:
                            columnGenerated = new BytesColumn(((ByteBuffer) gRecord.get(columnIndex)).array());
                            break;
                        default:
                            String errorMessage = String.format("您配置的列类型暂不支持 : [%s]", columnType);
                            LOG.error(errorMessage);
                            throw DataXException.asDataXException(UnstructuredStorageReaderErrorCode.NOT_SUPPORT_TYPE, errorMessage);
                    }
                }
                catch (Exception e) {
                    throw new IllegalArgumentException(String.format("类型转换错误, 无法将[%s] 转换为[%s], %s", columnValue, type, e));
                }
                record.addColumn(columnGenerated);
            } // end for

            recordSender.sendToWriter(record);
        }
        catch (IllegalArgumentException | IndexOutOfBoundsException iae) {
            taskPluginCollector.collectDirtyRecord(record, iae.getMessage());
        }
        catch (Exception e) {
            if (e instanceof DataXException) {
                throw (DataXException) e;
            }
            // 每一种转换失败都是脏数据处理,包括数字格式 & 日期格式
            taskPluginCollector.collectDirtyRecord(record, e.getMessage());
        }
    }

    public static long getTimestampMills(byte[] bytes)
    {
        assert bytes.length == 12;
        // little endian encoding - need to invert byte order
        long timeOfDayNanos = Longs.fromBytes(bytes[7], bytes[6], bytes[5], bytes[4], bytes[3], bytes[2], bytes[1], bytes[0]);
        int julianDay = Ints.fromBytes(bytes[11], bytes[10], bytes[9], bytes[8]);

        return julianDayToMillis(julianDay) + (timeOfDayNanos / NANOS_PER_MILLISECOND);
    }

    private static long julianDayToMillis(int julianDay)
    {
        return (julianDay - JULIAN_EPOCH_OFFSET_DAYS) * MILLIS_IN_DAY;
    }

    private Record transportOneRecord(List<ColumnEntry> columnConfigs, List<Object> recordFields, RecordSender recordSender, TaskPluginCollector taskPluginCollector, boolean isReadAllColumns, String nullFormat) {
        Record record = recordSender.createRecord();
        Column columnGenerated;
        try {
            if (isReadAllColumns) {
                // 读取所有列，创建都为String类型的column
                for (Object recordField : recordFields) {
                    String columnValue = null;
                    if (recordField != null) {
                        columnValue = recordField.toString();
                    }
                    columnGenerated = new StringColumn(columnValue);
                    record.addColumn(columnGenerated);
                }
            } else {
                for (ColumnEntry columnConfig : columnConfigs) {
                    String columnType = columnConfig.getType();
                    Integer columnIndex = columnConfig.getIndex();
                    String columnConst = columnConfig.getValue();

                    String columnValue = null;

                    if (null != columnIndex) {
                        if (null != recordFields.get(columnIndex))
                            columnValue = recordFields.get(columnIndex).toString();
                    } else {
                        columnValue = columnConst;
                    }
                    Type type = Type.valueOf(columnType.toUpperCase());
                    // it's all ok if nullFormat is null
                    if (StringUtils.equals(columnValue, nullFormat)) {
                        columnValue = null;
                    }
                    switch (type) {
                        case STRING:
                            columnGenerated = new StringColumn(columnValue);
                            break;
                        case LONG:
                            try {
                                columnGenerated = new LongColumn(columnValue);
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format("类型转换错误, 无法将[%s] 转换为[%s]", columnValue, "LONG"));
                            }
                            break;
                        case DOUBLE:
                            try {
                                columnGenerated = new DoubleColumn(columnValue);
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format("类型转换错误, 无法将[%s] 转换为[%s]", columnValue, "DOUBLE"));
                            }
                            break;
                        case BOOLEAN:
                            try {
                                columnGenerated = new BoolColumn(columnValue);
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format("类型转换错误, 无法将[%s] 转换为[%s]", columnValue, "BOOLEAN"));
                            }
                            break;
                        case DATE:
                            try {
                                if (columnValue == null) {
                                    columnGenerated = new DateColumn((Date) null);
                                } else {
                                    String formatString = columnConfig.getFormat();
                                    if (StringUtils.isNotBlank(formatString)) {
                                        // 用户自己配置的格式转换
                                        SimpleDateFormat format = new SimpleDateFormat(
                                                formatString);
                                        columnGenerated = new DateColumn(
                                                format.parse(columnValue));
                                    } else {
                                        // 框架尝试转换
                                        columnGenerated = new DateColumn(
                                                new StringColumn(columnValue)
                                                        .asDate());
                                    }
                                }
                            } catch (Exception e) {
                                throw new IllegalArgumentException(String.format("类型转换错误, 无法将[%s] 转换为[%s]", columnValue, "DATE"));
                            }
                            break;
                        default:
                            String errorMessage = String.format("您配置的列类型暂不支持 : [%s]", columnType);
                            LOG.error(errorMessage);
                            throw DataXException
                                    .asDataXException(UnstructuredStorageReaderErrorCode.NOT_SUPPORT_TYPE, errorMessage);
                    }
                    record.addColumn(columnGenerated);
                }
            }
            recordSender.sendToWriter(record);
        } catch (IllegalArgumentException iae) {
            taskPluginCollector.collectDirtyRecord(record, iae.getMessage());
        } catch (IndexOutOfBoundsException ioe) {
            taskPluginCollector.collectDirtyRecord(record, ioe.getMessage());
        } catch (Exception e) {
            if (e instanceof DataXException) {
                throw (DataXException) e;
            }
            // 每一种转换失败都是脏数据处理,包括数字格式 & 日期格式
            taskPluginCollector.collectDirtyRecord(record, e.getMessage());
        }
        return record;
    }

    // 获取列长度
    private int getAllColumnsCount(String filePath) {
        Path path = new Path(filePath);
        try {
            Reader reader = OrcFile.createReader(path, OrcFile.readerOptions(hadoopConf));
            return reader.getTypes().get(0).getSubtypesCount();
        } catch (IOException e) {
            String message = "读取orcfile column列数失败，请联系系统管理员";
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message);
        }
    }

    // 获取Parquet文件的列长度
    private int getParquetAllColumnsCount(String filePath) {
        int fieldCount = 0;
        try{
            //1.创建GroupReadSupport
            GroupReadSupport readSupport = new GroupReadSupport();
            //2.使用ParquetReader.builder方法创建reader对象。
            ParquetReader<Group> build = ParquetReader.builder(readSupport, new Path(filePath)).build();
            //3.读取一个数据，并输出group的schema列名
            Group group = null;
            if (((group = build.read()) != null)) {
                fieldCount = group.getType().getFieldCount();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fieldCount;
    }

    private int getMaxIndex(List<ColumnEntry> columnConfigs) {
        int maxIndex = -1;
        for (ColumnEntry columnConfig : columnConfigs) {
            Integer columnIndex = columnConfig.getIndex();
            if (columnIndex != null && columnIndex < 0) {
                String message = String.format("您column中配置的index不能小于0，请修改为正确的index,column配置:%s",
                        JSON.toJSONString(columnConfigs));
                LOG.error(message);
                throw DataXException.asDataXException(HdfsReaderErrorCode.CONFIG_INVALID_EXCEPTION, message);
            } else if (columnIndex != null && columnIndex > maxIndex) {
                maxIndex = columnIndex;
            }
        }
        return maxIndex;
    }

    private enum Type {
        STRING, LONG, BOOLEAN, DOUBLE, DATE,INT,BINARY,TIMESTAMP,DECIMAL,BYTES,
    }

    /**
     * 输入的hdfs文件类型和用户输入的filetype做校验
     * @param filepath              读取的hdfs文件路径
     * @param specifiedFileType     用户设置的文件类型
     * @return                      类型结果一致性，true代表一致，false代表不一致
     */
    public boolean checkHdfsFileType(String filepath, String specifiedFileType) {
        Path file = new Path(filepath);
        try {
            FileSystem fs = FileSystem.get(hadoopConf);
            FSDataInputStream in = fs.open(file);
            // 用户定义文件类型判断
            if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.CSV) || StringUtils.equalsIgnoreCase(specifiedFileType, Constant.TEXT)) {
                boolean isORC = isORCFile(file, fs, in);// 判断是否是 ORC File
                if (isORC) {
                    return false;
                }
                boolean isPAR = isParquetFile(filepath, in);// 判断是否是 PAR File
                if (isPAR) {
                    return false;
                }
                boolean isRC = isRCFile(filepath, in);// 判断是否是 RC File
                if (isRC) {
                    return false;
                }
                boolean isSEQ = isSequenceFile(filepath, in);// 判断是否是 Sequence File
                if (isSEQ) {
                    return false;
                }
                // 如果不是ORC,RC和SEQ,则默认为是TEXT或CSV类型
                return !isORC && !isPAR && !isRC && !isSEQ;

            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.ORC)) {// 文件类型判断是 ORC
                return isORCFile(file, fs, in);
            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.RC)) {// 文件类型判断是 RC
                return isRCFile(filepath, in);
            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.SEQ)) {// 文件类型判断是 SEQ
                return isSequenceFile(filepath, in);
            } else if (StringUtils.equalsIgnoreCase(specifiedFileType, Constant.PAR)){// 文件类型判断是 PAR
                return isParquetFile(filepath,in);
            }

        } catch (Exception e) {
            String message = String.format("检查文件[%s]类型失败，目前支持ORC,SEQUENCE,RCFile,TEXT,CSV五种格式的文件," +
                    "请检查您文件类型和文件是否正确。", filepath);
            LOG.error(message);
            throw DataXException.asDataXException(HdfsReaderErrorCode.READ_FILE_ERROR, message, e);
        }
        return false;
    }

    // 判断file是否是ORC File
    private boolean isORCFile(Path file, FileSystem fs, FSDataInputStream in) {
        try {
            // 使用选项或文件系统找出文件的大小
            long size = fs.getFileStatus(file).getLen();

            //将最后一个字节读入缓冲区以获取 PostScript
            int readSize = (int) Math.min(size, DIRECTORY_SIZE_GUESS);
            in.seek(size - readSize);
            ByteBuffer buffer = ByteBuffer.allocate(readSize);
            in.readFully(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());

            //读取PostScript，获取PostScript的长度
            int psLen = buffer.get(readSize - 1) & 0xff;
            int len = OrcFile.MAGIC.length();
            if (psLen < len + 1) {
                return false;
            }
            int offset = buffer.arrayOffset() + buffer.position() + buffer.limit() - 1 - len;
            byte[] array = buffer.array();
            // now look for the magic string at the end of the postscript.
            if (Text.decode(array, offset, len).equals(OrcFile.MAGIC)) {
                return true;
            } else {
                // If it isn't there, this may be the 0.11.0 version of ORC.
                // Read the first 3 bytes of the file to check for the header
                in.seek(0);
                byte[] header = new byte[len];
                in.readFully(header, 0, len);
                // if it isn't there, this isn't an ORC file
                if (Text.decode(header, 0, len).equals(OrcFile.MAGIC)) {
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.info(String.format("检查文件类型: [%s] 不是ORC File.", file.toString()));
        }
        return false;
    }

    /**
     * 判断file是否是PAR File
     * @param filepath
     * @param in
     * @return
     */
    private boolean isParquetFile(String filepath, FSDataInputStream in) {
        // RCFile 的第一个版本使用了序列文件头。
        final byte[] PARQUET_MAGIC = new byte[]{(byte) 'P', (byte) 'A', (byte) 'R',(byte)'1'};
        byte[] magic = new byte[PARQUET_MAGIC.length];
        try {
            in.seek(0);
            in.readFully(magic);
            if (Arrays.equals(magic, PARQUET_MAGIC)) {
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            LOG.info(String.format("检查文件类型: [%s] 不是PAR File.", filepath));
        }
        return false;
    }

    // 判断file是否是RC file
    private boolean isRCFile(String filepath, FSDataInputStream in) {
        // RCFile 的第一个版本使用了序列文件头。
        final byte[] ORIGINAL_MAGIC = new byte[]{(byte) 'S', (byte) 'E', (byte) 'Q'};

        // RCFile 开头的“magic”字节
        final byte[] RC_MAGIC = new byte[]{(byte) 'R', (byte) 'C', (byte) 'F'};

        // 原始magic中包含的版本，映射到 ORIGINAL_VERSION
        final byte ORIGINAL_MAGIC_VERSION_WITH_METADATA = 6;

        // 所有版本都应放在此列表中。
        final int ORIGINAL_VERSION = 0;  // version with SEQ
        final int NEW_MAGIC_VERSION = 1; // version with RCF
        final int CURRENT_VERSION = NEW_MAGIC_VERSION;
        byte version;

        byte[] magic = new byte[RC_MAGIC.length];
        try {
            in.seek(0);
            in.readFully(magic);
            // 判断 magic 是否一致
            if (Arrays.equals(magic, ORIGINAL_MAGIC)) {
                byte vers = in.readByte();
                if (vers != ORIGINAL_MAGIC_VERSION_WITH_METADATA) {
                    return false;
                }
                version = ORIGINAL_VERSION;
            } else {
                if (!Arrays.equals(magic, RC_MAGIC)) {
                    return false;
                }
                // 设置'版本'
                version = in.readByte();
                if (version > CURRENT_VERSION) {
                    return false;
                }
            }

            if (version == ORIGINAL_VERSION) {
                try {
                    Class<?> keyCls = hadoopConf.getClassByName(Text.readString(in));
                    Class<?> valCls = hadoopConf.getClassByName(Text.readString(in));
                    if (!keyCls.equals(RCFile.KeyBuffer.class)
                            || !valCls.equals(RCFile.ValueBuffer.class)) {
                        return false;
                    }
                } catch (ClassNotFoundException e) {
                    return false;
                }
            }
            boolean decompress = in.readBoolean(); // 是否压缩
            if (version == ORIGINAL_VERSION) {
                // 是否块压缩？该值应总设置为 false
                boolean blkCompressed = in.readBoolean();
                if (blkCompressed) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            LOG.info(String.format("检查文件类型: [%s] 不是RC File.", filepath));
        }
        return false;
    }

    // 判断file是否是Sequence file
    private boolean isSequenceFile(String filepath, FSDataInputStream in) {
        byte[] SEQ_MAGIC = new byte[]{(byte) 'S', (byte) 'E', (byte) 'Q'};
        byte[] magic = new byte[SEQ_MAGIC.length];
        try {
            in.seek(0);
            in.readFully(magic);
            if (Arrays.equals(magic, SEQ_MAGIC)) {
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            LOG.info(String.format("检查文件类型: [%s] 不是Sequence File.", filepath));
        }
        return false;
    }

}
