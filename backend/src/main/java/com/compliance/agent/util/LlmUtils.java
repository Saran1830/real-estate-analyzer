package com.compliance.agent.util;

public final class LlmUtils {

    private LlmUtils() {}

    public static String stripMarkdownFences(String text) {
        return text.replaceAll("```(?:json)?\\s*", "").replaceAll("```", "").trim();
    }

    public static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}
