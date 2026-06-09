package com.compliance.agent.controller;

import com.compliance.agent.agent.ComplianceAgentOrchestrator;
import com.compliance.agent.model.Models;
import com.compliance.agent.model.Models.*;
import com.compliance.agent.service.ConversationMemoryService;
import com.compliance.agent.service.RagService;
import com.compliance.agent.util.LlmUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
@Slf4j
@Validated
public class ComplianceController {

    private final ComplianceAgentOrchestrator orchestrator;
    private final ConversationMemoryService conversationMemoryService;
    private final RagService ragService;

    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(
            @Valid @RequestBody AnalyzeRequest request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default")
            @Size(max = 64, message = "tenantId must not exceed 64 characters")
            @Pattern(regexp = Models.SAFE_IDENTIFIER_REGEX,
                    message = "tenantId may only contain letters, numbers, underscores, and hyphens")
            String tenantId) {

        String effectiveTenant = request.tenantId() != null ? request.tenantId() : tenantId;
        log.info("Analyze request: type={} tenant={}",
                LlmUtils.safeIdentifier(request.documentType(), 32),
                LlmUtils.safeIdentifier(effectiveTenant, 32));
        AnalyzeResponse response = orchestrator.orchestrateAnalysis(
                request.documentText(), request.documentType(), effectiveTenant);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @Valid @RequestBody AskRequest request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "default")
            @Size(max = 64, message = "tenantId must not exceed 64 characters")
            @Pattern(regexp = Models.SAFE_IDENTIFIER_REGEX,
                    message = "tenantId may only contain letters, numbers, underscores, and hyphens")
            String tenantId) {

        String effectiveTenant = request.tenantId() != null ? request.tenantId() : tenantId;
        log.info("Ask request: session={} questionLength={}",
                LlmUtils.safeIdentifier(request.sessionId(), 32),
                request.question() == null ? 0 : request.question().length());
        AskResponse response = orchestrator.orchestrateQA(
                request.sessionId(), request.question(), effectiveTenant);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(
            @PathVariable
            @Size(max = 64, message = "sessionId must not exceed 64 characters")
            @Pattern(regexp = Models.SAFE_IDENTIFIER_REGEX,
                    message = "sessionId may only contain letters, numbers, underscores, and hyphens")
            String sessionId) {
        log.info("Clear session: {}", LlmUtils.safeIdentifier(sessionId, 32));
        conversationMemoryService.clearSession(sessionId);
        ragService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

}
