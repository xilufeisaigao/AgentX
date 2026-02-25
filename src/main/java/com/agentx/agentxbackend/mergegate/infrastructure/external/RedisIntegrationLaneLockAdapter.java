package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.mergegate.application.port.out.IntegrationLaneLockPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "agentx.mergegate.lock.mode", havingValue = "redis")
public class RedisIntegrationLaneLockAdapter implements IntegrationLaneLockPort {

    private static final RedisScript<Long> RELEASE_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
        Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;
    private final String keyPrefix;
    private final Duration ttl;
    private final String ownerToken;

    public RedisIntegrationLaneLockAdapter(
        StringRedisTemplate stringRedisTemplate,
        @Value("${agentx.mergegate.lock.redis.key-prefix:agentx:mergegate:lock:}") String keyPrefix,
        @Value("${agentx.mergegate.lock.redis.ttl-seconds:600}") int ttlSeconds
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.keyPrefix = keyPrefix == null ? "agentx:mergegate:lock:" : keyPrefix;
        this.ttl = Duration.ofSeconds(Math.max(30, ttlSeconds));
        this.ownerToken = UUID.randomUUID().toString();
    }

    @Override
    public boolean tryAcquire(String lockKey) {
        String redisKey = toRedisKey(lockKey);
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, ownerToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void release(String lockKey) {
        String redisKey = toRedisKey(lockKey);
        stringRedisTemplate.execute(
            RELEASE_IF_OWNER_SCRIPT,
            Collections.singletonList(redisKey),
            ownerToken
        );
    }

    private String toRedisKey(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("lockKey must not be blank");
        }
        return keyPrefix + lockKey.trim();
    }
}
