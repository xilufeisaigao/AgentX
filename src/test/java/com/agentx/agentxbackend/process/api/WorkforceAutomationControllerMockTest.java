package com.agentx.agentxbackend.process.api;

import com.agentx.agentxbackend.execution.application.port.in.RunLeaseRecoveryUseCase;
import com.agentx.agentxbackend.process.application.WorkerAutoProvisionService;
import com.agentx.agentxbackend.process.application.WorkerPoolCleanupService;
import com.agentx.agentxbackend.process.application.WorkerRuntimeAutoRunService;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkforceAutomationControllerMockTest {

    @Mock
    private WorkerAutoProvisionService workerAutoProvisionService;
    @Mock
    private RunLeaseRecoveryUseCase runLeaseRecoveryUseCase;
    @Mock
    private WorkerRuntimeAutoRunService workerRuntimeAutoRunService;
    @Mock
    private WorkerPoolCleanupService workerPoolCleanupService;
    @Mock
    private WorkerCapabilityUseCase workerCapabilityUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WorkforceAutomationController controller = new WorkforceAutomationController(
            workerAutoProvisionService,
            runLeaseRecoveryUseCase,
            workerRuntimeAutoRunService,
            workerPoolCleanupService,
            workerCapabilityUseCase
        );
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void autoProvisionShouldReturnSummary() throws Exception {
        when(workerAutoProvisionService.provisionForWaitingTasks(100))
            .thenReturn(new WorkerAutoProvisionService.AutoProvisionResult(
                3,
                1,
                1,
                0,
                1,
                1,
                List.of("WRK-AUTO-1"),
                List.of("TCK-CLARIFY-1")
            ));

        mockMvc.perform(post("/api/v0/workforce/auto-provision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"max_tasks\":100}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanned_waiting_tasks").value(3))
            .andExpect(jsonPath("$.created_workers").value(1))
            .andExpect(jsonPath("$.created_clarification_tickets").value(1))
            .andExpect(jsonPath("$.created_worker_ids[0]").value("WRK-AUTO-1"))
            .andExpect(jsonPath("$.created_clarification_ticket_ids[0]").value("TCK-CLARIFY-1"));
    }

    @Test
    void leaseRecoveryShouldReturnRecoveredCount() throws Exception {
        when(runLeaseRecoveryUseCase.recoverExpiredRuns(50)).thenReturn(2);

        mockMvc.perform(post("/api/v0/execution/lease-recovery")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"max_runs\":50}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recovered_runs").value(2));
    }

    @Test
    void runtimeAutoRunShouldReturnSummary() throws Exception {
        when(workerRuntimeAutoRunService.runOnce(12)).thenReturn(new WorkerRuntimeAutoRunService.AutoRunResult(
            10,
            4,
            2,
            1,
            1
        ));

        mockMvc.perform(post("/api/v0/workforce/runtime/auto-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"max_workers\":12}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanned_ready_workers").value(10))
            .andExpect(jsonPath("$.claimed_runs").value(4))
            .andExpect(jsonPath("$.succeeded_runs").value(2))
            .andExpect(jsonPath("$.need_input_runs").value(1))
            .andExpect(jsonPath("$.failed_runs").value(1));
    }

    @Test
    void workerCleanupShouldReturnSummary() throws Exception {
        when(workerPoolCleanupService.cleanupOnce(3)).thenReturn(
            new WorkerPoolCleanupService.CleanupResult(
                40,
                20,
                8,
                3,
                4,
                List.of("WRK-9", "WRK-10")
            )
        );

        mockMvc.perform(post("/api/v0/workforce/cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"max_disable\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total_workers").value(40))
            .andExpect(jsonPath("$.over_capacity_workers").value(8))
            .andExpect(jsonPath("$.disabled_workers").value(2))
            .andExpect(jsonPath("$.disabled_worker_ids[0]").value("WRK-9"));
    }

    @Test
    void listWorkersShouldReturnBackendTruth() throws Exception {
        when(workerCapabilityUseCase.countWorkers()).thenReturn(3);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.READY)).thenReturn(1);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING)).thenReturn(1);
        when(workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.DISABLED)).thenReturn(1);
        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 100))
            .thenReturn(List.of(new Worker(
                "WRK-READY-1",
                WorkerStatus.READY,
                Instant.parse("2026-02-24T00:00:00Z"),
                Instant.parse("2026-02-24T00:05:00Z")
            )));
        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.PROVISIONING, 100))
            .thenReturn(List.of(new Worker(
                "WRK-PROV-1",
                WorkerStatus.PROVISIONING,
                Instant.parse("2026-02-24T00:00:00Z"),
                Instant.parse("2026-02-24T00:02:00Z")
            )));
        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.DISABLED, 100))
            .thenReturn(List.of(new Worker(
                "WRK-DIS-1",
                WorkerStatus.DISABLED,
                Instant.parse("2026-02-24T00:00:00Z"),
                Instant.parse("2026-02-24T00:01:00Z")
            )));
        when(workerCapabilityUseCase.listToolpackIdsByWorker("WRK-READY-1")).thenReturn(List.of("TP-JAVA-21"));
        when(workerCapabilityUseCase.listToolpackIdsByWorker("WRK-PROV-1")).thenReturn(List.of("TP-MAVEN-3"));
        when(workerCapabilityUseCase.listToolpackIdsByWorker("WRK-DIS-1")).thenReturn(List.of());

        mockMvc.perform(post("/api/v0/workforce/workers?status=READY,PROVISIONING,DISABLED&limit=100"))
            .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v0/workforce/workers?status=READY,PROVISIONING,DISABLED&limit=100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total_workers").value(3))
            .andExpect(jsonPath("$.ready_workers").value(1))
            .andExpect(jsonPath("$.workers[0].worker_id").value("WRK-DIS-1"))
            .andExpect(jsonPath("$.workers[2].worker_id").value("WRK-READY-1"));
    }
}
