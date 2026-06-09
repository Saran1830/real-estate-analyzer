package com.compliance.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class Models {

    private Models() {}

    public static final String SAFE_IDENTIFIER_REGEX = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$";
    public static final String DOCUMENT_TYPE_REGEX = "^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$";

    public record AnalyzeRequest(
            @NotBlank
            @Size(max = 102_400, message = "documentText must not exceed 100 KB")
            String documentText,

            @NotBlank
            @Size(max = 100, message = "documentType must not exceed 100 characters")
            @Pattern(regexp = DOCUMENT_TYPE_REGEX,
                    message = "documentType may only contain letters, numbers, underscores, and hyphens")
            String documentType,

            @Size(max = 64, message = "tenantId must not exceed 64 characters")
            @Pattern(regexp = SAFE_IDENTIFIER_REGEX,
                    message = "tenantId may only contain letters, numbers, underscores, and hyphens")
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
            @NotBlank
            @Size(max = 64, message = "sessionId must not exceed 64 characters")
            @Pattern(regexp = SAFE_IDENTIFIER_REGEX,
                    message = "sessionId may only contain letters, numbers, underscores, and hyphens")
            String sessionId,

            @NotBlank
            @Size(max = 1_000, message = "question must not exceed 1000 characters")
            String question,

            @Size(max = 64, message = "tenantId must not exceed 64 characters")
            @Pattern(regexp = SAFE_IDENTIFIER_REGEX,
                    message = "tenantId may only contain letters, numbers, underscores, and hyphens")
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
