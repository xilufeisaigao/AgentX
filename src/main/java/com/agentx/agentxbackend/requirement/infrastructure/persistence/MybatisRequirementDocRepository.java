package com.agentx.agentxbackend.requirement.infrastructure.persistence;

import com.agentx.agentxbackend.requirement.application.port.out.RequirementDocRepository;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocStatus;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class MybatisRequirementDocRepository implements RequirementDocRepository {

    private final RequirementDocMapper mapper;

    public MybatisRequirementDocRepository(RequirementDocMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RequirementDoc save(RequirementDoc doc) {
        int inserted = mapper.insert(toRow(doc));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert requirement doc: " + doc.docId());
        }
        return doc;
    }

    @Override
    public Optional<RequirementDoc> findById(String docId) {
        RequirementDocRow row = mapper.findById(docId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Optional<RequirementDoc> findLatestBySessionId(String sessionId) {
        RequirementDocRow row = mapper.findLatestBySessionId(sessionId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public RequirementDoc update(RequirementDoc doc) {
        int updated = mapper.update(toRow(doc));
        if (updated != 1) {
            throw new IllegalStateException("Failed to update requirement doc: " + doc.docId());
        }
        return doc;
    }

    @Override
    public boolean updateAfterVersionAppend(RequirementDoc doc, int expectedCurrentVersion) {
        int updated = mapper.updateAfterVersionAppend(toRow(doc), expectedCurrentVersion);
        return updated == 1;
    }

    private RequirementDocRow toRow(RequirementDoc doc) {
        RequirementDocRow row = new RequirementDocRow();
        row.setDocId(doc.docId());
        row.setSessionId(doc.sessionId());
        row.setCurrentVersion(doc.currentVersion());
        row.setConfirmedVersion(doc.confirmedVersion());
        row.setStatus(doc.status().name());
        row.setTitle(doc.title());
        row.setCreatedAt(Timestamp.from(doc.createdAt()));
        row.setUpdatedAt(Timestamp.from(doc.updatedAt()));
        return row;
    }

    private RequirementDoc toDomain(RequirementDocRow row) {
        return new RequirementDoc(
            row.getDocId(),
            row.getSessionId(),
            row.getCurrentVersion(),
            row.getConfirmedVersion(),
            RequirementDocStatus.valueOf(row.getStatus()),
            row.getTitle(),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}
