package com.agentx.platform.runtime.agentkernel.requirement;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class RequirementConversationAgent {

    private final ModelGateway modelGateway;
    private final ObjectMapper objectMapper;

    public RequirementConversationAgent(
            ModelGateway modelGateway,
            ObjectMapper objectMapper
    ) {
        this.modelGateway = modelGateway;
        this.objectMapper = objectMapper;
    }

    public StructuredModelResult<RequirementAgentDecision> evaluate(
            AgentDefinition agentDefinition,
            RequirementConversationContext context
    ) {
        return modelGateway.generateStructuredObject(
                agentDefinition,
                systemPrompt(),
                userPrompt(context),
                RequirementAgentDecision.class
        );
    }

    private String systemPrompt() {
        return """
                你是 AgentX 的需求代理。
                你的职责只有两个：
                1. 判断当前需求信息是否足够形成需求文档；
                2. 当信息足够时，输出完整的候选需求文档。

                严格遵守这些规则：
                - 如果信息仍有关键缺口，必须返回 NEED_INPUT。
                - 在 NEED_INPUT 时，不要编造需求文档内容。
                - 在 DRAFT_READY 时，必须输出完整 draftTitle 和 draftContent。
                - 不要做 DAG 设计、任务拆分、技术实现建议、RAG 查询。
                - 只围绕用户意图、边界、验收和约束来整理需求。
                - 输出必须是合法 JSON，并满足 schema。
                """;
    }

    private String userPrompt(RequirementConversationContext context) {
        try {
            return """
                    请基于下面的上下文做判断：

                    %s

                    判断标准：
                    - 如果初始种子输入、已有文档、修改意见和补充答复足以形成当前轮需求文档，返回 DRAFT_READY。
                    - 如果还有关键缺口会导致需求文档不完整或无法确认，返回 NEED_INPUT。
                    - questions 请写成人类可以直接回答的问题。
                    - gaps 请写成简洁的缺失点列表。
                    - summary 请简要说明这次判断的原因。
                    """.formatted(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(contextPayload(context)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize requirement conversation context", exception);
        }
    }

    private java.util.Map<String, Object> contextPayload(RequirementConversationContext context) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("workflowRunId", context.workflowRunId());
        payload.put("workflowTitle", context.workflowTitle());
        payload.put("initialRequirementTitle", context.initialRequirementTitle());
        payload.put("initialRequirementContent", context.initialRequirementContent());
        payload.put("latestRequirementVersion", context.latestRequirementVersion().orElse(null));
        payload.put("answeredTicketHistory", context.answeredTicketHistory());
        payload.put("latestInteractionPhase", context.latestInteractionPhase());
        payload.put("latestHumanInput", context.latestHumanInput());
        return payload;
    }
}
