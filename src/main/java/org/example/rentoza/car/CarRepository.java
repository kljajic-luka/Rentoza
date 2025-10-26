package org.example.rentoza.car;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CarRepository extends JpaRepository<Car, Long> {
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByLocationIgnoreCase(String location);
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findByOwnerEmailIgnoreCase(String email);
    @Override
    @EntityGraph(attributePaths = {"owner"})
    List<Car> findAll();
}