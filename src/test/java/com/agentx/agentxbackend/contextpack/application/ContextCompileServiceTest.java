package com.agentx.agentxbackend.contextpack.application;

import com.agentx.agentxbackend.contextpack.application.port.out.ArtifactStorePort;
import com.agentx.agentxbackend.contextpack.application.port.out.ContextFactsQueryPort;
import com.agentx.agentxbackend.contextpack.application.port.out.RepoContextQueryPort;
import com.agentx.agentxbackend.contextpack.application.port.out.TaskContextSnapshotRepository;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextPack;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshot;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatus;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatusView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextCompileServiceTest {

    @Mock
    private ContextFactsQueryPort factsQueryPort;
    @Mock
    private ArtifactStorePort artifactStorePort;
    @Mock
    private TaskContextSnapshotRepository snapshotRepository;
    @Mock
    private RepoContextQueryPort repoContextQueryPort;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(repoContextQueryPort.query(any()))
            .thenReturn(
                new RepoContextQueryPort.RepoContext(
                    "lexical_v1",
                    ".",
                    "git:HEAD_UNKNOWN",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
                )
            );
    }

    @Test
    void compileTaskContextPackShouldPersistSnapshotToReady() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        when(artifactStorePort.store(anyString(), anyString()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-1/IMPL/CTXS-1.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-1/IMPL/CTXS-1.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        TaskContextPack pack = service.compileTaskContextPack("TASK-1", "IMPL");

        assertNotNull(pack.snapshotId());
        assertEquals("TASK-1", pack.taskId());
        assertEquals("IMPL", pack.runKind());
        assertEquals("module:MOD-1", pack.moduleRef());
        verify(snapshotRepository).markReadyAsStale(eq("TASK-1"), eq("IMPL"), any());
        verify(snapshotRepository).markReady(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void compileTaskContextPackShouldDeduplicateRepeatedTicketSummariesAndEmbedRequirementHighlights() throws Exception {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        when(factsQueryPort.listRecentArchitectureTickets("SES-1", 20))
            .thenReturn(
                List.of(
                    new ContextFactsQueryPort.TicketFact(
                        "TCK-CL-2",
                        "CLARIFICATION",
                        "DONE",
                        "need impl details",
                        "REQ-1",
                        1
                    ),
                    new ContextFactsQueryPort.TicketFact(
                        "TCK-CL-1",
                        "CLARIFICATION",
                        "DONE",
                        "need impl details",
                        "REQ-1",
                        1
                    )
                )
            );
        List<ContextFactsQueryPort.TicketEventFact> repeatedQaEvents = List.of(
            new ContextFactsQueryPort.TicketEventFact(
                "TEV-1",
                "TCK-CL-1",
                "DECISION_REQUESTED",
                "architect_agent",
                "请提供学生CRUD接口的具体需求。",
                null,
                "2026-02-22T00:00:00Z"
            ),
            new ContextFactsQueryPort.TicketEventFact(
                "TEV-2",
                "TCK-CL-1",
                "USER_RESPONDED",
                "user",
                "请直接实现学生CRUD并包含基础校验。",
                null,
                "2026-02-22T00:01:00Z"
            )
        );
        when(factsQueryPort.listRecentTicketEvents("TCK-CL-1", 20)).thenReturn(repeatedQaEvents);
        when(factsQueryPort.listRecentTicketEvents("TCK-CL-2", 20)).thenReturn(repeatedQaEvents);
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        ArgumentCaptor<String> storedPathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> storedPayloadCaptor = ArgumentCaptor.forClass(String.class);
        when(artifactStorePort.store(storedPathCaptor.capture(), storedPayloadCaptor.capture()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-1/IMPL/CTXS-2.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-1/IMPL/CTXS-2.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.compileTaskContextPack("TASK-1", "IMPL");

        assertEquals(2, storedPayloadCaptor.getAllValues().size());
        JsonNode contextPackJson = new ObjectMapper().readTree(storedPayloadCaptor.getAllValues().get(0));
        long summaryCount = 0;
        for (JsonNode decisionRef : contextPackJson.path("decisionRefs")) {
            if (decisionRef.asText("").startsWith("ticket-summary:")) {
                summaryCount++;
            }
        }
        assertEquals(1, summaryCount);

        String taskSkillMarkdown = storedPayloadCaptor.getAllValues().get(1);
        assertTrue(taskSkillMarkdown.contains("task_title:implement order api"));
        assertTrue(taskSkillMarkdown.contains("requirement_title:Order Center MVP"));
        assertTrue(taskSkillMarkdown.contains("requirement_scope:groupId=com.example.helloapi must stay fixed."));
        assertTrue(taskSkillMarkdown.contains("requirement_scope_out:Do not introduce database integration."));
        assertTrue(taskSkillMarkdown.contains("requirement_goal:"));
        assertTrue(taskSkillMarkdown.contains("requirement_constraint:Keep p95 latency under 300ms."));
        assertTrue(taskSkillMarkdown.contains("requirement_acceptance:"));
        assertTrue(taskSkillMarkdown.contains("content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)"));
        assertTrue(taskSkillMarkdown.contains("@RequestParam(\"name\")"));
    }

    @Test
    void compileTaskContextPackShouldEmbedPriorRunFailureEvidence() throws Exception {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        when(factsQueryPort.listRecentTaskRuns("TASK-1", 8))
            .thenReturn(
                List.of(
                    new ContextFactsQueryPort.RunFact(
                        "RUN-VERIFY-1",
                        "FAILED",
                        "VERIFY",
                        "CTXS-VERIFY-1",
                        "file:.agentx/verify-skill.md",
                        "abc123def456",
                        "2026-02-22T00:05:00Z",
                        "RUN_FINISHED",
                        "Run finished with status FAILED",
                        """
                        {
                          "result_status":"FAILED",
                          "work_report":"VERIFY command failed: mvn -q test, reason=Command failed (exit 1): bash -lc mvn -q test, output=Caused by: java.lang.IllegalArgumentException: Name for argument of type [java.lang.String] not specified, and parameter name information not found in class file either. at org.springframework.web.method.annotation.AbstractNamedValueMethodArgumentResolver.updateNamedValueInfo(AbstractNamedValueMethodArgumentResolver.java:183)",
                          "delivery_commit":"git:abc123def456"
                        }
                        """
                    )
                )
            );
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        ArgumentCaptor<String> storedPayloadCaptor = ArgumentCaptor.forClass(String.class);
        when(artifactStorePort.store(anyString(), storedPayloadCaptor.capture()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-1/IMPL/CTXS-3.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-1/IMPL/CTXS-3.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.compileTaskContextPack("TASK-1", "IMPL");

        JsonNode contextPackJson = new ObjectMapper().readTree(storedPayloadCaptor.getAllValues().get(0));
        String priorRunRef = contextPackJson.path("priorRunRefs").get(0).asText();
        assertTrue(priorRunRef.contains("RUN-VERIFY-1"));
        assertTrue(priorRunRef.contains("FAILED"));
        assertTrue(priorRunRef.contains("VERIFY"));
        assertTrue(priorRunRef.contains("RUN_FINISHED"));
        assertTrue(priorRunRef.contains("java.lang.IllegalArgumentException"));
        assertTrue(priorRunRef.contains("parameter name information not found"));
        assertTrue(priorRunRef.contains("delivery_commit=abc123def456"));
    }

    @Test
    void compileTaskContextPackShouldEmitBootstrapOnlyGuidanceForInitTasks() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        when(factsQueryPort.findTaskPlanningByTaskId("TASK-INIT"))
            .thenReturn(
                Optional.of(
                    new ContextFactsQueryPort.TaskPlanningFact(
                        "TASK-INIT",
                        "MOD-1",
                        "bootstrap",
                        "SES-1",
                        "initialize baseline",
                        "tmpl.init.v0",
                        "[\"TP-JAVA-21\",\"TP-MAVEN-3\",\"TP-GIT-2\"]"
                    )
                )
            );
        when(factsQueryPort.findRequirementBaselineBySessionId("SES-1"))
            .thenReturn(
                Optional.of(
                    new ContextFactsQueryPort.RequirementBaselineFact(
                        "REQ-1",
                        1,
                        "Boot App",
                        "CONFIRMED",
                        """
                        ## 1. Summary
                        Bootstrap a service repository.
                        ## 2. Goals
                        - Establish the baseline scaffold.
                        ## 5. Acceptance Criteria
                        - The project compiles.
                        """
                    )
                )
            );
        when(factsQueryPort.listRecentArchitectureTickets("SES-1", 20)).thenReturn(List.of());
        when(factsQueryPort.listRecentTaskRuns("TASK-INIT", 8)).thenReturn(List.of());
        when(factsQueryPort.listToolpacksByIds(List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2")))
            .thenReturn(List.of());
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        ArgumentCaptor<String> storedPayloadCaptor = ArgumentCaptor.forClass(String.class);
        when(artifactStorePort.store(anyString(), storedPayloadCaptor.capture()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-INIT/IMPL/CTXS-INIT.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-INIT/IMPL/CTXS-INIT.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.compileTaskContextPack("TASK-INIT", "IMPL");

        String taskSkillMarkdown = storedPayloadCaptor.getAllValues().get(1);
        assertTrue(taskSkillMarkdown.contains("init_scope:bootstrap scaffold only"));
        assertTrue(taskSkillMarkdown.contains("scaffold must use that exact stack"));
        assertTrue(taskSkillMarkdown.contains("Do not ask for permission to adopt Spring Boot"));
        assertTrue(taskSkillMarkdown.contains("Do not implement business endpoints"));
        assertTrue(taskSkillMarkdown.contains("Scaffold matches the confirmed framework/runtime/build stack"));
        assertTrue(taskSkillMarkdown.contains("No feature-specific business endpoints or acceptance tests added during init."));
    }

    @Test
    void compileTaskContextPackShouldUseValidateForInitVerifyCommands() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        when(factsQueryPort.findTaskPlanningByTaskId("TASK-INIT-VERIFY"))
            .thenReturn(
                Optional.of(
                    new ContextFactsQueryPort.TaskPlanningFact(
                        "TASK-INIT-VERIFY",
                        "MOD-1",
                        "bootstrap",
                        "SES-1",
                        "initialize baseline",
                        "tmpl.init.v0",
                        "[\"TP-JAVA-21\",\"TP-MAVEN-3\",\"TP-GIT-2\"]"
                    )
                )
            );
        when(factsQueryPort.findRequirementBaselineBySessionId("SES-1"))
            .thenReturn(
                Optional.of(
                    new ContextFactsQueryPort.RequirementBaselineFact(
                        "REQ-1",
                        1,
                        "Boot App",
                        "CONFIRMED",
                        "## 1. Summary\nBootstrap a service repository."
                    )
                )
            );
        when(factsQueryPort.listRecentArchitectureTickets("SES-1", 20)).thenReturn(List.of());
        when(factsQueryPort.listRecentTaskRuns("TASK-INIT-VERIFY", 8)).thenReturn(List.of());
        when(factsQueryPort.listToolpacksByIds(List.of("TP-JAVA-21", "TP-MAVEN-3", "TP-GIT-2")))
            .thenReturn(List.of());
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        ArgumentCaptor<String> storedPayloadCaptor = ArgumentCaptor.forClass(String.class);
        when(artifactStorePort.store(anyString(), storedPayloadCaptor.capture()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-INIT-VERIFY/VERIFY/CTXS-INIT-VERIFY.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-INIT-VERIFY/VERIFY/CTXS-INIT-VERIFY.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.compileTaskContextPack("TASK-INIT-VERIFY", "VERIFY");

        String taskSkillMarkdown = storedPayloadCaptor.getAllValues().get(1);
        assertTrue(taskSkillMarkdown.contains("mvn -q -DskipTests validate"));
    }

    @Test
    void compileTaskContextPackShouldUseMavenTestForFeatureVerifyCommands() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        ArgumentCaptor<String> storedPayloadCaptor = ArgumentCaptor.forClass(String.class);
        when(artifactStorePort.store(anyString(), storedPayloadCaptor.capture()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-1/VERIFY/CTXS-VERIFY.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-1/VERIFY/CTXS-VERIFY.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        service.compileTaskContextPack("TASK-1", "VERIFY");

        String taskSkillMarkdown = storedPayloadCaptor.getAllValues().get(1);
        assertTrue(taskSkillMarkdown.contains("mvn -q test"));
        assertFalse(taskSkillMarkdown.contains("-Dtest=* verify"));
    }

    @Test
    void compileTaskContextPackShouldReuseExistingReadySnapshot() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        TaskContextSnapshot existing = new TaskContextSnapshot(
            "CTXS-EXISTING",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.READY,
            "MANUAL_REFRESH",
            "sha256:abc",
            "file:ctx",
            "file:skill",
            null,
            null,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.of(existing));

        TaskContextPack pack = service.compileTaskContextPack("TASK-1", "IMPL");

        assertEquals("CTXS-EXISTING", pack.snapshotId());
        verify(snapshotRepository, never()).save(any());
        verify(artifactStorePort, never()).store(anyString(), anyString());
    }

    @Test
    void compileTaskContextPackShouldMarkFailedWhenArtifactStoreThrows() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        when(artifactStorePort.store(anyString(), anyString()))
            .thenThrow(new IllegalStateException("disk full"));
        when(snapshotRepository.markFailed(anyString(), anyString(), anyString(), any())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.compileTaskContextPack("TASK-1", "IMPL"));

        verify(snapshotRepository).markFailed(anyString(), eq("COMPILE_ERROR"), anyString(), any());
    }

    @Test
    void getTaskContextStatusShouldReturnLatestSnapshot() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        TaskContextSnapshot latest = new TaskContextSnapshot(
            "CTXS-1",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.READY,
            "MANUAL_REFRESH",
            "sha256:1",
            "file:ctx",
            "file:skill",
            null,
            null,
            Instant.parse("2026-02-22T01:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-22T00:59:00Z"),
            Instant.parse("2026-02-22T01:00:00Z")
        );
        TaskContextSnapshot older = new TaskContextSnapshot(
            "CTXS-0",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.STALE,
            "MANUAL_REFRESH",
            "sha256:0",
            "file:ctx0",
            "file:skill0",
            null,
            null,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-21T23:59:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(snapshotRepository.findLatestByTaskId("TASK-1", 10)).thenReturn(List.of(latest, older));

        TaskContextSnapshotStatusView statusView = service.getTaskContextStatus("TASK-1", 10);

        assertEquals("TASK-1", statusView.taskId());
        assertEquals("CTXS-1", statusView.latest().snapshotId());
        assertEquals(2, statusView.snapshots().size());
    }

    @Test
    void refreshTaskContextsByTicketShouldReturnZeroWhenTicketMissing() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        when(factsQueryPort.findTicketSessionByTicketId("TCK-MISSING")).thenReturn(Optional.empty());

        int refreshed = service.refreshTaskContextsByTicket("TCK-MISSING", "TICKET_DONE", 128);

        assertEquals(0, refreshed);
    }

    @Test
    void refreshTaskContextByTaskShouldReturnFalseWhenSnapshotReused() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        TaskContextSnapshot existing = new TaskContextSnapshot(
            "CTXS-EXISTING",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.READY,
            "TICKET_DONE",
            "sha256:abc",
            "file:ctx",
            "file:skill",
            null,
            null,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(snapshotRepository.findLatestByTaskAndRunKind("TASK-1", "IMPL"))
            .thenReturn(Optional.of(existing));
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.of(existing));

        boolean refreshed = service.refreshTaskContextByTask("TASK-1", "RUN_FINISHED");

        assertFalse(refreshed);
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void refreshTaskContextByTaskShouldReturnTrueWhenNewSnapshotCreated() {
        ContextCompileService service = new ContextCompileService(
            factsQueryPort,
            artifactStorePort,
            snapshotRepository,
            repoContextQueryPort,
            new ObjectMapper(),
            180,
            20,
            8
        );
        stubTaskFacts();
        TaskContextSnapshot existing = new TaskContextSnapshot(
            "CTXS-OLD",
            "TASK-1",
            "IMPL",
            TaskContextSnapshotStatus.READY,
            "MANUAL_REFRESH",
            "sha256:old",
            "file:ctx-old",
            "file:skill-old",
            null,
            null,
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-08-21T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
        when(snapshotRepository.findLatestByTaskAndRunKind("TASK-1", "IMPL"))
            .thenReturn(Optional.of(existing));
        when(snapshotRepository.findLatestReadyByFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotRepository.transitionStatus(anyString(), eq(TaskContextSnapshotStatus.PENDING), eq(TaskContextSnapshotStatus.COMPILING), any()))
            .thenReturn(true);
        when(artifactStorePort.store(anyString(), anyString()))
            .thenReturn("file:.agentx/context/task-context-packs/TASK-1/IMPL/CTXS-NEW.json")
            .thenReturn("file:.agentx/context/task-skills/TASK-1/IMPL/CTXS-NEW.md");
        when(snapshotRepository.markReady(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);

        boolean refreshed = service.refreshTaskContextByTask("TASK-1", "RUN_FINISHED");

        assertTrue(refreshed);
        verify(snapshotRepository).markReadyAsStale(eq("TASK-1"), eq("IMPL"), any());
    }

    private void stubTaskFacts() {
        when(factsQueryPort.findTaskPlanningByTaskId("TASK-1"))
            .thenReturn(
                Optional.of(
                    new ContextFactsQueryPort.TaskPlanningFact(
                        "TASK-1",
                        "MOD-1",
                        "order-core",
                        "SES-1",
                        "implement order api",
                        "tmpl.impl.v0",
                        "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"
                    )
                )
            );
        when(factsQueryPort.findRequirementBaselineBySessionId("SES-1"))
            .thenReturn(
                Optional.of(
                    new ContextFactsQueryPort.RequirementBaselineFact(
                        "REQ-1",
                        1,
                        "Order Center MVP",
                        "CONFIRMED",
                        """
                        ## 1. Summary
                        Build order-center minimal backend for pilot rollout.
                        ## 2. Goals
                        - Provide order create/query APIs.
                        - Ensure deterministic validation behavior.
                        ## 4. Scope
                        ### In
                        - groupId=com.example.helloapi must stay fixed.
                        - package name com.example.helloapi must stay fixed.
                        ### Out
                        - Do not introduce database integration.
                        ## 5. Acceptance Criteria
                        - At least one end-to-end order flow passes tests.
                        ## 6. Value Constraints
                        - Keep p95 latency under 300ms.
                        - Preserve audit logs for all order state changes.
                        """
                    )
                )
            );
        when(factsQueryPort.listRecentArchitectureTickets("SES-1", 20))
            .thenReturn(
                List.of(
                    new ContextFactsQueryPort.TicketFact(
                        "TCK-1",
                        "ARCH_REVIEW",
                        "DONE",
                        "review",
                        "REQ-1",
                        1
                    )
                )
            );
        when(factsQueryPort.listRecentTaskRuns("TASK-1", 8))
            .thenReturn(
                List.of(
                    new ContextFactsQueryPort.RunFact(
                        "RUN-1",
                        "SUCCEEDED",
                        "IMPL",
                        "CTXS-OLD",
                        "file:.agentx/old-skill.md",
                        "abc123",
                        "2026-02-22T00:00:00Z",
                        "RUN_FINISHED",
                        "Run finished with status SUCCEEDED",
                        """
                        {
                          "result_status":"SUCCEEDED",
                          "work_report":"Implemented baseline order API.",
                          "delivery_commit":"git:abc123"
                        }
                        """
                    )
                )
            );
        when(factsQueryPort.listToolpacksByIds(List.of("TP-JAVA-21", "TP-MAVEN-3")))
            .thenReturn(
                List.of(
                    new ContextFactsQueryPort.ToolpackFact(
                        "TP-JAVA-21",
                        "java",
                        "21",
                        "runtime",
                        "JDK 21"
                    ),
                    new ContextFactsQueryPort.ToolpackFact(
                        "TP-MAVEN-3",
                        "maven",
                        "3.9",
                        "build",
                        "Maven 3"
                    )
                )
            );
    }
}
