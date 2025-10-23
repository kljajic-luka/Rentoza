package org.example.rentoza.car;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CarRepository extends JpaRepository<Car, Long> {
    List<Car> findByLocation(String location);
    List<Car> findByOwnerEmail(String email);
}
