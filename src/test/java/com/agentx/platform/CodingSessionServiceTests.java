package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentkernel.coding.CodingAgentDecision;
import com.agentx.platform.runtime.agentkernel.coding.CodingConversationAgent;
import com.agentx.platform.runtime.agentkernel.coding.CodingDecisionType;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.application.workflow.CodingSessionService;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContract;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContractBuilder;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.context.ContextCompilationProperties;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalBundle;
import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.runtime.tooling.ToolExecutor;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingSessionServiceTests {

    @Test
    void shouldReusePriorToolCallEvidenceWithoutExecutingToolTwice() throws Exception {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextProperties = new ContextCompilationProperties();
        CodingConversationAgent codingConversationAgent = mock(CodingConversationAgent.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        TaskExecutionContractBuilder taskExecutionContractBuilder = mock(TaskExecutionContractBuilder.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        WorkflowScenarioResolver workflowScenarioResolver = mock(WorkflowScenarioResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        CodingSessionService service = new CodingSessionService(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                contextCompilationCenter,
                contextProperties,
                codingConversationAgent,
                toolExecutor,
                taskExecutionContractBuilder,
                agentRuntime,
                workspaceProvisioner,
                workflowScenarioResolver,
                TestStackProfiles.registry(),
                objectMapper
        );

        WorkTask task = new WorkTask(
                "task-1",
                "module-1",
                "实现学生功能",
                "交付学生管理最小骨架",
                "java-backend-code",
                WorkTaskStatus.IN_PROGRESS,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
        AgentPoolInstance agentInstance = new AgentPoolInstance(
                "agent-instance-1",
                "coding-agent-java",
                "docker",
                AgentPoolStatus.READY,
                "TASK_RUN_CONTAINER",
                "workflow-1",
                LocalDateTime.now().plusSeconds(20),
                LocalDateTime.now(),
                "docker://agent-instance-1",
                JsonPayload.emptyObject()
        );
        TaskRun run = new TaskRun(
                "run-1",
                task.taskId(),
                agentInstance.agentInstanceId(),
                TaskRunStatus.RUNNING,
                RunKind.IMPL,
                "snapshot-1",
                LocalDateTime.now().plusSeconds(20),
                LocalDateTime.now(),
                LocalDateTime.now(),
                null,
                new JsonPayload("{\"contract\":true}")
        );
        GitWorkspace workspace = new GitWorkspace(
                "workspace-1",
                run.runId(),
                task.taskId(),
                GitWorkspaceStatus.READY,
                "D:/repo",
                "D:/repo/worktree-1",
                "task/task-1/001",
                "base-commit",
                null,
                null,
                CleanupStatus.PENDING
        );
        TaskExecutionContract contract = new TaskExecutionContract(
                "maven:3.9.11-eclipse-temurin-21",
                "/workspace",
                List.of("sh", "-lc", "sleep 1"),
                Map.of("TASK_ID", task.taskId()),
                20,
                new CompiledToolCatalog(List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "list_directory", "search_text", "write_file", "delete_file"), "schema://tool-filesystem", "")
                )),
                List.of("rt-java-21"),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of("src/main/java"),
                "src/main/java/.agentx-task-1.txt"
        );
        ToolCall toolCall = new ToolCall(
                "call-fixed-1",
                "tool-filesystem",
                "write_file",
                Map.of("path", "src/main/java/App.java", "content", "class App {}"),
                "write student source"
        );

        when(executionStore.listActiveTaskRuns()).thenReturn(List.of(run));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(executionStore.findAgentInstance(agentInstance.agentInstanceId())).thenReturn(Optional.of(agentInstance));
        when(executionStore.findWorkspaceByRun(run.runId())).thenReturn(Optional.of(workspace));
        when(executionStore.listTaskRunEvents(run.runId())).thenReturn(List.of(priorToolCallEvent(run.runId(), objectMapper)));
        when(taskExecutionContractBuilder.fromPayload(run.executionContractJson())).thenReturn(contract);
        when(workflowScenarioResolver.resolveProfileRef("workflow-1")).thenReturn(Optional.of(TestStackProfiles.defaultProfileRef()));
        when(contextCompilationCenter.compile(any())).thenReturn(new CompiledContextPack(
                ContextPackType.CODING,
                ContextScope.task("workflow-1", task.taskId(), run.runId(), "coding", null),
                "fingerprint",
                "artifact-ref",
                "{\"packType\":\"CODING\"}",
                new FactBundle(Map.of("task", task)),
                new RetrievalBundle(List.of()),
                LocalDateTime.now()
        ));
        when(catalogStore.findAgent(agentInstance.agentId())).thenReturn(Optional.of(new AgentDefinition(
                "coding-agent-java",
                "Coding Agent",
                "coding",
                "SYSTEM",
                "docker",
                "deepseek-chat",
                4,
                false,
                true,
                true,
                true
        )));
        when(codingConversationAgent.evaluate(
                any(),
                any(),
                org.mockito.ArgumentMatchers.contains("tool-filesystem.write_file"),
                eq(TestStackProfiles.defaultProfileRef())
        ))
                .thenReturn(new StructuredModelResult<>(
                        new CodingAgentDecision(CodingDecisionType.TOOL_CALL, toolCall, null, null, "write student source"),
                        "stub",
                        "deepseek-chat",
                        "{\"decisionType\":\"TOOL_CALL\"}"
                ));
        when(toolExecutor.normalizeForRun(run.runId(), toolCall)).thenReturn(toolCall);

        service.advanceActiveRuns();

        verify(toolExecutor, never()).executeForRun(any(), any(), any(), any(), any(), any());
        ArgumentCaptor<TaskRunEvent> eventCaptor = ArgumentCaptor.forClass(TaskRunEvent.class);
        verify(executionStore).appendTaskRunEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("CODING_TURN_REUSED");
        assertThat(eventCaptor.getValue().dataJson().json()).contains("\"toolCallReused\":true");
    }

    @Test
    void shouldCleanupWorkspaceWhenRunFailsByTurnBudget() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextProperties = new ContextCompilationProperties();
        CodingConversationAgent codingConversationAgent = mock(CodingConversationAgent.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        TaskExecutionContractBuilder taskExecutionContractBuilder = mock(TaskExecutionContractBuilder.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        WorkflowScenarioResolver workflowScenarioResolver = mock(WorkflowScenarioResolver.class);
        ObjectMapper objectMapper = new ObjectMapper();

        CodingSessionService service = new CodingSessionService(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                contextCompilationCenter,
                contextProperties,
                codingConversationAgent,
                toolExecutor,
                taskExecutionContractBuilder,
                agentRuntime,
                workspaceProvisioner,
                workflowScenarioResolver,
                TestStackProfiles.registry(),
                objectMapper
        );

        WorkTask task = new WorkTask(
                "task-budget",
                "module-budget",
                "实现学生功能",
                "交付学生管理最小骨架",
                "java-backend-code",
                WorkTaskStatus.IN_PROGRESS,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
        AgentPoolInstance agentInstance = new AgentPoolInstance(
                "agent-instance-budget",
                "coding-agent-java",
                "docker",
                AgentPoolStatus.READY,
                "TASK_RUN_CONTAINER",
                "workflow-budget",
                LocalDateTime.now().plusSeconds(20),
                LocalDateTime.now(),
                "docker://agent-instance-budget",
                JsonPayload.emptyObject()
        );
        TaskRun run = new TaskRun(
                "run-budget",
                task.taskId(),
                agentInstance.agentInstanceId(),
                TaskRunStatus.RUNNING,
                RunKind.IMPL,
                "snapshot-budget",
                LocalDateTime.now().plusSeconds(20),
                LocalDateTime.now(),
                LocalDateTime.now().minusSeconds(30),
                null,
                new JsonPayload("{\"contract\":true}")
        );
        GitWorkspace workspace = new GitWorkspace(
                "workspace-budget",
                run.runId(),
                task.taskId(),
                GitWorkspaceStatus.READY,
                "D:/repo",
                "D:/repo/worktree-budget",
                "task/task-budget/run-budget",
                "base-commit",
                null,
                null,
                CleanupStatus.PENDING
        );
        GitWorkspace cleanedWorkspace = new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                GitWorkspaceStatus.CLEANED,
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                workspace.headCommit(),
                workspace.mergeCommit(),
                CleanupStatus.DONE
        );

        when(executionStore.listActiveTaskRuns()).thenReturn(List.of(run));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(executionStore.findAgentInstance(agentInstance.agentInstanceId())).thenReturn(Optional.of(agentInstance));
        when(executionStore.findWorkspaceByRun(run.runId())).thenReturn(Optional.of(workspace));
        when(executionStore.listTaskRunEvents(run.runId())).thenReturn(List.of(
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED"),
                codingEvent(run.runId(), "CODING_TURN_COMPLETED")
        ));
        when(workspaceProvisioner.cleanup(any())).thenReturn(cleanedWorkspace);

        service.advanceActiveRuns();

        verify(workspaceProvisioner).cleanup(any());
        verify(executionStore).saveWorkspace(cleanedWorkspace);
        ArgumentCaptor<WorkTask> taskCaptor = ArgumentCaptor.forClass(WorkTask.class);
        verify(planningStore, atLeastOnce()).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues()).anyMatch(savedTask -> savedTask.status() == WorkTaskStatus.READY);
    }

    private TaskRunEvent priorToolCallEvent(String runId, ObjectMapper objectMapper) throws Exception {
        Map<String, Object> payload = Map.of(
                "decision", Map.of(
                        "decisionType", "TOOL_CALL",
                        "toolCall", Map.of(
                                "callId", "call-fixed-1",
                                "toolId", "tool-filesystem",
                                "operation", "write_file",
                                "arguments", Map.of("path", "src/main/java/App.java"),
                                "summary", "write student source"
                        )
                ),
                "toolPayload", Map.of(
                        "callId", "call-fixed-1",
                        "toolId", "tool-filesystem",
                        "operation", "write_file",
                        "succeeded", true,
                        "terminal", false,
                        "body", "wrote src/main/java/App.java"
                )
        );
        return new TaskRunEvent(
                "event-1",
                runId,
                "CODING_TURN_COMPLETED",
                "write student source",
                new JsonPayload(objectMapper.writeValueAsString(payload))
        );
    }

    private TaskRunEvent codingEvent(String runId, String eventType) {
        return new TaskRunEvent(
                "event-" + eventType + "-" + runId,
                runId,
                eventType,
                eventType,
                JsonPayload.emptyObject()
        );
    }
}
