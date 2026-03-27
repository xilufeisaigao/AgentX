package com.agentx.platform.domain.shared.model;

import java.util.Objects;

public record JsonPayload(String json) implements ValueObject {

    public JsonPayload {
        Objects.requireNonNull(json, "json must not be null");
        json = json.trim();
        if (json.isEmpty()) {
            throw new IllegalArgumentException("json must not be blank");
        }
        boolean objectLike = json.startsWith("{") && json.endsWith("}");
        boolean arrayLike = json.startsWith("[") && json.endsWith("]");
        if (!objectLike && !arrayLike) {
            throw new IllegalArgumentException("json payload must be an object or array string");
        }
    }

    public static JsonPayload emptyObject() {
        return new JsonPayload("{}");
    }
}
