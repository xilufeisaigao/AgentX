package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class RunNeedsInputProcessManager {

    private static final Logger log = LoggerFactory.getLogger(RunNeedsInputProcessManager.class);
    private static final Set<TicketStatus> ACTIVE_STATUSES = Set.of(
        TicketStatus.OPEN,
        TicketStatus.IN_PROGRESS,
        TicketStatus.WAITING_USER
    );

    private final TicketCommandUseCase ticketCommandUseCase;
    private final TicketQueryUseCase ticketQueryUseCase;
    private final WaitingTaskQueryUseCase waitingTaskQueryUseCase;
    private final RequirementDocQueryUseCase requirementDocQueryUseCase;
    private final ObjectMapper objectMapper;
    private final String architectAgentId;
    private final int architectLeaseSeconds;

    public RunNeedsInputProcessManager(
        TicketCommandUseCase ticketCommandUseCase,
        TicketQueryUseCase ticketQueryUseCase,
        WaitingTaskQueryUseCase waitingTaskQueryUseCase,
        RequirementDocQueryUseCase requirementDocQueryUseCase,
        ObjectMapper objectMapper,
        @Value("${agentx.architect.auto-processor.agent-id:architect-agent-auto}") String architectAgentId,
        @Value("${agentx.architect.auto-processor.lease-seconds:300}") int architectLeaseSeconds
    ) {
        this.ticketCommandUseCase = ticketCommandUseCase;
        this.ticketQueryUseCase = ticketQueryUseCase;
        this.waitingTaskQueryUseCase = waitingTaskQueryUseCase;
        this.requirementDocQueryUseCase = requirementDocQueryUseCase;
        this.objectMapper = objectMapper;
        this.architectAgentId = normalizeAgentId(architectAgentId);
        this.architectLeaseSeconds = Math.max(30, architectLeaseSeconds);
    }

    public void handle(RunNeedsDecisionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        handleNeedInput(
            event.runId(),
            event.taskId(),
            event.body(),
            event.dataJson(),
            TicketType.DECISION,
            "DECISION required for run " + event.runId()
        );
    }

    public void handle(RunNeedsClarificationEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        handleNeedInput(
            event.runId(),
            event.taskId(),
            event.body(),
            event.dataJson(),
            TicketType.CLARIFICATION,
            "CLARIFICATION required for run " + event.runId()
        );
    }

    private void handleNeedInput(
        String runId,
        String taskId,
        String body,
        String dataJson,
        TicketType type,
        String title
    ) {
        String normalizedRunId = requireNotBlank(runId, "runId");
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedBody = requireNotBlank(body, "body");
        String sessionId = waitingTaskQueryUseCase.findSessionIdByTaskId(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException(
                "Session not found for task: " + normalizedTaskId
            ));
        RequirementRef requirementRef = resolveRequirementRef(sessionId);
        Optional<Ticket> reusableTicket = reuseOrSupersedeRunNeedInputTickets(
            sessionId,
            normalizedRunId,
            normalizedTaskId,
            type,
            normalizedBody,
            dataJson
        );
        if (reusableTicket.isPresent()) {
            return;
        }

        Ticket created = ticketCommandUseCase.createTicket(
            sessionId,
            type,
            title,
            "architect_agent",
            "architect_agent",
            requirementRef.docId(),
            requirementRef.docVersion(),
            buildPayloadJson(normalizedRunId, normalizedTaskId, type, normalizedBody, dataJson)
        );
        Ticket claimed = claimByArchitect(created.ticketId(), normalizedRunId, normalizedTaskId);
        if (claimed == null) {
            return;
        }
        ticketCommandUseCase.appendEvent(
            claimed.ticketId(),
            "architect_agent",
            "COMMENT",
            "Worker requested " + type.name() + " triage for run " + normalizedRunId + ".",
            buildCommentDataJson(normalizedRunId, normalizedTaskId, type, dataJson)
        );
        ticketCommandUseCase.appendEvent(
            claimed.ticketId(),
            "architect_agent",
            "DECISION_REQUESTED",
            normalizedBody,
            buildEventDataJson(normalizedRunId, normalizedTaskId, type, normalizedBody, dataJson)
        );
    }

    private Optional<Ticket> reuseOrSupersedeRunNeedInputTickets(
        String sessionId,
        String runId,
        String taskId,
        TicketType ticketType,
        String body,
        String dataJson
    ) {
        for (Ticket ticket : ticketQueryUseCase.listBySession(sessionId, null, "architect_agent", ticketType.name())) {
            if (ticket == null || !ACTIVE_STATUSES.contains(ticket.status())) {
                continue;
            }
            RunNeedInputRef ref = parseRunNeedInputRef(ticket);
            if (ref == null || !taskId.equals(ref.taskId()) || ticketType != ref.ticketType()) {
                continue;
            }
            if (!runId.equals(ref.runId())) {
                supersedeTicket(ticket, ref, runId);
                continue;
            }
            return Optional.of(promoteExistingTicket(ticket, runId, taskId, ticketType, body, dataJson));
        }
        return Optional.empty();
    }

    private Ticket promoteExistingTicket(
        Ticket ticket,
        String runId,
        String taskId,
        TicketType ticketType,
        String body,
        String dataJson
    ) {
        Ticket working = ticket;
        if (working.status() == TicketStatus.OPEN) {
            Ticket claimed = claimByArchitect(working.ticketId(), runId, taskId);
            if (claimed == null) {
                return working;
            }
            working = claimed;
        }
        ticketCommandUseCase.appendEvent(
            working.ticketId(),
            "architect_agent",
            "COMMENT",
            "Merged duplicated worker " + ticketType.name() + " request for run " + runId + ".",
            buildCommentDataJson(runId, taskId, ticketType, dataJson)
        );
        if (working.status() != TicketStatus.WAITING_USER) {
            ticketCommandUseCase.appendEvent(
                working.ticketId(),
                "architect_agent",
                "DECISION_REQUESTED",
                body,
                buildEventDataJson(runId, taskId, ticketType, body, dataJson)
            );
        }
        return working;
    }

    private void supersedeTicket(Ticket staleTicket, RunNeedInputRef staleRef, String replacementRunId) {
        try {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("to_status", "BLOCKED");
            data.put("reason", "Superseded by run " + replacementRunId + " for the same task need-input flow.");
            data.put("source", "run_need_input_dedup");
            data.put("task_id", staleRef.taskId());
            data.put("run_id", staleRef.runId());
            data.put("superseded_by_run_id", replacementRunId);
            ticketCommandUseCase.appendEvent(
                staleTicket.ticketId(),
                "architect_agent",
                "STATUS_CHANGED",
                "Ticket superseded by a newer run-level need-input request.",
                data.toString()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Failed to supersede stale run need-input ticket, ticketId={}, runId={}, replacementRunId={}, cause={}",
                staleTicket.ticketId(),
                staleRef.runId(),
                replacementRunId,
                ex.getMessage()
            );
        }
    }

    private RunNeedInputRef parseRunNeedInputRef(Ticket ticket) {
        if (ticket == null || ticket.payloadJson() == null || ticket.payloadJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(ticket.payloadJson());
            if (!"run_need_input".equalsIgnoreCase(root.path("kind").asText())) {
                return null;
            }
            String runId = normalizeNullable(root.path("run_id").asText(null));
            String taskId = normalizeNullable(root.path("task_id").asText(null));
            String payloadType = normalizeNullable(root.path("ticket_type").asText(null));
            if (runId == null || taskId == null) {
                return null;
            }
            TicketType ticketType = payloadType == null
                ? ticket.type()
                : TicketType.valueOf(payloadType.toUpperCase(java.util.Locale.ROOT));
            if (ticketType == null) {
                return null;
            }
            return new RunNeedInputRef(runId, taskId, ticketType);
        } catch (Exception ex) {
            return null;
        }
    }

    private String buildPayloadJson(
        String runId,
        String taskId,
        TicketType type,
        String body,
        String dataJson
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("kind", "run_need_input");
        root.put("source", "worker_run_event");
        root.put("run_id", runId);
        root.put("task_id", taskId);
        root.put("ticket_type", type.name());
        root.put("summary", body);
        if (dataJson != null) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        return root.toString();
    }

    private String buildEventDataJson(String runId, String taskId, TicketType type, String body, String dataJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source", "worker_run_event");
        root.put("run_id", runId);
        root.put("task_id", taskId);
        root.put("ticket_type", type.name());
        root.put("request_kind", type == TicketType.DECISION ? "DECISION" : "CLARIFICATION");
        root.put("question", body == null ? "" : body);
        if (dataJson != null) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        mergeStructuredRunData(root, dataJson);
        if (root.path("question").asText("").isBlank()) {
            root.put("question", "Worker requires additional user input to proceed.");
        }
        return root.toString();
    }

    private String buildCommentDataJson(String runId, String taskId, TicketType type, String dataJson) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("source", "worker_run_event");
        root.put("run_id", runId);
        root.put("task_id", taskId);
        root.put("ticket_type", type.name());
        if (dataJson != null) {
            root.put("run_data_json", dataJson);
        } else {
            root.putNull("run_data_json");
        }
        return root.toString();
    }

    private void mergeStructuredRunData(ObjectNode root, String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return;
        }
        JsonNode runData;
        try {
            runData = objectMapper.readTree(dataJson);
        } catch (Exception ex) {
            return;
        }
        if (runData == null || !runData.isObject()) {
            return;
        }

        JsonNode questionNode = runData.path("question");
        if (questionNode.isTextual() && !questionNode.asText().isBlank()) {
            root.put("question", questionNode.asText());
        }
        JsonNode contextNode = runData.path("context");
        if (contextNode.isArray()) {
            root.set("context", contextNode);
        }
        JsonNode optionsNode = runData.path("options");
        if (optionsNode.isArray()) {
            root.set("options", optionsNode);
        }
        JsonNode recommendationNode = runData.path("recommendation");
        if (recommendationNode.isObject()) {
            root.set("recommendation", recommendationNode);
        }
    }

    private Ticket claimByArchitect(String ticketId, String runId, String taskId) {
        try {
            return ticketCommandUseCase.claimTicket(
                ticketId,
                architectAgentId,
                architectLeaseSeconds
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Run need-input ticket claim failed, ticketId={}, runId={}, taskId={}, cause={}",
                ticketId,
                runId,
                taskId,
                ex.getMessage()
            );
            return null;
        }
    }

    private RequirementRef resolveRequirementRef(String sessionId) {
        Optional<RequirementCurrentDoc> currentDocOptional = requirementDocQueryUseCase.findCurrentBySessionId(sessionId);
        if (currentDocOptional == null || currentDocOptional.isEmpty()) {
            return new RequirementRef(null, null);
        }
        RequirementCurrentDoc currentDoc = currentDocOptional.get();
        if (currentDoc.docId() == null || currentDoc.docId().isBlank()) {
            return new RequirementRef(null, null);
        }
        if (currentDoc.confirmedVersion() == null || currentDoc.confirmedVersion() <= 0) {
            return new RequirementRef(null, null);
        }
        if (currentDoc.status() == null || !currentDoc.status().trim().equalsIgnoreCase("CONFIRMED")) {
            return new RequirementRef(null, null);
        }
        return new RequirementRef(currentDoc.docId(), currentDoc.confirmedVersion());
    }

    private static String normalizeAgentId(String rawAgentId) {
        if (rawAgentId == null || rawAgentId.isBlank()) {
            return "architect-agent-auto";
        }
        return rawAgentId.trim();
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record RunNeedInputRef(String runId, String taskId, TicketType ticketType) {
    }

    private record RequirementRef(String docId, Integer docVersion) {
    }
}
