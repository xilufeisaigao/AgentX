package com.agentx.platform.runtime.tooling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Component
public class ToolCallNormalizer {

    private final ObjectMapper objectMapper;

    public ToolCallNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolCall normalize(String runId, ToolCall rawToolCall) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(rawToolCall, "rawToolCall must not be null");

        String normalizedToolId = rawToolCall.toolId().trim().toLowerCase(Locale.ROOT);
        String normalizedOperation = rawToolCall.operation().trim().toLowerCase(Locale.ROOT);
        Map<String, Object> normalizedArguments = normalizeArguments(normalizedToolId, normalizedOperation, rawToolCall.arguments());

        if (normalizedToolId.equals("tool-filesystem") && normalizedOperation.equals("read_file")) {
            Object path = normalizedArguments.get("path");
            if (path == null || String.valueOf(path).isBlank()) {
                normalizedOperation = "list_directory";
                normalizedArguments = withStringArgument(normalizedArguments, "path", ".");
            }
        }

        if (normalizedToolId.equals("tool-filesystem") && normalizedOperation.equals("list_directory")) {
            Object path = normalizedArguments.get("path");
            if (path == null || String.valueOf(path).isBlank()) {
                normalizedArguments = withStringArgument(normalizedArguments, "path", ".");
            }
        }

        String normalizedCallId = rawToolCall.callId();
        if (normalizedCallId == null || normalizedCallId.isBlank()) {
            normalizedCallId = generatedCallId(runId, normalizedToolId, normalizedOperation, normalizedArguments);
        }

        return new ToolCall(
                normalizedCallId,
                normalizedToolId,
                normalizedOperation,
                normalizedArguments,
                rawToolCall.summary()
        );
    }

    private Map<String, Object> normalizeArguments(
            String toolId,
            String operation,
            Map<String, Object> rawArguments
    ) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        rawArguments.forEach((key, value) -> normalized.put(key, normalizeValue(key, value)));
        if (toolId.equals("tool-http-client") && operation.equals("http_request") && !normalized.containsKey("method")) {
            normalized.put("method", "GET");
        }
        return Map.copyOf(normalized);
    }

    private Object normalizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            String trimmed = stringValue.trim();
            if (key.equals("path")) {
                return trimmed.replace('\\', '/');
            }
            if (key.equals("method")) {
                return trimmed.toUpperCase(Locale.ROOT);
            }
            return trimmed;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalized = new TreeMap<>();
            mapValue.forEach((entryKey, entryValue) -> normalized.put(String.valueOf(entryKey), normalizeValue(String.valueOf(entryKey), entryValue)));
            return normalized;
        }
        if (value instanceof List<?> listValue) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : listValue) {
                normalized.add(normalizeValue("item", item));
            }
            return normalized;
        }
        return value;
    }

    private Map<String, Object> withStringArgument(Map<String, Object> arguments, String key, String value) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments);
        normalized.put(key, value);
        return Map.copyOf(normalized);
    }

    private String generatedCallId(
            String runId,
            String toolId,
            String operation,
            Map<String, Object> arguments
    ) {
        try {
            String seed = runId + "|" + toolId + "|" + operation + "|" + objectMapper.writeValueAsString(canonicalize(arguments));
            return "call-" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8))
                    .toString()
                    .replace("-", "")
                    .substring(0, 24);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to canonicalize tool call for callId generation", exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> canonical = new TreeMap<>();
            mapValue.forEach((entryKey, entryValue) -> canonical.put(String.valueOf(entryKey), canonicalize(entryValue)));
            return canonical;
        }
        if (value instanceof List<?> listValue) {
            List<Object> canonical = new ArrayList<>();
            for (Object item : listValue) {
                canonical.add(canonicalize(item));
            }
            return canonical;
        }
        return value;
    }
}
