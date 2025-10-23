package org.example.rentoza.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "email"),
                @UniqueConstraint(columnNames = "phone")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Size(min = 3, max = 50)
    @Column(nullable = false)
    private String fullName;

    @Email
    @Column(unique = true, nullable = false)
    private String email;

    @Size(min = 8)
    @Pattern(
            regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d).+$",
            message = "Password must contain at least one uppercase letter, one lowercase letter, and one number"
    )
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Pattern(regexp = "^[0-9]{8,15}$")
    @Column(unique = true)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", length = 50)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean locked = false;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public void setEmail(String email) {
        this.email = email == null ? null : email.toLowerCase();
    }
}