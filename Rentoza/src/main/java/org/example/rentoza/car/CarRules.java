package org.example.rentoza.car;

import java.util.List;

/**
 * Standard car rental rules for Rentoza platform
 * Serbian localization (Latin script)
 */
public class CarRules {

    /**
     * Default rental rules that apply to all cars
     */
    public static final List<String> DEFAULT_RULES = List.of(
            "Zabranjeno pušenje",                               // No smoking
            "Vozilo vratiti sa punim rezervoarom",              // Return with full tank
            "Bez vožnje van puta",                              // No off-road driving
            "Održavajte vozilo čistim",                          // Keep vehicle clean
            "Prijavite eventualne štete",                        // Report any damage
            "Vratite na vreme"                                   // Return on time
    );

    /**
     * Get all default rules as a list
     */
    public static List<String> getDefaultRules() {
        return DEFAULT_RULES;
    }

    /**
     * Get rules as formatted string (one per line)
     */
    public static String getFormattedRules() {
        return String.join("\n• ", DEFAULT_RULES);
    }
}
