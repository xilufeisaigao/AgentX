package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import com.agentx.agentxbackend.planning.domain.model.WorkTaskDependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MergeConflictRecoveryProcessManagerTest {

    @Mock
    private TaskQueryUseCase taskQueryUseCase;
    @Mock
    private PlanningCommandUseCase planningCommandUseCase;
    @Mock
    private TaskStateMutationUseCase taskStateMutationUseCase;
    @Mock
    private ContextCompileUseCase contextCompileUseCase;

    @Test
    void onMergeConflictShouldCreateRecoveryTaskAndReopenSourceTask() {
        MergeConflictRecoveryProcessManager manager = new MergeConflictRecoveryProcessManager(
            taskQueryUseCase,
            planningCommandUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase
        );
        WorkTask sourceTask = workTask(
            "TASK-1",
            "MOD-1",
            "original",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DELIVERED
        );
        WorkTask recoveryTask = workTask(
            "TASK-REC-1",
            "MOD-1",
            "Resolve merge conflict for TASK-1",
            TaskTemplateId.TMPL_BUGFIX_V0,
            TaskStatus.READY_FOR_ASSIGN
        );
        when(taskQueryUseCase.findTaskById("TASK-1")).thenReturn(Optional.of(sourceTask));
        for (TaskStatus status : List.of(
            TaskStatus.PLANNED,
            TaskStatus.WAITING_DEPENDENCY,
            TaskStatus.WAITING_WORKER,
            TaskStatus.READY_FOR_ASSIGN,
            TaskStatus.ASSIGNED,
            TaskStatus.DELIVERED
        )) {
            when(taskQueryUseCase.listTasksByStatus(status, 500)).thenReturn(List.of());
        }
        when(planningCommandUseCase.createTask(
            eq("MOD-1"),
            eq("Resolve merge conflict for TASK-1 (Git rebase failed for task/TASK-1)"),
            eq("tmpl.bugfix.v0"),
            eq("[\"TP-JAVA-21\"]"),
            eq(List.of())
        )).thenReturn(recoveryTask);
        when(planningCommandUseCase.addTaskDependency("TASK-1", "TASK-REC-1", "DONE"))
            .thenReturn(new WorkTaskDependency("TASK-1", "TASK-REC-1", TaskStatus.DONE, Instant.now()));

        manager.onMergeConflict("TASK-1", "Git rebase failed for task/TASK-1");

        verify(planningCommandUseCase).createTask(
            eq("MOD-1"),
            eq("Resolve merge conflict for TASK-1 (Git rebase failed for task/TASK-1)"),
            eq("tmpl.bugfix.v0"),
            eq("[\"TP-JAVA-21\"]"),
            eq(List.of())
        );
        verify(planningCommandUseCase).addTaskDependency("TASK-1", "TASK-REC-1", "DONE");
        verify(taskStateMutationUseCase).reopenDelivered("TASK-1");
        verify(contextCompileUseCase).compileTaskContextPack("TASK-REC-1", "IMPL", "MERGE_CONFLICT_RECOVERY");
        verify(contextCompileUseCase).compileTaskContextPack("TASK-1", "IMPL", "MERGE_CONFLICT_REOPEN");
    }

    @Test
    void onMergeConflictShouldSkipWhenSourceTaskNotDelivered() {
        MergeConflictRecoveryProcessManager manager = new MergeConflictRecoveryProcessManager(
            taskQueryUseCase,
            planningCommandUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase
        );
        when(taskQueryUseCase.findTaskById("TASK-2"))
            .thenReturn(Optional.of(workTask("TASK-2", "MOD-1", "original", TaskTemplateId.TMPL_IMPL_V0, TaskStatus.READY_FOR_ASSIGN)));

        manager.onMergeConflict("TASK-2", "Git rebase failed");

        verify(planningCommandUseCase, never()).createTask(any(), any(), any(), any(), any());
        verify(taskStateMutationUseCase, never()).reopenDelivered("TASK-2");
        verify(contextCompileUseCase, never()).compileTaskContextPack(any(), any(), any());
    }

    private static WorkTask workTask(
        String taskId,
        String moduleId,
        String title,
        TaskTemplateId templateId,
        TaskStatus status
    ) {
        Instant now = Instant.now();
        return new WorkTask(
            taskId,
            moduleId,
            title,
            templateId,
            status,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            now,
            now
        );
    }
}
