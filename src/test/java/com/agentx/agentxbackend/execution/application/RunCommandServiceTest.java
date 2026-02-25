package com.agentx.agentxbackend.execution.application;

import com.agentx.agentxbackend.execution.application.port.out.ContextSnapshotReadPort;
import com.agentx.agentxbackend.execution.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.execution.application.port.out.TaskAllocationPort;
import com.agentx.agentxbackend.execution.application.port.out.TaskRunEventRepository;
import com.agentx.agentxbackend.execution.application.port.out.TaskRunRepository;
import com.agentx.agentxbackend.execution.application.port.out.WorkerRuntimePort;
import com.agentx.agentxbackend.execution.application.port.out.WorkspacePort;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunCommandServiceTest {

    @Mock
    private TaskRunRepository taskRunRepository;
    @Mock
    private TaskRunEventRepository taskRunEventRepository;
    @Mock
    private TaskAllocationPort taskAllocationPort;
    @Mock
    private WorkspacePort workspacePort;
    @Mock
    private ContextSnapshotReadPort contextSnapshotReadPort;
    @Mock
    private WorkerRuntimePort workerRuntimePort;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private RunCommandService service;

    @BeforeEach
    void setUp() {
        service = new RunCommandService(
            taskRunRepository,
            taskRunEventRepository,
            taskAllocationPort,
            workspacePort,
            contextSnapshotReadPort,
            workerRuntimePort,
            domainEventPublisher,
            new ObjectMapper(),
            300,
            "BASELINE_UNAVAILABLE",
            "WRK-VERIFY",
            ".",
            ".agentx"
        );
    }

    @Test
    void claimTaskShouldReturnEmptyWhenNoTaskAvailable() {
        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.empty());

        Optional<TaskPackage> claimed = service.claimTask("WRK-1");

        assertTrue(claimed.isEmpty());
    }

    @Test
    void claimTaskShouldCreateRunAndReturnTaskPackage() {
        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(workerRuntimePort.listWorkerToolpackIds("WRK-1"))
            .thenReturn(List.of("TP-JAVA-21", "TP-MAVEN-3"));
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.of(new TaskAllocationPort.ClaimedTask(
                "TASK-1",
                "MOD-1",
                "Implement module baseline",
                "tmpl.impl.v0",
                "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"
            )));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-1", RunKind.IMPL))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-1",
                null,
                "file:.agentx/skill.md"
            )));
        when(workspacePort.allocateWorkspace(any(), eq("TASK-1"), eq("BASELINE_UNAVAILABLE"), any()))
            .thenReturn("worktrees/TASK-1/RUN-1");

        Optional<TaskPackage> claimed = service.claimTask("WRK-1");

        assertTrue(claimed.isPresent());
        TaskPackage pkg = claimed.get();
        assertEquals("TASK-1", pkg.taskId());
        assertEquals("Implement module baseline", pkg.taskTitle());
        assertEquals("MOD-1", pkg.moduleId());
        assertEquals("CTXS-1", pkg.contextSnapshotId());
        assertEquals(RunKind.IMPL, pkg.runKind());
        assertEquals(List.of("TP-JAVA-21", "TP-MAVEN-3"), pkg.requiredToolpacks());
        assertTrue(pkg.writeScope().contains("src/main/java/"));
        assertTrue(pkg.writeScope().contains("pom.xml"));
        assertTrue(pkg.verifyCommands().isEmpty());

        ArgumentCaptor<TaskRun> runCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepository).save(runCaptor.capture());
        TaskRun saved = runCaptor.getValue();
        assertEquals("TASK-1", saved.taskId());
        assertEquals("WRK-1", saved.workerId());
        assertEquals(RunStatus.RUNNING, saved.status());
        assertEquals("CTXS-1", saved.contextSnapshotId());
        InOrder inOrder = inOrder(taskRunRepository, workspacePort, taskRunEventRepository);
        inOrder.verify(taskRunRepository).save(any(TaskRun.class));
        inOrder.verify(workspacePort).allocateWorkspace(
            any(),
            eq("TASK-1"),
            eq("BASELINE_UNAVAILABLE"),
            any()
        );
        inOrder.verify(taskRunEventRepository).save(any());
    }

    @Test
    void claimTaskShouldInjectTaskContextFromContextArtifact() throws Exception {
        Path repoRoot = Files.createTempDirectory("agentx-context-pack-");
        Path contextPath = repoRoot.resolve(".agentx/context/task-context-packs/TASK-CTX.json");
        Files.createDirectories(contextPath.getParent());
        Files.writeString(
            contextPath,
            """
                {
                  "requirement_ref": "req:REQ-CTX@v3",
                  "architecture_refs": ["ticket:TCK-1|ARCH_REVIEW"],
                  "decision_refs": ["ticket:TCK-2|DECISION"],
                  "prior_run_refs": ["run:RUN-OLD-1|FAILED"],
                  "repo_baseline_ref": "git:abc123"
                }
                """,
            StandardCharsets.UTF_8
        );
        RunCommandService localService = new RunCommandService(
            taskRunRepository,
            taskRunEventRepository,
            taskAllocationPort,
            workspacePort,
            contextSnapshotReadPort,
            workerRuntimePort,
            domainEventPublisher,
            new ObjectMapper(),
            300,
            "BASELINE_UNAVAILABLE",
            "WRK-VERIFY",
            repoRoot.toString(),
            repoRoot.resolve(".agentx").toString()
        );

        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(workerRuntimePort.listWorkerToolpackIds("WRK-1"))
            .thenReturn(List.of("TP-JAVA-21", "TP-MAVEN-3"));
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.of(new TaskAllocationPort.ClaimedTask(
                "TASK-CTX",
                "MOD-CTX",
                "Apply context-driven implementation",
                "tmpl.impl.v0",
                "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"
            )));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-CTX", RunKind.IMPL))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-CTX-1",
                "file:" + contextPath.toString(),
                "file:.agentx/context/task-skills/TASK-CTX.md"
            )));
        when(workspacePort.allocateWorkspace(any(), eq("TASK-CTX"), eq("BASELINE_UNAVAILABLE"), any()))
            .thenReturn("worktrees/TASK-CTX/RUN-CTX-1");

        Optional<TaskPackage> claimed = localService.claimTask("WRK-1");

        assertTrue(claimed.isPresent());
        TaskPackage pkg = claimed.get();
        assertEquals("req:REQ-CTX@v3", pkg.taskContext().requirementRef());
        assertTrue(pkg.taskContext().architectureRefs().contains("ticket:TCK-1|ARCH_REVIEW"));
        assertTrue(pkg.taskContext().architectureRefs().contains("ticket:TCK-2|DECISION"));
        assertEquals(List.of("run:RUN-OLD-1|FAILED"), pkg.taskContext().priorRunRefs());
        assertEquals("git:abc123", pkg.taskContext().repoBaselineRef());
    }

    @Test
    void claimTaskShouldInjectTaskContextFromCamelCaseContextArtifact() throws Exception {
        Path repoRoot = Files.createTempDirectory("agentx-context-pack-camel-");
        Path contextPath = repoRoot.resolve(".agentx/context/task-context-packs/TASK-CTX-CAMEL.json");
        Files.createDirectories(contextPath.getParent());
        Files.writeString(
            contextPath,
            """
                {
                  "requirementRef": "req:REQ-CAMEL@v1",
                  "architectureRefs": ["ticket:TCK-C1|ARCH_REVIEW"],
                  "decisionRefs": ["ticket:TCK-C2|DECISION"],
                  "priorRunRefs": ["run:RUN-C-OLD-1|FAILED"],
                  "repoBaselineRef": "git:def456"
                }
                """,
            StandardCharsets.UTF_8
        );
        RunCommandService localService = new RunCommandService(
            taskRunRepository,
            taskRunEventRepository,
            taskAllocationPort,
            workspacePort,
            contextSnapshotReadPort,
            workerRuntimePort,
            domainEventPublisher,
            new ObjectMapper(),
            300,
            "BASELINE_UNAVAILABLE",
            "WRK-VERIFY",
            repoRoot.toString(),
            repoRoot.resolve(".agentx").toString()
        );

        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(workerRuntimePort.listWorkerToolpackIds("WRK-1"))
            .thenReturn(List.of("TP-JAVA-21", "TP-MAVEN-3"));
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.of(new TaskAllocationPort.ClaimedTask(
                "TASK-CTX-CAMEL",
                "MOD-CTX",
                "Apply camel context pack",
                "tmpl.impl.v0",
                "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"
            )));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-CTX-CAMEL", RunKind.IMPL))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-CTX-CAMEL-1",
                "file:" + contextPath.toString(),
                "file:.agentx/context/task-skills/TASK-CTX-CAMEL.md"
            )));
        when(workspacePort.allocateWorkspace(any(), eq("TASK-CTX-CAMEL"), eq("BASELINE_UNAVAILABLE"), any()))
            .thenReturn("worktrees/TASK-CTX-CAMEL/RUN-CTX-CAMEL-1");

        Optional<TaskPackage> claimed = localService.claimTask("WRK-1");

        assertTrue(claimed.isPresent());
        TaskPackage pkg = claimed.get();
        assertEquals("req:REQ-CAMEL@v1", pkg.taskContext().requirementRef());
        assertTrue(pkg.taskContext().architectureRefs().contains("ticket:TCK-C1|ARCH_REVIEW"));
        assertTrue(pkg.taskContext().architectureRefs().contains("ticket:TCK-C2|DECISION"));
        assertEquals(List.of("run:RUN-C-OLD-1|FAILED"), pkg.taskContext().priorRunRefs());
        assertEquals("git:def456", pkg.taskContext().repoBaselineRef());
    }

    @Test
    void claimTaskShouldReleaseAssignmentWhenSnapshotMissing() {
        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.of(new TaskAllocationPort.ClaimedTask(
                "TASK-2",
                "MOD-2",
                "Task without snapshot",
                "tmpl.impl.v0",
                "[\"TP-JAVA-21\"]"
            )));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-2", RunKind.IMPL))
            .thenReturn(Optional.empty());

        assertThrows(PreconditionFailedException.class, () -> service.claimTask("WRK-1"));
        verify(taskAllocationPort).releaseTaskAssignment("TASK-2");
    }

    @Test
    void claimTaskShouldRejectWhenInitGateActiveAndAnotherRunIsRunning() {
        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(taskAllocationPort.isInitGateActive()).thenReturn(true);
        when(taskRunRepository.countActiveRuns()).thenReturn(1);

        assertThrows(PreconditionFailedException.class, () -> service.claimTask("WRK-1"));
        verify(taskAllocationPort, never()).claimReadyTaskForWorker(any(), any());
    }

    @Test
    void claimTaskShouldRejectNonInitTaskWhenInitGateActive() {
        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(taskAllocationPort.isInitGateActive()).thenReturn(true);
        when(taskRunRepository.countActiveRuns()).thenReturn(0);
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.of(new TaskAllocationPort.ClaimedTask(
                "TASK-NON-INIT",
                "MOD-1",
                "Non-init task under init gate",
                "tmpl.impl.v0",
                "[\"TP-JAVA-21\"]"
            )));

        assertThrows(PreconditionFailedException.class, () -> service.claimTask("WRK-1"));
        verify(taskAllocationPort).releaseTaskAssignment("TASK-NON-INIT");
    }

    @Test
    void heartbeatShouldExtendLeaseAndPersistEvent() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun running = new TaskRun(
            "RUN-1",
            "TASK-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-1",
            "worktrees/TASK-1/RUN-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-1")).thenReturn(Optional.of(running));
        when(taskRunRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRun updated = service.heartbeat("RUN-1");

        assertEquals("RUN-1", updated.runId());
        assertTrue(updated.leaseUntil().isAfter(running.leaseUntil()));
        verify(taskRunRepository).update(any());
        verify(taskRunEventRepository).save(any());
    }

    @Test
    void failWaitingRunForUserResponseShouldReleaseAssignment() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun waiting = new TaskRun(
            "RUN-WAIT-1",
            "TASK-WAIT-1",
            "WRK-1",
            RunStatus.WAITING_FOREMAN,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-WAIT-1",
            "worktrees/TASK-WAIT-1/RUN-WAIT-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-WAIT-1")).thenReturn(Optional.of(waiting));
        when(taskRunRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRun failed = service.failWaitingRunForUserResponse("RUN-WAIT-1", "user responded");

        assertEquals(RunStatus.FAILED, failed.status());
        verify(taskRunEventRepository).save(any());
        verify(taskAllocationPort).releaseTaskAssignment("TASK-WAIT-1");
        verify(workspacePort).releaseWorkspace("RUN-WAIT-1", "worktrees/TASK-WAIT-1/RUN-WAIT-1");
    }

    @Test
    void claimTaskShouldRejectNonReadyWorker() {
        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.claimTask("WRK-1"));
    }

    @Test
    void pickupRunningVerifyRunShouldReturnTaskPackageForWorker() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun verifyRun = new TaskRun(
            "RUN-VERIFY-RUNNING-1",
            "TASK-VERIFY-1",
            "WRK-VERIFY",
            RunStatus.RUNNING,
            RunKind.VERIFY,
            "CTXS-VERIFY-1",
            now.plusSeconds(300),
            now,
            now,
            null,
            "file:.agentx/skill-verify.md",
            "[\"TP-GIT-2\",\"TP-MAVEN-3\"]",
            "abc123",
            "run/RUN-VERIFY-RUNNING-1",
            "worktrees/TASK-VERIFY-1/RUN-VERIFY-RUNNING-1",
            now,
            now
        );
        when(workerRuntimePort.workerExists("WRK-VERIFY")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-VERIFY")).thenReturn(true);
        when(taskRunRepository.findOldestRunningVerifyRunByWorker("WRK-VERIFY")).thenReturn(Optional.of(verifyRun));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-VERIFY-1", RunKind.VERIFY))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-VERIFY-1",
                null,
                "file:.agentx/skill-verify.md"
            )));

        Optional<TaskPackage> picked = service.pickupRunningVerifyRun("WRK-VERIFY");

        assertTrue(picked.isPresent());
        TaskPackage pkg = picked.get();
        assertEquals("RUN-VERIFY-RUNNING-1", pkg.runId());
        assertEquals(RunKind.VERIFY, pkg.runKind());
        assertEquals("tmpl.verify.v0", pkg.taskTemplateId());
        assertTrue(pkg.verifyCommands().contains("mvn -q test"));
        assertTrue(pkg.writeScope().isEmpty());
    }

    @Test
    void recoverExpiredRunsShouldFailRunReleaseTaskAndWorkspace() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun expired = new TaskRun(
            "RUN-EXPIRED-1",
            "TASK-EXPIRED-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            now.minusSeconds(60),
            now.minusSeconds(120),
            now.minusSeconds(600),
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-EXPIRED-1",
            "worktrees/TASK-EXPIRED-1/RUN-EXPIRED-1",
            now.minusSeconds(600),
            now.minusSeconds(120)
        );
        when(taskRunRepository.findExpiredActiveRuns(any(), eq(10))).thenReturn(List.of(expired));
        when(taskRunRepository.findById("RUN-EXPIRED-1")).thenReturn(Optional.of(expired));
        when(taskRunRepository.markFailedIfLeaseExpired(eq("RUN-EXPIRED-1"), any(), any())).thenReturn(true);

        int recovered = service.recoverExpiredRuns(10);

        assertEquals(1, recovered);
        verify(taskRunRepository).markFailedIfLeaseExpired(eq("RUN-EXPIRED-1"), any(), any());
        verify(taskRunEventRepository).save(any());
        verify(taskAllocationPort).releaseTaskAssignment("TASK-EXPIRED-1");
        verify(workspacePort).releaseWorkspace("RUN-EXPIRED-1", "worktrees/TASK-EXPIRED-1/RUN-EXPIRED-1");
    }

    @Test
    void recoverExpiredRunsShouldSkipWhenLeaseAlreadyRenewed() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun candidate = new TaskRun(
            "RUN-LEASE-RECOVERED",
            "TASK-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            now.minusSeconds(60),
            now.minusSeconds(120),
            now.minusSeconds(600),
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-LEASE-RECOVERED",
            "worktrees/TASK-1/RUN-LEASE-RECOVERED",
            now.minusSeconds(600),
            now.minusSeconds(120)
        );
        TaskRun renewed = new TaskRun(
            candidate.runId(),
            candidate.taskId(),
            candidate.workerId(),
            candidate.status(),
            candidate.runKind(),
            candidate.contextSnapshotId(),
            Instant.now().plusSeconds(120),
            candidate.lastHeartbeatAt(),
            candidate.startedAt(),
            candidate.finishedAt(),
            candidate.taskSkillRef(),
            candidate.toolpacksSnapshotJson(),
            candidate.baseCommit(),
            candidate.branchName(),
            candidate.worktreePath(),
            candidate.createdAt(),
            candidate.updatedAt()
        );
        when(taskRunRepository.findExpiredActiveRuns(any(), eq(10))).thenReturn(List.of(candidate));
        when(taskRunRepository.findById("RUN-LEASE-RECOVERED")).thenReturn(Optional.of(renewed));

        int recovered = service.recoverExpiredRuns(10);

        assertEquals(0, recovered);
    }

    @Test
    void recoverExpiredRunsShouldNotReleaseAssignmentForVerifyRun() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun expiredVerify = new TaskRun(
            "RUN-VERIFY-EXPIRED-1",
            "TASK-VERIFY-EXPIRED-1",
            "WRK-VERIFY",
            RunStatus.RUNNING,
            RunKind.VERIFY,
            "CTXS-VERIFY-1",
            now.minusSeconds(60),
            now.minusSeconds(120),
            now.minusSeconds(600),
            null,
            "file:.agentx/skill-verify.md",
            "[\"TP-GIT-2\"]",
            "abc123",
            "run/RUN-VERIFY-EXPIRED-1",
            "worktrees/TASK-VERIFY-EXPIRED-1/RUN-VERIFY-EXPIRED-1",
            now.minusSeconds(600),
            now.minusSeconds(120)
        );
        when(taskRunRepository.findExpiredActiveRuns(any(), eq(10))).thenReturn(List.of(expiredVerify));
        when(taskRunRepository.findById("RUN-VERIFY-EXPIRED-1")).thenReturn(Optional.of(expiredVerify));
        when(taskRunRepository.markFailedIfLeaseExpired(eq("RUN-VERIFY-EXPIRED-1"), any(), any())).thenReturn(true);

        int recovered = service.recoverExpiredRuns(10);

        assertEquals(1, recovered);
        verify(taskAllocationPort, never()).releaseTaskAssignment("TASK-VERIFY-EXPIRED-1");
        verify(workspacePort).releaseWorkspace(
            "RUN-VERIFY-EXPIRED-1",
            "worktrees/TASK-VERIFY-EXPIRED-1/RUN-VERIFY-EXPIRED-1"
        );
    }

    @Test
    void createVerifyRunShouldPersistVerifyRunAndAllocateWorkspace() {
        when(workerRuntimePort.workerExists("WRK-VERIFY")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-VERIFY")).thenReturn(true);
        when(workerRuntimePort.listWorkerToolpackIds("WRK-VERIFY")).thenReturn(List.of("TP-JAVA-21"));
        when(taskRunRepository.findLatestVerifyRunByTaskAndBaseCommit("TASK-VERIFY-1", "abc123"))
            .thenReturn(Optional.empty());
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-VERIFY-1", RunKind.VERIFY))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-VERIFY-1",
                null,
                "file:.agentx/skill-verify.md"
            )));
        when(workspacePort.allocateWorkspace(any(), eq("TASK-VERIFY-1"), eq("abc123"), any()))
            .thenReturn("worktrees/TASK-VERIFY-1/RUN-VERIFY-1");

        TaskRun run = service.createVerifyRun("TASK-VERIFY-1", "abc123");

        assertEquals("TASK-VERIFY-1", run.taskId());
        assertEquals("WRK-VERIFY", run.workerId());
        assertEquals(RunKind.VERIFY, run.runKind());
        assertEquals("abc123", run.baseCommit());
        verify(taskRunRepository).save(any(TaskRun.class));
        verify(taskRunEventRepository).save(any());
    }

    @Test
    void createVerifyRunShouldRejectWhenSameMergeCandidateAlreadyFailed() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun failedVerify = new TaskRun(
            "RUN-VERIFY-FAILED-1",
            "TASK-VERIFY-1",
            "WRK-VERIFY",
            RunStatus.FAILED,
            RunKind.VERIFY,
            "CTXS-VERIFY-OLD",
            now.minusSeconds(10),
            now.minusSeconds(10),
            now.minusSeconds(60),
            now.minusSeconds(20),
            "file:.agentx/skill-verify.md",
            "[\"TP-GIT-2\"]",
            "abc123",
            "run/RUN-VERIFY-FAILED-1",
            "worktrees/TASK-VERIFY-1/RUN-VERIFY-FAILED-1",
            now.minusSeconds(60),
            now.minusSeconds(20)
        );
        when(workerRuntimePort.workerExists("WRK-VERIFY")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-VERIFY")).thenReturn(true);
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-VERIFY-1", RunKind.VERIFY))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-VERIFY-1",
                null,
                "file:.agentx/skill-verify.md"
            )));
        when(taskRunRepository.findLatestVerifyRunByTaskAndBaseCommit("TASK-VERIFY-1", "abc123"))
            .thenReturn(Optional.of(failedVerify));

        assertThrows(PreconditionFailedException.class, () -> service.createVerifyRun("TASK-VERIFY-1", "abc123"));
        verify(taskRunRepository, never()).save(any(TaskRun.class));
        verify(workspacePort, never()).allocateWorkspace(any(), any(), any(), any());
    }

    @Test
    void claimTaskShouldUseTaskSkillRecommendedCommandsForVerifyRun() throws Exception {
        Path repoRoot = Files.createTempDirectory("agentx-run-command-test-");
        Path taskSkillPath = repoRoot.resolve(".agentx/context/task-skills/verify-skill.md");
        Files.createDirectories(taskSkillPath.getParent());
        Files.writeString(
            taskSkillPath,
            """
                # Task Skill

                ## Recommended Commands
                - mvn -q -Dtest=* verify
                - Run the project-defined verification command set.
                """,
            StandardCharsets.UTF_8
        );

        RunCommandService localService = new RunCommandService(
            taskRunRepository,
            taskRunEventRepository,
            taskAllocationPort,
            workspacePort,
            contextSnapshotReadPort,
            workerRuntimePort,
            domainEventPublisher,
            new ObjectMapper(),
            300,
            "BASELINE_UNAVAILABLE",
            "WRK-VERIFY",
            repoRoot.toString(),
            repoRoot.resolve(".agentx").toString()
        );

        when(workerRuntimePort.workerExists("WRK-1")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-1")).thenReturn(true);
        when(workerRuntimePort.listWorkerToolpackIds("WRK-1")).thenReturn(List.of("TP-MAVEN-3"));
        when(taskAllocationPort.claimReadyTaskForWorker(eq("WRK-1"), any()))
            .thenReturn(Optional.of(new TaskAllocationPort.ClaimedTask(
                "TASK-VERIFY-1",
                "MOD-VERIFY-1",
                "Verify delivery candidate",
                "tmpl.verify.v0",
                "[\"TP-MAVEN-3\"]"
            )));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-VERIFY-1", RunKind.VERIFY))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-VERIFY-1",
                null,
                "file:" + taskSkillPath.toString()
            )));
        when(workspacePort.allocateWorkspace(any(), eq("TASK-VERIFY-1"), eq("BASELINE_UNAVAILABLE"), any()))
            .thenReturn("worktrees/TASK-VERIFY-1/RUN-VERIFY-1");

        Optional<TaskPackage> claimed = localService.claimTask("WRK-1");

        assertTrue(claimed.isPresent());
        TaskPackage pkg = claimed.get();
        assertEquals(RunKind.VERIFY, pkg.runKind());
        assertTrue(pkg.writeScope().isEmpty());
        assertEquals(List.of("mvn -q -Dtest=* verify"), pkg.verifyCommands());
    }

    @Test
    void pickupRunningVerifyRunShouldReadCommandsFromContextArtifactRootOutsideRepoRoot() throws Exception {
        Path repoRoot = Files.createTempDirectory("agentx-run-command-repo-");
        Path contextRoot = Files.createTempDirectory("agentx-run-command-context-");
        Path taskSkillPath = contextRoot.resolve("context/task-skills/verify-skill.md");
        Files.createDirectories(taskSkillPath.getParent());
        Files.writeString(
            taskSkillPath,
            """
                # Task Skill

                ## Recommended Commands
                - mvn -q -DskipTests compile
                """,
            StandardCharsets.UTF_8
        );

        RunCommandService localService = new RunCommandService(
            taskRunRepository,
            taskRunEventRepository,
            taskAllocationPort,
            workspacePort,
            contextSnapshotReadPort,
            workerRuntimePort,
            domainEventPublisher,
            new ObjectMapper(),
            300,
            "BASELINE_UNAVAILABLE",
            "WRK-VERIFY",
            repoRoot.toString(),
            contextRoot.toString()
        );

        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun verifyRun = new TaskRun(
            "RUN-VERIFY-CONTEXT-ROOT-1",
            "TASK-VERIFY-CONTEXT-ROOT-1",
            "WRK-VERIFY",
            RunStatus.RUNNING,
            RunKind.VERIFY,
            "CTXS-VERIFY-CONTEXT-ROOT-1",
            now.plusSeconds(300),
            now,
            now,
            null,
            "file:" + taskSkillPath.toString(),
            "[\"TP-MAVEN-3\"]",
            "abc123",
            "run/RUN-VERIFY-CONTEXT-ROOT-1",
            "worktrees/TASK-VERIFY-CONTEXT-ROOT-1/RUN-VERIFY-CONTEXT-ROOT-1",
            now,
            now
        );
        when(workerRuntimePort.workerExists("WRK-VERIFY")).thenReturn(true);
        when(workerRuntimePort.isWorkerReady("WRK-VERIFY")).thenReturn(true);
        when(taskRunRepository.findOldestRunningVerifyRunByWorker("WRK-VERIFY")).thenReturn(Optional.of(verifyRun));
        when(contextSnapshotReadPort.findLatestReadySnapshot("TASK-VERIFY-CONTEXT-ROOT-1", RunKind.VERIFY))
            .thenReturn(Optional.of(new ContextSnapshotReadPort.ReadySnapshot(
                "CTXS-VERIFY-CONTEXT-ROOT-1",
                null,
                "file:" + taskSkillPath.toString()
            )));

        Optional<TaskPackage> picked = localService.pickupRunningVerifyRun("WRK-VERIFY");

        assertTrue(picked.isPresent());
        assertEquals(List.of("mvn -q -DskipTests compile"), picked.get().verifyCommands());
    }

    @Test
    void finishRunShouldUpdateTaskBranchWhenImplSucceeded() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun running = new TaskRun(
            "RUN-SUCC-1",
            "TASK-SUCC-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-SUCC-1",
            "worktrees/TASK-SUCC-1/RUN-SUCC-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-SUCC-1")).thenReturn(Optional.of(running));
        when(taskRunRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRun finished = service.finishRun(
            "RUN-SUCC-1",
            new RunFinishedPayload("SUCCEEDED", "done", "abc123", null)
        );

        assertEquals(RunStatus.SUCCEEDED, finished.status());
        verify(workspacePort).updateTaskBranch("TASK-SUCC-1", "abc123");
        verify(workspacePort).releaseWorkspace("RUN-SUCC-1", "worktrees/TASK-SUCC-1/RUN-SUCC-1");
        verify(taskRunEventRepository).save(any());
        verify(domainEventPublisher).publish(any(RunFinishedEvent.class));
    }

    @Test
    void failRunShouldFinishAsFailedAndPublishEvent() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun running = new TaskRun(
            "RUN-FAIL-1",
            "TASK-FAIL-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-FAIL-1",
            "worktrees/TASK-FAIL-1/RUN-FAIL-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-FAIL-1")).thenReturn(Optional.of(running));
        when(taskRunRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRun failed = service.failRun("RUN-FAIL-1", "manual stop");

        assertEquals(RunStatus.FAILED, failed.status());
        verify(taskRunEventRepository).save(any());
        verify(workspacePort).releaseWorkspace("RUN-FAIL-1", "worktrees/TASK-FAIL-1/RUN-FAIL-1");
        verify(domainEventPublisher).publish(any(RunFinishedEvent.class));
    }

    @Test
    void failRunShouldNotReleaseAssignmentForVerifyRun() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun verifyRunning = new TaskRun(
            "RUN-VERIFY-FAIL-1",
            "TASK-VERIFY-FAIL-1",
            "WRK-VERIFY",
            RunStatus.RUNNING,
            RunKind.VERIFY,
            "CTXS-VERIFY-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill-verify.md",
            "[\"TP-GIT-2\"]",
            "abc123",
            "run/RUN-VERIFY-FAIL-1",
            "worktrees/TASK-VERIFY-FAIL-1/RUN-VERIFY-FAIL-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-VERIFY-FAIL-1")).thenReturn(Optional.of(verifyRunning));
        when(taskRunRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRun failed = service.failRun("RUN-VERIFY-FAIL-1", "verify failed");

        assertEquals(RunStatus.FAILED, failed.status());
        verify(taskAllocationPort, never()).releaseTaskAssignment("TASK-VERIFY-FAIL-1");
        verify(workspacePort).releaseWorkspace(
            "RUN-VERIFY-FAIL-1",
            "worktrees/TASK-VERIFY-FAIL-1/RUN-VERIFY-FAIL-1"
        );
    }

    @Test
    void failWaitingRunForUserResponseShouldFailWaitingForemanRun() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun waiting = new TaskRun(
            "RUN-WAIT-1",
            "TASK-WAIT-1",
            "WRK-1",
            RunStatus.WAITING_FOREMAN,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-WAIT-1",
            "worktrees/TASK-WAIT-1/RUN-WAIT-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-WAIT-1")).thenReturn(Optional.of(waiting), Optional.of(waiting));
        when(taskRunRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskRun failed = service.failWaitingRunForUserResponse(
            "RUN-WAIT-1",
            "User responded; regenerate with refreshed context."
        );

        assertEquals(RunStatus.FAILED, failed.status());
        verify(taskRunEventRepository).save(any());
        verify(workspacePort).releaseWorkspace("RUN-WAIT-1", "worktrees/TASK-WAIT-1/RUN-WAIT-1");
        verify(domainEventPublisher).publish(any(RunFinishedEvent.class));
    }

    @Test
    void failWaitingRunForUserResponseShouldSkipNonWaitingRun() {
        Instant startedAt = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun running = new TaskRun(
            "RUN-RUNNING-1",
            "TASK-1",
            "WRK-1",
            RunStatus.RUNNING,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-22T00:05:00Z"),
            Instant.parse("2026-02-22T00:04:00Z"),
            startedAt,
            null,
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-RUNNING-1",
            "worktrees/TASK-1/RUN-RUNNING-1",
            startedAt,
            Instant.parse("2026-02-22T00:04:00Z")
        );
        when(taskRunRepository.findById("RUN-RUNNING-1")).thenReturn(Optional.of(running));

        TaskRun result = service.failWaitingRunForUserResponse("RUN-RUNNING-1", "ignored");

        assertEquals(RunStatus.RUNNING, result.status());
        verify(taskRunRepository, never()).update(any());
        verify(taskRunEventRepository, never()).save(any());
    }

    @Test
    void finishRunShouldBeIdempotentWhenAlreadyTerminal() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        TaskRun terminal = new TaskRun(
            "RUN-TERMINAL-1",
            "TASK-TERMINAL-1",
            "WRK-1",
            RunStatus.SUCCEEDED,
            RunKind.IMPL,
            "CTXS-1",
            now.minusSeconds(60),
            now.minusSeconds(120),
            now.minusSeconds(600),
            now.minusSeconds(10),
            "file:.agentx/skill.md",
            "[\"TP-JAVA-21\"]",
            "BASELINE_UNAVAILABLE",
            "run/RUN-TERMINAL-1",
            "worktrees/TASK-TERMINAL-1/RUN-TERMINAL-1",
            now.minusSeconds(600),
            now.minusSeconds(10)
        );
        when(taskRunRepository.findById("RUN-TERMINAL-1")).thenReturn(Optional.of(terminal));

        TaskRun result = service.finishRun(
            "RUN-TERMINAL-1",
            new RunFinishedPayload("SUCCEEDED", "already done", "abc123", null)
        );

        assertEquals(terminal, result);
        verify(taskRunRepository, never()).update(any());
        verify(taskRunEventRepository, never()).save(any());
        verify(workspacePort, never()).releaseWorkspace(any(), any());
        verify(domainEventPublisher, never()).publish(any(RunFinishedEvent.class));
    }
}
