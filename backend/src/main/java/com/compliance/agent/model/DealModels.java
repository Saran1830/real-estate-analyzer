package com.compliance.agent.model;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public final class DealModels {

    private DealModels() {}

    public record DealDocument(
            String name,
            String text,
            String type
    ) {}

    public record DealAnalysisRequest(
            @NotEmpty List<DealDocument> documents,
            String address,
            Double askingPrice,
            Double estimatedRepairs,
            String notes
    ) {}

    public record DealFinancials(
            Double askingPrice,
            Double estimatedRepairs,
            Double estimatedARV,
            Double maxAllowableOffer,
            Double projectedProfit,
            String roi,
            String projectedMonthlyRent,
            String capRate,
            String cashOnCash
    ) {}

    public record DealAnalysisResponse(
            String sessionId,
            String verdict,
            int score,
            String strategy,
            String framework,
            DealFinancials financials,
            String marketNotes,
            List<String> riskFactors,
            List<String> complianceFlags,
            String summary,
            String recommendation,
            List<Models.NodeExecution> agentTrace
    ) {}
}
