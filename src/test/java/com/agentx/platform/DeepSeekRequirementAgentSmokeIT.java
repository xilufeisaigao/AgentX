package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.agentkernel.model.AgentModelProperties;
import com.agentx.platform.runtime.agentkernel.model.DeepSeekOpenAiCompatibleGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationAgent;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationContext;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementDecisionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekRequirementAgentSmokeIT {

    @Test
    void shouldRunRequirementConversationAgainstRealDeepSeek() {
        String apiKey = System.getenv("AGENTX_DEEPSEEK_API_KEY");
        boolean smokeEnabled = Boolean.parseBoolean(System.getProperty("agentx.llm.smoke", "false"))
                || Boolean.parseBoolean(System.getenv("AGENTX_LLM_SMOKE"));
        Assumptions.assumeTrue(smokeEnabled);
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank());

        AgentModelProperties properties = new AgentModelProperties();
        properties.getDeepseek().setApiKey(apiKey);
        RequirementConversationAgent agent = new RequirementConversationAgent(
                new DeepSeekOpenAiCompatibleGateway(properties, new ObjectMapper()),
                new ObjectMapper()
        );
        AgentDefinition requirementAgent = new AgentDefinition(
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

        StructuredModelResult<RequirementAgentDecision> clarification = agent.evaluate(
                requirementAgent,
                new RequirementConversationContext(
                        "workflow-smoke-1",
                        "需求烟雾测试",
                        "做一个应用",
                        "想做个东西",
                        Optional.empty(),
                        List.of(),
                        "SEED",
                        "想做个东西"
                )
        );
        assertThat(clarification.value().decision()).isEqualTo(RequirementDecisionType.NEED_INPUT);
        assertThat(clarification.value().gaps().isEmpty() && clarification.value().questions().isEmpty()).isFalse();

        StructuredModelResult<RequirementAgentDecision> draft = agent.evaluate(
                requirementAgent,
                new RequirementConversationContext(
                        "workflow-smoke-2",
                        "登录需求",
                        "用户登录",
                        "支持邮箱密码登录、错误提示、会话保持和基础验收标准",
                        Optional.empty(),
                        List.of(
                                new RequirementConversationContext.RequirementTicketTurn(
                                        "ticket-1",
                                        "CLARIFICATION",
                                        "DISCOVERY",
                                        "需求补充",
                                        null,
                                        List.of("缺少验收标准"),
                                        List.of("登录失败时显示什么？"),
                                        "登录失败显示“账号或密码错误”，登录成功后保持会话 7 天"
                                )
                        ),
                        "DISCOVERY",
                        "登录失败显示“账号或密码错误”，登录成功后保持会话 7 天"
                )
        );
        assertStructuredDecision(draft.value());

        StructuredModelResult<RequirementAgentDecision> revised = agent.evaluate(
                requirementAgent,
                new RequirementConversationContext(
                        "workflow-smoke-3",
                        "登录需求",
                        "用户登录",
                        "支持邮箱密码登录",
                        Optional.of(new RequirementConversationContext.CurrentRequirementVersion(
                                2,
                                "用户登录",
                                "需要支持邮箱密码登录，并在失败时给出明确提示。",
                                "IN_REVIEW",
                                ActorType.HUMAN.name(),
                                "editor-1"
                        )),
                        List.of(
                                new RequirementConversationContext.RequirementTicketTurn(
                                        "ticket-2",
                                        "DECISION",
                                        "CONFIRMATION",
                                        "需求文档待确认",
                                        2,
                                        List.of(),
                                        List.of(),
                                        "请补充会话保持时长和登出要求"
                                )
                        ),
                        "CONFIRMATION",
                        "请补充会话保持时长和登出要求"
                )
        );
        assertStructuredDecision(revised.value());
    }

    private void assertStructuredDecision(RequirementAgentDecision decision) {
        assertThat(decision.decision()).isIn(RequirementDecisionType.NEED_INPUT, RequirementDecisionType.DRAFT_READY);
        if (decision.decision() == RequirementDecisionType.DRAFT_READY) {
            assertThat(decision.draftTitle()).isNotBlank();
            assertThat(decision.draftContent()).isNotBlank();
            return;
        }
        assertThat(decision.gaps().isEmpty() && decision.questions().isEmpty()).isFalse();
    }
}
