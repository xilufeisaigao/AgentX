package com.agentx.platform.domain.agent;

public record AgentRuntimeProfile(
    String runtimeType,
    String model,
    int maxParallelRuns
) {

    public AgentRuntimeProfile {
        runtimeType = requireText(runtimeType, "runtimeType");
        model = requireText(model, "model");
        if (maxParallelRuns <= 0) {
            throw new IllegalArgumentException("maxParallelRuns must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

