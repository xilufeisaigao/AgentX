package com.agentx.agentxbackend.mergegate.application;

import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeGateMaintenanceServiceTest {

    @Test
    void recoverRepositoryIfNeededShouldDelegateToGitClient() {
        GitClientPort gitClientPort = mock(GitClientPort.class);
        when(gitClientPort.recoverRepositoryIfNeeded()).thenReturn(true);
        MergeGateMaintenanceService service = new MergeGateMaintenanceService(gitClientPort);

        boolean recovered = service.recoverRepositoryIfNeeded();

        assertTrue(recovered);
        verify(gitClientPort).recoverRepositoryIfNeeded();
    }
}
