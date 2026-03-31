package com.agentx.platform.runtime.evaluation;

import com.agentx.platform.runtime.context.CompiledContextPack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WorkflowEvalTraceCollector {

    private final WorkflowEvalProperties properties;
    private final Map<String, List<WorkflowEvalContextArtifact>> contextsByWorkflow = new ConcurrentHashMap<>();

    public WorkflowEvalTraceCollector(WorkflowEvalProperties properties) {
        this.properties = properties;
    }

    public void recordContextPack(CompiledContextPack contextPack) {
        if (!properties.isTraceCollectionEnabled()) {
            return;
        }
        WorkflowEvalContextArtifact artifact = new WorkflowEvalContextArtifact(
                contextPack.packType(),
                contextPack.scope().workflowRunId(),
                contextPack.scope().taskId(),
                contextPack.scope().runId(),
                contextPack.scope().originNodeId(),
                contextPack.artifactRef(),
                contextPack.sourceFingerprint(),
                contextPack.compiledAt(),
                contextPack.factBundle().sections(),
                contextPack.retrievalBundle().snippets()
        );
        contextsByWorkflow.computeIfAbsent(contextPack.scope().workflowRunId(), ignored -> new ArrayList<>()).add(artifact);
    }

    public List<WorkflowEvalContextArtifact> listContextArtifacts(String workflowRunId) {
        List<WorkflowEvalContextArtifact> artifacts = contextsByWorkflow.getOrDefault(workflowRunId, List.of());
        return artifacts.stream()
                .sorted(Comparator.comparing(WorkflowEvalContextArtifact::compiledAt))
                .toList();
    }

    public void clearWorkflow(String workflowRunId) {
        contextsByWorkflow.remove(workflowRunId);
    }
}
