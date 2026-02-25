package com.agentx.agentxbackend.requirement.infrastructure.external;

import com.agentx.agentxbackend.requirement.application.port.out.RequirementConversationHistoryRepository;
import com.agentx.agentxbackend.requirement.application.port.out.RequirementDraftGeneratorPort.ConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class RedisRequirementConversationHistoryRepository implements RequirementConversationHistoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisRequirementConversationHistoryRepository.class);
    private static final String REDIS_KEY_PREFIX = "agentx:req:discovery:history:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final Duration ttl;

    public RedisRequirementConversationHistoryRepository(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${agentx.requirement.discovery-history.max-messages:24}") int maxMessages,
        @Value("${agentx.requirement.discovery-history.ttl-seconds:86400}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxMessages = Math.max(4, maxMessages);
        this.ttl = Duration.ofSeconds(Math.max(60L, ttlSeconds));
    }

    @Override
    public List<ConversationTurn> load(String sessionId) {
        String key = redisKey(sessionId);
        try {
            ListOperations<String, String> ops = redisTemplate.opsForList();
            List<String> encoded = ops.range(key, 0, -1);
            if (encoded == null || encoded.isEmpty()) {
                return List.of();
            }
            ArrayList<ConversationTurn> turns = new ArrayList<>(encoded.size());
            for (String item : encoded) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                try {
                    ConversationTurnRecord record = objectMapper.readValue(item, ConversationTurnRecord.class);
                    turns.add(new ConversationTurn(record.role(), record.content()));
                } catch (Exception parseException) {
                    log.warn("Failed to parse discovery history record, key={}", key, parseException);
                }
            }
            if (turns.isEmpty()) {
                return List.of();
            }
            redisTemplate.expire(key, ttl);
            return List.copyOf(turns);
        } catch (RuntimeException ex) {
            log.warn("Failed to load discovery history from redis, key={}", key, ex);
            return List.of();
        }
    }

    @Override
    public void append(String sessionId, ConversationTurn turn) {
        if (turn == null) {
            return;
        }
        String key = redisKey(sessionId);
        try {
            String encoded = objectMapper.writeValueAsString(new ConversationTurnRecord(turn.role(), turn.content()));
            ListOperations<String, String> ops = redisTemplate.opsForList();
            ops.rightPush(key, encoded);
            ops.trim(key, -maxMessages, -1);
            redisTemplate.expire(key, ttl);
        } catch (Exception ex) {
            log.warn("Failed to append discovery history to redis, key={}", key, ex);
        }
    }

    @Override
    public void clear(String sessionId) {
        String key = redisKey(sessionId);
        try {
            redisTemplate.delete(Collections.singleton(key));
        } catch (RuntimeException ex) {
            log.warn("Failed to clear discovery history from redis, key={}", key, ex);
        }
    }

    private static String redisKey(String sessionId) {
        return REDIS_KEY_PREFIX + sessionId;
    }

    private record ConversationTurnRecord(String role, String content) {
    }
}
