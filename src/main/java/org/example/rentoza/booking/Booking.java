package org.example.rentoza.booking;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;

import java.time.LocalDate;

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

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id")
    private Car car;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "renter_id")
    private User renter;
}