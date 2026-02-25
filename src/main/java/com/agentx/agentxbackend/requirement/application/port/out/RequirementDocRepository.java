package com.agentx.agentxbackend.requirement.application.port.out;

import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;

import java.util.Optional;

public interface RequirementDocRepository {

    RequirementDoc save(RequirementDoc doc);

    Optional<RequirementDoc> findById(String docId);

    Optional<RequirementDoc> findLatestBySessionId(String sessionId);

    RequirementDoc update(RequirementDoc doc);

    boolean updateAfterVersionAppend(RequirementDoc doc, int expectedCurrentVersion);
}
