package com.agentx.platform.controlplane.application;

import com.agentx.platform.controlplane.config.PlatformKernelProperties;
import com.agentx.platform.domain.workflow.WorkflowMutability;
import com.agentx.platform.domain.workflow.WorkflowNodeDefinition;
import com.agentx.platform.domain.workflow.WorkflowNodeKind;
import com.agentx.platform.domain.workflow.WorkflowTemplateDefinition;
import com.agentx.platform.runtime.application.WorkflowRuntimeDescriptor;
import com.agentx.platform.runtime.application.WorkflowRuntimePort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class WorkflowCatalogService {

    private final PlatformKernelProperties properties;
    private final WorkflowRuntimePort workflowRuntimePort;

    public WorkflowCatalogService(
        PlatformKernelProperties properties,
        WorkflowRuntimePort workflowRuntimePort
    ) {
        this.properties = properties;
        this.workflowRuntimePort = workflowRuntimePort;
    }

    public List<WorkflowTemplateDefinition> listBuiltIns() {
        return List.of(builtInCodingWorkflow());
    }

    public KernelPolicyView kernelPolicy() {
        WorkflowRuntimeDescriptor runtime = workflowRuntimePort.describeRuntime();
        return new KernelPolicyView(
            properties.getWorkflow().isUserDefinedEnabled(),
            properties.getWorkflow().isAgentNodeRebindingEnabled(),
            properties.getWorkflow().isParameterOverrideEnabled(),
            properties.getAgent().isArchitectSuggestionEnabled(),
            properties.getAgent().isArchitectAutoPoolEnabled(),
            runtime
        );
    }

    private WorkflowTemplateDefinition builtInCodingWorkflow() {
        return new WorkflowTemplateDefinition(
            "builtin-coding-flow",
            "内置代码交付流程",
            "固定结构的代码工作流，允许替换部分 Agent 节点，但不允许自由增删节点。",
            WorkflowMutability.FIXED_STRUCTURE_AGENT_TUNABLE,
            List.of(
                new WorkflowNodeDefinition("requirement", "需求代理", WorkflowNodeKind.AGENT, "requirement-agent", true),
                new WorkflowNodeDefinition("architect", "架构代理", WorkflowNodeKind.AGENT, "architect-agent", true),
                new WorkflowNodeDefinition("ticket-gate", "工单收件箱", WorkflowNodeKind.HUMAN_GATE, null, false),
                new WorkflowNodeDefinition("task-graph", "任务图", WorkflowNodeKind.SYSTEM, null, false),
                new WorkflowNodeDefinition("worker-manager", "工作代理管理器", WorkflowNodeKind.SYSTEM, null, false),
                new WorkflowNodeDefinition("coding", "编码代理", WorkflowNodeKind.AGENT, "coding-agent-java", true),
                new WorkflowNodeDefinition("merge-gate", "合并闸门", WorkflowNodeKind.SYSTEM, null, false),
                new WorkflowNodeDefinition("verify", "验证代理", WorkflowNodeKind.AGENT, "verify-agent-java", true)
            ),
            configurableAgentNodeIds(),
            configurableParameters()
        );
    }

    private Set<String> configurableAgentNodeIds() {
        if (!properties.getWorkflow().isAgentNodeRebindingEnabled()) {
            return Set.of();
        }
        return Set.of("requirement", "architect", "coding", "verify");
    }

    private Set<String> configurableParameters() {
        if (!properties.getWorkflow().isParameterOverrideEnabled()) {
            return Set.of();
        }
        return Set.of("autoAgentMode", "maxParallelCodingAgents", "verificationStrictness");
    }

    public record KernelPolicyView(
        boolean userDefinedWorkflowEnabled,
        boolean agentNodeRebindingEnabled,
        boolean parameterOverrideEnabled,
        boolean architectSuggestionEnabled,
        boolean architectAutoPoolEnabled,
        WorkflowRuntimeDescriptor runtime
    ) {
    }
}

