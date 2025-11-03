package org.example.rentoza.car;

import org.example.rentoza.user.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CarRepository extends JpaRepository<Car, Long> {
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByLocationIgnoreCase(String location);

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByOwnerEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByOwner(User owner);

    @Override
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findAll();

    // Public listings - only available cars
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByAvailableTrue();

    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByLocationIgnoreCaseAndAvailableTrue(String location);
}