package com.agentx.platform.controlplane.application;

import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;

public record WorkflowCommandResult(
        String workflowRunId,
        WorkflowRunStatus workflowStatus,
        String requirementDocId,
        RequirementStatus requirementStatus,
        Integer currentRequirementVersion,
        Integer confirmedRequirementVersion,
        long pendingHumanTickets,
        long openGlobalBlockers,
        long openTaskBlockers,
        Map<String, Long> taskCounts
) {

    public static WorkflowCommandResult fromSnapshot(WorkflowRuntimeSnapshot snapshot) {
        RequirementDoc requirementDoc = snapshot.requirementDoc().orElse(null);
        long pendingHumanTickets = snapshot.tickets().stream()
                .filter(ticket -> ticket.assignee().type().name().equals("HUMAN"))
                .filter(ticket -> ticket.status() == TicketStatus.OPEN || ticket.status() == TicketStatus.CLAIMED)
                .count();
        long openGlobalBlockers = snapshot.tickets().stream()
                .filter(ticket -> ticket.blockingScope() == TicketBlockingScope.GLOBAL_BLOCKING)
                .filter(WorkflowCommandResult::isOpenTicket)
                .count();
        long openTaskBlockers = snapshot.tickets().stream()
                .filter(ticket -> ticket.blockingScope() == TicketBlockingScope.TASK_BLOCKING)
                .filter(WorkflowCommandResult::isOpenTicket)
                .count();

        Map<String, Long> taskCounts = new LinkedHashMap<>();
        for (WorkTask task : snapshot.tasks()) {
            taskCounts.merge(task.status().name(), 1L, Long::sum);
        }

        return new WorkflowCommandResult(
                snapshot.workflowRun().workflowRunId(),
                snapshot.workflowRun().status(),
                requirementDoc == null ? null : requirementDoc.docId(),
                requirementDoc == null ? null : requirementDoc.status(),
                requirementDoc == null ? null : requirementDoc.currentVersion(),
                requirementDoc == null ? null : requirementDoc.confirmedVersion(),
                pendingHumanTickets,
                openGlobalBlockers,
                openTaskBlockers,
                Map.copyOf(taskCounts)
        );
    }

    private static boolean isOpenTicket(Ticket ticket) {
        return ticket.status() != TicketStatus.RESOLVED && ticket.status() != TicketStatus.CANCELED;
    }
}
