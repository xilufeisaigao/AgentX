package com.agentx.platform.controlplane.api;

import com.agentx.platform.controlplane.application.AgentRegistryService;
import com.agentx.platform.controlplane.application.WorkflowCatalogService;
import com.agentx.platform.domain.agent.AgentDefinition;
import com.agentx.platform.domain.workflow.WorkflowTemplateDefinition;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/catalog")
public class PlatformCatalogController {

    private final AgentRegistryService agentRegistryService;
    private final WorkflowCatalogService workflowCatalogService;

    public PlatformCatalogController(
        AgentRegistryService agentRegistryService,
        WorkflowCatalogService workflowCatalogService
    ) {
        this.agentRegistryService = agentRegistryService;
        this.workflowCatalogService = workflowCatalogService;
    }

    @GetMapping("/agents")
    public List<AgentDefinition> listAgents() {
        return agentRegistryService.listAgents();
    }

    @PostMapping("/agents")
    public AgentDefinition registerAgent(@RequestBody RegisterAgentRequest request) {
        return agentRegistryService.register(
            new AgentRegistryService.RegisterAgentCommand(
                request.agentId(),
                request.displayName(),
                request.purpose(),
                request.capabilities(),
                request.allowedTools(),
                request.allowedSkills(),
                request.runtimeType(),
                request.model(),
                request.maxParallelRuns(),
                request.architectSuggested(),
                request.autoPoolEligible(),
                request.enabled()
            )
        );
    }

    @GetMapping("/workflows")
    public List<WorkflowTemplateDefinition> listWorkflows() {
        return workflowCatalogService.listBuiltIns();
    }

    @GetMapping("/kernel-policy")
    public WorkflowCatalogService.KernelPolicyView kernelPolicy() {
        return workflowCatalogService.kernelPolicy();
    }

    public record RegisterAgentRequest(
        @NotBlank String agentId,
        @NotBlank String displayName,
        @NotBlank String purpose,
        Set<String> capabilities,
        Set<String> allowedTools,
        Set<String> allowedSkills,
        @NotBlank String runtimeType,
        @NotBlank String model,
        @Min(1) int maxParallelRuns,
        boolean architectSuggested,
        boolean autoPoolEligible,
        boolean enabled
    ) {
    }
}

