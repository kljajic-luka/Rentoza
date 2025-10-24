package org.example.rentoza.car;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CarRepository extends JpaRepository<Car, Long> {
    List<Car> findByLocationIgnoreCase(String location);
    List<Car> findByOwnerEmailIgnoreCase(String email);
}