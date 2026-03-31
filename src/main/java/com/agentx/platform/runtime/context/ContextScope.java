package com.agentx.platform.runtime.context;

import java.nio.file.Path;
import java.util.Objects;

public record ContextScope(
        String workflowRunId,
        String taskId,
        String runId,
        String originNodeId,
        Path overlayRoot
) {

    public ContextScope {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        overlayRoot = overlayRoot == null ? null : overlayRoot.toAbsolutePath().normalize();
    }

    public static ContextScope workflow(String workflowRunId, String originNodeId) {
        return new ContextScope(workflowRunId, null, null, originNodeId, null);
    }

    public static ContextScope task(String workflowRunId, String taskId, String runId, String originNodeId, Path overlayRoot) {
        return new ContextScope(workflowRunId, taskId, runId, originNodeId, overlayRoot);
    }
}
