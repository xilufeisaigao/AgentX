package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifyFailureRecoveryProcessManagerTest {

    @Mock
    private TaskQueryUseCase taskQueryUseCase;

    @Mock
    private PlanningCommandUseCase planningCommandUseCase;

    @Mock
    private ContextCompileUseCase contextCompileUseCase;

    @Test
    void shouldCreateRecoveryTaskAndCompileContextWhenVerifyFailed() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            taskQueryUseCase,
            planningCommandUseCase,
            contextCompileUseCase
        );
        WorkTask sourceTask = workTask(
            "TASK-1",
            "MOD-1",
            "Deliver feature",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DELIVERED,
            "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"
        );
        WorkTask recoveryTask = workTask(
            "TASK-REC-1",
            "MOD-1",
            "Repair verify failure",
            TaskTemplateId.TMPL_BUGFIX_V0,
            TaskStatus.READY_FOR_ASSIGN,
            "[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-1",
            "TASK-1",
            RunKind.VERIFY,
            "abc123456789",
            new RunFinishedPayload("FAILED", "verify failed", null, null)
        );

        when(taskQueryUseCase.findTaskById("TASK-1")).thenReturn(Optional.of(sourceTask));
        when(planningCommandUseCase.createTask(
            eq("MOD-1"),
            startsWith("Repair verify failure for TASK-1"),
            eq("tmpl.bugfix.v0"),
            eq("[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"),
            eq(java.util.List.of())
        )).thenReturn(recoveryTask);

        manager.onVerifyFailed(event);

        verify(planningCommandUseCase).createTask(
            eq("MOD-1"),
            startsWith("Repair verify failure for TASK-1"),
            eq("tmpl.bugfix.v0"),
            eq("[\"TP-JAVA-21\",\"TP-MAVEN-3\"]"),
            eq(java.util.List.of())
        );
        verify(contextCompileUseCase).compileTaskContextPack("TASK-REC-1", "IMPL", "VERIFY_FAILED_RECOVERY");
    }

    @Test
    void shouldIgnoreNonFailedOrNonVerifyEvents() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            taskQueryUseCase,
            planningCommandUseCase,
            contextCompileUseCase
        );
        RunFinishedEvent verifySucceeded = new RunFinishedEvent(
            "RUN-V-2",
            "TASK-2",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("SUCCEEDED", "ok", null, null)
        );
        RunFinishedEvent implFailed = new RunFinishedEvent(
            "RUN-I-1",
            "TASK-3",
            RunKind.IMPL,
            "def456",
            new RunFinishedPayload("FAILED", "bad", null, null)
        );

        manager.onVerifyFailed(verifySucceeded);
        manager.onVerifyFailed(implFailed);

        verifyNoInteractions(taskQueryUseCase);
        verifyNoInteractions(planningCommandUseCase);
        verifyNoInteractions(contextCompileUseCase);
    }

    @Test
    void shouldSkipWhenSourceTaskMissing() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            taskQueryUseCase,
            planningCommandUseCase,
            contextCompileUseCase
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-3",
            "TASK-404",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("FAILED", "bad", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-404")).thenReturn(Optional.empty());

        manager.onVerifyFailed(event);

        verify(taskQueryUseCase).findTaskById("TASK-404");
        verify(planningCommandUseCase, never()).createTask(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList()
        );
        verifyNoInteractions(contextCompileUseCase);
    }

    private static WorkTask workTask(
        String taskId,
        String moduleId,
        String title,
        TaskTemplateId templateId,
        TaskStatus status,
        String requiredToolpacksJson
    ) {
        Instant now = Instant.now();
        return new WorkTask(
            taskId,
            moduleId,
            title,
            templateId,
            status,
            requiredToolpacksJson,
            null,
            "architect_agent",
            now,
            now
        );
    }
}
