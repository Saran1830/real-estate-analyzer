package com.compliance.agent.controller;

import com.compliance.agent.agent.ComplianceAgentOrchestrator;
import com.compliance.agent.model.Models.*;
import com.compliance.agent.service.ConversationMemoryService;
import com.compliance.agent.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
@Slf4j
public class ComplianceController {

    private final ComplianceAgentOrchestrator orchestrator;
    private final ConversationMemoryService conversationMemoryService;
    private final RagService ragService;

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(
            @Valid @RequestBody AnalyzeRequest request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default") String tenantId) {

        log.info("Analyze request: type={} tenant={}", request.documentType(), tenantId);
        String effectiveTenant = request.tenantId() != null ? request.tenantId() : tenantId;
        AnalyzeResponse response = orchestrator.orchestrateAnalysis(
                request.documentText(), request.documentType(), effectiveTenant);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default") String tenantId) {

        log.info("Ask request: session={} question='{}'", request.sessionId(), request.question());
        String effectiveTenant = request.tenantId() != null ? request.tenantId() : tenantId;
        AskResponse response = orchestrator.orchestrateQA(
                request.sessionId(), request.question(), effectiveTenant);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        log.info("Clear session: {}", sessionId);
        conversationMemoryService.clearSession(sessionId);
        ragService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleError(Exception e) {
        log.error("Unhandled error: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", e.getMessage()));
    }
}
