package com.agentx.agentxbackend.planning.infrastructure.persistence;

import com.agentx.agentxbackend.planning.application.port.out.WorkModuleRepository;
import com.agentx.agentxbackend.planning.domain.model.WorkModule;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class MybatisWorkModuleRepository implements WorkModuleRepository {

    private final WorkModuleMapper mapper;

    public MybatisWorkModuleRepository(WorkModuleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public WorkModule save(WorkModule module) {
        int inserted = mapper.insert(toRow(module));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert work module: " + module.moduleId());
        }
        return module;
    }

    @Override
    public Optional<WorkModule> findById(String moduleId) {
        WorkModuleRow row = mapper.findById(moduleId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    private WorkModuleRow toRow(WorkModule module) {
        WorkModuleRow row = new WorkModuleRow();
        row.setModuleId(module.moduleId());
        row.setSessionId(module.sessionId());
        row.setName(module.name());
        row.setDescription(module.description());
        row.setCreatedAt(Timestamp.from(module.createdAt()));
        row.setUpdatedAt(Timestamp.from(module.updatedAt()));
        return row;
    }

    private WorkModule toDomain(WorkModuleRow row) {
        return new WorkModule(
            row.getModuleId(),
            row.getSessionId(),
            row.getName(),
            row.getDescription(),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}
