package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectConversationAgent;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecision;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecisionType;
import com.agentx.platform.runtime.agentkernel.model.ModelGateway;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalBundle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectConversationAgentTests {

    @Test
    void shouldEmbedSupportedTemplateCatalogIntoPrompt() {
        CapturingGateway gateway = new CapturingGateway();
        ArchitectConversationAgent agent = new ArchitectConversationAgent(gateway, new TaskTemplateCatalog());

        StructuredModelResult<ArchitectDecision> result = agent.evaluate(agentDefinition(), compiledPack());

        assertThat(result.value().decision()).isEqualTo(ArchitectDecisionType.NO_CHANGES);
        assertThat(gateway.lastSystemPrompt).contains("java-backend-code");
        assertThat(gateway.lastSystemPrompt).contains("java-backend-test");
        assertThat(gateway.lastSystemPrompt).contains("cap-java-backend-coding");
        assertThat(gateway.lastSystemPrompt).contains("src/main/java");
        assertThat(gateway.lastUserPrompt).contains("\"workflowRunId\":\"workflow-architect-test\"");
    }

    private AgentDefinition agentDefinition() {
        return new AgentDefinition(
                "architect-agent",
                "Architect Agent",
                "plan workflow DAG",
                "SYSTEM",
                "in-process",
                "stub-model",
                1,
                true,
                false,
                true,
                true
        );
    }

    private CompiledContextPack compiledPack() {
        return new CompiledContextPack(
                ContextPackType.ARCHITECT,
                ContextScope.workflow("workflow-architect-test", "architect"),
                "architect-fingerprint",
                "eval://architect",
                "{\"workflowRunId\":\"workflow-architect-test\"}",
                new FactBundle(Map.of("workflowRunId", "workflow-architect-test")),
                new RetrievalBundle(List.of()),
                LocalDateTime.now()
        );
    }

    private static final class CapturingGateway implements ModelGateway {

        private String lastSystemPrompt;
        private String lastUserPrompt;

        @Override
        public <T> StructuredModelResult<T> generateStructuredObject(
                AgentDefinition agentDefinition,
                String systemPrompt,
                String userPrompt,
                Class<T> responseType
        ) {
            this.lastSystemPrompt = systemPrompt;
            this.lastUserPrompt = userPrompt;
            @SuppressWarnings("unchecked")
            T value = (T) new ArchitectDecision(
                    ArchitectDecisionType.NO_CHANGES,
                    List.of(),
                    List.of(),
                    "no changes",
                    null
            );
            return new StructuredModelResult<>(value, "stub", agentDefinition.model(), "{\"decision\":\"NO_CHANGES\"}");
        }
    }
}
