package com.agentx.platform;

import com.agentx.platform.controlplane.application.AgentDefinitionCommandResult;
import com.agentx.platform.controlplane.application.AgentDefinitionCommandService;
import com.agentx.platform.domain.catalog.model.AgentCapabilityBinding;
import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.model.CapabilityPack;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDefinitionCommandServiceTests {

    @Test
    void shouldCreateAgentDefinitionWithCapabilityBindings() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        AgentDefinitionCommandService service = new AgentDefinitionCommandService(catalogStore);
        when(catalogStore.findAgent("coding-agent-student")).thenReturn(Optional.empty());
        when(catalogStore.findCapabilityPack("cap-java-backend-coding")).thenReturn(Optional.of(capability("cap-java-backend-coding")));
        when(catalogStore.findCapabilityPack("cap-verify")).thenReturn(Optional.of(capability("cap-verify")));
        doNothing().when(catalogStore).saveAgent(any(AgentDefinition.class));
        doNothing().when(catalogStore).replaceAgentCapabilityBindings(any(), any());

        AgentDefinitionCommandResult result = service.createAgent(
                "coding-agent-student",
                "Student Coding Agent",
                "handle java backend tasks",
                "docker",
                "deepseek-chat",
                4,
                true,
                false,
                true,
                true,
                List.of("cap-java-backend-coding", "cap-verify")
        );

        ArgumentCaptor<AgentDefinition> agentCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(catalogStore).saveAgent(agentCaptor.capture());
        assertThat(agentCaptor.getValue().registrationSource()).isEqualTo("MANUAL");
        assertThat(agentCaptor.getValue().enabled()).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentCapabilityBinding>> bindingCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(catalogStore).replaceAgentCapabilityBindings(org.mockito.ArgumentMatchers.eq("coding-agent-student"), bindingCaptor.capture());
        assertThat(bindingCaptor.getValue()).extracting(AgentCapabilityBinding::capabilityPackId)
                .containsExactly("cap-java-backend-coding", "cap-verify");

        assertThat(result.agentId()).isEqualTo("coding-agent-student");
        assertThat(result.capabilityPackIds()).containsExactly("cap-java-backend-coding", "cap-verify");
    }

    @Test
    void shouldFailWhenCapabilityPackDoesNotExist() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        AgentDefinitionCommandService service = new AgentDefinitionCommandService(catalogStore);
        when(catalogStore.findAgent("coding-agent-student")).thenReturn(Optional.empty());
        when(catalogStore.findCapabilityPack("cap-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createAgent(
                "coding-agent-student",
                "Student Coding Agent",
                "handle java backend tasks",
                "docker",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true,
                List.of("cap-missing")
        )).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("capability pack not found");
    }

    @Test
    void shouldDisableExistingAgentDefinition() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        AgentDefinitionCommandService service = new AgentDefinitionCommandService(catalogStore);
        when(catalogStore.findAgent("coding-agent-student")).thenReturn(Optional.of(new AgentDefinition(
                "coding-agent-student",
                "Student Coding Agent",
                "handle java backend tasks",
                "MANUAL",
                "docker",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true
        )));
        when(catalogStore.listAgentCapabilityBindings("coding-agent-student")).thenReturn(List.of(
                new AgentCapabilityBinding("coding-agent-student", "cap-java-backend-coding", true)
        ));

        AgentDefinitionCommandResult result = service.setAgentEnabled("coding-agent-student", false);

        ArgumentCaptor<AgentDefinition> agentCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(catalogStore).saveAgent(agentCaptor.capture());
        assertThat(agentCaptor.getValue().enabled()).isFalse();
        assertThat(result.enabled()).isFalse();
    }

    @Test
    void shouldReplaceCapabilityBindingsForExistingAgent() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        AgentDefinitionCommandService service = new AgentDefinitionCommandService(catalogStore);
        when(catalogStore.findAgent("coding-agent-student")).thenReturn(Optional.of(new AgentDefinition(
                "coding-agent-student",
                "Student Coding Agent",
                "handle java backend tasks",
                "MANUAL",
                "docker",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true
        )));
        when(catalogStore.findCapabilityPack("cap-api-test")).thenReturn(Optional.of(capability("cap-api-test")));
        when(catalogStore.findCapabilityPack("cap-verify")).thenReturn(Optional.of(capability("cap-verify")));
        doNothing().when(catalogStore).replaceAgentCapabilityBindings(any(), any());

        AgentDefinitionCommandResult result = service.replaceCapabilityBindings(
                "coding-agent-student",
                List.of("cap-api-test", "cap-verify", "cap-api-test")
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentCapabilityBinding>> bindingCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(catalogStore).replaceAgentCapabilityBindings(org.mockito.ArgumentMatchers.eq("coding-agent-student"), bindingCaptor.capture());
        assertThat(bindingCaptor.getValue()).extracting(AgentCapabilityBinding::capabilityPackId)
                .containsExactly("cap-api-test", "cap-verify");
        assertThat(result.capabilityPackIds()).containsExactly("cap-api-test", "cap-verify");
    }

    private CapabilityPack capability(String capabilityPackId) {
        return new CapabilityPack(
                capabilityPackId,
                capabilityPackId,
                "coding",
                "task",
                "baseline capability",
                null,
                true
        );
    }
}
