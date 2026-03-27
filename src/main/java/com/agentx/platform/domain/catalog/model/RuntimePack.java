package com.agentx.platform.domain.catalog.model;

import java.util.Objects;

public record RuntimePack(
        String runtimePackId,
        String displayName,
        String packType,
        String version,
        String locator,
        String description,
        boolean enabled
) {

    public RuntimePack {
        Objects.requireNonNull(runtimePackId, "runtimePackId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(packType, "packType must not be null");
        Objects.requireNonNull(version, "version must not be null");
    }
}
