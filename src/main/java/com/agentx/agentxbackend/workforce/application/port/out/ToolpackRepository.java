package com.agentx.agentxbackend.workforce.application.port.out;

import com.agentx.agentxbackend.workforce.domain.model.Toolpack;

import java.util.List;
import java.util.Optional;

public interface ToolpackRepository {

    Toolpack save(Toolpack toolpack);

    Optional<Toolpack> findById(String toolpackId);

    Optional<Toolpack> findByNameAndVersion(String name, String version);

    List<Toolpack> findAll();

    List<Toolpack> findByWorkerId(String workerId);
}
