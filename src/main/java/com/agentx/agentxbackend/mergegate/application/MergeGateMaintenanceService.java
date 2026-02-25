package com.agentx.agentxbackend.mergegate.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateMaintenanceUseCase;
import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import org.springframework.stereotype.Service;

@Service
public class MergeGateMaintenanceService implements MergeGateMaintenanceUseCase {

    private final GitClientPort gitClientPort;

    public MergeGateMaintenanceService(GitClientPort gitClientPort) {
        this.gitClientPort = gitClientPort;
    }

    @Override
    public boolean recoverRepositoryIfNeeded() {
        return gitClientPort.recoverRepositoryIfNeeded();
    }
}
