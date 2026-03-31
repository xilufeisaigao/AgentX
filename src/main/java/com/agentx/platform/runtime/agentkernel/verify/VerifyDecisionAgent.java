package com.agentx.platform.runtime.agentkernel.verify;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.runtime.application.workflow.WorkflowProfileRef;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.context.CompiledContextPack;
import org.springframework.stereotype.Component;

@Component
public class VerifyDecisionAgent {

    private static final String PROMPT_VERSION = "verify-v1";

    private final ModelGateway modelGateway;
    private final StackProfileRegistry stackProfileRegistry;

    public VerifyDecisionAgent(ModelGateway modelGateway, StackProfileRegistry stackProfileRegistry) {
        this.modelGateway = modelGateway;
        this.stackProfileRegistry = stackProfileRegistry;
    }

    public StructuredModelResult<VerifyDecision> evaluate(AgentDefinition agentDefinition, CompiledContextPack contextPack) {
        return evaluate(agentDefinition, contextPack, stackProfileRegistry.defaultProfileRef());
    }

    public StructuredModelResult<VerifyDecision> evaluate(
            AgentDefinition agentDefinition,
            CompiledContextPack contextPack,
            WorkflowProfileRef workflowProfile
    ) {
        ActiveStackProfileSnapshot activeProfile = stackProfileRegistry.resolveRequired(workflowProfile.profileId());
        return modelGateway.generateStructuredObject(
                agentDefinition,
                systemPrompt(activeProfile),
                userPrompt(contextPack, activeProfile),
                VerifyDecision.class
        );
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    private String systemPrompt(ActiveStackProfileSnapshot activeProfile) {
        return """
                你是 AgentX 的验证代理。
                请只输出 PASS、REWORK 或 ESCALATE。

                规则：
                - deterministic verify 通过且交付满足要求时返回 PASS。
                - 在原任务返工范围内的问题返回 REWORK。
                - 超出原任务范围或需要架构介入时返回 ESCALATE。
                - 输出必须是结构化 JSON。

                当前技术栈 profile：
                - profileId=`%s`
                - displayName=`%s`
                - workspaceShape=`%s`

                该 profile 的验证提醒：
                %s
                """.formatted(
                activeProfile.profileId(),
                activeProfile.displayName(),
                activeProfile.workspaceShapeSummary(),
                rulesBlock(activeProfile.verifyRules())
        );
    }

    private String userPrompt(CompiledContextPack contextPack, ActiveStackProfileSnapshot activeProfile) {
        return """
                promptVersion=%s
                profileId=%s

                请基于下面的上下文 JSON 输出验证裁决：

                %s
                """.formatted(PROMPT_VERSION, activeProfile.profileId(), contextPack.contentJson());
    }

    private String rulesBlock(java.util.List<String> rules) {
        if (rules.isEmpty()) {
            return "- 无额外 profile 验证约束";
        }
        return rules.stream()
                .map(rule -> "- " + rule)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
    }
}
