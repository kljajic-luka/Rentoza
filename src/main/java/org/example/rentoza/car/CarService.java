package org.example.rentoza.car;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CarService {

    private final CarRepository repo;

    public CarService(CarRepository repo) {
        this.repo = repo;
    }

    public Car addCar(Car car) {
        return repo.save(car);
    }

    public List<Car> getAllCars() {
        return repo.findAll();
    }

    public List<Car> getCarsByLocation(String location) {
        return repo.findByLocation(location);
    }

    public List<Car> getCarsByOwner(String email) {
        return repo.findByOwnerEmail(email);
    }
}
