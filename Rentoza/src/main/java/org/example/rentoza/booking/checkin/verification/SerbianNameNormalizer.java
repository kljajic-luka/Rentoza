package org.example.rentoza.booking.checkin.verification;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Serbian name normalizer for identity verification.
 * 
 * <p>Handles the unique characteristics of Serbian names which use both Latin
 * and Cyrillic scripts, and contain diacritical marks not found in standard ASCII.
 * 
 * <h2>Serbian Latin Alphabet Conversion</h2>
 * <ul>
 *   <li>Đ → DJ</li>
 *   <li>Ž → Z</li>
 *   <li>Č → C</li>
 *   <li>Ć → C</li>
 *   <li>Š → S</li>
 *   <li>Dž → DZ</li>
 *   <li>Lj → LJ</li>
 *   <li>Nj → NJ</li>
 * </ul>
 * 
 * <h2>Use Case</h2>
 * <p>OCR systems may extract names as ASCII approximations. This normalizer
 * ensures consistent comparison between:
 * <ul>
 *   <li>User profile names (may contain diacritics)</li>
 *   <li>OCR-extracted names from ID documents (may be ASCII)</li>
 * </ul>
 *
 * @see CheckInIdVerification#extractedNameNormalized
 * @see CheckInIdVerification#profileNameNormalized
 */
@Component
public class SerbianNameNormalizer {

    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");

    /**
     * Normalize a Serbian name to ASCII uppercase.
     * 
     * <p>Steps:
     * <ol>
     *   <li>Handle Serbian-specific characters (Đ, Dž, Lj, Nj)</li>
     *   <li>Apply Unicode NFD normalization</li>
     *   <li>Remove combining diacritical marks</li>
     *   <li>Convert to uppercase</li>
     *   <li>Remove non-letter characters</li>
     * </ol>
     *
     * @param name The name to normalize (may contain Serbian diacritics)
     * @return ASCII uppercase normalized version, or empty string if null
     */
    public String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        String result = name.trim();
        
        // Step 1: Handle Serbian multi-character digraphs FIRST
        // These must be handled before single-character conversions
        result = result.replace("DŽ", "DZ")
                      .replace("Dž", "DZ")
                      .replace("dž", "dz")
                      .replace("LJ", "LJ")
                      .replace("Lj", "LJ")
                      .replace("lj", "lj")
                      .replace("NJ", "NJ")
                      .replace("Nj", "NJ")
                      .replace("nj", "nj");
        
        // Step 2: Handle Serbian-specific single characters
        result = result.replace('Đ', 'D')
                      .replace('đ', 'd')
                      .replace('Ž', 'Z')
                      .replace('ž', 'z')
                      .replace('Č', 'C')
                      .replace('č', 'c')
                      .replace('Ć', 'C')
                      .replace('ć', 'c')
                      .replace('Š', 'S')
                      .replace('š', 's');
        
        // Step 3: Unicode NFD normalization (decomposes accented characters)
        result = Normalizer.normalize(result, Normalizer.Form.NFD);
        
        // Step 4: Remove combining diacritical marks
        result = DIACRITICS_PATTERN.matcher(result).replaceAll("");
        
        // Step 5: Convert to uppercase
        result = result.toUpperCase();
        
        // Step 6: Keep only letters and spaces
        result = result.replaceAll("[^A-Z\\s]", "");
        
        // Step 7: Normalize whitespace
        result = result.replaceAll("\\s+", " ").trim();
        
        return result;
    }

    /**
     * Normalize and concatenate first and last name.
     *
     * @param firstName First name (may be null)
     * @param lastName Last name (may be null)
     * @return Normalized full name, or empty string if both are null
     */
    public String normalizeFullName(String firstName, String lastName) {
        String first = normalize(firstName);
        String last = normalize(lastName);
        
        if (first.isEmpty() && last.isEmpty()) {
            return "";
        }
        if (first.isEmpty()) {
            return last;
        }
        if (last.isEmpty()) {
            return first;
        }
        
        return first + " " + last;
    }

    /**
     * Calculate Jaro-Winkler similarity between two strings.
     * 
     * <p>Jaro-Winkler is ideal for name matching because:
     * <ul>
     *   <li>Handles transposition errors (common in OCR)</li>
     *   <li>Gives bonus to strings with common prefix</li>
     *   <li>Returns 1.0 for exact match, 0.0 for completely different</li>
     * </ul>
     *
     * @param s1 First string
     * @param s2 Second string
     * @return Similarity score between 0.0 and 1.0
     */
    public double jaroWinklerSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        
        String str1 = normalize(s1);
        String str2 = normalize(s2);
        
        if (str1.equals(str2)) {
            return 1.0;
        }
        if (str1.isEmpty() || str2.isEmpty()) {
            return 0.0;
        }
        
        // Jaro similarity
        double jaroScore = jaroSimilarity(str1, str2);
        
        // Winkler adjustment (prefix bonus)
        int prefixLength = 0;
        int maxPrefix = Math.min(4, Math.min(str1.length(), str2.length()));
        
        for (int i = 0; i < maxPrefix; i++) {
            if (str1.charAt(i) == str2.charAt(i)) {
                prefixLength++;
            } else {
                break;
            }
        }
        
        // Winkler scaling factor (standard value: 0.1)
        double winklerBonus = prefixLength * 0.1 * (1.0 - jaroScore);
        
        return jaroScore + winklerBonus;
    }

    /**
     * Calculate Jaro similarity between two strings.
     */
    private double jaroSimilarity(String s1, String s2) {
        int s1Len = s1.length();
        int s2Len = s2.length();
        
        if (s1Len == 0 && s2Len == 0) {
            return 1.0;
        }
        
        int matchDistance = Math.max(s1Len, s2Len) / 2 - 1;
        matchDistance = Math.max(0, matchDistance);
        
        boolean[] s1Matches = new boolean[s1Len];
        boolean[] s2Matches = new boolean[s2Len];
        
        int matches = 0;
        int transpositions = 0;
        
        for (int i = 0; i < s1Len; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, s2Len);
            
            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        
        if (matches == 0) {
            return 0.0;
        }
        
        int k = 0;
        for (int i = 0; i < s1Len; i++) {
            if (!s1Matches[i]) {
                continue;
            }
            while (!s2Matches[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        
        double jaro = (((double) matches / s1Len) +
                       ((double) matches / s2Len) +
                       ((double) (matches - transpositions / 2.0) / matches)) / 3.0;
        
        return jaro;
    }
}

