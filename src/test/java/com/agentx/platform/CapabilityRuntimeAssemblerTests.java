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
import com.agentx.platform.runtime.application.workflow.CapabilityRuntimeAssembler;
import com.agentx.platform.runtime.application.workflow.CapabilityRuntimeAssembly;
import com.agentx.platform.runtime.application.workflow.CapabilityToolCatalogBuilder;
import com.agentx.platform.runtime.tooling.ToolRegistry;
import com.agentx.platform.support.TestStackProfiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRuntimeAssemblerTests {

    @Test
    void shouldAssembleRuntimePacksToolsAndBaselineEndpoints() {
        CapabilityRuntimeAssembler assembler = new CapabilityRuntimeAssembler(
                new AssemblerCatalogStore(),
                new CapabilityToolCatalogBuilder(new ToolRegistry())
        );

        CapabilityRuntimeAssembly assembly = assembler.assemble(
                TestStackProfiles.defaultProfile(),
                List.of(
                        new TaskCapabilityRequirement("task-1", "cap-java-backend-coding", true, "PRIMARY"),
                        new TaskCapabilityRequirement("task-1", "cap-api-test", false, "OPTIONAL")
                )
        );

        assertThat(assembly.image()).isEqualTo("maven:3.9.11-eclipse-temurin-21");
        assertThat(assembly.runtimePacks())
                .containsExactly("rt-java-21", "rt-maven-3", "rt-git", "rt-python-3_11", "rt-curl");
        assertThat(assembly.toolCatalog().entries())
                .extracting(entry -> entry.toolId())
                .containsExactlyInAnyOrder("tool-filesystem", "tool-git", "tool-shell", "tool-http-client");
        assertThat(assembly.allowedCommandCatalog()).containsKeys("git-commit-delivery", "maven-test", "python-version");
        assertThat(assembly.httpEndpointCatalog()).containsKey("local-http");
        assertThat(assembly.toolEnvironment())
                .containsEntry("AGENTX_CAPABILITY_PACKS", "cap-java-backend-coding,cap-api-test")
                .containsEntry("AGENTX_RUNTIME_PACKS", "rt-java-21,rt-maven-3,rt-git,rt-python-3_11,rt-curl");
    }

    private static final class AssemblerCatalogStore implements CatalogStore {

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
                case "cap-api-test" -> List.of(
                        new CapabilityRuntimeRequirement(capabilityPackId, "rt-python-3_11", false, "python"),
                        new CapabilityRuntimeRequirement(capabilityPackId, "rt-curl", true, "curl")
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
                case "cap-api-test" -> List.of(
                        new CapabilityToolGrant(capabilityPackId, "tool-http-client", true, "DIRECT"),
                        new CapabilityToolGrant(capabilityPackId, "tool-shell", true, "DIRECT")
                );
                default -> List.of();
            };
        }

        @Override
        public List<CapabilitySkillGrant> listCapabilitySkillGrants(String capabilityPackId) {
            return switch (capabilityPackId) {
                case "cap-java-backend-coding" -> List.of(new CapabilitySkillGrant(capabilityPackId, "skill-java-coding", true, "PRIMARY"));
                case "cap-api-test" -> List.of(new CapabilitySkillGrant(capabilityPackId, "skill-api-testing", true, "PRIMARY"));
                default -> List.of();
            };
        }

        @Override
        public List<SkillToolBinding> listSkillToolBindings(String skillId) {
            return switch (skillId) {
                case "skill-java-coding" -> List.of(
                        new SkillToolBinding(skillId, "tool-filesystem", true, "PRIMARY", 10),
                        new SkillToolBinding(skillId, "tool-git", true, "PRIMARY", 20),
                        new SkillToolBinding(skillId, "tool-shell", true, "PRIMARY", 30)
                );
                case "skill-api-testing" -> List.of(
                        new SkillToolBinding(skillId, "tool-http-client", true, "PRIMARY", 10),
                        new SkillToolBinding(skillId, "tool-shell", true, "PRIMARY", 20)
                );
                default -> List.of();
            };
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
