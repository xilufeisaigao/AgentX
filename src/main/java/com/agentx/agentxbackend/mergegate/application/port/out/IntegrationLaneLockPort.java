package com.agentx.agentxbackend.mergegate.application.port.out;

public interface IntegrationLaneLockPort {

    boolean tryAcquire(String lockKey);

    void release(String lockKey);
}
