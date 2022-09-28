package com.alibaba.datax.core.transport.transformer;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.element.StringColumn;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.transformer.Transformer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * @ClassName EncodeAndDecode
 * @Description TODO
 * @Author bb
 * @Date 2022/9/23 18:06
 */
public class Base64EncodeTransformer extends Transformer {
    private static final Logger LOG = LoggerFactory.getLogger(Base64EncodeTransformer.class);
    private static final String SALT = "dy!@#$%^&*";
    private static Base64.Encoder encoder = Base64.getEncoder();

    public Base64EncodeTransformer(){
        setTransformerName("dx_base64encode");
    }

    @Override
    public Record evaluate(Record record, Object... paras) {
        int columnIndex;
        try {
            if (paras.length < 1) {
                throw new RuntimeException("dx_base64encode paras lose");
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

            // 加密
            String newValue = encodeValue(oriValue);
            record.setColumn(columnIndex, new StringColumn(newValue));

        } catch (Exception e) {
            throw DataXException.asDataXException(TransformerErrorCode.TRANSFORMER_RUN_EXCEPTION, e.getMessage(), e);
        }

        return record;
    }

    /**
     * 编码 base64
     */
    public static String encodeValue(String password) {
        if (StringUtils.isEmpty(password)) {
            return StringUtils.EMPTY;
        }
        // Using Base64 + salt to process password

        String passwordWithSalt = SALT + encoder.encodeToString(password.getBytes(StandardCharsets.UTF_8));

        return encoder.encodeToString(passwordWithSalt.getBytes(StandardCharsets.UTF_8));
    }
}
