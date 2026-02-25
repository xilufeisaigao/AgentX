package com.agentx.agentxbackend.requirement.application.query;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocVersionRepository;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RequirementDocQueryService implements RequirementDocQueryUseCase {

    private final RequirementDocRepository requirementDocRepository;
    private final RequirementDocVersionRepository requirementDocVersionRepository;

    public RequirementDocQueryService(
        RequirementDocRepository requirementDocRepository,
        RequirementDocVersionRepository requirementDocVersionRepository
    ) {
        this.requirementDocRepository = requirementDocRepository;
        this.requirementDocVersionRepository = requirementDocVersionRepository;
    }

    @Override
    public Optional<RequirementCurrentDoc> findCurrentBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }

        return requirementDocRepository.findLatestBySessionId(sessionId)
            .map(this::toView);
    }

    private RequirementCurrentDoc toView(RequirementDoc doc) {
        String content = null;
        if (doc.currentVersion() > 0) {
            RequirementDocVersion version = requirementDocVersionRepository
                .findByDocIdAndVersion(doc.docId(), doc.currentVersion())
                .orElseThrow(() -> new IllegalStateException(
                    "Current requirement version not found: " + doc.docId() + "@" + doc.currentVersion()
                ));
            content = version.content();
        }

        return new RequirementCurrentDoc(
            doc.docId(),
            doc.currentVersion(),
            doc.confirmedVersion(),
            doc.status().name(),
            doc.title(),
            content,
            doc.updatedAt()
        );
    }
}
