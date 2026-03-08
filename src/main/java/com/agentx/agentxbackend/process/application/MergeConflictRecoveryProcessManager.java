package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.planning.application.port.in.PlanningCommandUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class MergeConflictRecoveryProcessManager {

    private static final Logger log = LoggerFactory.getLogger(MergeConflictRecoveryProcessManager.class);

    private static final String RECOVERY_TEMPLATE_ID = "tmpl.bugfix.v0";
    private static final String RECOVERY_RUN_KIND = "IMPL";
    private static final String RECOVERY_TRIGGER = "MERGE_CONFLICT_RECOVERY";
    private static final String SOURCE_REOPEN_TRIGGER = "MERGE_CONFLICT_REOPEN";

    private static final List<TaskStatus> OPEN_STATUSES = List.of(
        TaskStatus.PLANNED,
        TaskStatus.WAITING_DEPENDENCY,
        TaskStatus.WAITING_WORKER,
        TaskStatus.READY_FOR_ASSIGN,
        TaskStatus.ASSIGNED,
        TaskStatus.DELIVERED
    );

    private final TaskQueryUseCase taskQueryUseCase;
    private final PlanningCommandUseCase planningCommandUseCase;
    private final TaskStateMutationUseCase taskStateMutationUseCase;
    private final ContextCompileUseCase contextCompileUseCase;

    public MergeConflictRecoveryProcessManager(
        TaskQueryUseCase taskQueryUseCase,
        PlanningCommandUseCase planningCommandUseCase,
        TaskStateMutationUseCase taskStateMutationUseCase,
        ContextCompileUseCase contextCompileUseCase
    ) {
        this.taskQueryUseCase = taskQueryUseCase;
        this.planningCommandUseCase = planningCommandUseCase;
        this.taskStateMutationUseCase = taskStateMutationUseCase;
        this.contextCompileUseCase = contextCompileUseCase;
    }

    public void onMergeConflict(String sourceTaskId, String reason) {
        String normalizedTaskId = normalize(sourceTaskId);
        if (normalizedTaskId == null) {
            return;
        }
        Optional<WorkTask> sourceOptional = taskQueryUseCase.findTaskById(normalizedTaskId);
        if (sourceOptional.isEmpty()) {
            log.warn("Skip merge-conflict recovery because source task is missing, taskId={}", normalizedTaskId);
            return;
        }
        WorkTask sourceTask = sourceOptional.get();
        if (sourceTask.status() != TaskStatus.DELIVERED) {
            log.debug(
                "Skip merge-conflict recovery because source task is not DELIVERED, taskId={}, status={}",
                sourceTask.taskId(),
                sourceTask.status()
            );
            return;
        }

        String titlePrefix = buildRecoveryTitlePrefix(sourceTask.taskId());
        WorkTask recoveryTask = findOpenRecoveryTask(sourceTask.moduleId(), titlePrefix)
            .orElseGet(() -> createRecoveryTask(sourceTask, titlePrefix, reason));

        try {
            planningCommandUseCase.addTaskDependency(sourceTask.taskId(), recoveryTask.taskId(), TaskStatus.DONE.name());
            taskStateMutationUseCase.reopenDelivered(sourceTask.taskId());
            contextCompileUseCase.compileTaskContextPack(sourceTask.taskId(), RECOVERY_RUN_KIND, SOURCE_REOPEN_TRIGGER);
            log.info(
                "Created/reused merge-conflict recovery task and reopened source task, sourceTaskId={}, recoveryTaskId={}",
                sourceTask.taskId(),
                recoveryTask.taskId()
            );
        } catch (RuntimeException ex) {
            log.error(
                "Failed to apply merge-conflict recovery linkage, sourceTaskId={}, recoveryTaskId={}, reason={}",
                sourceTask.taskId(),
                recoveryTask.taskId(),
                ex.getMessage(),
                ex
            );
        }
    }

    private WorkTask createRecoveryTask(WorkTask sourceTask, String titlePrefix, String reason) {
        String title = titlePrefix + " (" + summarize(reason) + ")";
        WorkTask recoveryTask = planningCommandUseCase.createTask(
            sourceTask.moduleId(),
            title,
            RECOVERY_TEMPLATE_ID,
            sourceTask.requiredToolpacksJson(),
            List.of()
        );
        contextCompileUseCase.compileTaskContextPack(recoveryTask.taskId(), RECOVERY_RUN_KIND, RECOVERY_TRIGGER);
        return recoveryTask;
    }

    private Optional<WorkTask> findOpenRecoveryTask(String moduleId, String titlePrefix) {
        for (TaskStatus status : OPEN_STATUSES) {
            List<WorkTask> tasks = taskQueryUseCase.listTasksByStatus(status, 500);
            for (WorkTask task : tasks) {
                if (!moduleId.equals(task.moduleId())) {
                    continue;
                }
                if (task.taskTemplateId() != TaskTemplateId.TMPL_BUGFIX_V0) {
                    continue;
                }
                if (task.title() == null || !task.title().startsWith(titlePrefix)) {
                    continue;
                }
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    private static String buildRecoveryTitlePrefix(String sourceTaskId) {
        return "Resolve merge conflict for " + sourceTaskId;
    }

    private static String summarize(String reason) {
        String normalized = normalize(reason);
        if (normalized == null) {
            return "mergegate conflict";
        }
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
