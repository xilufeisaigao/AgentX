package com.agentx.agentxbackend.workforce.application;

import com.agentx.agentxbackend.workforce.application.port.out.ToolpackRepository;
import com.agentx.agentxbackend.workforce.application.port.out.WorkerRepository;
import com.agentx.agentxbackend.workforce.application.port.out.WorkerToolpackRepository;
import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerCapabilityServiceTest {

    @Mock
    private WorkerRepository workerRepository;
    @Mock
    private ToolpackRepository toolpackRepository;
    @Mock
    private WorkerToolpackRepository workerToolpackRepository;
    @InjectMocks
    private WorkerCapabilityService service;

    @Test
    void registerToolpackShouldPersistWhenAbsent() {
        when(toolpackRepository.findById("TP-JAVA-21")).thenReturn(Optional.empty());
        when(toolpackRepository.findByNameAndVersion("java", "21")).thenReturn(Optional.empty());
        when(toolpackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Toolpack toolpack = service.registerToolpack(
            "TP-JAVA-21",
            "java",
            "21",
            "language",
            "Java runtime"
        );

        assertEquals("TP-JAVA-21", toolpack.toolpackId());
        assertEquals("java", toolpack.name());
        assertEquals("21", toolpack.version());
        assertEquals("language", toolpack.kind());
        verify(toolpackRepository).save(any());
    }

    @Test
    void registerToolpackShouldReturnExistingById() {
        Toolpack existing = new Toolpack(
            "TP-MAVEN-3",
            "maven",
            "3.x",
            "build",
            "Maven",
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(toolpackRepository.findById("TP-MAVEN-3")).thenReturn(Optional.of(existing));

        Toolpack result = service.registerToolpack(
            "TP-MAVEN-3",
            "maven",
            "3.x",
            "build",
            "Maven"
        );

        assertEquals("TP-MAVEN-3", result.toolpackId());
        verify(toolpackRepository, never()).save(any());
    }

    @Test
    void registerToolpackShouldRejectUnsupportedKind() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.registerToolpack("TP-X", "x", "1", "container", "x")
        );
    }

    @Test
    void registerWorkerShouldCreateProvisioningWorker() {
        when(workerRepository.findById("WRK-1")).thenReturn(Optional.empty());
        when(workerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Worker worker = service.registerWorker("WRK-1");

        assertEquals("WRK-1", worker.workerId());
        assertEquals(WorkerStatus.PROVISIONING, worker.status());
        assertNotNull(worker.createdAt());
        assertNotNull(worker.updatedAt());
        verify(workerRepository).save(any());
    }

    @Test
    void updateWorkerStatusShouldAllowProvisioningToReady() {
        Worker current = new Worker(
            "WRK-2",
            WorkerStatus.PROVISIONING,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        Worker updated = new Worker(
            "WRK-2",
            WorkerStatus.READY,
            current.createdAt(),
            Instant.parse("2026-02-22T00:01:00Z")
        );
        when(workerRepository.findById("WRK-2")).thenReturn(Optional.of(current));
        when(workerRepository.updateStatus(eq("WRK-2"), eq(WorkerStatus.READY), any())).thenReturn(updated);

        Worker result = service.updateWorkerStatus("WRK-2", WorkerStatus.READY);

        assertEquals(WorkerStatus.READY, result.status());
        verify(workerRepository).updateStatus(eq("WRK-2"), eq(WorkerStatus.READY), any());
    }

    @Test
    void updateWorkerStatusShouldRejectReadyToProvisioning() {
        Worker current = new Worker(
            "WRK-3",
            WorkerStatus.READY,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workerRepository.findById("WRK-3")).thenReturn(Optional.of(current));

        assertThrows(
            IllegalStateException.class,
            () -> service.updateWorkerStatus("WRK-3", WorkerStatus.PROVISIONING)
        );
    }

    @Test
    void bindToolpacksShouldValidateWorkerAndToolpacks() {
        Worker worker = new Worker(
            "WRK-4",
            WorkerStatus.PROVISIONING,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workerRepository.findById("WRK-4")).thenReturn(Optional.of(worker));
        when(toolpackRepository.findById("TP-JAVA-21"))
            .thenReturn(Optional.of(sampleToolpack("TP-JAVA-21", "java", "21", "language")));
        when(toolpackRepository.findById("TP-MAVEN-3"))
            .thenReturn(Optional.of(sampleToolpack("TP-MAVEN-3", "maven", "3.x", "build")));

        service.bindToolpacks("WRK-4", List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-JAVA-21"));

        ArgumentCaptor<String> toolpackCaptor = ArgumentCaptor.forClass(String.class);
        verify(workerToolpackRepository, org.mockito.Mockito.times(2))
            .bind(eq("WRK-4"), toolpackCaptor.capture());
        assertEquals(List.of("TP-JAVA-21", "TP-MAVEN-3"), toolpackCaptor.getAllValues());
    }

    @Test
    void bindToolpacksShouldRejectMissingToolpack() {
        Worker worker = new Worker(
            "WRK-5",
            WorkerStatus.PROVISIONING,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(workerRepository.findById("WRK-5")).thenReturn(Optional.of(worker));
        when(toolpackRepository.findById("TP-MISSING")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.bindToolpacks("WRK-5", List.of("TP-MISSING")));
    }

    @Test
    void hasEligibleWorkerShouldUseCoverageQueryForJavaBackendScenario() {
        List<String> required = List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2");
        when(workerToolpackRepository.existsReadyWorkerCoveringAll(required)).thenReturn(true);

        boolean eligible = service.hasEligibleWorker(required);

        assertTrue(eligible);
        verify(workerToolpackRepository).existsReadyWorkerCoveringAll(required);
    }

    @Test
    void hasEligibleWorkerWithEmptyRequirementShouldFallbackToReadyWorkerExists() {
        when(workerRepository.existsByStatus(WorkerStatus.READY)).thenReturn(true);

        boolean eligible = service.hasEligibleWorker(List.of());

        assertTrue(eligible);
        verify(workerRepository).existsByStatus(WorkerStatus.READY);
    }

    @Test
    void listToolpacksByWorkerShouldRejectUnknownWorker() {
        when(workerRepository.findById("WRK-404")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.listToolpacksByWorker("WRK-404"));
    }

    private Toolpack sampleToolpack(String id, String name, String version, String kind) {
        return new Toolpack(
            id,
            name,
            version,
            kind,
            name,
            Instant.parse("2026-02-22T00:00:00Z")
        );
    }
}

