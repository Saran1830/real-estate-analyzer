package com.compliance.agent.controller;

import com.compliance.agent.agent.DealAnalyzerOrchestrator;
import com.compliance.agent.model.DealModels.*;
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
        log.info("Deal analysis request: docs={} addressProvided={}",
                request.documents().size(), request.address() != null && !request.address().isBlank());
        DealAnalysisResponse response = orchestrator.analyzeDeal(request);
        log.info("Deal analysis completed: verdict={} score={} sessionId={}",
                response.verdict(), response.score(), response.sessionId());
        return ResponseEntity.ok(response);
    }

}
