package org.example.rentoza.car;

import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

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
    public List<CarResponseDTO> getAllCars() {
        return repo.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByLocation(String location) {
        return repo.findByLocationIgnoreCase(location)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByOwner(String email) {
        return repo.findByOwnerEmailIgnoreCase(email)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CarResponseDTO getCarById(Long id) {
        Car car = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + id));

        return mapToResponse(car);
    }

    private CarResponseDTO mapToResponse(Car car) {
        return new CarResponseDTO(car);
    }
}
