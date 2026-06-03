package com.compliance.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryServiceTest {

    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationMemoryService();
    }

    @Test
    void emptySessionReturnsNoHistoryMessage() {
        String history = service.getFormattedHistory("session-1");
        assertThat(history).isEqualTo("No previous conversation.");
    }

    @Test
    void singleTurnFormatsCorrectly() {
        service.addUserMessage("s1", "What are the payment terms?");
        service.addAiMessage("s1", "Payment is due at closing.");

        String history = service.getFormattedHistory("s1");

        assertThat(history).contains("User: What are the payment terms?");
        assertThat(history).contains("Assistant: Payment is due at closing.");
    }

    @Test
    void multiTurnPreservesOrder() {
        service.addUserMessage("s1", "Question 1");
        service.addAiMessage("s1", "Answer 1");
        service.addUserMessage("s1", "Question 2");
        service.addAiMessage("s1", "Answer 2");

        String history = service.getFormattedHistory("s1");
        int idx1 = history.indexOf("Question 1");
        int idx2 = history.indexOf("Question 2");

        assertThat(idx1).isLessThan(idx2);
    }

    @Test
    void sessionsAreIsolated() {
        service.addUserMessage("session-A", "Question for A");
        service.addAiMessage("session-A", "Answer for A");

        String historyB = service.getFormattedHistory("session-B");

        assertThat(historyB).isEqualTo("No previous conversation.");
        assertThat(historyB).doesNotContain("Question for A");
    }

    @Test
    void clearSessionRemovesHistory() {
        service.addUserMessage("s1", "Question");
        service.addAiMessage("s1", "Answer");

        service.clearSession("s1");

        assertThat(service.getFormattedHistory("s1")).isEqualTo("No previous conversation.");
    }

    @Test
    void clearNonExistentSessionDoesNotThrow() {
        service.clearSession("ghost-session");
        // no exception expected
    }

    @Test
    void windowDropsOldestMessagesWhenFull() {
        // Window is 10 messages; add 12 turns (24 messages = 12 user + 12 ai)
        // After 10-message window, only last 10 messages remain
        for (int i = 1; i <= 12; i++) {
            service.addUserMessage("s1", "Q" + i);
            service.addAiMessage("s1", "A" + i);
        }

        String history = service.getFormattedHistory("s1");

        // Earliest messages should have been evicted
        assertThat(history).doesNotContain("Q1");
        // Recent messages should still be there
        assertThat(history).contains("Q12");
    }
}
