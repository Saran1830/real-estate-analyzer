package com.compliance.agent.controller;

import com.compliance.agent.agent.DealAnalyzerOrchestrator;
import com.compliance.agent.model.DealModels.*;
import com.compliance.agent.model.Models.ErrorResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/deal")
@RequiredArgsConstructor
@Slf4j
public class DealController {

    private final DealAnalyzerOrchestrator orchestrator;

    @PostMapping("/analyze")
    public ResponseEntity<DealAnalysisResponse> analyze(@Valid @RequestBody DealAnalysisRequest request) {
        log.info("Deal analysis request: docs={} address='{}'",
                request.documents().size(), request.address());
        DealAnalysisResponse response = orchestrator.analyzeDeal(request);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleError(Exception e) {
        log.error("Deal analysis error: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("INTERNAL_ERROR", e.getMessage()));
    }
}
