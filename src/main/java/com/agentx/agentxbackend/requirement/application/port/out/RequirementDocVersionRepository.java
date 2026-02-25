package com.agentx.agentxbackend.requirement.application.port.out;

import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;

import java.util.Optional;

public interface RequirementDocVersionRepository {

    RequirementDocVersion save(RequirementDocVersion version);

    Optional<RequirementDocVersion> findByDocIdAndVersion(String docId, int version);
}
