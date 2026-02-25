package com.agentx.agentxbackend.planning.infrastructure.external;

import com.agentx.agentxbackend.planning.application.port.out.WorkerEligibilityPort;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class WorkforceWorkerEligibilityAdapter implements WorkerEligibilityPort {

    private final WorkerCapabilityUseCase workerCapabilityUseCase;
    private final ObjectMapper objectMapper;

    public WorkforceWorkerEligibilityAdapter(WorkerCapabilityUseCase workerCapabilityUseCase, ObjectMapper objectMapper) {
        this.workerCapabilityUseCase = workerCapabilityUseCase;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasEligibleWorker(String requiredToolpacksJson) {
        List<String> requiredToolpacks = parseToolpackIds(requiredToolpacksJson);
        return workerCapabilityUseCase.hasEligibleWorker(requiredToolpacks);
    }

    @Override
    public boolean isWorkerEligible(String workerId, String requiredToolpacksJson) {
        List<String> requiredToolpacks = parseToolpackIds(requiredToolpacksJson);
        return workerCapabilityUseCase.isWorkerEligible(workerId, requiredToolpacks);
    }

    private List<String> parseToolpackIds(String requiredToolpacksJson) {
        if (requiredToolpacksJson == null || requiredToolpacksJson.isBlank()) {
            throw new IllegalArgumentException("requiredToolpacksJson must not be blank");
        }
        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(requiredToolpacksJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("requiredToolpacksJson must be valid JSON array text", ex);
        }
        if (jsonNode == null || !jsonNode.isArray()) {
            throw new IllegalArgumentException("requiredToolpacksJson must be JSON array text");
        }
        LinkedHashSet<String> toolpackIds = new LinkedHashSet<>();
        for (JsonNode element : jsonNode) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException("requiredToolpacksJson element must be string");
            }
            String toolpackId = element.asText().trim();
            if (toolpackId.isEmpty()) {
                throw new IllegalArgumentException("requiredToolpacksJson element must not be blank");
            }
            toolpackIds.add(toolpackId);
        }
        return new ArrayList<>(toolpackIds);
    }
}
