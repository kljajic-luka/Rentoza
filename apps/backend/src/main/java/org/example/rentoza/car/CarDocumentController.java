package org.example.rentoza.car;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.car.dto.CarDocumentDto;
import org.example.rentoza.exception.ApiErrorResponse;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.example.rentoza.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST API for car document management (Serbian compliance).
 * 
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/cars/{id}/documents - Upload document</li>
 *   <li>GET /api/cars/{id}/documents - List documents</li>
 *   <li>GET /api/cars/{id}/documents/status - Get compliance status</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/cars/{carId}/documents")
@RequiredArgsConstructor
public class CarDocumentController {
    
    private static final Logger log = LoggerFactory.getLogger(CarDocumentController.class);
    
    private final CarDocumentService documentService;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    
    /**
     * Upload a document for a car.
     * 
     * @param carId Car ID
     * @param file Document file (PDF, JPEG, PNG)
     * @param type Document type
     * @param expiryDate When document expires
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> uploadDocument(
            @PathVariable Long carId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") DocumentType type,
            @RequestParam(value = "expiryDate", required = false) LocalDate expiryDate) {
        
        Long userId = currentUser.id();
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalStateException("User not found"));
        
        log.info("Document upload request: carId={}, type={}, filename={}, size={}, userId={}", 
            carId, type, file.getOriginalFilename(), file.getSize(), userId);
        
        try {
            CarDocument document = documentService.uploadDocument(carId, type, file, expiryDate, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(CarDocumentDto.from(document));
        } catch (IOException e) {
            log.error("File upload failed for carId={}: {}", carId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiErrorResponse.internalError(
                            "An unexpected error occurred while uploading the document",
                            UUID.randomUUID().toString(),
                            "/api/cars/" + carId + "/documents"));
        }
    }
    
    /**
     * Get all documents for a car.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CarDocumentDto>> getDocuments(@PathVariable Long carId) {
        User requester = loadCurrentUser();

        List<CarDocument> documents = documentService.getDocumentsForCar(carId, requester);
        List<CarDocumentDto> dtos = documents.stream()
            .map(CarDocumentDto::from)
            .toList();
        
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * Get document compliance status for a car.
     */
    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DocumentComplianceStatus> getComplianceStatus(@PathVariable Long carId) {
        User requester = loadCurrentUser();

        List<CarDocument> documents = documentService.getDocumentsForCar(carId, requester);
        boolean allVerified = documentService.hasAllRequiredDocumentsVerified(carId, requester);
        
        DocumentComplianceStatus status = new DocumentComplianceStatus(
            allVerified,
            documents.stream()
                .filter(d -> d.getType() == DocumentType.REGISTRATION)
                .findFirst()
                .map(d -> d.getStatus() == DocumentVerificationStatus.VERIFIED)
                .orElse(false),
            documents.stream()
                .filter(d -> d.getType() == DocumentType.TECHNICAL_INSPECTION)
                .findFirst()
                .map(d -> d.getStatus() == DocumentVerificationStatus.VERIFIED)
                .orElse(false),
            documents.stream()
                .filter(d -> d.getType() == DocumentType.LIABILITY_INSURANCE)
                .findFirst()
                .map(d -> d.getStatus() == DocumentVerificationStatus.VERIFIED)
                .orElse(false)
        );
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * Document compliance status response.
     */
    public record DocumentComplianceStatus(
        boolean allRequiredVerified,
        boolean registrationVerified,
        boolean technicalInspectionVerified,
        boolean insuranceVerified
    ) {}

    private User loadCurrentUser() {
        Long userId = currentUser.id();
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
