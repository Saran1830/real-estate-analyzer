package com.compliance.agent.model;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class Models {

    private Models() {}

    public record AnalyzeRequest(
            @NotBlank String documentText,
            @NotBlank String documentType,
            String tenantId
    ) {}

    public record AnalyzeResponse(
            String sessionId,
            String riskLevel,
            String summary,
            List<Finding> findings,
            List<NodeExecution> agentTrace
    ) {}

    public record AskRequest(
            @NotBlank String sessionId,
            @NotBlank String question,
            String tenantId
    ) {}

    public record AskResponse(
            String answer,
            String confidence,
            List<SourceChunk> sources,
            List<RerankScore> rerankScores,
            List<NodeExecution> agentTrace
    ) {}

    public record Finding(
            String clause,
            String risk,
            String explanation,
            String confidence
    ) {}

    public record NodeExecution(
            String node,
            String status,
            long latencyMs,
            String detail
    ) {}

    public record SourceChunk(
            String text,
            double cosineScore,
            double rerankScore
    ) {}

    public record RerankScore(
            int originalIndex,
            double cosineScore,
            double rerankScore,
            String excerpt
    ) {}

    public record ErrorResponse(
            String error,
            String message
    ) {}
}
