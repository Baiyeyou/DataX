package com.alibaba.datax.plugin.reader.hivereader;


import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.RCFile;
import org.apache.hadoop.hive.ql.io.orc.OrcFile;
import org.apache.hadoop.io.*;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Created by mingya.wmy on 2015/8/12.
 */
public class DFSUtil {
    private static final Logger LOG = LoggerFactory.getLogger(HiveReader.Job.class);
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
                throw DataXException.asDataXException(HiveReaderErrorCode.KERBEROS_LOGIN_ERROR, message, e);
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
            throw DataXException.asDataXException(HiveReaderErrorCode.PATH_CONFIG_ERROR, e);
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
            String message = String.format("文件[%s]的类型与用户配置的fileType类型不一致，请确认您配置的目录下面所有文件的类型均为[%s]"
                    , filePath, this.specifiedFileType);
            LOG.error(message);
            throw DataXException.asDataXException(HiveReaderErrorCode.FILE_TYPE_UNSUPPORT, message);
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
            throw DataXException.asDataXException(HiveReaderErrorCode.READ_FILE_ERROR, message, e);
        }
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
            }

        } catch (Exception e) {
            String message = String.format("检查文件[%s]类型失败，目前支持TEXT格式的文件," +
                    "请检查您文件类型和文件是否正确。", filepath);
            LOG.error(message);
            throw DataXException.asDataXException(HiveReaderErrorCode.READ_FILE_ERROR, message, e);
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
