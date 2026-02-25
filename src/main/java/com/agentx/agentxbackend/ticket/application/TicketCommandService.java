package com.agentx.agentxbackend.ticket.application;

import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.ticket.application.port.out.TicketEventRepository;
import com.agentx.agentxbackend.ticket.application.port.out.TicketRepository;
import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TicketCommandService implements TicketCommandUseCase {

    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong(0L);

    private final TicketRepository ticketRepository;
    private final TicketEventRepository ticketEventRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final ObjectMapper objectMapper;

    public TicketCommandService(
        TicketRepository ticketRepository,
        TicketEventRepository ticketEventRepository,
        DomainEventPublisher domainEventPublisher,
        ObjectMapper objectMapper
    ) {
        this.ticketRepository = ticketRepository;
        this.ticketEventRepository = ticketEventRepository;
        this.domainEventPublisher = domainEventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Ticket createTicket(
        String sessionId,
        TicketType type,
        String title,
        String createdByRole,
        String assigneeRole,
        String requirementDocId,
        Integer requirementDocVer,
        String payloadJson
    ) {
        requireNotBlank(sessionId, "sessionId");
        Objects.requireNonNull(type, "type must not be null");
        requireNotBlank(title, "title");
        String normalizedCreatedByRole = normalizeRole(createdByRole, "createdByRole");
        String normalizedAssigneeRole = normalizeAssigneeRole(assigneeRole);
        validateRequirementRef(requirementDocId, requirementDocVer);
        requireNotBlank(payloadJson, "payloadJson");

        Instant now = Instant.now();
        Ticket ticket = new Ticket(
            generateTicketId(),
            sessionId,
            type,
            TicketStatus.OPEN,
            title,
            normalizedCreatedByRole,
            normalizedAssigneeRole,
            requirementDocId,
            requirementDocVer,
            payloadJson,
            null,
            null,
            now,
            now
        );
        return ticketRepository.save(ticket);
    }

    @Override
    @Transactional
    public Ticket claimTicket(String ticketId, String claimedBy, int leaseSeconds) {
        requireNotBlank(ticketId, "ticketId");
        requireNotBlank(claimedBy, "claimedBy");
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }

        Instant now = Instant.now();
        boolean claimed = ticketRepository.claimIfOpen(
            ticketId,
            claimedBy,
            now.plusSeconds(leaseSeconds),
            now
        );
        if (!claimed) {
            Ticket current = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));
            throw new IllegalStateException(
                "Only OPEN ticket can be claimed: " + ticketId + ", currentStatus=" + current.status()
            );
        }
        return ticketRepository.findById(ticketId)
            .orElseThrow(() -> new NoSuchElementException("Ticket not found after claim: " + ticketId));
    }

    @Override
    @Transactional
    public Optional<Ticket> tryMovePlanningLease(
        String ticketId,
        String expectedClaimedBy,
        String nextClaimedBy,
        int leaseSeconds
    ) {
        requireNotBlank(ticketId, "ticketId");
        requireNotBlank(expectedClaimedBy, "expectedClaimedBy");
        requireNotBlank(nextClaimedBy, "nextClaimedBy");
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds must be positive");
        }
        Instant now = Instant.now();
        boolean moved = ticketRepository.movePlanningLeaseIfInProgressClaimed(
            ticketId,
            expectedClaimedBy,
            nextClaimedBy,
            now.plusSeconds(leaseSeconds),
            now
        );
        if (!moved) {
            return Optional.empty();
        }
        return ticketRepository.findById(ticketId);
    }

    @Override
    @Transactional
    public TicketEvent appendEvent(String ticketId, String actorRole, String eventType, String body, String dataJson) {
        requireNotBlank(ticketId, "ticketId");
        String normalizedActorRole = normalizeRole(actorRole, "actorRole");
        requireNotBlank(eventType, "eventType");
        requireNotBlank(body, "body");
        TicketEventType parsedEventType = TicketEventType.valueOf(eventType.trim().toUpperCase(Locale.ROOT));
        Ticket ticket = ticketRepository.findById(ticketId)
            .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));
        TicketStatus nextStatus = deriveStatusAfterEvent(ticket.status(), parsedEventType, dataJson, objectMapper);
        validateStatusTransition(ticket.status(), nextStatus, parsedEventType);

        Instant now = Instant.now();
        TicketEvent event = new TicketEvent(
            generateEventId(),
            ticketId,
            parsedEventType,
            normalizedActorRole,
            body,
            dataJson,
            now
        );
        TicketEvent saved = ticketEventRepository.save(event);

        if (nextStatus != ticket.status()) {
            Ticket updated = new Ticket(
                ticket.ticketId(),
                ticket.sessionId(),
                ticket.type(),
                nextStatus,
                ticket.title(),
                ticket.createdByRole(),
                ticket.assigneeRole(),
                ticket.requirementDocId(),
                ticket.requirementDocVer(),
                ticket.payloadJson(),
                ticket.claimedBy(),
                ticket.leaseUntil(),
                ticket.createdAt(),
                now
            );
            ticketRepository.update(updated);
        }

        domainEventPublisher.publish(new TicketEventAppendedEvent(
            ticket.ticketId(),
            ticket.sessionId(),
            ticket.type(),
            ticket.assigneeRole(),
            parsedEventType,
            nextStatus
        ));

        return saved;
    }

    private static TicketStatus deriveStatusAfterEvent(
        TicketStatus currentStatus,
        TicketEventType eventType,
        String dataJson,
        ObjectMapper objectMapper
    ) {
        return switch (eventType) {
            case DECISION_REQUESTED -> TicketStatus.WAITING_USER;
            case USER_RESPONDED -> TicketStatus.IN_PROGRESS;
            case STATUS_CHANGED -> parseTargetStatusFromDataJson(dataJson, objectMapper);
            default -> currentStatus;
        };
    }

    private static TicketStatus parseTargetStatusFromDataJson(String dataJson, ObjectMapper objectMapper) {
        requireNotBlank(dataJson, "dataJson");
        JsonNode root;
        try {
            root = objectMapper.readTree(dataJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("STATUS_CHANGED event requires valid JSON data_json", ex);
        }
        String rawToStatus = root.path("to_status").asText("");
        if (rawToStatus == null || rawToStatus.isBlank()) {
            throw new IllegalArgumentException("STATUS_CHANGED event requires data_json.to_status");
        }
        TicketStatus targetStatus;
        try {
            targetStatus = TicketStatus.valueOf(rawToStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported to_status: " + rawToStatus, ex);
        }
        if (targetStatus == TicketStatus.OPEN) {
            throw new IllegalArgumentException("STATUS_CHANGED does not support transition to OPEN");
        }
        return targetStatus;
    }

    private static void validateStatusTransition(
        TicketStatus currentStatus,
        TicketStatus nextStatus,
        TicketEventType eventType
    ) {
        if (currentStatus == nextStatus) {
            return;
        }
        if (currentStatus == TicketStatus.DONE || currentStatus == TicketStatus.BLOCKED) {
            throw new IllegalStateException("Cannot transition terminal ticket from " + currentStatus);
        }

        boolean allowed = switch (currentStatus) {
            case OPEN -> nextStatus == TicketStatus.IN_PROGRESS || nextStatus == TicketStatus.BLOCKED;
            case IN_PROGRESS -> nextStatus == TicketStatus.WAITING_USER
                || nextStatus == TicketStatus.DONE
                || nextStatus == TicketStatus.BLOCKED;
            case WAITING_USER -> nextStatus == TicketStatus.IN_PROGRESS || nextStatus == TicketStatus.BLOCKED;
            case DONE, BLOCKED -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                "Illegal ticket status transition: " + currentStatus + " -> " + nextStatus
                    + " for event " + eventType.name()
            );
        }
    }

    private static void validateRequirementRef(String requirementDocId, Integer requirementDocVer) {
        boolean hasDocId = requirementDocId != null && !requirementDocId.isBlank();
        boolean hasDocVer = requirementDocVer != null;
        if (hasDocId != hasDocVer) {
            throw new IllegalArgumentException(
                "requirement_doc_id and requirement_doc_ver must be set together"
            );
        }
        if (hasDocVer && requirementDocVer <= 0) {
            throw new IllegalArgumentException("requirement_doc_ver must be positive");
        }
    }

    private static String normalizeRole(String role, String fieldName) {
        requireNotBlank(role, fieldName);
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(normalized)
            && !"requirement_agent".equals(normalized)
            && !"architect_agent".equals(normalized)) {
            throw new IllegalArgumentException(fieldName + " has unsupported role: " + role);
        }
        return normalized;
    }

    private static String normalizeAssigneeRole(String assigneeRole) {
        String normalized = normalizeRole(assigneeRole, "assigneeRole");
        if (!"requirement_agent".equals(normalized) && !"architect_agent".equals(normalized)) {
            throw new IllegalArgumentException("assigneeRole has unsupported role: " + assigneeRole);
        }
        return normalized;
    }

    private static void requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static String generateTicketId() {
        return "TCK-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String generateEventId() {
        long epochMillis = Instant.now().toEpochMilli();
        long sequence = Math.floorMod(EVENT_SEQUENCE.incrementAndGet(), 1_000_000L);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "TEV-%013d-%06d-%s".formatted(epochMillis, sequence, randomSuffix);
    }
}
