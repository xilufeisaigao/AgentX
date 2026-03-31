package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Map;

@Component
public class WorkflowScenarioResolver {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final FlowStore flowStore;
    private final ObjectMapper objectMapper;

    public WorkflowScenarioResolver(FlowStore flowStore, ObjectMapper objectMapper) {
        this.flowStore = flowStore;
        this.objectMapper = objectMapper;
    }

    public WorkflowScenario resolve(String workflowRunId) {
        return resolveMetadata(workflowRunId).scenario();
    }

    public Optional<WorkflowProfileRef> resolveProfileRef(String workflowRunId) {
        WorkflowStartMetadata metadata = resolveMetadata(workflowRunId);
        if (metadata.profileId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new WorkflowProfileRef(
                metadata.profileId(),
                metadata.profileDisplayName(),
                metadata.profileVersion(),
                metadata.profileDigest()
        ));
    }

    public String resolveProfileId(String workflowRunId) {
        return resolveProfileRef(workflowRunId)
                .map(WorkflowProfileRef::profileId)
                .orElse("");
    }

    private WorkflowStartMetadata resolveMetadata(String workflowRunId) {
        WorkflowRunEvent latestStartEvent = null;
        for (WorkflowRunEvent event : flowStore.listRunEvents(workflowRunId)) {
            if ("WORKFLOW_STARTED".equals(event.eventType())) {
                latestStartEvent = event;
            }
        }
        return latestStartEvent == null ? WorkflowStartMetadata.defaultMetadata() : metadataFromJson(latestStartEvent.dataJson());
    }

    private WorkflowStartMetadata metadataFromJson(JsonPayload payload) {
        if (payload == null) {
            return WorkflowStartMetadata.defaultMetadata();
        }
        try {
            Map<String, Object> data = objectMapper.readValue(payload.json(), MAP_TYPE);
            return new WorkflowStartMetadata(
                    new WorkflowScenario(
                            booleanValue(data.get("requireHumanClarification")),
                            booleanValue(data.get("architectCanAutoResolveClarification")),
                            booleanValue(data.get("verifyNeedsRework"))
                    ),
                    stringValue(data.get("profileId")),
                    stringValue(data.get("profileDisplayName")),
                    stringValue(data.get("profileVersion")),
                    stringValue(data.get("profileDigest"))
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse workflow scenario", exception);
        }
    }

    private boolean booleanValue(Object rawValue) {
        if (rawValue instanceof Boolean boolValue) {
            return boolValue;
        }
        return rawValue != null && Boolean.parseBoolean(String.valueOf(rawValue));
    }

    private String stringValue(Object rawValue) {
        return rawValue == null ? "" : String.valueOf(rawValue);
    }

    private record WorkflowStartMetadata(
            WorkflowScenario scenario,
            String profileId,
            String profileDisplayName,
            String profileVersion,
            String profileDigest
    ) {

        private static WorkflowStartMetadata defaultMetadata() {
            return new WorkflowStartMetadata(
                    WorkflowScenario.defaultScenario(),
                    "",
                    "",
                    "",
                    ""
            );
        }
    }
}
