package com.compliance.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversationMemoryService {

    private static final int MAX_MESSAGES = 10;
    private static final long SESSION_TTL_HOURS = 1;

    private final Map<String, ChatMemory> sessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAccessed = new ConcurrentHashMap<>();

    public void addUserMessage(String sessionId, String message) {
        touch(sessionId);
        getOrCreate(sessionId).add(UserMessage.from(message));
    }

    public void addAiMessage(String sessionId, String message) {
        touch(sessionId);
        getOrCreate(sessionId).add(AiMessage.from(message));
    }

    public String getFormattedHistory(String sessionId) {
        touch(sessionId);
        ChatMemory memory = sessions.get(sessionId);
        if (memory == null || memory.messages().isEmpty()) return "No previous conversation.";
        return memory.messages().stream()
                .map(ConversationMemoryService::formatMessage)
                .collect(Collectors.joining("\n"));
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        lastAccessed.remove(sessionId);
        log.info("Cleared conversation memory for session={}", sessionId);
    }

    @Scheduled(fixedDelay = 600_000) // every 10 minutes
    public void evictExpiredSessions() {
        try {
            Instant cutoff = Instant.now().minus(SESSION_TTL_HOURS, ChronoUnit.HOURS);
            lastAccessed.entrySet().removeIf(entry -> {
                if (entry.getValue().isBefore(cutoff)) {
                    sessions.remove(entry.getKey());
                    log.debug("Evicted expired conversation session={}", entry.getKey());
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.error("Conversation eviction task failed — will retry on next tick: {}", e.getMessage(), e);
        }
    }

    private void touch(String sessionId) {
        lastAccessed.put(sessionId, Instant.now());
    }

    private ChatMemory getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId,
                id -> MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES));
    }

    private static String formatMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) return "User: " + um.singleText();
        if (msg instanceof AiMessage am) return "Assistant: " + am.text();
        return "";
    }
}
