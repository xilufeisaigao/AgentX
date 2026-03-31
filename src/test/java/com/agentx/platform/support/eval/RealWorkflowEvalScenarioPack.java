package com.agentx.platform.support.eval;

import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import com.agentx.platform.runtime.evaluation.EvalScenario;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RealWorkflowEvalScenarioPack(
        String scenarioId,
        String profileId,
        String workflowTitle,
        String requirementTitle,
        String initialPrompt,
        List<ScriptedHumanResponse> scriptedHumanResponses,
        boolean autoConfirmRequirementDoc,
        String repoFixtureId,
        Map<String, String> agentModelOverrides,
        WorkflowScenarioSpec workflowScenario,
        EvalExpectations expectations,
        StopPolicy stopPolicy
) {

    public RealWorkflowEvalScenarioPack {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(workflowTitle, "workflowTitle must not be null");
        Objects.requireNonNull(requirementTitle, "requirementTitle must not be null");
        Objects.requireNonNull(initialPrompt, "initialPrompt must not be null");
        Objects.requireNonNull(repoFixtureId, "repoFixtureId must not be null");
        scriptedHumanResponses = List.copyOf(scriptedHumanResponses == null ? List.of() : scriptedHumanResponses);
        agentModelOverrides = Map.copyOf(agentModelOverrides == null ? Map.of() : agentModelOverrides);
        workflowScenario = workflowScenario == null ? WorkflowScenarioSpec.defaultSpec() : workflowScenario;
        expectations = expectations == null ? EvalExpectations.empty() : expectations;
        stopPolicy = stopPolicy == null ? StopPolicy.defaultPolicy() : stopPolicy;
    }

    public WorkflowScenario toWorkflowScenario() {
        return new WorkflowScenario(
                workflowScenario.requireHumanClarification(),
                workflowScenario.architectCanAutoResolveClarification(),
                workflowScenario.verifyNeedsRework()
        );
    }

    public EvalScenario toEvalScenario() {
        return new EvalScenario(
                scenarioId,
                workflowTitle,
                initialPrompt,
                expectations.expectedBehavior(),
                expectations.expectedFacts(),
                expectations.expectedSnippetRefs(),
                List.of(),
                expectations.expectedNodeOrder(),
                expectations.repoContextRequired()
        );
    }

    public int scriptedHumanResponseCount() {
        return scriptedHumanResponses.size();
    }

    public record WorkflowScenarioSpec(
            boolean requireHumanClarification,
            boolean architectCanAutoResolveClarification,
            boolean verifyNeedsRework
    ) {

        public static WorkflowScenarioSpec defaultSpec() {
            return new WorkflowScenarioSpec(false, false, false);
        }
    }

    public record EvalExpectations(
            String expectedBehavior,
            List<String> expectedFacts,
            List<String> expectedSnippetRefs,
            List<String> expectedNodeOrder,
            boolean repoContextRequired
    ) {

        public EvalExpectations {
            expectedBehavior = expectedBehavior == null ? "" : expectedBehavior;
            expectedFacts = List.copyOf(expectedFacts == null ? List.of() : expectedFacts);
            expectedSnippetRefs = List.copyOf(expectedSnippetRefs == null ? List.of() : expectedSnippetRefs);
            expectedNodeOrder = List.copyOf(expectedNodeOrder == null ? List.of() : expectedNodeOrder);
        }

        public static EvalExpectations empty() {
            return new EvalExpectations("", List.of(), List.of(), List.of(), false);
        }
    }

    public record ScriptedHumanResponse(
            String answer,
            String originNodeId,
            String ticketType
    ) {

        public ScriptedHumanResponse {
            Objects.requireNonNull(answer, "answer must not be null");
            answer = answer.trim();
            if (answer.isEmpty()) {
                throw new IllegalArgumentException("answer must not be blank");
            }
            originNodeId = originNodeId == null ? "" : originNodeId.trim();
            ticketType = ticketType == null ? "" : ticketType.trim();
        }
    }

    public record StopPolicy(
            int maxHumanInteractions,
            boolean terminateActiveRunsOnAbort,
            boolean disallowSyntheticAdvance,
            boolean terminateOnProviderFailure,
            boolean terminateOnSchemaFailure
    ) {

        public StopPolicy {
            maxHumanInteractions = maxHumanInteractions <= 0 ? 2 : maxHumanInteractions;
        }

        public static StopPolicy defaultPolicy() {
            return new StopPolicy(2, true, true, true, true);
        }
    }
}
