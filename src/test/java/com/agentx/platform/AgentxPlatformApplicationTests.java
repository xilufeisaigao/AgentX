package com.agentx.platform;

import com.agentx.platform.controlplane.application.AgentRegistryService;
import com.agentx.platform.controlplane.application.WorkflowCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AgentxPlatformApplicationTests {

	@Autowired
	private AgentRegistryService agentRegistryService;

	@Autowired
	private WorkflowCatalogService workflowCatalogService;

	@Test
	void contextLoads() {
	}

	@Test
	void shouldExposeSeededAgentsAndFixedWorkflowPolicy() {
		assertThat(agentRegistryService.listAgents()).isNotEmpty();
		assertThat(workflowCatalogService.listBuiltIns())
			.singleElement()
			.satisfies(workflow -> {
				assertThat(workflow.workflowId()).isEqualTo("builtin-coding-flow");
				assertThat(workflow.configurableAgentNodeIds()).contains("architect", "coding", "verify");
			});
	}

}
