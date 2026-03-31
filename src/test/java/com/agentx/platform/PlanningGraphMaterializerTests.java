package com.agentx.platform;

import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentkernel.architect.PlanningGraphSpec;
import com.agentx.platform.runtime.application.workflow.PlanningGraphMaterializer;
import com.agentx.platform.runtime.application.workflow.TaskTemplateCatalog;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.support.TestStackProfiles;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanningGraphMaterializerTests {

    @Test
    void shouldNotResetInProgressTaskBackToReadyDuringRepeatedMaterialization() {
        PlanningStore planningStore = mock(PlanningStore.class);
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);
        when(scenarioResolver.resolveProfileId("workflow-1")).thenReturn(TestStackProfiles.DEFAULT_PROFILE_ID);
        PlanningGraphMaterializer materializer = new PlanningGraphMaterializer(
                planningStore,
                TestStackProfiles.taskTemplateCatalog(),
                scenarioResolver
        );

        String workflowRunId = "workflow-1";
        String existingTaskId = materializer.taskId(workflowRunId, "impl");
        WorkTask inProgressTask = new WorkTask(
                existingTaskId,
                materializer.moduleId(workflowRunId, "core"),
                "existing title",
                "existing objective",
                "java-backend-code",
                WorkTaskStatus.IN_PROGRESS,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
        when(planningStore.findTask(existingTaskId)).thenReturn(Optional.of(inProgressTask));
        when(planningStore.listTasksByWorkflow(workflowRunId)).thenReturn(List.of(inProgressTask));

        PlanningGraphSpec graphSpec = new PlanningGraphSpec(
                "single task plan",
                List.of(new PlanningGraphSpec.ModulePlan("core", "Core", "core module")),
                List.of(new PlanningGraphSpec.TaskPlan(
                        "impl",
                        "core",
                        "new title should not overwrite active task",
                        "new objective should not overwrite active task",
                        "java-backend-code",
                        List.of(),
                        "cap-java-backend-coding"
                )),
                List.of()
        );

        materializer.materialize(workflowRunId, graphSpec);

        verify(planningStore, never()).saveTask(org.mockito.ArgumentMatchers.argThat(task -> existingTaskId.equals(task.taskId())));
        ArgumentCaptor<com.agentx.platform.domain.planning.model.TaskCapabilityRequirement> capabilityCaptor =
                ArgumentCaptor.forClass(com.agentx.platform.domain.planning.model.TaskCapabilityRequirement.class);
        verify(planningStore).saveCapabilityRequirement(capabilityCaptor.capture());
        assertThat(capabilityCaptor.getValue().taskId()).isEqualTo(existingTaskId);
    }

    @Test
    void shouldRejectTaskWhenCapabilityPackDoesNotMatchTemplate() {
        PlanningStore planningStore = mock(PlanningStore.class);
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);
        when(scenarioResolver.resolveProfileId("workflow-1")).thenReturn(TestStackProfiles.DEFAULT_PROFILE_ID);
        PlanningGraphMaterializer materializer = new PlanningGraphMaterializer(
                planningStore,
                TestStackProfiles.taskTemplateCatalog(),
                scenarioResolver
        );

        PlanningGraphSpec graphSpec = new PlanningGraphSpec(
                "invalid capability pack",
                List.of(new PlanningGraphSpec.ModulePlan("core", "Core", "core module")),
                List.of(new PlanningGraphSpec.TaskPlan(
                        "impl",
                        "core",
                        "implement student service",
                        "deliver backend code",
                        "java-backend-code",
                        List.of(),
                        "cap-java-backend-test"
                )),
                List.of()
        );

        assertThatThrownBy(() -> materializer.materialize("workflow-1", graphSpec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match template");
    }

    @Test
    void shouldRejectTaskWhenWriteScopeExceedsTemplateBoundary() {
        PlanningStore planningStore = mock(PlanningStore.class);
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);
        when(scenarioResolver.resolveProfileId("workflow-1")).thenReturn(TestStackProfiles.DEFAULT_PROFILE_ID);
        PlanningGraphMaterializer materializer = new PlanningGraphMaterializer(
                planningStore,
                TestStackProfiles.taskTemplateCatalog(),
                scenarioResolver
        );

        PlanningGraphSpec graphSpec = new PlanningGraphSpec(
                "invalid write scope",
                List.of(new PlanningGraphSpec.ModulePlan("core", "Core", "core module")),
                List.of(new PlanningGraphSpec.TaskPlan(
                        "impl",
                        "core",
                        "implement student service",
                        "deliver backend code",
                        "java-backend-code",
                        List.of(new WriteScope("src/main/resources")),
                        "cap-java-backend-coding"
                )),
                List.of()
        );

        assertThatThrownBy(() -> materializer.materialize("workflow-1", graphSpec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds template boundary");
    }
}
