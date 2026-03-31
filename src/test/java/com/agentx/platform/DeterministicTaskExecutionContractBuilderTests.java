package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentCapabilityBinding;
import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.model.CapabilityPack;
import com.agentx.platform.domain.catalog.model.CapabilityRuntimeRequirement;
import com.agentx.platform.domain.catalog.model.CapabilitySkillGrant;
import com.agentx.platform.domain.catalog.model.CapabilityToolGrant;
import com.agentx.platform.domain.catalog.model.SkillToolBinding;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.CapabilityRuntimeAssembler;
import com.agentx.platform.runtime.application.workflow.CapabilityToolCatalogBuilder;
import com.agentx.platform.runtime.application.workflow.DeterministicTaskExecutionContractBuilder;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContract;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.runtime.tooling.ToolRegistry;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicTaskExecutionContractBuilderTests {

    @Test
    void shouldBuildCapabilityAssembledToolContract() {
        CapabilityRuntimeAssembler assembler = new CapabilityRuntimeAssembler(
                new ContractCatalogStore(),
                new CapabilityToolCatalogBuilder(new ToolRegistry())
        );
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);
        when(scenarioResolver.resolveProfileId("workflow-1")).thenReturn(TestStackProfiles.DEFAULT_PROFILE_ID);
        DeterministicTaskExecutionContractBuilder builder = new DeterministicTaskExecutionContractBuilder(
                scenarioResolver,
                assembler,
                TestStackProfiles.taskTemplateCatalog(),
                new ObjectMapper()
        );
        WorkTask task = new WorkTask(
                "task-1",
                "module-1",
                "Implement runtime marker",
                "Create deterministic task output",
                "java-backend-task",
                WorkTaskStatus.READY,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );

        TaskExecutionContract contract = builder.build(
                "workflow-1",
                task,
                List.of(new TaskCapabilityRequirement(task.taskId(), "cap-java-backend-coding", true, "PRIMARY")),
                1,
                WorkflowScenario.defaultScenario()
        );

        assertThat(contract.image()).isEqualTo("maven:3.9.11-eclipse-temurin-21");
        assertThat(contract.command()).containsExactly(
                "sh",
                "-lc",
                "trap exit TERM INT; while true; do sleep 1; done"
        );
        assertThat(contract.toolCatalog().entries())
                .extracting(entry -> entry.toolId())
                .containsExactlyInAnyOrder("tool-filesystem", "tool-git", "tool-shell");
        assertThat(contract.runtimePacks())
                .containsExactly("rt-java-21", "rt-maven-3", "rt-git");
        assertThat(contract.allowedCommandCatalog()).containsKeys("show-marker", "require-marker", "maven-test", "git-commit-delivery");
        assertThat(contract.postDeliveryToolCalls()).singleElement().satisfies(toolCall -> {
            assertThat(toolCall.toolId()).isEqualTo("tool-shell");
            assertThat(toolCall.operation()).isEqualTo("run_command");
            assertThat(toolCall.arguments()).containsEntry("commandId", "git-commit-delivery");
        });
        assertThat(contract.verifyToolCalls()).hasSize(3);
        assertThat(contract.verifyToolCalls().get(2).toolId()).isEqualTo("tool-git");
        assertThat(contract.verifyToolCalls().get(2).operation()).isEqualTo("git_head");
        assertThat(contract.markerFile()).isEqualTo("src/main/java/.agentx-task-1.txt");
        assertThat(contract.toolEnvironment())
                .containsEntry("WORKFLOW_RUN_ID", "workflow-1")
                .containsEntry("TASK_ID", "task-1")
                .containsEntry("MARKER_FILE", "src/main/java/.agentx-task-1.txt");
    }

    private static final class ContractCatalogStore implements CatalogStore {

        @Override
        public Optional<AgentDefinition> findAgent(String agentId) {
            return Optional.empty();
        }

        @Override
        public Optional<CapabilityPack> findCapabilityPack(String capabilityPackId) {
            return Optional.empty();
        }

        @Override
        public List<AgentDefinition> listAgentsByCapability(String capabilityPackId) {
            return List.of();
        }

        @Override
        public List<AgentCapabilityBinding> listAgentCapabilityBindings(String agentId) {
            return List.of();
        }

        @Override
        public List<CapabilityRuntimeRequirement> listCapabilityRuntimeRequirements(String capabilityPackId) {
            return switch (capabilityPackId) {
                case "cap-java-backend-coding" -> List.of(
                        new CapabilityRuntimeRequirement(capabilityPackId, "rt-java-21", true, "java"),
                        new CapabilityRuntimeRequirement(capabilityPackId, "rt-maven-3", true, "maven"),
                        new CapabilityRuntimeRequirement(capabilityPackId, "rt-git", true, "git")
                );
                default -> List.of();
            };
        }

        @Override
        public List<CapabilityToolGrant> listCapabilityToolGrants(String capabilityPackId) {
            return switch (capabilityPackId) {
                case "cap-java-backend-coding" -> List.of(
                        new CapabilityToolGrant(capabilityPackId, "tool-filesystem", true, "DIRECT"),
                        new CapabilityToolGrant(capabilityPackId, "tool-git", true, "DIRECT"),
                        new CapabilityToolGrant(capabilityPackId, "tool-shell", true, "DIRECT")
                );
                default -> List.of();
            };
        }

        @Override
        public List<CapabilitySkillGrant> listCapabilitySkillGrants(String capabilityPackId) {
            return List.of(new CapabilitySkillGrant(capabilityPackId, "skill-java-coding", true, "PRIMARY"));
        }

        @Override
        public List<SkillToolBinding> listSkillToolBindings(String skillId) {
            return List.of(
                    new SkillToolBinding(skillId, "tool-filesystem", true, "PRIMARY", 10),
                    new SkillToolBinding(skillId, "tool-git", true, "PRIMARY", 20),
                    new SkillToolBinding(skillId, "tool-shell", true, "PRIMARY", 30)
            );
        }

        @Override
        public void saveAgent(AgentDefinition agentDefinition) {
            throw new UnsupportedOperationException("not needed in this test");
        }

        @Override
        public void replaceAgentCapabilityBindings(String agentId, List<AgentCapabilityBinding> bindings) {
            throw new UnsupportedOperationException("not needed in this test");
        }
    }
}
