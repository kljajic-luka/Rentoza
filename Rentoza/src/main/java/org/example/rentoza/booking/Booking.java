package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate startDate;
    private LocalDate endDate;
    private Double totalPrice;

    @Column(name = "insurance_type", length = 20)
    private String insuranceType = "BASIC"; // BASIC, STANDARD, PREMIUM

    @Column(name = "prepaid_refuel")
    private boolean prepaidRefuel = false;

    // Phase 2.2: Pickup time support
    @Column(name = "pickup_time_window", length = 20)
    private String pickupTimeWindow = "MORNING"; // MORNING, AFTERNOON, EVENING, EXACT

    @Column(name = "pickup_time")
    private LocalTime pickupTime; // Only used when pickupTimeWindow is EXACT

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id")
    private Car car;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id")
    private User renter;
}