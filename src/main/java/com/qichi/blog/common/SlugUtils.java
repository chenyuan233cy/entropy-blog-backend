package com.qichi.blog.common;

import java.text.Normalizer;
import java.util.Locale;

public final class SlugUtils {

    private SlugUtils() {}

    /**
     * Builds a URL-safe slug. Chinese titles produce no latin characters at all, so a caller that
     * needs a guaranteed unique value must pass a prefix and check for collisions afterwards.
     */
    public static String slugify(String input, String fallbackPrefix) {
        if (input == null) input = "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (normalized.isBlank()) {
            normalized = fallbackPrefix + "-" + Long.toString(System.currentTimeMillis(), 36);
        }
        if (normalized.length() > 180) {
            normalized = normalized.substring(0, 180).replaceAll("-+$", "");
        }
        return normalized;
    }

    public static String plainText(String markdown) {
        if (markdown == null) return "";
        return markdown
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("[#>*`_~|-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
