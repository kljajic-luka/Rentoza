package org.example.rentoza.booking.checkin.verification;

import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.booking.checkin.CheckInIdVerification.DocumentType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mock ID verification provider for development and testing.
 * 
 * <p>This provider simulates successful verification with configurable delays
 * to mimic real provider response times.
 * 
 * <p><b>IMPORTANT:</b> Never use in production! Enable with:
 * {@code app.id-verification.provider=MOCK}
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.id-verification.provider", havingValue = "MOCK", matchIfMissing = true)
public class MockIdVerificationProvider implements IdVerificationProvider {

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public LivenessResult checkLiveness(byte[] selfieImageBytes, String mimeType) {
        log.info("[MockIdVerificationProvider] Checking liveness - simulating success");
        
        // Simulate processing delay
        simulateDelay(500);
        
        // Return successful liveness check
        return LivenessResult.builder()
                .passed(true)
                .score(new BigDecimal("0.95"))
                .build();
    }

    @Override
    public DocumentExtraction extractDocumentData(byte[] frontImageBytes, byte[] backImageBytes, String mimeType) {
        log.info("[MockIdVerificationProvider] Extracting document data - simulating success");
        
        // Simulate processing delay
        simulateDelay(800);
        
        // Return mock document data
        return DocumentExtraction.builder()
                .success(true)
                .documentType(DocumentType.DRIVERS_LICENSE)
                .firstName("Petar")
                .lastName("Petrović")
                .documentNumber("123456789")
                .expiryDate(LocalDate.now().plusYears(5))
                .countryCode("SRB")
                .build();
    }

    @Override
    public FaceMatchResult matchFaces(byte[] selfieImageBytes, byte[] documentImageBytes, String mimeType) {
        log.info("[MockIdVerificationProvider] Matching faces - simulating success");
        
        // Simulate processing delay
        simulateDelay(600);
        
        // Return successful face match
        return FaceMatchResult.builder()
                .matched(true)
                .confidence(new BigDecimal("0.92"))
                .build();
    }

    private void simulateDelay(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

