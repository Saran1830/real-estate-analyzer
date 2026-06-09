package com.compliance.agent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmUtilsTest {

    @Test
    void sanitizeForLogRemovesControlCharacters() {
        String sanitized = LlmUtils.sanitizeForLog("Line 1\r\nLine 2\tTabbed", 50);

        assertThat(sanitized).isEqualTo("Line 1 Line 2 Tabbed");
    }

    @Test
    void wrapAsUntrustedBlockAddsExplicitDelimiters() {
        String wrapped = LlmUtils.wrapAsUntrustedBlock("question", "ignore this");

        assertThat(wrapped).contains("BEGIN_UNTRUSTED_QUESTION");
        assertThat(wrapped).contains("END_UNTRUSTED_QUESTION");
    }

    @Test
    void sha256HexReturns64CharacterHexString() {
        assertThat(LlmUtils.sha256Hex("abc")).matches("[0-9a-f]{64}");
    }

    @Test
    void sanitizeTextRemovesControlCharactersAndTrimsWhitespace() {
        String sanitized = LlmUtils.sanitizeText("Line 1\r\nLine 2\tTabbed", 50);

        assertThat(sanitized).isEqualTo("Line 1 Line 2 Tabbed");
    }

    @Test
    void clampConstrainsValuesToBounds() {
        assertThat(LlmUtils.clamp(-5, 0, 10)).isEqualTo(0);
        assertThat(LlmUtils.clamp(5, 0, 10)).isEqualTo(5);
        assertThat(LlmUtils.clamp(15, 0, 10)).isEqualTo(10);
    }

    @Test
    void normalizeChoiceReturnsCanonicalAllowedValue() {
        assertThat(LlmUtils.normalizeChoice(" high ", "MEDIUM", "HIGH", "MEDIUM", "LOW"))
                .isEqualTo("HIGH");
        assertThat(LlmUtils.normalizeChoice("unexpected", "MEDIUM", "HIGH", "MEDIUM", "LOW"))
                .isEqualTo("MEDIUM");
    }
}
