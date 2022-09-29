package com.alibaba.datax.plugin.writer.hivewriter;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class HiveServer2ConnectUtil {
    private static final Logger LOG = LoggerFactory.getLogger(HiveServer2ConnectUtil.class);

    /**
     * hive执行多个sql
     *
     * @param username
     * @param password
     * @param hiveSql
     * @param hiveJdbcUrl
     * @return
     */
    public static boolean execHiveSql(String username, String password, String hiveSql, String hiveJdbcUrl) {
        try {
            Class.forName("org.apache.hive.jdbc.HiveDriver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        try {
            LOG.info("hiveJdbcUrl:{}", hiveJdbcUrl);
            LOG.info("username:{}", username);
            LOG.info("password:{}", password);
            Connection conn = DriverManager.getConnection(hiveJdbcUrl, username, password);
            Statement stmt = conn.createStatement();

            String[] hiveSqls = hiveSql.split(";");
            for (int i = 0; i < hiveSqls.length; i++) {
                if (StringUtils.isNotEmpty(hiveSqls[i])) {
                    stmt.execute(hiveSqls[i]);
                }
            }
            return true;
        } catch (SQLException sqlException) {
            LOG.error(sqlException.getMessage(), sqlException);
            return false;
        }
    }
}