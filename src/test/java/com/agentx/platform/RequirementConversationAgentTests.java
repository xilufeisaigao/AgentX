package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationAgent;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationContext;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementDecisionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequirementConversationAgentTests {

    @Test
    void shouldDelegateRequirementContextToModelGateway() {
        CapturingGateway gateway = new CapturingGateway();
        RequirementConversationAgent agent = new RequirementConversationAgent(gateway, new ObjectMapper());
        AgentDefinition agentDefinition = new AgentDefinition(
                "requirement-agent",
                "Requirement Agent",
                "draft requirements",
                "SYSTEM",
                "in-process",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true
        );
        RequirementConversationContext context = new RequirementConversationContext(
                "workflow-1",
                "需求闭环",
                "用户登录",
                "支持邮箱加密码登录",
                Optional.empty(),
                List.of(),
                "SEED",
                "支持邮箱加密码登录"
        );

        StructuredModelResult<RequirementAgentDecision> result = agent.evaluate(agentDefinition, context);

        assertThat(result.value().decision()).isEqualTo(RequirementDecisionType.NEED_INPUT);
        assertThat(gateway.lastAgentDefinition.agentId()).isEqualTo("requirement-agent");
        assertThat(gateway.lastSystemPrompt).contains("你是 AgentX 的需求代理");
        assertThat(gateway.lastUserPrompt).contains("用户登录");
        assertThat(gateway.lastUserPrompt).contains("\"latestInteractionPhase\" : \"SEED\"");
    }

    @Test
    void shouldRejectNeedInputDecisionWithoutGapsOrQuestions() {
        assertThatThrownBy(() -> new RequirementAgentDecision(
                RequirementDecisionType.NEED_INPUT,
                List.of(),
                List.of(),
                null,
                null,
                "still missing"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("need-input");
    }

    @Test
    void shouldRejectDraftReadyDecisionWithoutContent() {
        assertThatThrownBy(() -> new RequirementAgentDecision(
                RequirementDecisionType.DRAFT_READY,
                List.of(),
                List.of(),
                "登录需求",
                " ",
                "ready"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draftContent");
    }

    private static final class CapturingGateway implements ModelGateway {

        private AgentDefinition lastAgentDefinition;
        private String lastSystemPrompt;
        private String lastUserPrompt;

        @Override
        public <T> StructuredModelResult<T> generateStructuredObject(
                AgentDefinition agentDefinition,
                String systemPrompt,
                String userPrompt,
                Class<T> responseType
        ) {
            this.lastAgentDefinition = agentDefinition;
            this.lastSystemPrompt = systemPrompt;
            this.lastUserPrompt = userPrompt;
            @SuppressWarnings("unchecked")
            T value = (T) new RequirementAgentDecision(
                    RequirementDecisionType.NEED_INPUT,
                    List.of("缺少验收标准"),
                    List.of("登录失败时应展示什么提示？"),
                    null,
                    null,
                    "need clarification"
            );
            return new StructuredModelResult<>(value, "stub", agentDefinition.model(), "{\"decision\":\"NEED_INPUT\"}");
        }
    }
}
