package com.agentx.agentxbackend.process.application.port.out;

import java.util.List;

public interface RuntimeEnvironmentPort {

    PreparedEnvironment ensureReady(String sessionId, String workerId, List<String> requiredToolpackIds);

    record PreparedEnvironment(
        String projectEnvironmentPath,
        String pythonVenvPath,
        List<String> preparedToolpackIds
    ) {
    }
}
