package org.example.rentoza.common;

/**
 * Utility class for normalizing strings for search and filtering
 * Handles Serbian Latin characters, case insensitivity, and whitespace
 */
public class StringNormalizationUtil {

    /**
     * Normalizes a string for search by:
     * - Converting to lowercase
     * - Trimming whitespace
     * - Removing accents (š→s, ć→c, č→c, ž→z, đ→dj)
     * - Collapsing multiple spaces to single space
     */
    public static String normalizeSearchString(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        return input
                .toLowerCase()
                .trim()
                .replace("š", "s")
                .replace("ć", "c")
                .replace("č", "c")
                .replace("ž", "z")
                .replace("đ", "dj")
                .replaceAll("\\s+", " "); // Collapse multiple spaces
    }

    /**
     * Removes Serbian accents from a string while preserving case
     */
    public static String removeAccents(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        return input
                .replace("š", "s")
                .replace("Š", "S")
                .replace("ć", "c")
                .replace("Ć", "C")
                .replace("č", "c")
                .replace("Č", "C")
                .replace("ž", "z")
                .replace("Ž", "Z")
                .replace("đ", "dj")
                .replace("Đ", "Dj");
    }

    /**
     * Checks if two strings match after normalization
     */
    public static boolean normalizedMatch(String str1, String str2) {
        return normalizeSearchString(str1).equals(normalizeSearchString(str2));
    }

    /**
     * Checks if a string contains another string after normalization
     */
    public static boolean normalizedContains(String haystack, String needle) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        return normalizeSearchString(haystack).contains(normalizeSearchString(needle));
    }
}
