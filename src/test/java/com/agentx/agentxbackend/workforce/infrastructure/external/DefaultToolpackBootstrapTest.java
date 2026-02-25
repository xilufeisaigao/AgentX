package com.agentx.agentxbackend.workforce.infrastructure.external;

import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DefaultToolpackBootstrapTest {

    @Test
    void bootstrapShouldRegisterCommonToolpacksWhenEnabled() {
        WorkerCapabilityUseCase useCase = mock(WorkerCapabilityUseCase.class);
        DefaultToolpackBootstrap bootstrap = new DefaultToolpackBootstrap(useCase, true, false);

        bootstrap.bootstrap();

        verify(useCase, times(5))
            .registerToolpack(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(useCase, never()).registerWorker(anyString());
    }

    @Test
    void bootstrapShouldSkipWhenDisabled() {
        WorkerCapabilityUseCase useCase = mock(WorkerCapabilityUseCase.class);
        DefaultToolpackBootstrap bootstrap = new DefaultToolpackBootstrap(useCase, false, false);

        bootstrap.bootstrap();

        verify(useCase, never())
            .registerToolpack(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bootstrapShouldRegisterDefaultWorkersWhenEnabled() {
        WorkerCapabilityUseCase useCase = mock(WorkerCapabilityUseCase.class);
        DefaultToolpackBootstrap bootstrap = new DefaultToolpackBootstrap(useCase, true, true);

        bootstrap.bootstrap();

        verify(useCase, times(3)).registerWorker(anyString());
        verify(useCase, times(3)).bindToolpacks(anyString(), anyList());
        verify(useCase, times(3)).updateWorkerStatus(anyString(), eq(WorkerStatus.READY));
    }
}
