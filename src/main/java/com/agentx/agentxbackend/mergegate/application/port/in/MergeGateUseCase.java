package com.agentx.agentxbackend.mergegate.application.port.in;

import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;

public interface MergeGateUseCase {

    MergeGateResult start(String taskId);
}
