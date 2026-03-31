package com.agentx.platform.controlplane.api.agent;

import com.agentx.platform.controlplane.application.AgentDefinitionCommandResult;
import com.agentx.platform.controlplane.application.AgentDefinitionCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/controlplane/agents")
public class AgentCommandController {

    private final AgentDefinitionCommandService agentDefinitionCommandService;

    public AgentCommandController(AgentDefinitionCommandService agentDefinitionCommandService) {
        this.agentDefinitionCommandService = agentDefinitionCommandService;
    }

    @PostMapping
    public AgentDefinitionCommandResult createAgent(@Valid @RequestBody CreateAgentRequest request) {
        return agentDefinitionCommandService.createAgent(
                request.agentId(),
                request.displayName(),
                request.purpose(),
                request.runtimeType(),
                request.model(),
                request.maxParallelRuns(),
                request.architectSuggested(),
                request.autoPoolEligible(),
                request.manualRegistrationAllowed(),
                request.enabled(),
                request.capabilityPackIds()
        );
    }

    @PatchMapping("/{agentId}/enable")
    public AgentDefinitionCommandResult enableAgent(@PathVariable String agentId) {
        return agentDefinitionCommandService.setAgentEnabled(agentId, true);
    }

    @PatchMapping("/{agentId}/disable")
    public AgentDefinitionCommandResult disableAgent(@PathVariable String agentId) {
        return agentDefinitionCommandService.setAgentEnabled(agentId, false);
    }

    @PutMapping("/{agentId}/capability-packs")
    public AgentDefinitionCommandResult replaceCapabilityPacks(
            @PathVariable String agentId,
            @Valid @RequestBody ReplaceCapabilityPacksRequest request
    ) {
        return agentDefinitionCommandService.replaceCapabilityBindings(agentId, request.capabilityPackIds());
    }

    public record CreateAgentRequest(
            @NotBlank String agentId,
            @NotBlank String displayName,
            @NotBlank String purpose,
            @NotBlank String runtimeType,
            @NotBlank String model,
            @Min(1) int maxParallelRuns,
            boolean architectSuggested,
            boolean autoPoolEligible,
            boolean manualRegistrationAllowed,
            boolean enabled,
            @NotEmpty List<@NotBlank String> capabilityPackIds
    ) {
    }

    public record ReplaceCapabilityPacksRequest(
            @NotEmpty List<@NotBlank String> capabilityPackIds
    ) {
    }
}
