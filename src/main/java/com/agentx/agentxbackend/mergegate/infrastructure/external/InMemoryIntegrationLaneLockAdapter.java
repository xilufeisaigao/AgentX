package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.mergegate.application.port.out.IntegrationLaneLockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
    name = "agentx.mergegate.lock.mode",
    havingValue = "in-memory",
    matchIfMissing = true
)
public class InMemoryIntegrationLaneLockAdapter implements IntegrationLaneLockPort {

    private final Set<String> locks = ConcurrentHashMap.newKeySet();

    @Override
    public boolean tryAcquire(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("lockKey must not be blank");
        }
        return locks.add(lockKey.trim());
    }

    @Override
    public void release(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("lockKey must not be blank");
        }
        locks.remove(lockKey.trim());
    }
}
