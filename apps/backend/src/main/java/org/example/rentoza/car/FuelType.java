package org.example.rentoza.car;

import java.util.Map;

/**
 * Fuel type enumeration for Serbian market.
 * Supports both Serbian enum names (BENZIN, DIZEL, ...) and English aliases
 * (PETROL, DIESEL, ELECTRIC, HYBRID, PLUG_IN_HYBRID) sent by the frontend.
 */
public enum FuelType {
    BENZIN,          // Gasoline/Petrol
    DIZEL,           // Diesel
    ELEKTRIČNI,      // Electric (Električni)
    HIBRID,          // Hybrid
    PLUG_IN_HIBRID;  // Plug-in Hybrid

    /** English-to-Serbian alias map used by frontend consumers. */
    private static final Map<String, FuelType> ALIASES = Map.of(
            "PETROL",          BENZIN,
            "GASOLINE",        BENZIN,
            "DIESEL",          DIZEL,
            "ELECTRIC",        ELEKTRIČNI,
            "HYBRID",          HIBRID,
            "PLUG_IN_HYBRID",  PLUG_IN_HIBRID
    );

    /**
     * Resolve a FuelType from its Serbian enum name <b>or</b> an English alias.
     * Returns {@code null} when the value cannot be mapped.
     */
    public static FuelType fromAlias(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String key = value.trim().toUpperCase();
        // Try native enum name first
        try {
            return FuelType.valueOf(key);
        } catch (IllegalArgumentException ignored) {
            // fall through to alias lookup
        }
        return ALIASES.get(key);
    }
}
