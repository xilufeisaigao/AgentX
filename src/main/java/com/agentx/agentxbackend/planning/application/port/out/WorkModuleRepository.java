package com.agentx.agentxbackend.planning.application.port.out;

import com.agentx.agentxbackend.planning.domain.model.WorkModule;

import java.util.Optional;

public interface WorkModuleRepository {

    WorkModule save(WorkModule module);

    Optional<WorkModule> findById(String moduleId);
}
