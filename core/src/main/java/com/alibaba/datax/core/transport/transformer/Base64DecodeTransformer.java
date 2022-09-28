package com.alibaba.datax.core.transport.transformer;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.element.StringColumn;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.transformer.Transformer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * @ClassName Base64DecodeTransformer
 * @Description TODO
 * @Author bb
 * @Date 2022/9/28 10:50
 */
public class Base64DecodeTransformer extends Transformer {
    private static final Logger LOG = LoggerFactory.getLogger(Base64DecodeTransformer.class);
    private static final String SALT = "dy!@#$%^&*";
    private static Base64.Decoder decoder = Base64.getDecoder();

    public Base64DecodeTransformer(){
        setTransformerName("dx_base64decode");
    }

    @Override
    public Record evaluate(Record record, Object... paras) {
        int columnIndex;
        try {
            if (paras.length < 1) {
                throw new RuntimeException("dx_base64decode paras lose");
            }
            columnIndex = (Integer) paras[0];
        } catch (Exception e) {
            throw DataXException.asDataXException(TransformerErrorCode.TRANSFORMER_ILLEGAL_PARAMETER, "paras:" + Arrays.asList(paras).toString() + " => " + e.getMessage());
        }

        Column column = record.getColumn(columnIndex);
        try {
            String oriValue = column.asString();
            if (oriValue == null) {
                return record;
            }
            // 解密
            String newValue = decodeValue(oriValue);
            record.setColumn(columnIndex, new StringColumn(newValue));

        } catch (Exception e) {
            throw DataXException.asDataXException(TransformerErrorCode.TRANSFORMER_RUN_EXCEPTION, e.getMessage(), e);
        }

        return record;
    }

    /**
     * 解码
     */
    public static String decodeValue(String password) {
        if (StringUtils.isEmpty(password)) {
            return StringUtils.EMPTY;
        }
        // Using Base64 + salt to process password
//        String passwordWithSalt = new String(Base64.decode(password), StandardCharsets.UTF_8);

        String passwordWithSalt = new String(decoder.decode(password), StandardCharsets.UTF_8);

        return new String(decoder.decode(passwordWithSalt.substring(SALT.length())), StandardCharsets.UTF_8);
    }
}
