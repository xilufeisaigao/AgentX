package com.agentx.agentxbackend.mergegate.infrastructure.external;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryIntegrationLaneLockAdapterTest {

    @Test
    void tryAcquireShouldAllowOnlyOneWinnerUnderConcurrency() throws Exception {
        InMemoryIntegrationLaneLockAdapter adapter = new InMemoryIntegrationLaneLockAdapter();
        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(() -> adapter.tryAcquire("integration-lane"));
            }
            List<Future<Boolean>> results = executor.invokeAll(calls);
            int successCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successCount++;
                }
            }
            assertEquals(1, successCount);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releaseShouldAllowReacquire() {
        InMemoryIntegrationLaneLockAdapter adapter = new InMemoryIntegrationLaneLockAdapter();

        assertTrue(adapter.tryAcquire("integration-lane"));
        adapter.release("integration-lane");
        assertTrue(adapter.tryAcquire("integration-lane"));
    }
}
