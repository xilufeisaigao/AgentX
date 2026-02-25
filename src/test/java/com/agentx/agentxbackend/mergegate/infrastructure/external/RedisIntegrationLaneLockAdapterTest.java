package com.agentx.agentxbackend.mergegate.infrastructure.external;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisIntegrationLaneLockAdapterTest {

    @Test
    void tryAcquireShouldUseSetIfAbsentAndReturnResult() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("prefix:integration-lane"), any(String.class), any(Duration.class)))
            .thenReturn(Boolean.TRUE)
            .thenReturn(Boolean.FALSE);

        RedisIntegrationLaneLockAdapter adapter = new RedisIntegrationLaneLockAdapter(redisTemplate, "prefix:", 120);

        assertTrue(adapter.tryAcquire("integration-lane"));
        assertFalse(adapter.tryAcquire("integration-lane"));
    }

    @Test
    void releaseShouldExecuteAtomicLuaScriptWithCurrentOwnerToken() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        AtomicReference<String> ownerTokenRef = new AtomicReference<>();
        when(valueOps.setIfAbsent(eq("prefix:integration-lane"), any(String.class), any(Duration.class)))
            .thenAnswer(invocation -> {
                ownerTokenRef.set(invocation.getArgument(1, String.class));
                return Boolean.TRUE;
            });
        when(redisTemplate.execute(
            any(RedisScript.class),
            eq(Collections.singletonList("prefix:integration-lane")),
            any(String.class)
        )).thenReturn(1L);

        RedisIntegrationLaneLockAdapter adapter = new RedisIntegrationLaneLockAdapter(redisTemplate, "prefix:", 120);
        assertTrue(adapter.tryAcquire("integration-lane"));
        adapter.release("integration-lane");
        verify(redisTemplate).execute(
            any(RedisScript.class),
            eq(Collections.singletonList("prefix:integration-lane")),
            eq(ownerTokenRef.get())
        );
    }

    @Test
    void releaseShouldUseAtomicLuaScriptEvenWithoutOwnership() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.execute(
            any(RedisScript.class),
            eq(Collections.singletonList("prefix:integration-lane")),
            any(String.class)
        )).thenReturn(0L);

        RedisIntegrationLaneLockAdapter adapter = new RedisIntegrationLaneLockAdapter(redisTemplate, "prefix:", 120);
        adapter.release("integration-lane");
        verify(redisTemplate).execute(
            any(RedisScript.class),
            eq(Collections.singletonList("prefix:integration-lane")),
            any(String.class)
        );
    }

    @Test
    void tryAcquireShouldRejectBlankLockKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisIntegrationLaneLockAdapter adapter = new RedisIntegrationLaneLockAdapter(redisTemplate, "prefix:", 120);

        assertThrows(IllegalArgumentException.class, () -> adapter.tryAcquire(" "));
    }
}
