package org.example.rentoza.availability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Entity representing a date range blocked by a car owner.
 * Blocked dates prevent renters from booking the car during those periods.
 * Similar to bookings, but initiated by owners for personal use, maintenance, etc.
 */
@Entity
@Table(
        name = "blocked_dates",
        indexes = {
                @Index(name = "idx_blocked_car_dates", columnList = "car_id, start_date, end_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @NotNull
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id", nullable = false)
    private Car car;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Helper method to check if a specific date falls within this blocked range.
     */
    public boolean containsDate(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    /**
     * Helper method to check if this blocked range overlaps with another date range.
     */
    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !this.endDate.isBefore(otherStart) && !otherEnd.isBefore(this.startDate);
    }
}
