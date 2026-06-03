package com.compliance.agent.prompt;

public final class DealPromptTemplates {

    private DealPromptTemplates() {}

    public static final String DEAL_ANALYSIS_PROMPT = """
            You are an expert real estate investment analyst specializing in wholesale deals,
            fix-and-flip, and buy-and-hold rentals.

            Property Information:
            {propertyContext}

            {marketData}
            Documents provided ({docCount} total):
            {documents}

            Analyze this real estate deal and return ONLY a valid JSON object — no markdown fences, no explanation:
            {
              "verdict": "STRONG_BUY" | "BUY" | "MARGINAL" | "PASS",
              "score": <integer 0-100>,
              "strategy": "FIX_AND_FLIP" | "RENTAL" | "WHOLESALE" | "UNKNOWN",
              "framework": "70_PERCENT_RULE" | "CAP_RATE" | "CASH_ON_CASH" | "QUALITATIVE",
              "financials": {
                "askingPrice": <number or null>,
                "estimatedRepairs": <number or null>,
                "estimatedARV": <number or null>,
                "maxAllowableOffer": <number or null>,
                "projectedProfit": <number or null>,
                "roi": "<percentage string or null>",
                "projectedMonthlyRent": "<dollar string or null>",
                "capRate": "<percentage string or null>",
                "cashOnCash": "<percentage string or null>"
              },
              "marketNotes": "<2-3 sentences on location and market conditions>",
              "riskFactors": ["<risk 1>", "<risk 2>"],
              "complianceFlags": ["<contract issue affecting deal economics>"],
              "summary": "<2-3 sentence executive summary of this deal>",
              "recommendation": "<specific, actionable recommendation>"
            }

            Analysis guidelines:
            - Infer the investment strategy from the documents and context
            - FIX_AND_FLIP: apply 70% Rule — MAO = (70% x ARV) - Repairs
            - RENTAL: calculate cap rate (annual NOI / value) and cash-on-cash return
            - WHOLESALE: evaluate assignment spread and exit potential
            - Extract all financial figures from the documents; estimate conservatively where not stated
            - complianceFlags should only list contract terms that directly impact deal economics
            - Score bands: 90-100 = STRONG_BUY, 70-89 = BUY, 50-69 = MARGINAL, 0-49 = PASS
            """;
}
