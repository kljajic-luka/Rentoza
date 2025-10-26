package org.example.rentoza.car;

import org.springframework.transaction.annotation.Transactional;
import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.user.User;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CarService {

    private final CarRepository repo;

    public CarService(CarRepository repo) {
        this.repo = repo;
    }

    public Car addCar(CarRequestDTO dto, User owner) {
        if (dto.getBrand() == null || dto.getBrand().isBlank()) {
            throw new RuntimeException("Brand is required");
        }
        if (dto.getModel() == null || dto.getModel().isBlank()) {
            throw new RuntimeException("Model is required");
        }
        if (dto.getPricePerDay() == null || dto.getPricePerDay() <= 0) {
            throw new RuntimeException("Invalid price per day");
        }

        Car car = new Car();
        car.setBrand(dto.getBrand().trim());
        car.setModel(dto.getModel().trim());
        car.setYear(dto.getYear());
        car.setPricePerDay(dto.getPricePerDay());
        car.setLocation(dto.getLocation().trim().toLowerCase());
        car.setImageUrl(dto.getImageUrl());
        car.setImageUrls(dto.getImageUrls());
        car.setOwner(owner);
        car.setAvailable(true);

        return repo.save(car);
    }
    @Transactional(readOnly = true)
    public List<Car> getAllCars() {
        return repo.findAll();
    }

    public List<Car> getCarsByLocation(String location) {
        return repo.findByLocationIgnoreCase(location);
    }

    public List<Car> getCarsByOwner(String email) {
        return repo.findByOwnerEmailIgnoreCase(email);
    }

    @Transactional(readOnly = true)
    public Car getCarById(Long id) {
        Car car = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + id));

        // ✅ Force initialize lazy owner before session closes
        if (car.getOwner() != null) {
            car.getOwner().getFirstName(); // triggers proxy initialization
            car.getOwner().getLastName();
            car.getOwner().getEmail();
        }

        return car;
    }
}