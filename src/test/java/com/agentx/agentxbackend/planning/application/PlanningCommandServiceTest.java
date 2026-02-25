package com.agentx.agentxbackend.planning.application;

import com.agentx.agentxbackend.planning.application.port.out.WorkModuleRepository;
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

    private PlanningCommandService service;

    @BeforeEach
    void setUp() {
        service = new PlanningCommandService(
            workModuleRepository,
            workTaskRepository,
            workTaskDependencyRepository,
            workerEligibilityPort,
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

    private static WorkModule sampleModule(String moduleId) {
        Instant now = Instant.parse("2026-02-22T00:00:00Z");
        return new WorkModule(
            moduleId,
            "SES-1",
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
}
