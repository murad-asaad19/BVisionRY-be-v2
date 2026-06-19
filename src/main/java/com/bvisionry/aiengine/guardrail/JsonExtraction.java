package com.bvisionry.aiengine.guardrail;

/**
 * Extracts a single JSON object from a model's raw text, model-agnostically.
 *
 * <p>Replaces the previous {@code lastIndexOf('}')} heuristic, which mis-extracts
 * when the model appends trailing prose containing braces. This scanner walks the
 * text tracking string literals and brace depth, returning the first
 * balanced {@code { … }} object — so trailing commentary is ignored (F1) and a
 * truncated/unterminated object yields {@code null} (F2/F3) instead of malformed
 * input. Markdown code fences are stripped first.
 */
public final class JsonExtraction {

    private JsonExtraction() {}

    /** The first balanced JSON object in {@code content}, or {@code null} if none. */
    public static String extract(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.isEmpty()) {
            return null;
        }
        // Strip a leading ```/```json fence and a trailing ``` fence, if present.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        return balancedObject(text.substring(start));
    }

    /**
     * Given a string starting at '{', returns the substring up to and including
     * the matching '}', or null if the object never closes (truncated output).
     * String literals are tracked so braces inside quoted values don't count.
     */
    private static String balancedObject(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return s.substring(0, i + 1);
                    }
                }
                default -> { /* ignore */ }
            }
        }
        return null; // never balanced — truncated or malformed
    }
}
