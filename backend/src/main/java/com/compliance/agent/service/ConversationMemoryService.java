package com.compliance.agent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversationMemoryService {

    private static final int MAX_MESSAGES = 10;

    // One ChatMemory per sessionId — never shared across sessions
    private final Map<String, ChatMemory> sessions = new ConcurrentHashMap<>();

    public void addUserMessage(String sessionId, String message) {
        getOrCreate(sessionId).add(UserMessage.from(message));
    }

    public void addAiMessage(String sessionId, String message) {
        getOrCreate(sessionId).add(AiMessage.from(message));
    }

    public String getFormattedHistory(String sessionId) {
        ChatMemory memory = sessions.get(sessionId);
        if (memory == null || memory.messages().isEmpty()) {
            return "No previous conversation.";
        }
        return memory.messages().stream()
                .map(ConversationMemoryService::formatMessage)
                .collect(Collectors.joining("\n"));
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Cleared conversation memory for session={}", sessionId);
    }

    private ChatMemory getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId,
                id -> MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES));
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
