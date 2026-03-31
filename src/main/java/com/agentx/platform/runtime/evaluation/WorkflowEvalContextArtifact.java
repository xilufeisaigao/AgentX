package com.agentx.platform.runtime.evaluation;

import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.RetrievalSnippet;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkflowEvalContextArtifact(
        ContextPackType packType,
        String workflowRunId,
        String taskId,
        String runId,
        String originNodeId,
        String artifactRef,
        String sourceFingerprint,
        LocalDateTime compiledAt,
        Map<String, Object> factSections,
        List<RetrievalSnippet> retrievalSnippets
) {

    public WorkflowEvalContextArtifact {
        Objects.requireNonNull(packType, "packType must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        taskId = blankToNull(taskId);
        runId = blankToNull(runId);
        originNodeId = blankToNull(originNodeId);
        Objects.requireNonNull(artifactRef, "artifactRef must not be null");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint must not be null");
        Objects.requireNonNull(compiledAt, "compiledAt must not be null");
        // Context facts legitimately contain nulls before lower-layer truth exists.
        // Eval tracing must preserve those partial facts instead of crashing the real workflow.
        factSections = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(factSections, "factSections must not be null")));
        retrievalSnippets = List.copyOf(Objects.requireNonNull(retrievalSnippets, "retrievalSnippets must not be null"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
