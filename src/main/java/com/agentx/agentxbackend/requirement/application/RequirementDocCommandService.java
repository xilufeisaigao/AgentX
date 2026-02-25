package com.agentx.agentxbackend.requirement.application;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocUseCase;
import com.agentx.agentxbackend.requirement.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocVersionRepository;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocStatus;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.agentx.agentxbackend.requirement.domain.policy.RequirementDocContentPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class RequirementDocCommandService implements RequirementDocUseCase {

    private final RequirementDocRepository requirementDocRepository;
    private final RequirementDocVersionRepository requirementDocVersionRepository;
    private final DomainEventPublisher domainEventPublisher;

    public RequirementDocCommandService(
        RequirementDocRepository requirementDocRepository,
        RequirementDocVersionRepository requirementDocVersionRepository,
        DomainEventPublisher domainEventPublisher
    ) {
        this.requirementDocRepository = requirementDocRepository;
        this.requirementDocVersionRepository = requirementDocVersionRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Override
    @Transactional
    public RequirementDoc createRequirementDoc(String sessionId, String title) {
        requireNotBlank(sessionId, "sessionId");
        requireNotBlank(title, "title");

        Instant now = Instant.now();
        RequirementDoc doc = new RequirementDoc(
            generateDocId(),
            sessionId,
            0,
            null,
            RequirementDocStatus.DRAFT,
            title,
            now,
            now
        );
        return requirementDocRepository.save(doc);
    }

    @Override
    @Transactional
    public RequirementDocVersion createVersion(String docId, String content, String createdByRole) {
        requireNotBlank(docId, "docId");
        requireNotBlank(content, "content");
        String normalizedCreatedByRole = normalizeAndValidateCreatedByRole(createdByRole);

        RequirementDoc currentDoc = requirementDocRepository.findById(docId)
            .orElseThrow(() -> new NoSuchElementException("Requirement doc not found: " + docId));
        RequirementDocContentPolicy.validateOrThrow(content);
        int nextVersion = currentDoc.currentVersion() + 1;
        Instant now = Instant.now();

        RequirementDocVersion version = new RequirementDocVersion(
            docId,
            nextVersion,
            content,
            normalizedCreatedByRole,
            now
        );
        RequirementDocVersion savedVersion;
        try {
            savedVersion = requirementDocVersionRepository.save(version);
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException(
                "Concurrent version conflict for requirement doc: " + docId + " version " + nextVersion,
                ex
            );
        }

        RequirementDoc updatedDoc = new RequirementDoc(
            currentDoc.docId(),
            currentDoc.sessionId(),
            nextVersion,
            currentDoc.confirmedVersion(),
            RequirementDocStatus.IN_REVIEW,
            currentDoc.title(),
            currentDoc.createdAt(),
            now
        );
        boolean updated = requirementDocRepository.updateAfterVersionAppend(updatedDoc, currentDoc.currentVersion());
        if (!updated) {
            throw new IllegalStateException(
                "Concurrent version conflict for requirement doc: " + docId
                    + " expected current version " + currentDoc.currentVersion()
            );
        }
        return savedVersion;
    }

    @Override
    @Transactional
    public RequirementDoc confirm(String docId) {
        requireNotBlank(docId, "docId");

        RequirementDoc currentDoc = requirementDocRepository.findById(docId)
            .orElseThrow(() -> new NoSuchElementException("Requirement doc not found: " + docId));

        if (currentDoc.currentVersion() <= 0) {
            throw new IllegalStateException("Cannot confirm requirement doc without versions: " + docId);
        }
        if (currentDoc.status() == RequirementDocStatus.DRAFT) {
            throw new IllegalStateException("Cannot confirm requirement doc in DRAFT status: " + docId);
        }

        if (currentDoc.status() == RequirementDocStatus.CONFIRMED
            && Objects.equals(currentDoc.confirmedVersion(), currentDoc.currentVersion())) {
            return currentDoc;
        }

        Instant now = Instant.now();
        RequirementDoc updatedDoc = new RequirementDoc(
            currentDoc.docId(),
            currentDoc.sessionId(),
            currentDoc.currentVersion(),
            currentDoc.currentVersion(),
            RequirementDocStatus.CONFIRMED,
            currentDoc.title(),
            currentDoc.createdAt(),
            now
        );
        RequirementDoc persisted = requirementDocRepository.update(updatedDoc);

        domainEventPublisher.publish(new RequirementConfirmedEvent(
            persisted.sessionId(),
            persisted.docId(),
            persisted.currentVersion(),
            currentDoc.confirmedVersion()
        ));
        return persisted;
    }

    private static void requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static String normalizeAndValidateCreatedByRole(String createdByRole) {
        String normalizedRole = createdByRole == null
            ? ""
            : createdByRole.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(normalizedRole) && !"requirement_agent".equals(normalizedRole)) {
            throw new IllegalArgumentException("createdByRole must be user or requirement_agent");
        }
        return normalizedRole;
    }

    private static String generateDocId() {
        return "REQ-" + UUID.randomUUID().toString().replace("-", "");
    }
}
