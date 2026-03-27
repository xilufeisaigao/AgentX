package com.agentx.platform.runtime.persistence.mybatis.repository;

import com.agentx.platform.domain.shared.model.WriteScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

public final class MybatisJsonSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private MybatisJsonSupport() {
    }

    public static List<String> readStringList(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        if (rawValue instanceof List<?> rawList) {
            return rawList.stream().map(String::valueOf).toList();
        }
        try {
            if (rawValue instanceof byte[] bytes) {
                return OBJECT_MAPPER.readValue(bytes, STRING_LIST_TYPE);
            }
            String json = String.valueOf(rawValue);
            if (json.isBlank()) {
                return List.of();
            }
            return OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to parse json string list", exception);
        }
    }

    public static String writeStringList(List<String> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to write json string list", exception);
        }
    }

    public static List<WriteScope> readWriteScopeList(Object rawValue) {
        return readStringList(rawValue).stream()
                .map(WriteScope::new)
                .toList();
    }

    public static String writeWriteScopeList(List<WriteScope> values) {
        List<String> rawPaths = values == null
                ? List.of()
                : values.stream().map(WriteScope::path).toList();
        return writeStringList(rawPaths);
    }
}
