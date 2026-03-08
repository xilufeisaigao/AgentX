package com.agentx.agentxbackend.planning.application;

import com.agentx.agentxbackend.planning.application.port.out.WorkModuleRepository;
import com.agentx.agentxbackend.planning.application.port.out.SessionDispatchPolicyPort;
import com.agentx.agentxbackend.planning.application.port.out.WorkTaskDependencyRepository;
import com.agentx.agentxbackend.planning.application.port.out.WorkTaskRepository;
import com.agentx.agentxbackend.planning.application.port.out.WorkerEligibilityPort;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class PlanningCommandServiceTest {

    @Mock
    private WorkModuleRepository workModuleRepository;
    @Mock
    private WorkTaskRepository workTaskRepository;
    @Mock
    private WorkTaskDependencyRepository workTaskDependencyRepository;
    @Mock
    private WorkerEligibilityPort workerEligibilityPort;
    @Mock
    private SessionDispatchPolicyPort sessionDispatchPolicyPort;

    private PlanningCommandService service;

    @BeforeEach
    void setUp() {
        service = new PlanningCommandService(
            workModuleRepository,
            workTaskRepository,
            workTaskDependencyRepository,
            workerEligibilityPort,
            sessionDispatchPolicyPort,
            new ObjectMapper(),
            64,
            2048
        );
    }

    @Test
    void createTaskShouldEnterWaitingWorkerWhenNoEligibleWorker() {
        when(workModuleRepository.findById("MOD-1")).thenReturn(Optional.of(sampleModule("MOD-1")));
        when(workTaskDependencyRepository.findByTaskId(any())).thenReturn(List.of());
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"))
            .thenReturn(false);
        when(workTaskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkTask created = service.createTask(
            "MOD-1",
            "Implement order create API",
            "tmpl.impl.v0",
            "[\"TP-JAVA-21\", \"TP-MAVEN-3\", \"TP-JAVA-21\"]"
        );

        assertTrue(created.taskId().startsWith("TASK-"));
        assertEquals(TaskStatus.WAITING_WORKER, created.status());
        assertEquals(TaskTemplateId.TMPL_IMPL_V0, created.taskTemplateId());
        assertEquals("[\"TP-JAVA-21\",\"TP-MAVEN-3\"]", created.requiredToolpacksJson());
        assertEquals("architect_agent", created.createdByRole());
        assertNotNull(created.createdAt());
        assertNotNull(created.updatedAt());
    }

    @Test
    void createTaskShouldEnterReadyForAssignWhenEligibleWorkerExists() {
        when(workModuleRepository.findById("MOD-2")).thenReturn(Optional.of(sampleModule("MOD-2")));
        when(workTaskDependencyRepository.findByTaskId(any())).thenReturn(List.of());
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workTaskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkTask created = service.createTask(
            "MOD-2",
            "Implement payment callback",
            "tmpl.impl.v0",
            "[\"TP-JAVA-21\"]"
        );

        assertEquals(TaskStatus.READY_FOR_ASSIGN, created.status());
    }

    @Test
    void createTaskShouldRejectInvalidRequiredToolpacksJson() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createTask("MOD-3", "task", "tmpl.impl.v0", "{\"toolpack\":\"TP-JAVA-21\"}")
        );
        verify(workTaskRepository, never()).save(any());
    }

    @Test
    void createTaskShouldRejectUnsupportedTemplate() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createTask("MOD-3", "task", "tmpl.unknown.v0", "[]")
        );
        verify(workTaskRepository, never()).save(any());
    }

    @Test
    void refreshWaitingTasksShouldAdvanceWhenEligibleWorkerAppears() {
        WorkTask waitingReady = sampleTask("TASK-1", TaskStatus.WAITING_WORKER, "[\"TP-JAVA-21\"]");
        WorkTask waitingStill = sampleTask("TASK-2", TaskStatus.WAITING_WORKER, "[\"TP-RUST-1_75\"]");
        when(workTaskRepository.findByStatus(TaskStatus.WAITING_WORKER, 100))
            .thenReturn(List.of(waitingReady, waitingStill));
        when(workTaskDependencyRepository.findByTaskId("TASK-1")).thenReturn(List.of());
        when(workTaskDependencyRepository.findByTaskId("TASK-2")).thenReturn(List.of());
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-RUST-1_75\"]")).thenReturn(false);
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        int advanced = service.refreshWaitingTasks(100);

        assertEquals(1, advanced);
        ArgumentCaptor<WorkTask> captor = ArgumentCaptor.forClass(WorkTask.class);
        verify(workTaskRepository).update(captor.capture());
        assertEquals("TASK-1", captor.getValue().taskId());
        assertEquals(TaskStatus.READY_FOR_ASSIGN, captor.getValue().status());
    }

    @Test
    void markAssignedShouldSetRunIdAndStatus() {
        WorkTask ready = sampleTask("TASK-3", TaskStatus.READY_FOR_ASSIGN, "[\"TP-JAVA-21\"]");
        when(workTaskRepository.findById("TASK-3")).thenReturn(Optional.of(ready));
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkTask assigned = service.markAssigned("TASK-3", "RUN-1");

        assertEquals(TaskStatus.ASSIGNED, assigned.status());
        assertEquals("RUN-1", assigned.activeRunId());
    }

    @Test
    void releaseAssignmentShouldFallbackToWaitingWorkerWhenCapabilityMissing() {
        WorkTask assigned = new WorkTask(
            "TASK-4",
            "MOD-1",
            "t4",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-4",
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workTaskRepository.findById("TASK-4")).thenReturn(Optional.of(assigned));
        when(workTaskDependencyRepository.findByTaskId("TASK-4")).thenReturn(List.of());
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-JAVA-21\"]")).thenReturn(false);
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkTask released = service.releaseAssignment("TASK-4");

        assertEquals(TaskStatus.WAITING_WORKER, released.status());
        assertNull(released.activeRunId());
    }

    @Test
    void claimReadyTaskForWorkerShouldClaimEligibleCandidate() {
        WorkTask candidate1 = sampleTask("TASK-11", TaskStatus.READY_FOR_ASSIGN, "[\"TP-RUST-1_75\"]");
        WorkTask candidate2 = sampleTask("TASK-12", TaskStatus.READY_FOR_ASSIGN, "[\"TP-JAVA-21\"]");
        WorkTask claimed2 = new WorkTask(
            "TASK-12",
            "MOD-1",
            "task",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-12",
            "architect_agent",
            candidate2.createdAt(),
            Instant.parse("2026-02-22T00:10:00Z")
        );
        when(workTaskRepository.findByStatus(TaskStatus.READY_FOR_ASSIGN, 64, 0))
            .thenReturn(List.of(candidate1, candidate2));
        when(workModuleRepository.findById("MOD-1")).thenReturn(Optional.of(sampleModule("MOD-1")));
        when(sessionDispatchPolicyPort.isSessionDispatchable("SES-1")).thenReturn(true);
        when(workTaskRepository.countNonDoneBySessionIdAndTemplateId("SES-1", TaskTemplateId.TMPL_INIT_V0.value()))
            .thenReturn(0);
        when(workerEligibilityPort.isWorkerEligible("WRK-1", "[\"TP-RUST-1_75\"]")).thenReturn(false);
        when(workerEligibilityPort.isWorkerEligible("WRK-1", "[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workTaskRepository.claimIfReady(eq("TASK-12"), eq("RUN-12"), any())).thenReturn(true);
        when(workTaskRepository.findById("TASK-12")).thenReturn(Optional.of(claimed2));

        Optional<WorkTask> claimed = service.claimReadyTaskForWorker("WRK-1", "RUN-12");

        assertTrue(claimed.isPresent());
        assertEquals("TASK-12", claimed.get().taskId());
        assertEquals(TaskStatus.ASSIGNED, claimed.get().status());
    }

    @Test
    void claimReadyTaskForWorkerShouldPreferInitTaskWhenInitGateActive() {
        WorkTask nonInit = sampleTask("TASK-NON-INIT-1", TaskStatus.READY_FOR_ASSIGN, "[\"TP-JAVA-21\"]");
        WorkTask initTask = sampleInitTask("TASK-INIT-1", TaskStatus.READY_FOR_ASSIGN, "[\"TP-JAVA-21\"]");
        WorkTask claimedInit = new WorkTask(
            "TASK-INIT-1",
            "MOD-1",
            "init baseline",
            TaskTemplateId.TMPL_INIT_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-INIT-1",
            "architect_agent",
            initTask.createdAt(),
            Instant.parse("2026-02-22T00:11:00Z")
        );
        when(workTaskRepository.findByStatus(TaskStatus.READY_FOR_ASSIGN, 64, 0))
            .thenReturn(List.of(nonInit, initTask));
        when(workModuleRepository.findById("MOD-1")).thenReturn(Optional.of(sampleModule("MOD-1")));
        when(sessionDispatchPolicyPort.isSessionDispatchable("SES-1")).thenReturn(true);
        when(workTaskRepository.countNonDoneBySessionIdAndTemplateId("SES-1", TaskTemplateId.TMPL_INIT_V0.value()))
            .thenReturn(1);
        when(workerEligibilityPort.isWorkerEligible("WRK-1", "[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workTaskRepository.claimIfReady(eq("TASK-INIT-1"), eq("RUN-INIT-1"), any())).thenReturn(true);
        when(workTaskRepository.findById("TASK-INIT-1")).thenReturn(Optional.of(claimedInit));

        Optional<WorkTask> claimed = service.claimReadyTaskForWorker("WRK-1", "RUN-INIT-1");

        assertTrue(claimed.isPresent());
        assertEquals("TASK-INIT-1", claimed.get().taskId());
        verify(workTaskRepository, never()).claimIfReady(eq("TASK-NON-INIT-1"), any(), any());
    }

    @Test
    void claimReadyTaskForWorkerShouldIgnoreInitGateFromOtherSessions() {
        WorkModule freeModule = new WorkModule(
            "MOD-FREE",
            "SES-FREE",
            "feature",
            "free session",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        WorkTask otherSessionTask = new WorkTask(
            "TASK-FREE-1",
            "MOD-FREE",
            "feature task",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.READY_FOR_ASSIGN,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        WorkTask claimed = new WorkTask(
            "TASK-FREE-1",
            "MOD-FREE",
            "feature task",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-FREE-1",
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:10:00Z")
        );

        when(workTaskRepository.findByStatus(TaskStatus.READY_FOR_ASSIGN, 64, 0))
            .thenReturn(List.of(otherSessionTask));
        when(workModuleRepository.findById("MOD-FREE")).thenReturn(Optional.of(freeModule));
        when(sessionDispatchPolicyPort.isSessionDispatchable("SES-FREE")).thenReturn(true);
        when(workTaskRepository.countNonDoneBySessionIdAndTemplateId("SES-FREE", TaskTemplateId.TMPL_INIT_V0.value()))
            .thenReturn(0);
        when(workerEligibilityPort.isWorkerEligible("WRK-1", "[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workTaskRepository.claimIfReady(eq("TASK-FREE-1"), eq("RUN-FREE-1"), any())).thenReturn(true);
        when(workTaskRepository.findById("TASK-FREE-1")).thenReturn(Optional.of(claimed));

        Optional<WorkTask> result = service.claimReadyTaskForWorker("WRK-1", "RUN-FREE-1");

        assertTrue(result.isPresent());
        assertEquals("TASK-FREE-1", result.get().taskId());
    }

    @Test
    void claimReadyTaskForWorkerShouldSkipInactiveSessionCandidates() {
        WorkModule inactiveModule = sampleModule("MOD-INACTIVE", "SES-INACTIVE");
        WorkTask inactiveTask = new WorkTask(
            "TASK-INACTIVE-1",
            "MOD-INACTIVE",
            "inactive task",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.READY_FOR_ASSIGN,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        WorkTask activeTask = sampleTask("TASK-ACTIVE-1", TaskStatus.READY_FOR_ASSIGN, "[\"TP-JAVA-21\"]");
        WorkTask claimedActive = new WorkTask(
            "TASK-ACTIVE-1",
            "MOD-1",
            "task",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-ACTIVE-1",
            "architect_agent",
            activeTask.createdAt(),
            Instant.parse("2026-02-22T00:10:00Z")
        );

        when(workTaskRepository.findByStatus(TaskStatus.READY_FOR_ASSIGN, 64, 0))
            .thenReturn(List.of(inactiveTask, activeTask));
        when(workModuleRepository.findById("MOD-INACTIVE")).thenReturn(Optional.of(inactiveModule));
        when(workModuleRepository.findById("MOD-1")).thenReturn(Optional.of(sampleModule("MOD-1")));
        when(sessionDispatchPolicyPort.isSessionDispatchable("SES-INACTIVE")).thenReturn(false);
        when(sessionDispatchPolicyPort.isSessionDispatchable("SES-1")).thenReturn(true);
        when(workTaskRepository.countNonDoneBySessionIdAndTemplateId("SES-1", TaskTemplateId.TMPL_INIT_V0.value()))
            .thenReturn(0);
        when(workerEligibilityPort.isWorkerEligible("WRK-1", "[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workTaskRepository.claimIfReady(eq("TASK-ACTIVE-1"), eq("RUN-ACTIVE-1"), any())).thenReturn(true);
        when(workTaskRepository.findById("TASK-ACTIVE-1")).thenReturn(Optional.of(claimedActive));

        Optional<WorkTask> claimed = service.claimReadyTaskForWorker("WRK-1", "RUN-ACTIVE-1");

        assertTrue(claimed.isPresent());
        assertEquals("TASK-ACTIVE-1", claimed.get().taskId());
        verify(workTaskRepository, never()).claimIfReady(eq("TASK-INACTIVE-1"), any(), any());
    }

    @Test
    void createTaskShouldEnterWaitingDependencyWhenUpstreamNotDone() {
        WorkTask upstream = sampleTask("TASK-UP", TaskStatus.DELIVERED, "[\"TP-JAVA-21\"]");
        WorkModule module = sampleModule("MOD-5");
        when(workModuleRepository.findById("MOD-5")).thenReturn(Optional.of(module));
        when(workModuleRepository.findById("MOD-1")).thenReturn(Optional.of(module));
        when(workTaskRepository.findById("TASK-UP")).thenReturn(Optional.of(upstream));
        when(workTaskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workTaskDependencyRepository.exists(any(), any())).thenReturn(false);
        when(workTaskDependencyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workTaskDependencyRepository.findByTaskId(any())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            if ("TASK-UP".equals(taskId)) {
                return List.of();
            }
            WorkTaskDependency dep = new WorkTaskDependency(
                taskId,
                "TASK-UP",
                TaskStatus.DONE,
                Instant.parse("2026-02-22T00:00:00Z")
            );
            return List.of(dep);
        });

        WorkTask created = service.createTask(
            "MOD-5",
            "Implement dependent task",
            "tmpl.impl.v0",
            "[\"TP-JAVA-21\"]",
            List.of("TASK-UP")
        );

        assertEquals(TaskStatus.WAITING_DEPENDENCY, created.status());
        verify(workerEligibilityPort, never()).hasEligibleWorker("[\"TP-JAVA-21\"]");
    }

    @Test
    void addTaskDependencyShouldRejectCycle() {
        WorkModule module = sampleModule("MOD-1");
        WorkTask taskA = sampleTask("TASK-A", TaskStatus.WAITING_WORKER, "[\"TP-JAVA-21\"]");
        WorkTask taskB = sampleTask("TASK-B", TaskStatus.WAITING_WORKER, "[\"TP-JAVA-21\"]");
        when(workTaskRepository.findById("TASK-A")).thenReturn(Optional.of(taskA));
        when(workTaskRepository.findById("TASK-B")).thenReturn(Optional.of(taskB));
        when(workModuleRepository.findById("MOD-1")).thenReturn(Optional.of(module));
        when(workTaskDependencyRepository.findByTaskId("TASK-B"))
            .thenReturn(List.of(
                new WorkTaskDependency(
                    "TASK-B",
                    "TASK-A",
                    TaskStatus.DONE,
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            ));

        assertThrows(
            IllegalArgumentException.class,
            () -> service.addTaskDependency("TASK-A", "TASK-B", "DONE")
        );
        verify(workTaskDependencyRepository, never()).save(any());
    }

    @Test
    void markDoneShouldRecomputeDependents() {
        WorkTask upstream = new WorkTask(
            "TASK-UP-2",
            "MOD-1",
            "upstream",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DELIVERED,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        WorkTask dependent = sampleTask("TASK-DOWN-2", TaskStatus.WAITING_DEPENDENCY, "[\"TP-JAVA-21\"]");
        WorkTask upstreamDone = new WorkTask(
            "TASK-UP-2",
            "MOD-1",
            "upstream",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DONE,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:01Z")
        );
        when(workTaskRepository.findById("TASK-UP-2"))
            .thenReturn(Optional.of(upstream), Optional.of(upstreamDone));
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workTaskDependencyRepository.findByDependsOnTaskId("TASK-UP-2"))
            .thenReturn(List.of(
                new WorkTaskDependency(
                    "TASK-DOWN-2",
                    "TASK-UP-2",
                    TaskStatus.DONE,
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            ));
        when(workTaskRepository.findById("TASK-DOWN-2")).thenReturn(Optional.of(dependent));
        when(workTaskDependencyRepository.findByTaskId("TASK-DOWN-2"))
            .thenReturn(List.of(
                new WorkTaskDependency(
                    "TASK-DOWN-2",
                    "TASK-UP-2",
                    TaskStatus.DONE,
                    Instant.parse("2026-02-22T00:00:00Z")
                )
            ));
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-JAVA-21\"]")).thenReturn(true);

        WorkTask done = service.markDone("TASK-UP-2");

        assertEquals(TaskStatus.DONE, done.status());
        verify(workTaskRepository, times(2)).update(any());
    }

    @Test
    void markDeliveredShouldBeIdempotentWhenAlreadyDelivered() {
        WorkTask delivered = new WorkTask(
            "TASK-DELIVERED-1",
            "MOD-1",
            "delivered",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DELIVERED,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workTaskRepository.findById("TASK-DELIVERED-1")).thenReturn(Optional.of(delivered));

        WorkTask result = service.markDelivered("TASK-DELIVERED-1");

        assertEquals(TaskStatus.DELIVERED, result.status());
        verify(workTaskRepository, never()).update(any());
    }

    @Test
    void markDoneShouldBeIdempotentWhenAlreadyDone() {
        WorkTask done = new WorkTask(
            "TASK-DONE-1",
            "MOD-1",
            "done",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DONE,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workTaskRepository.findById("TASK-DONE-1")).thenReturn(Optional.of(done));

        WorkTask result = service.markDone("TASK-DONE-1");

        assertEquals(TaskStatus.DONE, result.status());
        verify(workTaskRepository, never()).update(any());
        verify(workTaskDependencyRepository, never()).findByDependsOnTaskId("TASK-DONE-1");
    }

    @Test
    void markDoneShouldAllowAssignedVerifyTask() {
        WorkTask assignedVerify = new WorkTask(
            "TASK-VERIFY-DONE-1",
            "MOD-1",
            "verify",
            TaskTemplateId.TMPL_VERIFY_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-VERIFY-1",
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workTaskRepository.findById("TASK-VERIFY-DONE-1")).thenReturn(Optional.of(assignedVerify));
        when(workTaskDependencyRepository.findByDependsOnTaskId("TASK-VERIFY-DONE-1")).thenReturn(List.of());
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkTask result = service.markDone("TASK-VERIFY-DONE-1");

        assertEquals(TaskStatus.DONE, result.status());
        assertNull(result.activeRunId());
        verify(workTaskRepository).update(any());
    }

    @Test
    void reopenDeliveredShouldMoveBackToDispatchStatus() {
        WorkTask delivered = new WorkTask(
            "TASK-REOPEN-1",
            "MOD-1",
            "delivered",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DELIVERED,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workTaskRepository.findById("TASK-REOPEN-1")).thenReturn(Optional.of(delivered));
        when(workTaskDependencyRepository.findByTaskId("TASK-REOPEN-1")).thenReturn(List.of());
        when(workerEligibilityPort.hasEligibleWorker("[\"TP-JAVA-21\"]")).thenReturn(true);
        when(workTaskRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkTask reopened = service.reopenDelivered("TASK-REOPEN-1");

        assertEquals(TaskStatus.READY_FOR_ASSIGN, reopened.status());
        assertNull(reopened.activeRunId());
        verify(workTaskRepository).update(any());
    }

    @Test
    void reopenDeliveredShouldRejectDoneTask() {
        WorkTask done = new WorkTask(
            "TASK-REOPEN-DONE",
            "MOD-1",
            "done",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DONE,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workTaskRepository.findById("TASK-REOPEN-DONE")).thenReturn(Optional.of(done));

        assertThrows(IllegalStateException.class, () -> service.reopenDelivered("TASK-REOPEN-DONE"));
        verify(workTaskRepository, never()).update(any());
    }

    @Test
    void hasNonDoneDependentTaskByTemplateShouldDetectPendingDependentTestTask() {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        when(workTaskDependencyRepository.findByDependsOnTaskId("TASK-UP"))
            .thenReturn(List.of(new WorkTaskDependency("TASK-TEST", "TASK-UP", TaskStatus.DONE, now)));
        when(workTaskRepository.findById("TASK-TEST"))
            .thenReturn(Optional.of(new WorkTask(
                "TASK-TEST",
                "MOD-1",
                "test task",
                TaskTemplateId.TMPL_TEST_V0,
                TaskStatus.READY_FOR_ASSIGN,
                "[\"TP-JAVA-21\"]",
                null,
                "architect_agent",
                now,
                now
            )));

        assertTrue(service.hasNonDoneDependentTaskByTemplate("TASK-UP", "tmpl.test.v0"));
    }

    private static WorkModule sampleModule(String moduleId) {
        return sampleModule(moduleId, "SES-1");
    }

    private static WorkModule sampleModule(String moduleId, String sessionId) {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        return new WorkModule(
            moduleId,
            sessionId,
            "order-center",
            "module",
            now,
            now
        );
    }

    private static WorkTask sampleTask(String taskId, TaskStatus status, String requiredToolpacksJson) {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        return new WorkTask(
            taskId,
            "MOD-1",
            "task",
            TaskTemplateId.TMPL_IMPL_V0,
            status,
            requiredToolpacksJson,
            null,
            "architect_agent",
            now,
            now
        );
    }

    private static WorkTask sampleInitTask(String taskId, TaskStatus status, String requiredToolpacksJson) {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        return new WorkTask(
            taskId,
            "MOD-1",
            "init baseline",
            TaskTemplateId.TMPL_INIT_V0,
            status,
            requiredToolpacksJson,
            null,
            "architect_agent",
            now,
            now
        );
    }
}
