package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentruntime.AgentRuntimeHandle;
import com.agentx.platform.runtime.agentruntime.ContainerLaunchSpec;
import com.agentx.platform.runtime.application.workflow.DispatchDecision;
import com.agentx.platform.runtime.application.workflow.TaskDispatcher;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContract;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContractBuilder;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.context.ContextCompilationProperties;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalBundle;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolCatalogEntry;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class TaskDispatcherTests {

    @Test
    void shouldDispatchReadyTaskThroughCentralDispatcher() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        TaskExecutionContractBuilder contractBuilder = mock(TaskExecutionContractBuilder.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextCompilationProperties = new ContextCompilationProperties();
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(Path.of("."));
        properties.setWorkspaceRoot(Path.of("target/test-workspaces"));
        properties.setLeaseTtl(Duration.ofSeconds(20));

        TaskDispatcher dispatcher = new TaskDispatcher(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                workspaceProvisioner,
                agentRuntime,
                contractBuilder,
                contextCompilationCenter,
                contextCompilationProperties,
                scenarioResolver,
                properties,
                new ObjectMapper()
        );

        WorkTask task = task("task-ready", WorkTaskStatus.READY);
        TaskCapabilityRequirement capabilityRequirement =
                new TaskCapabilityRequirement(task.taskId(), "cap-java-backend-coding", true, "PRIMARY");
        TaskExecutionContract contract = new TaskExecutionContract(
                "maven:3.9.11-eclipse-temurin-21",
                "/workspace",
                List.of("sh", "-lc", "trap exit TERM INT; while true; do sleep 1; done"),
                Map.of("TASK_ID", task.taskId()),
                30,
                new CompiledToolCatalog(List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "list_directory", "write_file"), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command"), "schema://tool-shell", "")
                )),
                List.of("rt-java-21", "rt-maven-3", "rt-git"),
                Map.of("TASK_ID", task.taskId()),
                Map.of("git-commit-delivery", List.of("sh", "-lc", "git add -A && git commit -m test")),
                Map.of(),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of("src/main/java"),
                "src/main/java/.agentx-task-ready.txt"
        );
        Path repoRoot = tempDirectory("dispatcher-repo");
        Path commonGitDir = repoRoot.resolve(".git");
        Path worktreePath = tempDirectory("dispatcher-worktree");
        Path worktreeAdminDir = commonGitDir.resolve("worktrees").resolve("task-ready-run-001");
        createGitWorktreeFixture(worktreePath, worktreeAdminDir);
        GitWorkspace workspace = new GitWorkspace(
                "workspace-task-ready-run-001",
                "task-ready-run-001",
                task.taskId(),
                GitWorkspaceStatus.READY,
                repoRoot.toAbsolutePath().normalize().toString(),
                worktreePath.toAbsolutePath().normalize().toString(),
                "task/task-ready/task-ready-run-001",
                "base-commit",
                null,
                null,
                CleanupStatus.PENDING
        );

        when(planningStore.claimReadyTaskIdsForDispatch(properties.getDispatchBatchSize())).thenReturn(List.of(task.taskId()));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(planningStore.listDependenciesForTask(task.taskId())).thenReturn(List.of());
        when(intakeStore.hasOpenTaskBlocker(task.taskId())).thenReturn(false);
        when(executionStore.listTaskRuns(task.taskId())).thenReturn(List.of());
        when(planningStore.listCapabilityRequirements(task.taskId())).thenReturn(List.of(capabilityRequirement));
        when(contextCompilationCenter.compile(any())).thenReturn(contextPack("workflow-1", task.taskId(), "task-ready-run-001"));
        when(scenarioResolver.resolve("workflow-1")).thenReturn(WorkflowScenario.defaultScenario());
        when(contractBuilder.build("workflow-1", task, List.of(capabilityRequirement), 1, WorkflowScenario.defaultScenario()))
                .thenReturn(contract);
        when(contractBuilder.toPayload(contract)).thenReturn(new JsonPayload("{\"image\":\"maven:3.9.11-eclipse-temurin-21\"}"));
        when(catalogStore.listAgentsByCapability("cap-java-backend-coding")).thenReturn(List.of(new AgentDefinition(
                "coding-agent-java",
                "Coding Agent",
                "implement code",
                "SYSTEM",
                "docker",
                "gpt-5-class",
                8,
                false,
                true,
                true,
                true
        )));
        when(workspaceProvisioner.allocate(eq("workflow-1"), eq(task), any(TaskRun.class), anyString())).thenReturn(workspace);
        when(agentRuntime.launch(any(ContainerLaunchSpec.class))).thenReturn(new AgentRuntimeHandle(
                "docker://task-ready-run-001",
                new JsonPayload("{\"containerId\":\"cid-1\",\"containerName\":\"agentx-container\"}")
        ));

        List<DispatchDecision> decisions = dispatcher.dispatchReadyTasks();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.taskId()).isEqualTo(task.taskId());
            assertThat(decision.dispatched()).isTrue();
            assertThat(decision.runId()).isEqualTo("task-ready-run-001");
        });

        ArgumentCaptor<TaskRun> runCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(executionStore, times(2)).saveTaskRun(runCaptor.capture());
        assertThat(runCaptor.getAllValues())
                .extracting(TaskRun::status)
                .containsExactly(TaskRunStatus.QUEUED, TaskRunStatus.RUNNING);

        ArgumentCaptor<WorkTask> taskCaptor = ArgumentCaptor.forClass(WorkTask.class);
        verify(planningStore).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getValue().status()).isEqualTo(WorkTaskStatus.IN_PROGRESS);

        ArgumentCaptor<ContainerLaunchSpec> launchSpecCaptor = ArgumentCaptor.forClass(ContainerLaunchSpec.class);
        verify(agentRuntime).launch(launchSpecCaptor.capture());
        assertThat(launchSpecCaptor.getValue().image()).isEqualTo("maven:3.9.11-eclipse-temurin-21");
        assertThat(launchSpecCaptor.getValue().mounts()).hasSize(2);
        assertThat(launchSpecCaptor.getValue().mounts().get(0)).satisfies(mount -> {
            assertThat(mount.sourcePath()).isEqualTo(Path.of(workspace.worktreePath()));
            assertThat(mount.targetPath()).isEqualTo("/workspace");
            assertThat(mount.readOnly()).isFalse();
        });
        assertThat(launchSpecCaptor.getValue().mounts().get(1)).satisfies(mount -> {
            assertThat(mount.sourcePath()).isEqualTo(commonGitDir.toAbsolutePath().normalize());
            assertThat(mount.targetPath()).isEqualTo("/agentx/repo/.git");
            assertThat(mount.readOnly()).isFalse();
        });
        assertThat(launchSpecCaptor.getValue().environment())
                .containsEntry("GIT_DIR", "/agentx/repo/.git/worktrees/task-ready-run-001")
                .containsEntry("GIT_WORK_TREE", "/workspace");

        ArgumentCaptor<AgentPoolInstance> instanceCaptor = ArgumentCaptor.forClass(AgentPoolInstance.class);
        verify(executionStore, times(2)).saveAgentInstance(instanceCaptor.capture());
        assertThat(instanceCaptor.getAllValues())
                .extracting(AgentPoolInstance::status)
                .containsExactly(AgentPoolStatus.PROVISIONING, AgentPoolStatus.READY);
    }

    @Test
    void shouldBlockTaskWhenOpenTaskBlockerExists() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        TaskExecutionContractBuilder contractBuilder = mock(TaskExecutionContractBuilder.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextCompilationProperties = new ContextCompilationProperties();
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(Path.of("."));

        TaskDispatcher dispatcher = new TaskDispatcher(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                workspaceProvisioner,
                agentRuntime,
                contractBuilder,
                contextCompilationCenter,
                contextCompilationProperties,
                scenarioResolver,
                properties,
                new ObjectMapper()
        );

        WorkTask task = task("task-blocked", WorkTaskStatus.READY);
        when(planningStore.claimReadyTaskIdsForDispatch(properties.getDispatchBatchSize())).thenReturn(List.of(task.taskId()));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(planningStore.listDependenciesForTask(task.taskId())).thenReturn(List.of());
        when(intakeStore.hasOpenTaskBlocker(task.taskId())).thenReturn(true);

        List<DispatchDecision> decisions = dispatcher.dispatchReadyTasks();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.dispatched()).isFalse();
            assertThat(decision.reason()).isEqualTo("task has unresolved blockers");
        });

        ArgumentCaptor<WorkTask> taskCaptor = ArgumentCaptor.forClass(WorkTask.class);
        verify(planningStore).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getValue().status()).isEqualTo(WorkTaskStatus.BLOCKED);
        verify(agentRuntime, never()).launch(any(ContainerLaunchSpec.class));
        verify(workspaceProvisioner, never()).allocate(any(), any(), any(), anyString());
    }

    @Test
    void shouldPersistExplicitTaskIdOnClarificationTicket() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        TaskExecutionContractBuilder contractBuilder = mock(TaskExecutionContractBuilder.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextCompilationProperties = new ContextCompilationProperties();
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(Path.of("."));

        TaskDispatcher dispatcher = new TaskDispatcher(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                workspaceProvisioner,
                agentRuntime,
                contractBuilder,
                contextCompilationCenter,
                contextCompilationProperties,
                scenarioResolver,
                properties,
                new ObjectMapper()
        );

        WorkTask task = task("task-clarification", WorkTaskStatus.READY);
        TaskCapabilityRequirement capabilityRequirement =
                new TaskCapabilityRequirement(task.taskId(), "cap-java-backend-coding", true, "PRIMARY");

        when(planningStore.claimReadyTaskIdsForDispatch(properties.getDispatchBatchSize())).thenReturn(List.of(task.taskId()));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(planningStore.listDependenciesForTask(task.taskId())).thenReturn(List.of());
        when(intakeStore.hasOpenTaskBlocker(task.taskId())).thenReturn(false);
        when(executionStore.listTaskRuns(task.taskId())).thenReturn(List.of());
        when(planningStore.listCapabilityRequirements(task.taskId())).thenReturn(List.of(capabilityRequirement));
        when(contextCompilationCenter.compile(any())).thenReturn(contextPack("workflow-1", task.taskId(), "task-clarification-run-001"));
        when(scenarioResolver.resolve("workflow-1")).thenReturn(new WorkflowScenario(true, false, false));
        when(catalogStore.listAgentsByCapability("cap-java-backend-coding")).thenReturn(List.of(new AgentDefinition(
                "coding-agent-java",
                "Coding Agent",
                "implement code",
                "SYSTEM",
                "docker",
                "gpt-5-class",
                8,
                false,
                true,
                true,
                true
        )));
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of());

        List<DispatchDecision> decisions = dispatcher.dispatchReadyTasks();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.dispatched()).isFalse();
            assertThat(decision.reason()).isEqualTo("clarification required");
        });

        ArgumentCaptor<com.agentx.platform.domain.intake.model.Ticket> ticketCaptor = ArgumentCaptor.forClass(com.agentx.platform.domain.intake.model.Ticket.class);
        verify(intakeStore).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().taskId()).isEqualTo(task.taskId());
    }

    @Test
    void shouldReuseLatestMergedCommitAsWorkspaceBaseForRework() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        TaskExecutionContractBuilder contractBuilder = mock(TaskExecutionContractBuilder.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextCompilationProperties = new ContextCompilationProperties();
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(Path.of("."));
        properties.setBaseBranch("main");

        TaskDispatcher dispatcher = new TaskDispatcher(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                workspaceProvisioner,
                agentRuntime,
                contractBuilder,
                contextCompilationCenter,
                contextCompilationProperties,
                scenarioResolver,
                properties,
                new ObjectMapper()
        );

        WorkTask task = task("task-rework", WorkTaskStatus.READY);
        TaskCapabilityRequirement capabilityRequirement =
                new TaskCapabilityRequirement(task.taskId(), "cap-java-backend-coding", true, "PRIMARY");
        TaskRun priorRun = new TaskRun(
                "task-rework-run-001",
                task.taskId(),
                "ainst-prior",
                TaskRunStatus.SUCCEEDED,
                RunKind.IMPL,
                "snapshot-prior",
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now().minusMinutes(2),
                LocalDateTime.now().minusMinutes(1),
                JsonPayload.emptyObject()
        );
        Path repoRoot = tempDirectory("dispatcher-rework-repo");
        Path commonGitDir = repoRoot.resolve(".git");
        Path worktreePath = tempDirectory("dispatcher-rework-worktree");
        Path worktreeAdminDir = commonGitDir.resolve("worktrees").resolve("task-rework-run-002");
        createGitWorktreeFixture(worktreePath, worktreeAdminDir);
        GitWorkspace priorWorkspace = new GitWorkspace(
                "workspace-task-rework-run-001",
                priorRun.runId(),
                task.taskId(),
                GitWorkspaceStatus.CLEANED,
                repoRoot.toAbsolutePath().normalize().toString(),
                worktreePath.toAbsolutePath().normalize().toString(),
                "task/task-rework/task-rework-run-001",
                "base-commit",
                "head-commit",
                "merge-commit-123",
                CleanupStatus.DONE
        );
        GitWorkspace newWorkspace = new GitWorkspace(
                "workspace-task-rework-run-002",
                "task-rework-run-002",
                task.taskId(),
                GitWorkspaceStatus.READY,
                repoRoot.toAbsolutePath().normalize().toString(),
                worktreePath.toAbsolutePath().normalize().toString(),
                "task/task-rework/task-rework-run-002",
                "merge-commit-123",
                null,
                null,
                CleanupStatus.PENDING
        );
        TaskExecutionContract contract = new TaskExecutionContract(
                "maven:3.9.11-eclipse-temurin-21",
                "/workspace",
                List.of("sh", "-lc", "trap exit TERM INT; while true; do sleep 1; done"),
                Map.of("TASK_ID", task.taskId()),
                30,
                new CompiledToolCatalog(List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "list_directory", "write_file"), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command"), "schema://tool-shell", "")
                )),
                List.of("rt-java-21", "rt-maven-3", "rt-git"),
                Map.of("TASK_ID", task.taskId()),
                Map.of("git-commit-delivery", List.of("sh", "-lc", "git add -A && git commit -m test")),
                Map.of(),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of("src/main/java"),
                "src/main/java/.agentx-task-rework.txt"
        );

        when(planningStore.claimReadyTaskIdsForDispatch(properties.getDispatchBatchSize())).thenReturn(List.of(task.taskId()));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(planningStore.listDependenciesForTask(task.taskId())).thenReturn(List.of());
        when(intakeStore.hasOpenTaskBlocker(task.taskId())).thenReturn(false);
        when(executionStore.listTaskRuns(task.taskId())).thenReturn(List.of(priorRun));
        when(executionStore.findWorkspaceByRun(priorRun.runId())).thenReturn(Optional.of(priorWorkspace));
        when(planningStore.listCapabilityRequirements(task.taskId())).thenReturn(List.of(capabilityRequirement));
        when(contextCompilationCenter.compile(any())).thenReturn(contextPack("workflow-1", task.taskId(), "task-rework-run-002"));
        when(scenarioResolver.resolve("workflow-1")).thenReturn(WorkflowScenario.defaultScenario());
        when(contractBuilder.build("workflow-1", task, List.of(capabilityRequirement), 2, WorkflowScenario.defaultScenario()))
                .thenReturn(contract);
        when(contractBuilder.toPayload(contract)).thenReturn(new JsonPayload("{\"image\":\"maven:3.9.11-eclipse-temurin-21\"}"));
        when(catalogStore.listAgentsByCapability("cap-java-backend-coding")).thenReturn(List.of(new AgentDefinition(
                "coding-agent-java",
                "Coding Agent",
                "implement code",
                "SYSTEM",
                "docker",
                "gpt-5-class",
                8,
                false,
                true,
                true,
                true
        )));
        when(workspaceProvisioner.allocate(eq("workflow-1"), eq(task), any(TaskRun.class), eq("merge-commit-123"))).thenReturn(newWorkspace);
        when(agentRuntime.launch(any(ContainerLaunchSpec.class))).thenReturn(new AgentRuntimeHandle(
                "docker://task-rework-run-002",
                new JsonPayload("{\"containerId\":\"cid-2\",\"containerName\":\"agentx-container\"}")
        ));

        List<DispatchDecision> decisions = dispatcher.dispatchReadyTasks();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.dispatched()).isTrue();
            assertThat(decision.runId()).isEqualTo("task-rework-run-002");
        });
        verify(workspaceProvisioner).allocate(eq("workflow-1"), eq(task), any(TaskRun.class), eq("merge-commit-123"));
    }

    @Test
    void shouldBaseDependentTaskWorkspaceOnSatisfiedDependencyMergeCommit() {
        CatalogStore catalogStore = mock(CatalogStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);
        TaskExecutionContractBuilder contractBuilder = mock(TaskExecutionContractBuilder.class);
        ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        ContextCompilationProperties contextCompilationProperties = new ContextCompilationProperties();
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setRepoRoot(Path.of("."));
        properties.setBaseBranch("main");

        TaskDispatcher dispatcher = new TaskDispatcher(
                catalogStore,
                planningStore,
                intakeStore,
                executionStore,
                workspaceProvisioner,
                agentRuntime,
                contractBuilder,
                contextCompilationCenter,
                contextCompilationProperties,
                scenarioResolver,
                properties,
                new ObjectMapper()
        );

        WorkTask dependencyTask = task("task-backend", WorkTaskStatus.DONE);
        WorkTask task = task("task-tests", WorkTaskStatus.READY);
        TaskCapabilityRequirement capabilityRequirement =
                new TaskCapabilityRequirement(task.taskId(), "cap-java-backend-coding", true, "PRIMARY");
        TaskRun dependencyRun = new TaskRun(
                "task-backend-run-001",
                dependencyTask.taskId(),
                "ainst-backend",
                TaskRunStatus.SUCCEEDED,
                RunKind.IMPL,
                "snapshot-backend",
                LocalDateTime.now().minusMinutes(3),
                LocalDateTime.now().minusMinutes(3),
                LocalDateTime.now().minusMinutes(3),
                LocalDateTime.now().minusMinutes(2),
                JsonPayload.emptyObject()
        );
        GitWorkspace dependencyWorkspace = new GitWorkspace(
                "workspace-task-backend-run-001",
                dependencyRun.runId(),
                dependencyTask.taskId(),
                GitWorkspaceStatus.CLEANED,
                Path.of(".").toAbsolutePath().normalize().toString(),
                Path.of(".").toAbsolutePath().normalize().toString(),
                "task/task-backend/task-backend-run-001",
                "base-commit",
                "head-commit",
                "merge-commit-dependency",
                CleanupStatus.DONE
        );
        Path repoRoot = tempDirectory("dispatcher-dependency-repo");
        Path commonGitDir = repoRoot.resolve(".git");
        Path worktreePath = tempDirectory("dispatcher-dependency-worktree");
        Path worktreeAdminDir = commonGitDir.resolve("worktrees").resolve("task-tests-run-001");
        createGitWorktreeFixture(worktreePath, worktreeAdminDir);
        GitWorkspace newWorkspace = new GitWorkspace(
                "workspace-task-tests-run-001",
                "task-tests-run-001",
                task.taskId(),
                GitWorkspaceStatus.READY,
                repoRoot.toAbsolutePath().normalize().toString(),
                worktreePath.toAbsolutePath().normalize().toString(),
                "task/task-tests/task-tests-run-001",
                "merge-commit-dependency",
                null,
                null,
                CleanupStatus.PENDING
        );
        TaskExecutionContract contract = new TaskExecutionContract(
                "maven:3.9.11-eclipse-temurin-21",
                "/workspace",
                List.of("sh", "-lc", "trap exit TERM INT; while true; do sleep 1; done"),
                Map.of("TASK_ID", task.taskId()),
                30,
                new CompiledToolCatalog(List.of(
                        new ToolCatalogEntry("tool-filesystem", "Filesystem", "DIRECT", List.of("read_file", "list_directory", "write_file"), "schema://tool-filesystem", ""),
                        new ToolCatalogEntry("tool-shell", "Shell", "DIRECT", List.of("run_command"), "schema://tool-shell", "")
                )),
                List.of("rt-java-21", "rt-maven-3", "rt-git"),
                Map.of("TASK_ID", task.taskId()),
                Map.of("git-commit-delivery", List.of("sh", "-lc", "git add -A && git commit -m test")),
                Map.of(),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of(new ToolCall("tool-shell", "run_command", Map.of("commandId", "git-commit-delivery"), "commit task changes")),
                List.of("src/test/java"),
                "src/test/java/.agentx-task-tests.txt"
        );

        when(planningStore.claimReadyTaskIdsForDispatch(properties.getDispatchBatchSize())).thenReturn(List.of(task.taskId()));
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findTask(dependencyTask.taskId())).thenReturn(Optional.of(dependencyTask));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(planningStore.listDependenciesForTask(task.taskId()))
                .thenReturn(List.of(new TaskDependency(task.taskId(), dependencyTask.taskId(), WorkTaskStatus.DONE)));
        when(intakeStore.hasOpenTaskBlocker(task.taskId())).thenReturn(false);
        when(executionStore.listTaskRuns(task.taskId())).thenReturn(List.of());
        when(executionStore.listTaskRuns(dependencyTask.taskId())).thenReturn(List.of(dependencyRun));
        when(executionStore.findWorkspaceByRun(dependencyRun.runId())).thenReturn(Optional.of(dependencyWorkspace));
        when(planningStore.listCapabilityRequirements(task.taskId())).thenReturn(List.of(capabilityRequirement));
        when(contextCompilationCenter.compile(any())).thenReturn(contextPack("workflow-1", task.taskId(), "task-tests-run-001"));
        when(scenarioResolver.resolve("workflow-1")).thenReturn(WorkflowScenario.defaultScenario());
        when(contractBuilder.build("workflow-1", task, List.of(capabilityRequirement), 1, WorkflowScenario.defaultScenario()))
                .thenReturn(contract);
        when(contractBuilder.toPayload(contract)).thenReturn(new JsonPayload("{\"image\":\"maven:3.9.11-eclipse-temurin-21\"}"));
        when(catalogStore.listAgentsByCapability("cap-java-backend-coding")).thenReturn(List.of(new AgentDefinition(
                "coding-agent-java",
                "Coding Agent",
                "implement code",
                "SYSTEM",
                "docker",
                "gpt-5-class",
                8,
                false,
                true,
                true,
                true
        )));
        when(workspaceProvisioner.allocate(eq("workflow-1"), eq(task), any(TaskRun.class), eq("merge-commit-dependency"))).thenReturn(newWorkspace);
        when(agentRuntime.launch(any(ContainerLaunchSpec.class))).thenReturn(new AgentRuntimeHandle(
                "docker://task-tests-run-001",
                new JsonPayload("{\"containerId\":\"cid-3\",\"containerName\":\"agentx-container\"}")
        ));

        List<DispatchDecision> decisions = dispatcher.dispatchReadyTasks();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.dispatched()).isTrue();
            assertThat(decision.runId()).isEqualTo("task-tests-run-001");
        });
        verify(workspaceProvisioner).allocate(eq("workflow-1"), eq(task), any(TaskRun.class), eq("merge-commit-dependency"));
    }

    private WorkTask task(String taskId, WorkTaskStatus status) {
        return new WorkTask(
                taskId,
                "module-api",
                "实现 healthz",
                "生成交付候选",
                "java-backend-task",
                status,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
    }

    private Path tempDirectory(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create temp directory", exception);
        }
    }

    private CompiledContextPack contextPack(String workflowRunId, String taskId, String runId) {
        return new CompiledContextPack(
                ContextPackType.CODING,
                ContextScope.task(workflowRunId, taskId, runId, "coding", null),
                "fingerprint",
                "artifact-ref",
                "{\"packType\":\"CODING\"}",
                new FactBundle(Map.of()),
                new RetrievalBundle(List.of()),
                LocalDateTime.now()
        );
    }

    private void createGitWorktreeFixture(Path worktreePath, Path worktreeAdminDir) {
        try {
            Files.createDirectories(worktreePath);
            Files.createDirectories(worktreeAdminDir);
            Files.writeString(
                    worktreePath.resolve(".git"),
                    "gitdir: " + worktreeAdminDir.toAbsolutePath().normalize(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create git worktree fixture", exception);
        }
    }
}
