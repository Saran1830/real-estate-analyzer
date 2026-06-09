package com.compliance.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagServiceSecurityTest {

    @Test
    void collectionNameForSessionHashesInputInsteadOfUsingRawSessionId() {
        String collectionName = RagService.collectionNameForSession("session-abc/../../evil");

        assertThat(collectionName).startsWith("compliance_");
        assertThat(collectionName).doesNotContain("session-abc");
        assertThat(collectionName).matches("compliance_[0-9a-f]{24}");
    }
}
