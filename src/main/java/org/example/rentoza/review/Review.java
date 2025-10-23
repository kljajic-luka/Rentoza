package org.example.rentoza.review;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long carId;

    @Column(nullable = false)
    private String reviewerEmail;

    @Column(nullable = false)
    private int rating; // 1–5

    @Column(length = 500)
    private String comment;

    private Instant createdAt = Instant.now();
}
