package org.example.rentoza.dto;

/**
 * Statistike za pocetnu stranicu.
 *
 * <p>Nullable polja: ako je vrednost ispod minimuma za prikaz, backend
 * vraca {@code null} i frontend prikazuje "—" umesto sramotno niskih
 * brojeva (npr. 0.0 rejting, 3 vozila).
 */
public record HomeStatsDTO(
        Double guestSatisfactionRating,
        Long verifiedVehiclesCount,
        String supportAvailability
) {}
