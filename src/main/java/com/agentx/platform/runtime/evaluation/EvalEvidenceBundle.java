package com.agentx.platform.runtime.evaluation;

import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record EvalEvidenceBundle(
        WorkflowRuntimeSnapshot workflowSnapshot,
        Map<String, List<TaskRunEvent>> taskRunEventsByRun,
        List<WorkflowEvalContextArtifact> contextArtifacts,
        Map<String, Object> supplementalArtifacts,
        Map<String, String> artifactRefs
) {

    public EvalEvidenceBundle {
        Objects.requireNonNull(workflowSnapshot, "workflowSnapshot must not be null");
        taskRunEventsByRun = copyEventMap(taskRunEventsByRun);
        contextArtifacts = List.copyOf(Objects.requireNonNull(contextArtifacts, "contextArtifacts must not be null"));
        supplementalArtifacts = Map.copyOf(Objects.requireNonNull(supplementalArtifacts, "supplementalArtifacts must not be null"));
        artifactRefs = Map.copyOf(Objects.requireNonNull(artifactRefs, "artifactRefs must not be null"));
    }

    private static Map<String, List<TaskRunEvent>> copyEventMap(Map<String, List<TaskRunEvent>> source) {
        Objects.requireNonNull(source, "taskRunEventsByRun must not be null");
        return source.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())
                ));
    }
}
