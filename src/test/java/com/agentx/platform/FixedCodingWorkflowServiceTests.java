package com.agentx.platform;

import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowMutability;
import com.agentx.platform.domain.flow.model.WorkflowNodeKind;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowTemplate;
import com.agentx.platform.domain.flow.model.WorkflowTemplateNode;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowService;
import com.agentx.platform.runtime.application.workflow.RequirementStageService;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import com.agentx.platform.runtime.application.workflow.WorkflowDriverService;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedCodingWorkflowServiceTests {

    @Test
    void shouldStartWorkflowWithoutPersistingRequirementDoc() throws Exception {
        FlowStore flowStore = mock(FlowStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        WorkflowDriverService workflowDriverService = mock(WorkflowDriverService.class);
        WorkflowScenarioResolver workflowScenarioResolver = mock(WorkflowScenarioResolver.class);
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        ObjectMapper objectMapper = new ObjectMapper();

        when(flowStore.findTemplate("builtin-coding-flow")).thenReturn(Optional.of(template()));

        FixedCodingWorkflowService service = new FixedCodingWorkflowService(
                flowStore,
                intakeStore,
                planningStore,
                executionStore,
                requirementStageService,
                workflowDriverService,
                workflowScenarioResolver,
                TestStackProfiles.registry(),
                properties,
                objectMapper
        );

        String workflowRunId = service.start(new StartCodingWorkflowCommand(
                "登录需求",
                "用户登录",
                "支持邮箱密码登录",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                new ActorRef(ActorType.HUMAN, "user-1"),
                false,
                new WorkflowScenario(true, false, false)
        ));

        assertThat(workflowRunId).startsWith("workflow-");
        verify(intakeStore, never()).saveRequirement(any());
        verify(intakeStore, never()).appendRequirementVersion(any());

        ArgumentCaptor<WorkflowRunEvent> eventCaptor = ArgumentCaptor.forClass(WorkflowRunEvent.class);
        verify(flowStore).appendRunEvent(eventCaptor.capture());
        Map<String, Object> payload = objectMapper.readValue(
                eventCaptor.getValue().dataJson().json(),
                new TypeReference<>() {
                }
        );
        assertThat(payload).containsEntry("requirementSeedTitle", "用户登录");
        assertThat(payload).containsEntry("requirementSeedContent", "支持邮箱密码登录");
        assertThat(payload).containsEntry("requireHumanClarification", true);
    }

    @Test
    void shouldReturnSnapshotWithoutRequirementDocBeforeDiscoveryCompletes() {
        FlowStore flowStore = mock(FlowStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        WorkflowDriverService workflowDriverService = mock(WorkflowDriverService.class);
        WorkflowScenarioResolver workflowScenarioResolver = mock(WorkflowScenarioResolver.class);
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();

        FixedCodingWorkflowService service = new FixedCodingWorkflowService(
                flowStore,
                intakeStore,
                planningStore,
                executionStore,
                requirementStageService,
                workflowDriverService,
                workflowScenarioResolver,
                TestStackProfiles.registry(),
                properties,
                new ObjectMapper()
        );
        WorkflowRun workflowRun = new WorkflowRun(
                "workflow-1",
                "builtin-coding-flow",
                "登录需求",
                WorkflowRunStatus.WAITING_ON_HUMAN,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "user-1")
        );
        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(workflowRun));
        when(intakeStore.findRequirementByWorkflow("workflow-1")).thenReturn(Optional.empty());
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of());
        when(planningStore.listTasksByWorkflow("workflow-1")).thenReturn(List.of());
        when(flowStore.listNodeRuns("workflow-1")).thenReturn(List.of());
        when(workflowScenarioResolver.resolveProfileRef("workflow-1")).thenReturn(Optional.of(TestStackProfiles.defaultProfileRef()));

        WorkflowRuntimeSnapshot snapshot = service.getRuntimeSnapshot("workflow-1");

        assertThat(snapshot.requirementDoc()).isEmpty();
        assertThat(snapshot.requirementVersions()).isEmpty();
    }

    @Test
    void shouldTreatOpenAgentBlockerWithoutActiveRunsAsStableBoundary() {
        FlowStore flowStore = mock(FlowStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        WorkflowDriverService workflowDriverService = mock(WorkflowDriverService.class);
        WorkflowScenarioResolver workflowScenarioResolver = mock(WorkflowScenarioResolver.class);
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();

        FixedCodingWorkflowService service = new FixedCodingWorkflowService(
                flowStore,
                intakeStore,
                planningStore,
                executionStore,
                requirementStageService,
                workflowDriverService,
                workflowScenarioResolver,
                TestStackProfiles.registry(),
                properties,
                new ObjectMapper()
        );
        WorkflowRun workflowRun = new WorkflowRun(
                "workflow-1",
                "builtin-coding-flow",
                "登录需求",
                WorkflowRunStatus.EXECUTING_TASKS,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "user-1")
        );
        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(workflowRun));
        when(intakeStore.findRequirementByWorkflow("workflow-1")).thenReturn(Optional.empty());
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of(new Ticket(
                "ticket-runtime-1",
                "workflow-1",
                TicketType.ALERT,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "运行失败需要架构代理处理",
                new ActorRef(ActorType.SYSTEM, "runtime"),
                new ActorRef(ActorType.AGENT, "architect-agent"),
                "coding",
                null,
                null,
                "task-1",
                JsonPayload.emptyObject()
        )));
        when(planningStore.listTasksByWorkflow("workflow-1")).thenReturn(List.of());
        when(flowStore.listNodeRuns("workflow-1")).thenReturn(List.of());
        when(workflowScenarioResolver.resolveProfileRef("workflow-1")).thenReturn(Optional.of(TestStackProfiles.defaultProfileRef()));

        WorkflowRuntimeSnapshot snapshot = service.runUntilStable("workflow-1");

        assertThat(snapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.EXECUTING_TASKS);
        verify(workflowDriverService, never()).driveWorkflowOnce("workflow-1");
    }

    @Test
    void shouldKeepWaitingWhileWorkflowKeepsMakingProgress() throws Exception {
        FlowStore flowStore = mock(FlowStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        WorkflowDriverService workflowDriverService = mock(WorkflowDriverService.class);
        WorkflowScenarioResolver workflowScenarioResolver = mock(WorkflowScenarioResolver.class);
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setBlockingTimeout(Duration.ofMillis(30));
        properties.setBlockingPollInterval(Duration.ofMillis(1));
        properties.setDriverEnabled(false);

        FixedCodingWorkflowService service = new FixedCodingWorkflowService(
                flowStore,
                intakeStore,
                planningStore,
                executionStore,
                requirementStageService,
                workflowDriverService,
                workflowScenarioResolver,
                TestStackProfiles.registry(),
                properties,
                new ObjectMapper()
        );
        AtomicInteger phase = new AtomicInteger(0);

        when(flowStore.findRun("workflow-1")).thenAnswer(invocation -> Optional.of(new WorkflowRun(
                "workflow-1",
                "builtin-coding-flow",
                "登录需求",
                phase.get() >= 2 ? WorkflowRunStatus.COMPLETED : WorkflowRunStatus.EXECUTING_TASKS,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "user-1")
        )));
        when(intakeStore.findRequirementByWorkflow("workflow-1")).thenReturn(Optional.empty());
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of());
        when(planningStore.listTasksByWorkflow("workflow-1")).thenAnswer(invocation -> List.of(new WorkTask(
                "task-1",
                "module-api",
                "实现 healthz",
                "生成交付候选",
                "java-backend-task",
                switch (phase.get()) {
                    case 0 -> WorkTaskStatus.READY;
                    case 1 -> WorkTaskStatus.IN_PROGRESS;
                    default -> WorkTaskStatus.DONE;
                },
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        )));
        when(flowStore.listNodeRuns("workflow-1")).thenAnswer(invocation -> {
            if (phase.get() <= 0) {
                return List.of();
            }
            return java.util.stream.IntStream.rangeClosed(1, phase.get())
                    .mapToObj(index -> new com.agentx.platform.domain.flow.model.WorkflowNodeRun(
                            "node-run-" + index,
                            "workflow-1",
                            "coding",
                            "coding-agent-java",
                            null,
                            com.agentx.platform.domain.flow.model.WorkflowNodeRunStatus.SUCCEEDED,
                            JsonPayload.emptyObject(),
                            JsonPayload.emptyObject(),
                            java.time.LocalDateTime.now().minusSeconds(phase.get() - index + 1L),
                            java.time.LocalDateTime.now().minusSeconds(phase.get() - index)
                    ))
                    .toList();
        });
        when(executionStore.listSnapshots("task-1", com.agentx.platform.domain.execution.model.RunKind.IMPL)).thenReturn(List.of());
        when(executionStore.listTaskRuns("task-1")).thenReturn(List.of());
        when(executionStore.listWorkspaces("task-1")).thenReturn(List.of());
        when(workflowScenarioResolver.resolveProfileRef("workflow-1")).thenReturn(Optional.of(TestStackProfiles.defaultProfileRef()));
        doAnswer(invocation -> {
            Thread.sleep(40);
            phase.incrementAndGet();
            return null;
        }).when(workflowDriverService).driveWorkflowOnce("workflow-1");

        WorkflowRuntimeSnapshot snapshot = service.runUntilStable("workflow-1");

        assertThat(snapshot.workflowRun().status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        verify(workflowDriverService, times(2)).driveWorkflowOnce("workflow-1");
    }

    private WorkflowTemplate template() {
        return new WorkflowTemplate(
                "builtin-coding-flow",
                "内置代码交付流程",
                "fixed workflow",
                WorkflowMutability.FIXED_STRUCTURE_AGENT_TUNABLE,
                "SYSTEM_ONLY",
                true,
                true,
                "v1",
                List.of(
                        new WorkflowTemplateNode(
                                "builtin-coding-flow",
                                "requirement",
                                "需求代理",
                                WorkflowNodeKind.AGENT,
                                10,
                                "requirement-agent",
                                true
                        ),
                        new WorkflowTemplateNode(
                                "builtin-coding-flow",
                                "architect",
                                "架构代理",
                                WorkflowNodeKind.AGENT,
                                20,
                                "architect-agent",
                                true
                        )
                )
        );
    }

    private <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
