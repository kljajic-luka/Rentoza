package org.example.rentoza.user;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical driver-license number normalization used across manual and OCR flows.
 */
public final class DriverLicenseNumberNormalizer {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");

    private DriverLicenseNumberNormalizer() {
    }

    public static String normalize(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String normalized = NON_ALPHANUMERIC
            .matcher(rawValue.trim().toUpperCase(Locale.ROOT))
            .replaceAll("");

        return normalized.isBlank() ? null : normalized;
    }
}