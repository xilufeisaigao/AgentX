package com.agentx.agentxbackend.requirement.infrastructure.persistence;

import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocVersionRepository;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class MybatisRequirementDocVersionRepository implements RequirementDocVersionRepository {

    private final RequirementDocVersionMapper mapper;

    public MybatisRequirementDocVersionRepository(RequirementDocVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RequirementDocVersion save(RequirementDocVersion version) {
        RequirementDocVersionRow row = new RequirementDocVersionRow();
        row.setDocId(version.docId());
        row.setVersion(version.version());
        row.setContent(version.content());
        row.setCreatedByRole(version.createdByRole());
        row.setCreatedAt(Timestamp.from(version.createdAt()));

        int inserted = mapper.insert(row);
        if (inserted != 1) {
            throw new IllegalStateException(
                "Failed to insert requirement doc version: "
                    + version.docId() + "@" + version.version()
            );
        }
        return version;
    }

    @Override
    public Optional<RequirementDocVersion> findByDocIdAndVersion(String docId, int version) {
        RequirementDocVersionRow row = mapper.findByDocIdAndVersion(docId, version);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    private RequirementDocVersion toDomain(RequirementDocVersionRow row) {
        return new RequirementDocVersion(
            row.getDocId(),
            row.getVersion(),
            row.getContent(),
            row.getCreatedByRole(),
            row.getCreatedAt().toInstant()
        );
    }
}
