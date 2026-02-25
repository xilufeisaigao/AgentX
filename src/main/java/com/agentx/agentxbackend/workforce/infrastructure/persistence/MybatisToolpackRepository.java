package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import com.agentx.agentxbackend.workforce.application.port.out.ToolpackRepository;
import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisToolpackRepository implements ToolpackRepository {

    private final ToolpackMapper mapper;

    public MybatisToolpackRepository(ToolpackMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Toolpack save(Toolpack toolpack) {
        int inserted = mapper.insert(toRow(toolpack));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert toolpack: " + toolpack.toolpackId());
        }
        return toolpack;
    }

    @Override
    public Optional<Toolpack> findById(String toolpackId) {
        ToolpackRow row = mapper.findById(toolpackId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Optional<Toolpack> findByNameAndVersion(String name, String version) {
        ToolpackRow row = mapper.findByNameAndVersion(name, version);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public List<Toolpack> findAll() {
        List<ToolpackRow> rows = mapper.findAll();
        List<Toolpack> toolpacks = new ArrayList<>(rows.size());
        for (ToolpackRow row : rows) {
            toolpacks.add(toDomain(row));
        }
        return toolpacks;
    }

    @Override
    public List<Toolpack> findByWorkerId(String workerId) {
        List<ToolpackRow> rows = mapper.findByWorkerId(workerId);
        List<Toolpack> toolpacks = new ArrayList<>(rows.size());
        for (ToolpackRow row : rows) {
            toolpacks.add(toDomain(row));
        }
        return toolpacks;
    }

    private ToolpackRow toRow(Toolpack toolpack) {
        ToolpackRow row = new ToolpackRow();
        row.setToolpackId(toolpack.toolpackId());
        row.setName(toolpack.name());
        row.setVersion(toolpack.version());
        row.setKind(toolpack.kind());
        row.setDescription(toolpack.description());
        row.setCreatedAt(Timestamp.from(toolpack.createdAt()));
        return row;
    }

    private Toolpack toDomain(ToolpackRow row) {
        return new Toolpack(
            row.getToolpackId(),
            row.getName(),
            row.getVersion(),
            row.getKind(),
            row.getDescription(),
            row.getCreatedAt().toInstant()
        );
    }
}

