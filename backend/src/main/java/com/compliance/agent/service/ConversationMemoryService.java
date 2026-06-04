package com.compliance.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversationMemoryService {

    private static final int MAX_MESSAGES = 10;

    // One bounded deque per sessionId — never shared across sessions
    private final Map<String, Deque<ChatMessage>> sessions = new ConcurrentHashMap<>();

    public void addUserMessage(String sessionId, String message) {
        Deque<ChatMessage> mem = getOrCreate(sessionId);
        mem.addLast(UserMessage.from(message));
        evictIfNeeded(mem);
    }

    public void addAiMessage(String sessionId, String message) {
        Deque<ChatMessage> mem = getOrCreate(sessionId);
        mem.addLast(AiMessage.from(message));
        evictIfNeeded(mem);
    }

    public String getFormattedHistory(String sessionId) {
        Deque<ChatMessage> memory = sessions.get(sessionId);
        if (memory == null || memory.isEmpty()) {
            return "No previous conversation.";
        }
        return memory.stream()
                .map(ConversationMemoryService::formatMessage)
                .collect(Collectors.joining("\n"));
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Cleared conversation memory for session={}", sessionId);
    }

    private Deque<ChatMessage> getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new ArrayDeque<>());
    }

    private void evictIfNeeded(Deque<ChatMessage> mem) {
        while (mem.size() > MAX_MESSAGES) {
            mem.removeFirst();
        }
    }

    private static String formatMessage(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return "User: " + um.singleText();
        } else if (msg instanceof AiMessage am) {
            return "Assistant: " + am.text();
        }
        return "";
    }
}
