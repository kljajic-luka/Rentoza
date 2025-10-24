package org.example.rentoza.car;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.user.User;
import org.example.rentoza.booking.Booking;
import org.example.rentoza.review.Review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Car {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String brand;
    private String model;
    private Integer year;
    private Double pricePerDay;
    private String location;
    private String imageUrl;
    private boolean available = true;
    private Instant createdAt = Instant.now();

    @ElementCollection
    @CollectionTable(name = "car_images", joinColumns = @JoinColumn(name = "car_id"))
    @Column(name = "image_url")
    private List<String> imageUrls = new ArrayList<>();

    // 🔗 Relations
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @OneToMany(mappedBy = "car", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Booking> bookings = new ArrayList<>();

    @OneToMany(mappedBy = "car", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Review> reviews = new ArrayList<>();
}