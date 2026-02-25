package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.application.port.in.WorkerRunStatsUseCase;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerPoolCleanupServiceTest {

    @Mock
    private WorkerCapabilityUseCase workerCapabilityUseCase;
    @Mock
    private WorkerRunStatsUseCase workerRunStatsUseCase;

    private WorkerPoolCleanupService service;

    @BeforeEach
    void setUp() {
        service = new WorkerPoolCleanupService(
            workerCapabilityUseCase,
            workerRunStatsUseCase,
            3,
            0,
            2,
            128,
            "oldest_idle"
        );
    }

    @Test
    void cleanupShouldNotDisableActiveWorkers() {
        Instant now = Instant.now();
        Worker w1 = new Worker("WRK-1", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));
        Worker w2 = new Worker("WRK-2", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));
        Worker w3 = new Worker("WRK-3", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));
        Worker w4 = new Worker("WRK-4", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));

        when(workerCapabilityUseCase.countWorkers()).thenReturn(4);
        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 128)).thenReturn(List.of(w1, w2, w3, w4));
        when(workerRunStatsUseCase.listActiveWorkerIds(512)).thenReturn(Set.of("WRK-1"));
        when(workerRunStatsUseCase.findWorkerRunStats("WRK-2")).thenReturn(Optional.empty());
        when(workerRunStatsUseCase.findWorkerRunStats("WRK-3")).thenReturn(Optional.empty());
        when(workerRunStatsUseCase.findWorkerRunStats("WRK-4")).thenReturn(Optional.empty());

        WorkerPoolCleanupService.CleanupResult result = service.cleanupOnce(2);

        assertEquals(1, result.overCapacityWorkers());
        assertTrue(result.disabledWorkerIds().size() <= 2);
        verify(workerCapabilityUseCase, never()).updateWorkerStatus("WRK-1", WorkerStatus.DISABLED);
    }

    @Test
    void cleanupShouldDisableLeastUsedFirstWhenConfigured() {
        WorkerPoolCleanupService leastUsedService = new WorkerPoolCleanupService(
            workerCapabilityUseCase,
            workerRunStatsUseCase,
            2,
            0,
            2,
            128,
            "least_used"
        );
        Instant now = Instant.now();
        Worker w1 = new Worker("WRK-A", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));
        Worker w2 = new Worker("WRK-B", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));
        Worker w3 = new Worker("WRK-C", WorkerStatus.READY, now.minusSeconds(1000), now.minusSeconds(1000));

        when(workerCapabilityUseCase.countWorkers()).thenReturn(3);
        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 128)).thenReturn(List.of(w1, w2, w3));
        when(workerRunStatsUseCase.listActiveWorkerIds(512)).thenReturn(Set.of());
        when(workerRunStatsUseCase.findWorkerRunStats("WRK-A"))
            .thenReturn(Optional.of(new WorkerRunStatsUseCase.WorkerRunStats("WRK-A", now.minusSeconds(100), 10)));
        when(workerRunStatsUseCase.findWorkerRunStats("WRK-B"))
            .thenReturn(Optional.of(new WorkerRunStatsUseCase.WorkerRunStats("WRK-B", now.minusSeconds(100), 1)));
        when(workerRunStatsUseCase.findWorkerRunStats("WRK-C"))
            .thenReturn(Optional.of(new WorkerRunStatsUseCase.WorkerRunStats("WRK-C", now.minusSeconds(100), 5)));

        WorkerPoolCleanupService.CleanupResult result = leastUsedService.cleanupOnce(2);

        assertEquals(1, result.overCapacityWorkers());
        verify(workerCapabilityUseCase).updateWorkerStatus("WRK-B", WorkerStatus.DISABLED);
    }
}
