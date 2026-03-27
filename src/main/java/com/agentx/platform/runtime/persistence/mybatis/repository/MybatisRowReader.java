package com.agentx.platform.runtime.persistence.mybatis.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

final class MybatisRowReader {

    private MybatisRowReader() {
    }

    static boolean isEmpty(Map<String, Object> row) {
        return row == null || row.isEmpty();
    }

    static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required column: " + key);
        }
        return String.valueOf(value);
    }

    static String nullableString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static boolean bool(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(string(row, key));
    }

    static int integer(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(string(row, key));
    }

    static Integer nullableInteger(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static LocalDateTime nullableLocalDateTime(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value));
    }

    static <E extends Enum<E>> E enumValue(Map<String, Object> row, String key, Class<E> enumType) {
        return Enum.valueOf(enumType, string(row, key));
    }

    static List<String> stringList(Map<String, Object> row, String key) {
        return MybatisJsonSupport.readStringList(row.get(key));
    }
}
