package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketEvent;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationAgent;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class RequirementStageService {

    static final String REQUIREMENT_NODE_ID = "requirement";
    static final String DISCOVERY_PHASE = "DISCOVERY";
    static final String CONFIRMATION_PHASE = "CONFIRMATION";
    static final String DIRECT_EDIT_PHASE = "DIRECT_EDIT";
    private static final String WORKFLOW_STARTED = "WORKFLOW_STARTED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final FlowStore flowStore;
    private final IntakeStore intakeStore;
    private final CatalogStore catalogStore;
    private final RequirementConversationAgent requirementConversationAgent;
    private final ObjectMapper objectMapper;

    public RequirementStageService(
            FlowStore flowStore,
            IntakeStore intakeStore,
            CatalogStore catalogStore,
            RequirementConversationAgent requirementConversationAgent,
            ObjectMapper objectMapper
    ) {
        this.flowStore = flowStore;
        this.intakeStore = intakeStore;
        this.catalogStore = catalogStore;
        this.requirementConversationAgent = requirementConversationAgent;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequirementStageOutcome reconcile(String workflowRunId) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        Optional<RequirementDoc> requirementDoc = intakeStore.findRequirementByWorkflow(workflowRunId);
        if (isConfirmed(requirementDoc)) {
            saveWorkflowStatus(workflowRun, WorkflowRunStatus.ACTIVE, "需求文档已确认，流程恢复为活动态。");
            RequirementDoc confirmedDoc = requirementDoc.orElseThrow();
            return RequirementStageOutcome.succeeded(
                    "需求文档已确认，可以进入架构阶段。",
                    jsonPayload(Map.of(
                            "decision", "CONFIRMED",
                            "requirementDocId", confirmedDoc.docId(),
                            "requirementDocVersion", confirmedDoc.currentVersion(),
                            "status", confirmedDoc.status().name()
                    ))
            );
        }

        List<Ticket> requirementTickets = requirementTickets(workflowRunId);
        Optional<Ticket> pendingHumanTicket = requirementTickets.stream()
                .filter(this::isHumanPending)
                .findFirst();
        if (pendingHumanTicket.isPresent()) {
            saveWorkflowStatus(workflowRun, WorkflowRunStatus.WAITING_ON_HUMAN, "需求阶段仍在等待人类输入。");
            Ticket ticket = pendingHumanTicket.orElseThrow();
            Map<String, Object> waitingPayload = new HashMap<>();
            waitingPayload.put("decision", "WAITING_ON_HUMAN");
            waitingPayload.put("ticketId", ticket.ticketId());
            waitingPayload.put("phase", payloadString(ticket.payloadJson(), "phase"));
            return RequirementStageOutcome.waitingOnHuman(
                    "当前需求 ticket 正在等待人类输入。",
                    jsonPayload(waitingPayload)
            );
        }

        List<RequirementVersion> versions = requirementDoc
                .map(doc -> intakeStore.listRequirementVersions(doc.docId()))
                .orElse(List.of());
        Optional<Ticket> currentAnsweredTicket = requirementTickets.stream()
                .filter(ticket -> ticket.status() == TicketStatus.ANSWERED)
                .reduce((left, right) -> right);
        SeedInput seedInput = loadSeedInput(workflowRunId, workflowRun.title());
        RequirementConversationContext context = new RequirementConversationContext(
                workflowRunId,
                workflowRun.title(),
                seedInput.title(),
                seedInput.content(),
                latestRequirementVersion(requirementDoc, versions),
                answeredTicketHistory(requirementTickets),
                latestInteractionPhase(currentAnsweredTicket, versions),
                latestHumanInput(seedInput, currentAnsweredTicket, versions)
        );
        AgentDefinition requirementAgent = requirementAgent(workflowRunId);
        StructuredModelResult<RequirementAgentDecision> modelResult =
                requirementConversationAgent.evaluate(requirementAgent, context);
        RequirementAgentDecision decision = modelResult.value();
        return switch (decision.decision()) {
            case NEED_INPUT -> applyNeedInput(workflowRun, requirementDoc, currentAnsweredTicket, decision, modelResult);
            case DRAFT_READY -> applyDraftReady(workflowRun, requirementDoc, currentAnsweredTicket, decision, modelResult);
        };
    }

    @Transactional
    public String confirmRequirementDoc(ConfirmRequirementDocCommand command) {
        RequirementDoc requirementDoc = intakeStore.findRequirement(command.docId())
                .orElseThrow(() -> new IllegalArgumentException("requirement doc not found: " + command.docId()));
        if (requirementDoc.status() != RequirementStatus.IN_REVIEW) {
            throw new IllegalStateException("requirement doc is not in review: " + requirementDoc.docId());
        }
        if (requirementDoc.currentVersion() != command.version()) {
            throw new IllegalStateException("only the latest requirement version can be confirmed");
        }
        if (hasOpenClarificationTicket(requirementDoc.workflowRunId())) {
            throw new IllegalStateException("cannot confirm requirement doc while clarification ticket is still open");
        }
        if (hasAnsweredRequirementTicket(requirementDoc.workflowRunId())) {
            throw new IllegalStateException("cannot confirm requirement doc while requirement feedback is still waiting to be reconciled");
        }
        if (!hasOpenConfirmationTicket(requirementDoc.workflowRunId(), requirementDoc.docId(), requirementDoc.currentVersion())) {
            throw new IllegalStateException("current requirement version does not have an active confirmation ticket");
        }

        RequirementDoc confirmedDoc = new RequirementDoc(
                requirementDoc.docId(),
                requirementDoc.workflowRunId(),
                requirementDoc.currentVersion(),
                requirementDoc.currentVersion(),
                RequirementStatus.CONFIRMED,
                requirementDoc.title()
        );
        intakeStore.saveRequirement(confirmedDoc);
        resolveConfirmationTickets(
                requirementDoc.workflowRunId(),
                requirementDoc.docId(),
                requirementDoc.currentVersion(),
                command.confirmedBy(),
                "当前需求文档版本已被确认。"
        );
        reactivateWorkflowIfWaiting(requirementDoc.workflowRunId(), "需求文档已确认，流程恢复执行。");
        return requirementDoc.workflowRunId();
    }

    @Transactional
    public String editRequirementDoc(EditRequirementDocCommand command) {
        RequirementDoc requirementDoc = intakeStore.findRequirement(command.docId())
                .orElseThrow(() -> new IllegalArgumentException("requirement doc not found: " + command.docId()));
        int nextVersion = requirementDoc.currentVersion() + 1;
        intakeStore.appendRequirementVersion(new RequirementVersion(
                requirementDoc.docId(),
                nextVersion,
                command.content(),
                command.editedBy()
        ));
        intakeStore.saveRequirement(new RequirementDoc(
                requirementDoc.docId(),
                requirementDoc.workflowRunId(),
                nextVersion,
                null,
                RequirementStatus.IN_REVIEW,
                command.title()
        ));
        cancelOutstandingRequirementTickets(
                requirementDoc.workflowRunId(),
                command.editedBy(),
                "需求文档已被人工直接编辑，旧的需求 ticket 已失效。"
        );
        reactivateWorkflowIfWaiting(requirementDoc.workflowRunId(), "需求文档已更新，流程等待 requirement 节点重新审阅。");
        return requirementDoc.workflowRunId();
    }

    public boolean isRequirementConfirmed(String workflowRunId) {
        return isConfirmed(intakeStore.findRequirementByWorkflow(workflowRunId));
    }

    private RequirementStageOutcome applyNeedInput(
            WorkflowRun workflowRun,
            Optional<RequirementDoc> requirementDoc,
            Optional<Ticket> currentAnsweredTicket,
            RequirementAgentDecision decision,
            StructuredModelResult<RequirementAgentDecision> modelResult
    ) {
        requirementDoc.ifPresent(doc -> intakeStore.saveRequirement(new RequirementDoc(
                doc.docId(),
                doc.workflowRunId(),
                doc.currentVersion(),
                null,
                RequirementStatus.DRAFT,
                doc.title()
        )));
        closeRequirementTickets(
                workflowRun.workflowRunId(),
                requirementAgentActor(workflowRun.workflowRunId()),
                "需求代理已读取最新输入并重新打开需求澄清。"
        );

        Map<String, Object> clarificationPayload = new HashMap<>();
        clarificationPayload.put("phase", DISCOVERY_PHASE);
        clarificationPayload.put("gaps", decision.gaps());
        clarificationPayload.put("questions", decision.questions());
        clarificationPayload.put("summary", decision.summary());
        clarificationPayload.put("modelProvider", modelResult.provider());
        clarificationPayload.put("modelName", modelResult.model());
        clarificationPayload.put("triggerTicketId", currentAnsweredTicket.map(Ticket::ticketId).orElse(null));
        Ticket ticket = new Ticket(
                requirementTicketId(workflowRun.workflowRunId(), DISCOVERY_PHASE),
                workflowRun.workflowRunId(),
                TicketType.CLARIFICATION,
                TicketBlockingScope.GLOBAL_BLOCKING,
                TicketStatus.OPEN,
                "需求信息仍有缺口，请补充",
                requirementAgentActor(workflowRun.workflowRunId()),
                workflowOwner(workflowRun),
                REQUIREMENT_NODE_ID,
                requirementDoc.map(RequirementDoc::docId).orElse(null),
                requirementDoc.map(RequirementDoc::currentVersion).orElse(null),
                jsonPayload(clarificationPayload)
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "REQUIREMENT_CLARIFICATION_REQUESTED",
                requirementAgentActor(workflowRun.workflowRunId()),
                "需求代理识别到新的关键缺口，等待人类补充。",
                ticket.payloadJson()
        ));
        saveWorkflowStatus(workflowRun, WorkflowRunStatus.WAITING_ON_HUMAN, "需求阶段等待人类补充关键缺口。");
        return RequirementStageOutcome.waitingOnHuman(
                "需求信息不足，已生成新的澄清 ticket。",
                jsonPayload(Map.of(
                        "decision", decision.decision().name(),
                        "ticketId", ticket.ticketId(),
                        "phase", DISCOVERY_PHASE,
                        "gaps", decision.gaps(),
                        "questions", decision.questions(),
                        "summary", decision.summary(),
                        "modelProvider", modelResult.provider(),
                        "modelName", modelResult.model()
                ))
        );
    }

    private RequirementStageOutcome applyDraftReady(
            WorkflowRun workflowRun,
            Optional<RequirementDoc> requirementDoc,
            Optional<Ticket> currentAnsweredTicket,
            RequirementAgentDecision decision,
            StructuredModelResult<RequirementAgentDecision> modelResult
    ) {
        RequirementDoc updatedDoc;
        int targetVersion;
        if (requirementDoc.isEmpty()) {
            String docId = "requirement-" + workflowRun.workflowRunId();
            updatedDoc = new RequirementDoc(
                    docId,
                    workflowRun.workflowRunId(),
                    1,
                    null,
                    RequirementStatus.IN_REVIEW,
                    decision.draftTitle()
            );
            intakeStore.saveRequirement(updatedDoc);
            intakeStore.appendRequirementVersion(new RequirementVersion(
                    docId,
                    1,
                    decision.draftContent(),
                    requirementAgentActor(workflowRun.workflowRunId())
            ));
            targetVersion = 1;
        } else if (currentAnsweredTicket.isPresent()) {
            RequirementDoc currentDoc = requirementDoc.orElseThrow();
            int nextVersion = currentDoc.currentVersion() + 1;
            updatedDoc = new RequirementDoc(
                    currentDoc.docId(),
                    currentDoc.workflowRunId(),
                    nextVersion,
                    null,
                    RequirementStatus.IN_REVIEW,
                    decision.draftTitle()
            );
            intakeStore.saveRequirement(updatedDoc);
            intakeStore.appendRequirementVersion(new RequirementVersion(
                    currentDoc.docId(),
                    nextVersion,
                    decision.draftContent(),
                    requirementAgentActor(workflowRun.workflowRunId())
            ));
            targetVersion = nextVersion;
        } else {
            RequirementDoc currentDoc = requirementDoc.orElseThrow();
            updatedDoc = new RequirementDoc(
                    currentDoc.docId(),
                    currentDoc.workflowRunId(),
                    currentDoc.currentVersion(),
                    null,
                    RequirementStatus.IN_REVIEW,
                    currentDoc.title()
            );
            intakeStore.saveRequirement(updatedDoc);
            targetVersion = currentDoc.currentVersion();
        }

        closeRequirementTickets(
                workflowRun.workflowRunId(),
                requirementAgentActor(workflowRun.workflowRunId()),
                "需求代理已生成新的候选文档，旧 ticket 已被替换。"
        );

        Map<String, Object> confirmationPayload = new HashMap<>();
        confirmationPayload.put("phase", CONFIRMATION_PHASE);
        confirmationPayload.put("summary", decision.summary());
        confirmationPayload.put("draftTitle", updatedDoc.title());
        confirmationPayload.put("modelProvider", modelResult.provider());
        confirmationPayload.put("modelName", modelResult.model());
        confirmationPayload.put("triggerTicketId", currentAnsweredTicket.map(Ticket::ticketId).orElse(null));
        confirmationPayload.put("versionReused", currentAnsweredTicket.isEmpty() && requirementDoc.isPresent());
        Ticket ticket = new Ticket(
                requirementTicketId(workflowRun.workflowRunId(), CONFIRMATION_PHASE),
                workflowRun.workflowRunId(),
                TicketType.DECISION,
                TicketBlockingScope.GLOBAL_BLOCKING,
                TicketStatus.OPEN,
                "需求文档待确认",
                requirementAgentActor(workflowRun.workflowRunId()),
                workflowOwner(workflowRun),
                REQUIREMENT_NODE_ID,
                updatedDoc.docId(),
                targetVersion,
                jsonPayload(confirmationPayload)
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "REQUIREMENT_CONFIRMATION_REQUESTED",
                requirementAgentActor(workflowRun.workflowRunId()),
                "需求代理已生成候选需求文档，等待人类确认或给出修改意见。",
                ticket.payloadJson()
        ));
        saveWorkflowStatus(workflowRun, WorkflowRunStatus.WAITING_ON_HUMAN, "需求文档进入确认阶段。");
        return RequirementStageOutcome.waitingOnHuman(
                "需求文档候选版本已生成，等待确认。",
                jsonPayload(Map.of(
                        "decision", decision.decision().name(),
                        "ticketId", ticket.ticketId(),
                        "phase", CONFIRMATION_PHASE,
                        "requirementDocId", updatedDoc.docId(),
                        "requirementDocVersion", targetVersion,
                        "summary", decision.summary(),
                        "modelProvider", modelResult.provider(),
                        "modelName", modelResult.model()
                ))
        );
    }

    private AgentDefinition requirementAgent(String workflowRunId) {
        String selectedAgentId = flowStore.listNodeBindings(workflowRunId).stream()
                .filter(binding -> REQUIREMENT_NODE_ID.equals(binding.nodeId()))
                .map(WorkflowNodeBinding::selectedAgentId)
                .findFirst()
                .orElse("requirement-agent");
        return catalogStore.findAgent(selectedAgentId)
                .orElseThrow(() -> new IllegalStateException("requirement agent definition not found: " + selectedAgentId));
    }

    private SeedInput loadSeedInput(String workflowRunId, String workflowTitle) {
        WorkflowRunEvent startEvent = flowStore.listRunEvents(workflowRunId).stream()
                .filter(event -> WORKFLOW_STARTED.equals(event.eventType()))
                .reduce((left, right) -> right)
                .orElse(null);
        if (startEvent == null || startEvent.dataJson() == null) {
            return new SeedInput(workflowTitle, workflowTitle);
        }
        Map<String, Object> payload = payloadMap(startEvent.dataJson());
        return new SeedInput(
                stringValue(payload.getOrDefault("requirementSeedTitle", workflowTitle)),
                stringValue(payload.getOrDefault("requirementSeedContent", workflowTitle))
        );
    }

    private Optional<RequirementConversationContext.CurrentRequirementVersion> latestRequirementVersion(
            Optional<RequirementDoc> requirementDoc,
            List<RequirementVersion> versions
    ) {
        if (requirementDoc.isEmpty()) {
            return Optional.empty();
        }
        RequirementDoc doc = requirementDoc.orElseThrow();
        return versions.stream()
                .filter(version -> version.version() == doc.currentVersion())
                .findFirst()
                .map(version -> new RequirementConversationContext.CurrentRequirementVersion(
                        version.version(),
                        doc.title(),
                        version.content(),
                        doc.status().name(),
                        version.createdBy().type().name(),
                        version.createdBy().actorId()
                ));
    }

    private List<RequirementConversationContext.RequirementTicketTurn> answeredTicketHistory(List<Ticket> requirementTickets) {
        return requirementTickets.stream()
                .filter(ticket -> {
                    String answer = payloadString(ticket.payloadJson(), "answer");
                    return answer != null && !answer.isBlank();
                })
                .map(ticket -> new RequirementConversationContext.RequirementTicketTurn(
                        ticket.ticketId(),
                        ticket.type().name(),
                        payloadString(ticket.payloadJson(), "phase"),
                        ticket.title(),
                        ticket.requirementDocVersion(),
                        payloadStringList(ticket.payloadJson(), "gaps"),
                        payloadStringList(ticket.payloadJson(), "questions"),
                        payloadString(ticket.payloadJson(), "answer")
                ))
                .toList();
    }

    private String latestInteractionPhase(Optional<Ticket> currentAnsweredTicket, List<RequirementVersion> versions) {
        if (currentAnsweredTicket.isPresent()) {
            return payloadString(currentAnsweredTicket.orElseThrow().payloadJson(), "phase");
        }
        return versions.stream()
                .reduce((left, right) -> right)
                .filter(version -> version.createdBy().type() == ActorType.HUMAN)
                .map(version -> DIRECT_EDIT_PHASE)
                .orElse("SEED");
    }

    private String latestHumanInput(
            SeedInput seedInput,
            Optional<Ticket> currentAnsweredTicket,
            List<RequirementVersion> versions
    ) {
        if (currentAnsweredTicket.isPresent()) {
            return payloadString(currentAnsweredTicket.orElseThrow().payloadJson(), "answer");
        }
        return versions.stream()
                .reduce((left, right) -> right)
                .filter(version -> version.createdBy().type() == ActorType.HUMAN)
                .map(RequirementVersion::content)
                .orElse(seedInput.content());
    }

    private boolean hasOpenClarificationTicket(String workflowRunId) {
        return requirementTickets(workflowRunId).stream()
                .anyMatch(ticket -> ticket.type() == TicketType.CLARIFICATION && ticket.status() != TicketStatus.RESOLVED
                        && ticket.status() != TicketStatus.CANCELED);
    }

    private boolean hasAnsweredRequirementTicket(String workflowRunId) {
        return requirementTickets(workflowRunId).stream()
                .anyMatch(ticket -> ticket.status() == TicketStatus.ANSWERED);
    }

    private boolean hasOpenConfirmationTicket(String workflowRunId, String requirementDocId, int requirementVersion) {
        return requirementTickets(workflowRunId).stream()
                .filter(ticket -> ticket.type() == TicketType.DECISION)
                .filter(ticket -> ticket.status() != TicketStatus.RESOLVED && ticket.status() != TicketStatus.CANCELED)
                .anyMatch(ticket -> Objects.equals(requirementDocId, ticket.requirementDocId())
                        && Objects.equals(requirementVersion, ticket.requirementDocVersion()));
    }

    private void resolveConfirmationTickets(
            String workflowRunId,
            String requirementDocId,
            int requirementVersion,
            ActorRef actor,
            String body
    ) {
        for (Ticket ticket : requirementTickets(workflowRunId)) {
            if (ticket.type() != TicketType.DECISION || ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.CANCELED) {
                continue;
            }
            if (!Objects.equals(requirementDocId, ticket.requirementDocId())
                    || !Objects.equals(requirementVersion, ticket.requirementDocVersion())) {
                continue;
            }
            Ticket resolvedTicket = new Ticket(
                    ticket.ticketId(),
                    ticket.workflowRunId(),
                    ticket.type(),
                    ticket.blockingScope(),
                    TicketStatus.RESOLVED,
                    ticket.title(),
                    ticket.createdBy(),
                    actor,
                    ticket.originNodeId(),
                    ticket.requirementDocId(),
                    ticket.requirementDocVersion(),
                    ticket.taskId(),
                    ticket.payloadJson()
            );
            intakeStore.saveTicket(resolvedTicket);
            intakeStore.appendTicketEvent(new TicketEvent(
                    eventId("ticket"),
                    resolvedTicket.ticketId(),
                    "REQUIREMENT_CONFIRMED",
                    actor,
                    body,
                    resolvedTicket.payloadJson()
            ));
        }
    }

    private void cancelOutstandingRequirementTickets(String workflowRunId, ActorRef actor, String body) {
        for (Ticket ticket : requirementTickets(workflowRunId)) {
            if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.CANCELED) {
                continue;
            }
            Ticket canceledTicket = new Ticket(
                    ticket.ticketId(),
                    ticket.workflowRunId(),
                    ticket.type(),
                    ticket.blockingScope(),
                    TicketStatus.CANCELED,
                    ticket.title(),
                    ticket.createdBy(),
                    actor,
                    ticket.originNodeId(),
                    ticket.requirementDocId(),
                    ticket.requirementDocVersion(),
                    ticket.taskId(),
                    ticket.payloadJson()
            );
            intakeStore.saveTicket(canceledTicket);
            intakeStore.appendTicketEvent(new TicketEvent(
                    eventId("ticket"),
                    canceledTicket.ticketId(),
                    "REQUIREMENT_TICKET_CANCELED",
                    actor,
                    body,
                    canceledTicket.payloadJson()
            ));
        }
    }

    private void closeRequirementTickets(String workflowRunId, ActorRef actor, String body) {
        for (Ticket ticket : requirementTickets(workflowRunId)) {
            if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.CANCELED) {
                continue;
            }
            TicketStatus nextStatus = ticket.status() == TicketStatus.ANSWERED ? TicketStatus.RESOLVED : TicketStatus.CANCELED;
            Ticket closedTicket = new Ticket(
                    ticket.ticketId(),
                    ticket.workflowRunId(),
                    ticket.type(),
                    ticket.blockingScope(),
                    nextStatus,
                    ticket.title(),
                    ticket.createdBy(),
                    actor,
                    ticket.originNodeId(),
                    ticket.requirementDocId(),
                    ticket.requirementDocVersion(),
                    ticket.taskId(),
                    ticket.payloadJson()
            );
            intakeStore.saveTicket(closedTicket);
            intakeStore.appendTicketEvent(new TicketEvent(
                    eventId("ticket"),
                    closedTicket.ticketId(),
                    nextStatus == TicketStatus.RESOLVED ? "REQUIREMENT_TICKET_RESOLVED" : "REQUIREMENT_TICKET_CANCELED",
                    actor,
                    body,
                    closedTicket.payloadJson()
            ));
        }
    }

    private List<Ticket> requirementTickets(String workflowRunId) {
        return intakeStore.listTicketsForWorkflow(workflowRunId).stream()
                .filter(ticket -> REQUIREMENT_NODE_ID.equals(ticket.originNodeId()))
                .filter(ticket -> ticket.blockingScope() == TicketBlockingScope.GLOBAL_BLOCKING)
                .toList();
    }

    private boolean isHumanPending(Ticket ticket) {
        return ticket.assignee().type() == ActorType.HUMAN
                && ticket.status() != TicketStatus.ANSWERED
                && ticket.status() != TicketStatus.RESOLVED
                && ticket.status() != TicketStatus.CANCELED;
    }

    private boolean isConfirmed(Optional<RequirementDoc> requirementDoc) {
        return requirementDoc
                .filter(doc -> doc.status() == RequirementStatus.CONFIRMED)
                .filter(doc -> doc.confirmedVersion() != null && doc.confirmedVersion() == doc.currentVersion())
                .isPresent();
    }

    private void reactivateWorkflowIfWaiting(String workflowRunId, String body) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        if (workflowRun.status() == WorkflowRunStatus.WAITING_ON_HUMAN) {
            saveWorkflowStatus(workflowRun, WorkflowRunStatus.ACTIVE, body);
        }
    }

    private void saveWorkflowStatus(WorkflowRun workflowRun, WorkflowRunStatus status, String body) {
        if (workflowRun.status() == status) {
            return;
        }
        flowStore.saveRun(new WorkflowRun(
                workflowRun.workflowRunId(),
                workflowRun.workflowTemplateId(),
                workflowRun.title(),
                status,
                workflowRun.entryMode(),
                workflowRun.autoAgentMode(),
                workflowRun.createdBy()
        ));
        flowStore.appendRunEvent(new WorkflowRunEvent(
                eventId("workflow"),
                workflowRun.workflowRunId(),
                "WORKFLOW_STATUS_UPDATED",
                new ActorRef(ActorType.SYSTEM, "runtime"),
                body,
                jsonPayload(Map.of("status", status.name()))
        ));
    }

    private WorkflowRun workflow(String workflowRunId) {
        return flowStore.findRun(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("workflow run not found: " + workflowRunId));
    }

    private ActorRef workflowOwner(WorkflowRun workflowRun) {
        if (workflowRun.createdBy().type() == ActorType.HUMAN) {
            return workflowRun.createdBy();
        }
        return new ActorRef(ActorType.HUMAN, "workflow-owner");
    }

    private ActorRef requirementAgentActor(String workflowRunId) {
        return new ActorRef(ActorType.AGENT, requirementAgent(workflowRunId).agentId());
    }

    private Map<String, Object> payloadMap(JsonPayload payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload.json(), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse json payload", exception);
        }
    }

    private String payloadString(JsonPayload payload, String key) {
        Object value = payloadMap(payload).get(key);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> payloadStringList(JsonPayload payload, String key) {
        Object value = payloadMap(payload).get(key);
        if (value instanceof List<?> listValue) {
            return listValue.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to write json payload", exception);
        }
    }

    private String stringValue(Object rawValue) {
        return rawValue == null ? "" : String.valueOf(rawValue);
    }

    private String requirementTicketId(String workflowRunId, String phase) {
        return "ticket-requirement-" + shortToken(workflowRunId, phase, UUID.randomUUID().toString());
    }

    private String eventId(String prefix) {
        return prefix + "-event-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String shortToken(String... values) {
        return UUID.nameUUIDFromBytes(String.join("|", values).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 24);
    }

    private record SeedInput(String title, String content) {
    }

    public record RequirementStageOutcome(
            WorkflowRunStatus workflowStatus,
            String body,
            JsonPayload outputPayloadJson
    ) {

        private static RequirementStageOutcome succeeded(String body, JsonPayload payload) {
            return new RequirementStageOutcome(WorkflowRunStatus.ACTIVE, body, payload);
        }

        private static RequirementStageOutcome waitingOnHuman(String body, JsonPayload payload) {
            return new RequirementStageOutcome(WorkflowRunStatus.WAITING_ON_HUMAN, body, payload);
        }
    }
}
