package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentPort;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerAutoProvisionServiceTest {

    @Mock
    private WaitingTaskQueryUseCase waitingTaskQueryUseCase;
    @Mock
    private TicketCommandUseCase ticketCommandUseCase;
    @Mock
    private TicketQueryUseCase ticketQueryUseCase;
    @Mock
    private WorkerCapabilityUseCase workerCapabilityUseCase;
    @Mock
    private RuntimeEnvironmentPort runtimeEnvironmentPort;

    @Test
    void provisionForWaitingTasksShouldCreateReadyWorkerWhenTaskIsStale() {
        WorkerAutoProvisionService service = new WorkerAutoProvisionService(
            waitingTaskQueryUseCase,
            ticketCommandUseCase,
            ticketQueryUseCase,
            workerCapabilityUseCase,
            runtimeEnvironmentPort,
            new ObjectMapper(),
            10,
            10,
            5,
            2,
            "architect-agent-auto",
            300
        );
        WorkTask waiting = sampleWaitingTask(
            "TASK-1",
            "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]",
            Instant.now().minusSeconds(120)
        );
        when(waitingTaskQueryUseCase.listWaitingWorkerTasks(50)).thenReturn(List.of(waiting));
        when(workerCapabilityUseCase.countWorkers()).thenReturn(0);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING)).thenReturn(0);
        when(workerCapabilityUseCase.listToolpacks()).thenReturn(List.of(
            sampleToolpack("TP-JAVA-21"),
            sampleToolpack("TP-MAVEN-3")
        ));
        when(workerCapabilityUseCase.hasEligibleWorker(List.of("TP-JAVA-21", "TP-MAVEN-3"))).thenReturn(false);
        when(waitingTaskQueryUseCase.findSessionIdByModuleId("MOD-1")).thenReturn(java.util.Optional.of("SES-1"));
        when(runtimeEnvironmentPort.ensureReady(eq("SES-1"), any(), eq(List.of("TP-JAVA-21", "TP-MAVEN-3"))))
            .thenReturn(new RuntimeEnvironmentPort.PreparedEnvironment(
                ".agentx/runtime-env/projects/SES-1/mock",
                null,
                List.of("TP-JAVA-21", "TP-MAVEN-3")
            ));
        when(workerCapabilityUseCase.registerWorker(any())).thenAnswer(invocation -> {
            String workerId = invocation.getArgument(0);
            return new Worker(
                workerId,
                WorkerStatus.PROVISIONING,
                Instant.now(),
                Instant.now()
            );
        });

        WorkerAutoProvisionService.AutoProvisionResult result = service.provisionForWaitingTasks(50);

        assertEquals(1, result.scannedWaitingTasks());
        assertEquals(1, result.createdWorkers());
        assertEquals(0, result.skippedTooFresh());
        assertEquals(0, result.createdClarificationTickets());
        assertTrue(result.createdWorkerIds().get(0).startsWith("WRK-AUTO-"));
        verify(workerCapabilityUseCase).bindToolpacks(any(), eq(List.of("TP-JAVA-21", "TP-MAVEN-3")));
        verify(workerCapabilityUseCase).updateWorkerStatus(any(), eq(WorkerStatus.READY));
        verify(ticketCommandUseCase, never()).createTicket(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void provisionForWaitingTasksShouldSkipWhenCapacityReached() {
        WorkerAutoProvisionService service = new WorkerAutoProvisionService(
            waitingTaskQueryUseCase,
            ticketCommandUseCase,
            ticketQueryUseCase,
            workerCapabilityUseCase,
            runtimeEnvironmentPort,
            new ObjectMapper(),
            10,
            1,
            1,
            1,
            "architect-agent-auto",
            300
        );
        WorkTask waiting = sampleWaitingTask(
            "TASK-2",
            "[\"TP-JAVA-21\"]",
            Instant.now().minusSeconds(120)
        );
        when(waitingTaskQueryUseCase.listWaitingWorkerTasks(20)).thenReturn(List.of(waiting));
        when(workerCapabilityUseCase.countWorkers()).thenReturn(1);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING)).thenReturn(0);
        when(workerCapabilityUseCase.listToolpacks()).thenReturn(List.of(sampleToolpack("TP-JAVA-21")));

        WorkerAutoProvisionService.AutoProvisionResult result = service.provisionForWaitingTasks(20);

        assertEquals(0, result.createdWorkers());
        assertEquals(1, result.skippedByCapacity());
    }

    @Test
    void provisionForWaitingTasksShouldConsumeBudgetEvenIfCreateFails() {
        WorkerAutoProvisionService service = new WorkerAutoProvisionService(
            waitingTaskQueryUseCase,
            ticketCommandUseCase,
            ticketQueryUseCase,
            workerCapabilityUseCase,
            runtimeEnvironmentPort,
            new ObjectMapper(),
            10,
            10,
            10,
            1,
            "architect-agent-auto",
            300
        );
        WorkTask waiting1 = sampleWaitingTask(
            "TASK-3",
            "[\"TP-JAVA-21\"]",
            Instant.now().minusSeconds(120)
        );
        WorkTask waiting2 = sampleWaitingTask(
            "TASK-4",
            "[\"TP-JAVA-21\"]",
            Instant.now().minusSeconds(120)
        );
        when(waitingTaskQueryUseCase.listWaitingWorkerTasks(20)).thenReturn(List.of(waiting1, waiting2));
        when(workerCapabilityUseCase.countWorkers()).thenReturn(0);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING)).thenReturn(0);
        when(workerCapabilityUseCase.listToolpacks()).thenReturn(List.of(sampleToolpack("TP-JAVA-21")));
        when(workerCapabilityUseCase.hasEligibleWorker(List.of("TP-JAVA-21"))).thenReturn(false);
        when(waitingTaskQueryUseCase.findSessionIdByModuleId("MOD-1")).thenReturn(java.util.Optional.of("SES-1"));
        when(runtimeEnvironmentPort.ensureReady(eq("SES-1"), any(), eq(List.of("TP-JAVA-21"))))
            .thenReturn(new RuntimeEnvironmentPort.PreparedEnvironment(
                ".agentx/runtime-env/projects/SES-1/mock",
                null,
                List.of("TP-JAVA-21")
            ));
        when(workerCapabilityUseCase.registerWorker(any())).thenThrow(new IllegalStateException("create failed"));

        WorkerAutoProvisionService.AutoProvisionResult result = service.provisionForWaitingTasks(20);

        assertEquals(0, result.createdWorkers());
        assertEquals(1, result.skippedByCapacity());
        verify(workerCapabilityUseCase, times(1)).registerWorker(any());
    }

    @Test
    void provisionForWaitingTasksShouldRaiseClarificationWhenToolpackMissing() {
        WorkerAutoProvisionService service = new WorkerAutoProvisionService(
            waitingTaskQueryUseCase,
            ticketCommandUseCase,
            ticketQueryUseCase,
            workerCapabilityUseCase,
            runtimeEnvironmentPort,
            new ObjectMapper(),
            10,
            10,
            10,
            2,
            "architect-agent-auto",
            300
        );
        WorkTask waiting = sampleWaitingTask(
            "TASK-MISSING",
            "[\"TP-NOT-EXISTS\"]",
            Instant.now().minusSeconds(120)
        );
        when(waitingTaskQueryUseCase.listWaitingWorkerTasks(20)).thenReturn(List.of(waiting));
        when(waitingTaskQueryUseCase.findSessionIdByModuleId("MOD-1")).thenReturn(java.util.Optional.of("SES-1"));
        when(workerCapabilityUseCase.countWorkers()).thenReturn(0);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING)).thenReturn(0);
        when(workerCapabilityUseCase.listToolpacks()).thenReturn(List.of(sampleToolpack("TP-JAVA-21")));
        when(workerCapabilityUseCase.hasEligibleWorker(List.of("TP-NOT-EXISTS"))).thenReturn(false);
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", "CLARIFICATION"))
            .thenReturn(List.of());
        when(ticketCommandUseCase.createTicket(
            eq("SES-1"),
            eq(TicketType.CLARIFICATION),
            any(),
            eq("architect_agent"),
            eq("architect_agent"),
            eq(null),
            eq(null),
            any()
        )).thenReturn(new Ticket(
            "TCK-1",
            "SES-1",
            TicketType.CLARIFICATION,
            TicketStatus.OPEN,
            "missing",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{}",
            null,
            null,
            Instant.now(),
            Instant.now()
        ));
        when(ticketCommandUseCase.claimTicket("TCK-1", "architect-agent-auto", 300))
            .thenReturn(new Ticket(
                "TCK-1",
                "SES-1",
                TicketType.CLARIFICATION,
                TicketStatus.IN_PROGRESS,
                "missing",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                "architect-agent-auto",
                Instant.now().plusSeconds(300),
                Instant.now(),
                Instant.now()
            ));
        when(ticketCommandUseCase.appendEvent(any(), any(), any(), any(), any()))
            .thenReturn(new TicketEvent(
                "TEV-1",
                "TCK-1",
                TicketEventType.DECISION_REQUESTED,
                "architect_agent",
                "CLARIFICATION request",
                "{}",
                Instant.now()
            ));

        WorkerAutoProvisionService.AutoProvisionResult result = service.provisionForWaitingTasks(20);

        assertEquals(1, result.skippedMissingToolpacks());
        assertEquals(1, result.createdClarificationTickets());
        assertEquals(List.of("TCK-1"), result.createdClarificationTicketIds());
        verify(ticketCommandUseCase).createTicket(
            eq("SES-1"),
            eq(TicketType.CLARIFICATION),
            any(),
            eq("architect_agent"),
            eq("architect_agent"),
            eq(null),
            eq(null),
            any()
        );
        verify(ticketCommandUseCase).appendEvent(eq("TCK-1"), eq("architect_agent"), eq("DECISION_REQUESTED"), any(), any());
        verify(workerCapabilityUseCase, never()).registerWorker(any());
    }

    private static WorkTask sampleWaitingTask(String taskId, String requiredToolpacksJson, Instant updatedAt) {
        Instant createdAt = updatedAt.minusSeconds(10);
        return new WorkTask(
            taskId,
            "MOD-1",
            "auto provision test",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.WAITING_WORKER,
            requiredToolpacksJson,
            null,
            "architect_agent",
            createdAt,
            updatedAt
        );
    }

    private static Toolpack sampleToolpack(String toolpackId) {
        return new Toolpack(
            toolpackId,
            toolpackId.toLowerCase(),
            "1",
            "misc",
            null,
            Instant.now()
        );
    }
}
