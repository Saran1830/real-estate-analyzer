package com.compliance.agent.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class DealModels {

    private DealModels() {}

    public record DealDocument(
            @NotBlank
            @Size(max = 255, message = "document name must not exceed 255 characters")
            String name,

            @NotBlank
            @Size(max = 204_800, message = "document text must not exceed 200 KB")
            String text,

            @NotBlank
            String type
    ) {}

    public record DealAnalysisRequest(
            @NotEmpty
            @Valid
            List<DealDocument> documents,

            @Size(max = 500, message = "address must not exceed 500 characters")
            String address,

            Double askingPrice,
            Double estimatedRepairs,

            @Size(max = 2_000, message = "notes must not exceed 2000 characters")
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
