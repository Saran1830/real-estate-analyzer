package com.compliance.agent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class LlmUtils {

    private LlmUtils() {}

    public static String stripMarkdownFences(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("```(?:json)?\\s*", "").replaceAll("```", "").trim();
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    public static String sanitizeText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return truncate(normalized, maxChars);
    }

    public static String sanitizeForLog(String text, int maxChars) {
        if (text == null) {
            return "<null>";
        }
        return sanitizeText(text, maxChars);
    }

    public static String safeIdentifier(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        String normalized = text.replaceAll("[^A-Za-z0-9._-]", "_");
        return truncate(normalized, maxChars);
    }

    public static String wrapAsUntrustedBlock(String label, String text) {
        String safeLabel = safeIdentifier(label, 32).toUpperCase(Locale.ROOT);
        String body = text == null ? "" : text;
        return "BEGIN_UNTRUSTED_" + safeLabel + "\n" + body + "\nEND_UNTRUSTED_" + safeLabel;
    }

    public static int clamp(int value, int minInclusive, int maxInclusive) {
        if (minInclusive > maxInclusive) {
            throw new IllegalArgumentException("minInclusive must not exceed maxInclusive");
        }
        return Math.max(minInclusive, Math.min(maxInclusive, value));
    }

    public static String normalizeChoice(String value, String defaultValue, String... allowedChoices) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String candidate = value.trim().toUpperCase(Locale.ROOT);
        if (allowedChoices != null) {
            for (String allowed : allowedChoices) {
                if (allowed != null && allowed.equalsIgnoreCase(candidate)) {
                    return allowed.toUpperCase(Locale.ROOT);
                }
            }
        }
        return defaultValue;
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
