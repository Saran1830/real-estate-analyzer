package com.compliance.agent.service;

import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatGenerationServiceTest {

    @Test
    void generateRetries429AndEventuallySucceeds() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.generate("prompt"))
                .thenThrow(new OpenAiHttpException(429, "rate limited"))
                .thenReturn("ok");

        ChatGenerationService service = new ChatGenerationService(
                chatModel, 3, 1, 10, 2.0, 0, delayMs -> {});

        assertThat(service.generate("prompt")).isEqualTo("ok");
        verify(chatModel, times(2)).generate("prompt");
    }

    @Test
    void generateDoesNotRetryNonRetryableStatus() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.generate("prompt"))
                .thenThrow(new OpenAiHttpException(400, "bad request"));

        ChatGenerationService service = new ChatGenerationService(
                chatModel, 3, 1, 10, 2.0, 0, delayMs -> {});

        assertThatThrownBy(() -> service.generate("prompt"))
                .isInstanceOf(OpenAiHttpException.class);
        verify(chatModel, times(1)).generate("prompt");
    }

    @Test
    void generateStopsAfterMaxAttemptsForRetryableStatus() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        when(chatModel.generate("prompt"))
                .thenThrow(new OpenAiHttpException(429, "rate limited"));

        ChatGenerationService service = new ChatGenerationService(
                chatModel, 3, 1, 10, 2.0, 0, delayMs -> {});

        assertThatThrownBy(() -> service.generate("prompt"))
                .isInstanceOf(OpenAiHttpException.class);
        verify(chatModel, times(3)).generate("prompt");
    }
}
