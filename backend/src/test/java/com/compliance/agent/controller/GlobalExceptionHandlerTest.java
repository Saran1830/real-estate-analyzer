package com.compliance.agent.controller;

import com.compliance.agent.model.Models.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMalformedRequestBodyReturnsBadRequest() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Malformed JSON payload", new MockHttpInputMessage(new byte[0]));

        var response = handler.handleMalformedRequestBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void handleTypeMismatchReturnsValidationError() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "sessionId", null, null);

        var response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("VALIDATION_ERROR");
    }
}
