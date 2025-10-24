package org.example.rentoza.car;

import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CarService {

    private final CarRepository repo;
    private final UserRepository userRepo;

    public CarService(CarRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    public Car addCar(Car car, String ownerEmail) {
        User owner = userRepo.findByEmail(ownerEmail)
                .orElseThrow(() -> new RuntimeException("Owner not found: " + ownerEmail));

        car.setOwner(owner);
        car.setAvailable(true);
        return repo.save(car);
    }

    public List<Car> getAllCars() {
        return repo.findAll();
    }

    public List<Car> getCarsByLocation(String location) {
        return repo.findByLocationIgnoreCase(location);
    }

    public List<Car> getCarsByOwner(String email) {
        return repo.findByOwnerEmailIgnoreCase(email);
    }

    public Car getCarById(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + id));
    }
}