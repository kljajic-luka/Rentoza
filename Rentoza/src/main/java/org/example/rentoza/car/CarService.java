package org.example.rentoza.car;

import org.example.rentoza.car.dto.CarRequestDTO;
import org.example.rentoza.car.dto.CarResponseDTO;
import org.example.rentoza.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
        // Public listing - only show available cars to users
        return repo.findByAvailableTrue()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<CarResponseDTO> getCarsByLocation(String location) {
        // Public listing - only show available cars to users
        return repo.findByLocationIgnoreCaseAndAvailableTrue(location)
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

    @Transactional
    public CarResponseDTO updateCar(Long carId, CarRequestDTO dto, User requester) {
        Car car = repo.findById(carId)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + carId));

        // Verify ownership
        if (!car.getOwner().getId().equals(requester.getId())) {
            throw new RuntimeException("You do not have permission to edit this car");
        }

        // Update fields
        if (dto.getBrand() != null && !dto.getBrand().isBlank()) {
            car.setBrand(dto.getBrand().trim());
        }
        if (dto.getModel() != null && !dto.getModel().isBlank()) {
            car.setModel(dto.getModel().trim());
        }
        if (dto.getYear() != null) {
            car.setYear(dto.getYear());
        }
        if (dto.getPricePerDay() != null && dto.getPricePerDay() > 0) {
            car.setPricePerDay(dto.getPricePerDay());
        }
        if (dto.getLocation() != null && !dto.getLocation().isBlank()) {
            car.setLocation(dto.getLocation().trim().toLowerCase());
        }
        if (dto.getDescription() != null) {
            car.setDescription(dto.getDescription());
        }
        if (dto.getSeats() != null) {
            car.setSeats(dto.getSeats());
        }
        if (dto.getFuelType() != null) {
            car.setFuelType(dto.getFuelType());
        }
        if (dto.getFuelConsumption() != null) {
            car.setFuelConsumption(dto.getFuelConsumption());
        }
        if (dto.getTransmissionType() != null) {
            car.setTransmissionType(dto.getTransmissionType());
        }
        if (dto.getFeatures() != null) {
            car.getFeatures().clear();
            car.getFeatures().addAll(dto.getFeatures());
        }
        if (dto.getAddOns() != null) {
            car.getAddOns().clear();
            car.getAddOns().addAll(dto.getAddOns());
        }
        if (dto.getCancellationPolicy() != null) {
            car.setCancellationPolicy(dto.getCancellationPolicy());
        }
        if (dto.getMinRentalDays() != null) {
            car.setMinRentalDays(dto.getMinRentalDays());
        }
        if (dto.getMaxRentalDays() != null) {
            car.setMaxRentalDays(dto.getMaxRentalDays());
        }
        if (dto.getImageUrl() != null) {
            car.setImageUrl(dto.getImageUrl());
        }
        if (dto.getImageUrls() != null) {
            car.getImageUrls().clear();
            car.getImageUrls().addAll(dto.getImageUrls());
        }

        Car savedCar = repo.save(car);
        // Return DTO to avoid lazy initialization issues
        return mapToResponse(savedCar);
    }

    @Transactional
    public CarResponseDTO toggleAvailability(Long carId, boolean available, User requester) {
        Car car = repo.findById(carId)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + carId));

        // Verify ownership
        if (!car.getOwner().getId().equals(requester.getId())) {
            throw new RuntimeException("You do not have permission to modify this car");
        }

        car.setAvailable(available);
        Car savedCar = repo.save(car);
        // Return DTO to avoid lazy initialization issues
        return mapToResponse(savedCar);
    }

    @Transactional
    public void deleteCar(Long carId, User requester) {
        Car car = repo.findById(carId)
                .orElseThrow(() -> new RuntimeException("Car not found with ID: " + carId));

        // Verify ownership
        if (!car.getOwner().getId().equals(requester.getId())) {
            throw new RuntimeException("You do not have permission to delete this car");
        }

        // Cascade delete will handle car_images, car_features, car_add_ons
        // due to orphanRemoval = true in entity
        repo.delete(car);
    }

    private CarResponseDTO mapToResponse(Car car) {
        return new CarResponseDTO(car);
    }
}
