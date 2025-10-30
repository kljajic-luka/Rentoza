package org.example.rentoza.favorite;

import jakarta.persistence.*;
import lombok.*;
import org.example.rentoza.car.Car;
import org.example.rentoza.user.User;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Favorite entity - Represents a user's favorited car
 * Many-to-many relationship between User and Car through Favorite join table
 */
@Entity
@Table(
        name = "favorites",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_car_favorite",
                        columnNames = {"user_id", "car_id"}
                )
        },
        indexes = {
                @Index(name = "idx_favorite_user", columnList = "user_id"),
                @Index(name = "idx_favorite_car", columnList = "car_id"),
                @Index(name = "idx_favorite_created", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "car_id", nullable = false)
    private Car car;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Favorite)) return false;
        Favorite favorite = (Favorite) o;
        return user != null && car != null &&
                user.equals(favorite.getUser()) &&
                car.equals(favorite.getCar());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
