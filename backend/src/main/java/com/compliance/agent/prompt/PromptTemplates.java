package com.compliance.agent.prompt;

public final class PromptTemplates {

    private PromptTemplates() {}

    public static final String GUARDRAIL_PROMPT = """
            You are a compliance document classifier.
            Your only job is to decide whether the user's input is related to document compliance review.
            Compliance-related: legal contracts, real estate agreements, NDAs, leases, purchase agreements,
            loan documents, LOIs, construction contracts, vendor contracts, payment terms, clauses, risk.
            Off-topic: weather, sports, cooking, entertainment, coding questions unrelated to documents.

            Input: {input}

            Reply with VALID if compliance-related, INVALID otherwise.
            Reply with EXACTLY one word — no punctuation, no explanation.
            """;

    public static final String ANALYZE_PROMPT = """
            You are a senior compliance attorney specializing in contract review.

            Document type: {documentType}

            Document:
            {document}

            Analyze this document and return a JSON object with this exact structure:
            {
              "riskLevel": "HIGH" | "MEDIUM" | "LOW",
              "summary": "2-3 sentence executive summary",
              "findings": [
                {
                  "clause": "clause name or section",
                  "risk": "HIGH" | "MEDIUM" | "LOW",
                  "explanation": "what the risk is and why it matters",
                  "confidence": "HIGH" | "MEDIUM" | "LOW"
                }
              ]
            }

            Focus on: payment terms, termination conditions, default consequences, liability caps,
            indemnification, dispute resolution, and unusual or missing standard clauses.
            Return ONLY valid JSON — no markdown fences, no preamble.
            """;

    public static final String REAL_ESTATE_ANALYZE_PROMPT = """
            You are a senior compliance attorney specializing in real estate transactions.

            Document type: {documentType}

            Document:
            {document}

            Analyze this real estate document and return a JSON object with this exact structure:
            {
              "riskLevel": "HIGH" | "MEDIUM" | "LOW",
              "summary": "2-3 sentence executive summary",
              "findings": [
                {
                  "clause": "clause name or section",
                  "risk": "HIGH" | "MEDIUM" | "LOW",
                  "explanation": "what the risk is and why it matters for the party",
                  "confidence": "HIGH" | "MEDIUM" | "LOW"
                }
              ]
            }

            Real estate focus areas: purchase price and earnest money, closing date and contingencies,
            default and remedy clauses, assignment rights, inspection periods, financing contingencies,
            title and escrow, representations and warranties, penalty and liquidated damages clauses,
            as-is provisions, and wholesaling-specific assignment language.
            Return ONLY valid JSON — no markdown fences, no preamble.
            """;

    public static final String QA_PROMPT = """
            You are a compliance document assistant helping a user understand a contract they are reviewing.

            Previous conversation:
            {history}

            Relevant document excerpts:
            {context}

            Current question: {question}

            Answer using only the document excerpts and conversation history above.
            If the answer is not in the provided material, say so clearly — do not speculate.
            Be concise and precise; quote relevant clause text where helpful.
            At the end of your answer add exactly: "Confidence: HIGH" or "Confidence: MEDIUM" or "Confidence: LOW"
            """;

    // Maps document type values to which prompt to use
    public static boolean useRealEstatePrompt(String documentType) {
        return switch (documentType.toLowerCase()) {
            case "wholesale_purchase_agreement",
                 "loan_agreement",
                 "letter_of_intent",
                 "commercial_sales_agreement",
                 "residential_lease",
                 "executive_developer_program" -> true;
            default -> false;
        };
    }
}
