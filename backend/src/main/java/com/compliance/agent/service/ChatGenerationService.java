package com.compliance.agent.service;

import com.compliance.agent.util.LlmUtils;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

@Service
@Slf4j
public class ChatGenerationService {

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 409, 425, 429, 500, 502, 503, 504);

    private final OpenAiChatModel chatModel;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double backoffMultiplier;
    private final long jitterMs;
    private final Sleeper sleeper;

    public ChatGenerationService(
            OpenAiChatModel chatModel,
            @Value("${llm.retry.max-attempts:4}") int maxAttempts,
            @Value("${llm.retry.initial-backoff-ms:1500}") long initialBackoffMs,
            @Value("${llm.retry.max-backoff-ms:12000}") long maxBackoffMs,
            @Value("${llm.retry.backoff-multiplier:2.0}") double backoffMultiplier,
            @Value("${llm.retry.jitter-ms:250}") long jitterMs) {
        this(chatModel, maxAttempts, initialBackoffMs, maxBackoffMs, backoffMultiplier, jitterMs, Thread::sleep);
    }

    ChatGenerationService(OpenAiChatModel chatModel,
                          int maxAttempts,
                          long initialBackoffMs,
                          long maxBackoffMs,
                          double backoffMultiplier,
                          long jitterMs,
                          Sleeper sleeper) {
        this.chatModel = chatModel;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(0, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
        this.backoffMultiplier = backoffMultiplier < 1.0 ? 1.0 : backoffMultiplier;
        this.jitterMs = Math.max(0, jitterMs);
        this.sleeper = sleeper;
    }

    public String generate(String prompt) {
        RuntimeException lastException = null;
        String promptHash = LlmUtils.sha256Hex(prompt);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return chatModel.generate(prompt);
            } catch (RuntimeException ex) {
                lastException = ex;
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    throw ex;
                }

                long delayMs = computeDelayMs(attempt);
                log.warn("Transient LLM failure, retrying: attempt={}/{} delayMs={} reason={} promptHash={}",
                        attempt, maxAttempts, delayMs, retryReason(ex), promptHash);
                sleep(delayMs);
            }
        }

        throw lastException == null ? new IllegalStateException("LLM call failed without an exception")
                : lastException;
    }

    private boolean isRetryable(Throwable throwable) {
        OpenAiHttpException httpException = findCause(throwable, OpenAiHttpException.class);
        if (httpException != null) {
            return RETRYABLE_STATUS_CODES.contains(httpException.code());
        }
        return findCause(throwable, SocketTimeoutException.class) != null
                || findCause(throwable, TimeoutException.class) != null
                || findCause(throwable, ConnectException.class) != null;
    }

    private String retryReason(Throwable throwable) {
        OpenAiHttpException httpException = findCause(throwable, OpenAiHttpException.class);
        if (httpException != null) {
            return "http_" + httpException.code();
        }
        Throwable timeout = findCause(throwable, SocketTimeoutException.class);
        if (timeout != null) {
            return timeout.getClass().getSimpleName();
        }
        timeout = findCause(throwable, TimeoutException.class);
        if (timeout != null) {
            return timeout.getClass().getSimpleName();
        }
        Throwable connect = findCause(throwable, ConnectException.class);
        if (connect != null) {
            return connect.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName();
    }

    private long computeDelayMs(int attempt) {
        if (attempt <= 0 || initialBackoffMs <= 0) {
            return 0;
        }
        double rawDelay = initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1);
        long boundedDelay = Math.min(maxBackoffMs, (long) rawDelay);
        long jitter = jitterMs == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMs + 1);
        return boundedDelay + jitter;
    }

    private void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            sleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry LLM call", e);
        }
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long delayMs) throws InterruptedException;
    }
}
