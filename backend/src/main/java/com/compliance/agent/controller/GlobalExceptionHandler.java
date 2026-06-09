package com.compliance.agent.controller;

import com.compliance.agent.model.Models.ErrorResponse;
import com.compliance.agent.util.LlmUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.ai4j.openai4j.OpenAiHttpException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<ErrorResponse> handleJsonParsing(JsonProcessingException ex) {
        log.warn("LLM response JSON parse failure: {}", LlmUtils.sanitizeForLog(ex.getMessage(), 200));
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("PARSE_ERROR", "The AI response could not be parsed. Please try again."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedRequestBody(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", LlmUtils.sanitizeForLog(ex.getMessage(), 200));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "The request body is malformed."));
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ErrorResponse> handleExternalService(WebClientResponseException ex) {
        log.error("External service error: status={}", ex.getStatusCode());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("EXTERNAL_SERVICE_ERROR", "An upstream service is unavailable. Please try again later."));
    }

    @ExceptionHandler(OpenAiHttpException.class)
    public ResponseEntity<ErrorResponse> handleOpenAiHttpException(OpenAiHttpException ex) {
        HttpStatus status = ex.code() == 429 || ex.code() >= 500
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        String code = ex.code() == 429 ? "UPSTREAM_RATE_LIMIT" : "UPSTREAM_AI_ERROR";
        log.warn("LLM provider failure: status={}", ex.code());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, "The AI provider is temporarily unavailable. Please try again shortly."));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(TimeoutException ex) {
        log.warn("Request timed out: {}", LlmUtils.sanitizeForLog(ex.getMessage(), 200));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("TIMEOUT", "The request timed out. Please try again."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request rejected: {}", LlmUtils.sanitizeForLog(ex.getMessage(), 200));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", "The request contains invalid data."));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "One or more request parameters are invalid.";
        }
        log.warn("Constraint violation: {}", LlmUtils.sanitizeForLog(message, 200));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch: {}", LlmUtils.sanitizeForLog(ex.getMessage(), 200));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", "One or more request parameters are invalid."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled error: {}", LlmUtils.sanitizeForLog(ex.getMessage(), 200), ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred."));
    }
}
