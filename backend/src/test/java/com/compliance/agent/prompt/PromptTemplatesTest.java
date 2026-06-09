package com.compliance.agent.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplatesTest {

    @Test
    void qaPromptContainsAllPlaceholders() {
        assertThat(PromptTemplates.QA_PROMPT).contains("{history}");
        assertThat(PromptTemplates.QA_PROMPT).contains("{context}");
        assertThat(PromptTemplates.QA_PROMPT).contains("{question}");
    }

    @Test
    void analyzePromptContainsRequiredPlaceholders() {
        assertThat(PromptTemplates.ANALYZE_PROMPT).contains("{documentType}");
        assertThat(PromptTemplates.ANALYZE_PROMPT).contains("{document}");
    }

    @Test
    void realEstatePromptContainsRequiredPlaceholders() {
        assertThat(PromptTemplates.REAL_ESTATE_ANALYZE_PROMPT).contains("{documentType}");
        assertThat(PromptTemplates.REAL_ESTATE_ANALYZE_PROMPT).contains("{document}");
    }

    @Test
    void guardrailPromptContainsInputPlaceholder() {
        assertThat(PromptTemplates.GUARDRAIL_PROMPT).contains("{input}");
    }

    @Test
    void analyzePromptInstructsJsonOnlyResponse() {
        assertThat(PromptTemplates.ANALYZE_PROMPT.toLowerCase()).contains("json");
    }

    @Test
    void promptsCallOutUntrustedContent() {
        assertThat(PromptTemplates.GUARDRAIL_PROMPT.toLowerCase()).contains("untrusted");
        assertThat(PromptTemplates.ANALYZE_PROMPT.toLowerCase()).contains("untrusted");
        assertThat(PromptTemplates.QA_PROMPT.toLowerCase()).contains("untrusted");
        assertThat(DealPromptTemplates.DEAL_ANALYSIS_PROMPT.toLowerCase()).contains("untrusted");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "wholesale_purchase_agreement",
            "loan_agreement",
            "letter_of_intent",
            "commercial_sales_agreement",
            "residential_lease"
    })
    void realEstateDocTypesUseRealEstatePrompt(String docType) {
        assertThat(PromptTemplates.useRealEstatePrompt(docType)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nda",
            "vendor_contract",
            "design_construction_agreement",
            "unknown_type"
    })
    void nonRealEstateDocTypesUseGenericPrompt(String docType) {
        assertThat(PromptTemplates.useRealEstatePrompt(docType)).isFalse();
    }

    @Test
    void useRealEstatePromptIsCaseInsensitive() {
        assertThat(PromptTemplates.useRealEstatePrompt("LOAN_AGREEMENT")).isTrue();
        assertThat(PromptTemplates.useRealEstatePrompt("Wholesale_Purchase_Agreement")).isTrue();
    }

    @Test
    void qaPromptSubstitutionWorksCorrectly() {
        String filled = PromptTemplates.QA_PROMPT
                .replace("{history}", "User: Hi\nAssistant: Hello")
                .replace("{context}", "Clause 5 says...")
                .replace("{question}", "What is clause 5?");

        assertThat(filled).contains("User: Hi");
        assertThat(filled).contains("Clause 5 says...");
        assertThat(filled).contains("What is clause 5?");
        assertThat(filled).doesNotContain("{history}");
        assertThat(filled).doesNotContain("{context}");
        assertThat(filled).doesNotContain("{question}");
    }
}
