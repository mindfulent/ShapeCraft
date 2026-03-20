package com.shapecraft.config;

import com.shapecraft.ShapeCraft;

import java.util.List;

/**
 * Simple blocklist-based content filter for generation descriptions.
 * Checks against a hardcoded list of inappropriate terms.
 */
public class ContentFilter {

    private static final List<String> BLOCKED_TERMS = List.of(
            "swastika", "nazi", "penis", "vagina", "genitals", "pornograph",
            "racial slur", "hate symbol", "offensive", "sexually explicit",
            "genital", "phallic", "explicit"
    );

    /**
     * Check if a description contains blocked content.
     *
     * @param description The user's block description
     * @return Error message if blocked, null if the description is acceptable
     */
    public static String check(String description) {
        if (description == null || description.isBlank()) {
            return "Description cannot be empty.";
        }

        if (!ShapeCraft.getInstance().getConfig().isContentFilterEnabled()) {
            return null; // Filter disabled
        }

        String lower = description.toLowerCase();
        for (String term : BLOCKED_TERMS) {
            if (lower.contains(term)) {
                return "Description contains inappropriate content.";
            }
        }

        return null; // Passed
    }
}
