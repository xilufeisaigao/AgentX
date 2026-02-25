package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunInternalUseCase;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

@Component
public class ContextRefreshProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ContextRefreshProcessManager.class);
    private static final Set<TicketType> ARCHITECT_TICKET_TYPES = Set.of(
        TicketType.ARCH_REVIEW,
        TicketType.HANDOFF,
        TicketType.DECISION,
        TicketType.CLARIFICATION
    );

    private final ContextCompileUseCase contextCompileUseCase;
    private final TicketQueryUseCase ticketQueryUseCase;
    private final TicketCommandUseCase ticketCommandUseCase;
    private final RunInternalUseCase runInternalUseCase;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int maxSessionTasksPerRefresh;
    private final int maxTicketTasksPerRefresh;

    public ContextRefreshProcessManager(
        ContextCompileUseCase contextCompileUseCase,
        TicketQueryUseCase ticketQueryUseCase,
        TicketCommandUseCase ticketCommandUseCase,
        RunInternalUseCase runInternalUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.contextpack.refresh.enabled:true}") boolean enabled,
        @Value("${agentx.contextpack.refresh.max-session-tasks-per-refresh:512}") int maxSessionTasksPerRefresh,
        @Value("${agentx.contextpack.refresh.max-ticket-tasks-per-refresh:512}") int maxTicketTasksPerRefresh
    ) {
        this.contextCompileUseCase = contextCompileUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
        this.ticketCommandUseCase = ticketCommandUseCase;
        this.runInternalUseCase = runInternalUseCase;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxSessionTasksPerRefresh = Math.max(1, maxSessionTasksPerRefresh);
        this.maxTicketTasksPerRefresh = Math.max(1, maxTicketTasksPerRefresh);
    }

    public void handleRequirementConfirmed(RequirementConfirmedEvent event) {
        if (!enabled || event == null || isBlank(event.sessionId())) {
            return;
        }
        try {
            int refreshed = contextCompileUseCase.refreshTaskContextsBySession(
                event.sessionId(),
                "REQUIREMENT_CONFIRMED",
                maxSessionTasksPerRefresh
            );
            if (refreshed > 0) {
                log.info(
                    "Context refresh after requirement confirmed, sessionId={}, refreshedTasks={}",
                    event.sessionId(),
                    refreshed
                );
            }
        } catch (Exception ex) {
            log.error(
                "Context refresh failed after requirement confirmed, sessionId={}",
                event.sessionId(),
                ex
            );
        }
    }

    public void handleTicketEvent(TicketEventAppendedEvent event) {
        if (!enabled || event == null || isBlank(event.ticketId())) {
            return;
        }
        if (!isArchitectTicket(event)) {
            return;
        }
        if (!isRefreshTriggerTicketEvent(event)) {
            return;
        }
        try {
            int refreshed = contextCompileUseCase.refreshTaskContextsByTicket(
                event.ticketId(),
                "TICKET_DONE",
                maxTicketTasksPerRefresh
            );
            if (event.eventType() == TicketEventType.USER_RESPONDED) {
                recoverRunNeedInputAfterUserResponse(event.ticketId());
            }
            if (refreshed > 0) {
                log.info(
                    "Context refresh after ticket event, ticketId={}, eventType={}, refreshedTasks={}",
                    event.ticketId(),
                    event.eventType(),
                    refreshed
                );
            }
        } catch (Exception ex) {
            log.error(
                "Context refresh failed after ticket event, ticketId={}, eventType={}",
                event.ticketId(),
                event.eventType(),
                ex
            );
        }
    }

    public void handleRunFinished(RunFinishedEvent event) {
        if (!enabled || event == null || isBlank(event.taskId())) {
            return;
        }
        try {
            boolean refreshed = contextCompileUseCase.refreshTaskContextByTask(
                event.taskId(),
                "RUN_FINISHED"
            );
            // VERIFY merge gate requires a READY VERIFY snapshot for the same task.
            contextCompileUseCase.compileTaskContextPack(
                event.taskId(),
                "VERIFY",
                "RUN_FINISHED"
            );
            if (refreshed) {
                log.info("Context refresh after run finished, taskId={}, runId={}", event.taskId(), event.runId());
            }
        } catch (Exception ex) {
            log.error("Context refresh failed after run finished, taskId={}, runId={}", event.taskId(), event.runId(), ex);
        }
    }

    private void recoverRunNeedInputAfterUserResponse(String ticketId) {
        try {
            Ticket ticket = ticketQueryUseCase.findById(ticketId);
            RunNeedInputRef ref = parseRunNeedInputRef(ticket.payloadJson());
            if (ref == null || isBlank(ref.runId()) || isBlank(ref.taskId())) {
                return;
            }
            runInternalUseCase.failWaitingRunForUserResponse(
                ref.runId(),
                "User responded on ticket " + ticket.ticketId() + "; run is superseded for refreshed context dispatch."
            );
            closeRunNeedInputTicket(ticket, ref);
        } catch (Exception ex) {
            log.warn("Failed to recover run need-input flow after USER_RESPONDED, ticketId={}", ticketId, ex);
        }
    }

    private void closeRunNeedInputTicket(Ticket ticket, RunNeedInputRef ref) {
        if (ticket == null || ticket.status() == TicketStatus.DONE || ticket.status() == TicketStatus.BLOCKED) {
            return;
        }
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("to_status", "DONE");
            data.put("source", "run_need_input_recovery");
            data.put("reason", "User response captured and waiting run recovery started.");
            data.put("run_id", ref.runId());
            data.put("task_id", ref.taskId());
            ticketCommandUseCase.appendEvent(
                ticket.ticketId(),
                "architect_agent",
                "STATUS_CHANGED",
                "Run need-input ticket closed after user response and run recovery.",
                data.toString()
            );
        } catch (Exception ex) {
            log.warn(
                "Failed to close run need-input ticket after user response, ticketId={}, runId={}",
                ticket.ticketId(),
                ref.runId(),
                ex
            );
        }
    }

    private RunNeedInputRef parseRunNeedInputRef(String payloadJson) {
        if (isBlank(payloadJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (!"run_need_input".equalsIgnoreCase(root.path("kind").asText())) {
                return null;
            }
            String runId = normalizeNullable(root.path("run_id").asText(null));
            String taskId = normalizeNullable(root.path("task_id").asText(null));
            return isBlank(runId) || isBlank(taskId) ? null : new RunNeedInputRef(runId, taskId);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isArchitectTicket(TicketEventAppendedEvent event) {
        if (!"architect_agent".equalsIgnoreCase(event.assigneeRole())) {
            return false;
        }
        return event.ticketType() != null && ARCHITECT_TICKET_TYPES.contains(event.ticketType());
    }

    private static boolean isRefreshTriggerTicketEvent(TicketEventAppendedEvent event) {
        if (event.eventType() == TicketEventType.USER_RESPONDED) {
            return true;
        }
        if (event.eventType() != TicketEventType.STATUS_CHANGED) {
            return false;
        }
        return event.ticketStatus() == TicketStatus.DONE || event.ticketStatus() == TicketStatus.BLOCKED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record RunNeedInputRef(String runId, String taskId) {
    }
}
