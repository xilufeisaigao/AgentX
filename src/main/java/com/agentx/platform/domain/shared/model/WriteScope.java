package com.agentx.platform.domain.shared.model;

import java.util.Objects;

public record WriteScope(String path) implements ValueObject {

    public WriteScope {
        Objects.requireNonNull(path, "path must not be null");
        path = path.trim().replace('\\', '/');
        if (path.isEmpty()) {
            throw new IllegalArgumentException("write scope path must not be blank");
        }
    }
}
