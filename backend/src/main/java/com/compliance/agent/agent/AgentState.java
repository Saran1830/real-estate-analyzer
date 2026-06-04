package com.compliance.agent.agent;

/**
 * Immutable state record that flows through each LangGraph-style node.
 * Nodes return a new instance with updated fields rather than mutating this one.
 */
public record AgentState(
        String sessionId,
        String tenantId,
        String documentText,
        String documentType,
        String question,
        GuardrailStatus guardrailStatus,
        String guardrailReason,
        boolean ingestFailed,
        String ingestError
) {
    public enum GuardrailStatus { PENDING, VALID, INVALID }

    public static AgentState forAnalysis(String sessionId, String tenantId,
                                         String documentText, String documentType) {
        return new AgentState(sessionId, tenantId, documentText, documentType,
                null, GuardrailStatus.PENDING, null, false, null);
    }

    public static AgentState forQA(String sessionId, String tenantId, String question) {
        return new AgentState(sessionId, tenantId, null, null,
                question, GuardrailStatus.PENDING, null, false, null);
    }

    public AgentState withGuardrail(GuardrailStatus status, String reason) {
        return new AgentState(sessionId, tenantId, documentText, documentType,
                question, status, reason, ingestFailed, ingestError);
    }

    public AgentState withIngestFailed(String error) {
        return new AgentState(sessionId, tenantId, documentText, documentType,
                question, guardrailStatus, guardrailReason, true, error);
    }
}
