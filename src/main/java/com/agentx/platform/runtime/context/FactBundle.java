package com.agentx.platform.runtime.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record FactBundle(
        Map<String, Object> sections
) {

    public FactBundle {
        // Context compilation needs to preserve "fact absent" as a first-class state.
        // Map.copyOf rejects null values, which breaks requirement/task packs before their lower-layer truth exists.
        sections = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(sections, "sections must not be null")));
    }
}
