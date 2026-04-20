package org.example.rentoza.car;

import org.example.rentoza.user.OwnerType;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Phase 1: Legal Compliance Entities.
 * Validates User verification fields, Car document tracking, and CarDocument entity.
 */
class LegalComplianceEntitiesTest {

    // ==================== USER VERIFICATION TESTS ====================
    
    @Nested
    @DisplayName("User Owner Verification")
    class UserVerificationTests {
        
        private User user;
        
        @BeforeEach
        void setUp() {
            user = new User();
            user.setId(1L);
            user.setFirstName("Test");
            user.setLastName("User");
            user.setEmail("test@example.com");
        }
        
        @Test
        @DisplayName("Individual owner with JMBG and verified = verified owner")
        void individualWithJmbgAndVerified_isVerifiedOwner() {
            user.setOwnerType(OwnerType.INDIVIDUAL);
            user.setJmbg("1234567890123");  // 13 digits
            user.setIsIdentityVerified(true);
            
            assertTrue(user.isVerifiedOwner());
        }
        
        @Test
        @DisplayName("Individual owner without JMBG = not verified owner")
        void individualWithoutJmbg_isNotVerifiedOwner() {
            user.setOwnerType(OwnerType.INDIVIDUAL);
            user.setJmbg(null);
            user.setIsIdentityVerified(true);
            
            assertFalse(user.isVerifiedOwner());
        }
        
        @Test
        @DisplayName("Individual owner with JMBG but not verified = not verified owner")
        void individualWithJmbgNotVerified_isNotVerifiedOwner() {
            user.setOwnerType(OwnerType.INDIVIDUAL);
            user.setJmbg("1234567890123");
            user.setIsIdentityVerified(false);
            
            assertFalse(user.isVerifiedOwner());
        }
        
        @Test
        @DisplayName("Legal entity owner with PIB and verified = verified owner")
        void legalEntityWithPibAndVerified_isVerifiedOwner() {
            user.setOwnerType(OwnerType.LEGAL_ENTITY);
            user.setPib("123456789");  // 9 digits
            user.setIsIdentityVerified(true);
            
            assertTrue(user.isVerifiedOwner());
        }
        
        @Test
        @DisplayName("Legal entity owner without PIB = not verified owner")
        void legalEntityWithoutPib_isNotVerifiedOwner() {
            user.setOwnerType(OwnerType.LEGAL_ENTITY);
            user.setPib(null);
            user.setIsIdentityVerified(true);
            
            assertFalse(user.isVerifiedOwner());
        }
        
        @Test
        @DisplayName("Masked PIB hides middle digits")
        void maskedPib_hidesMiddleDigits() {
            user.setPib("123456789");
            String masked = user.getMaskedPib();
            assertEquals("1234***89", masked);
        }
        
        @Test
        @DisplayName("Masked JMBG hides middle digits")
        void maskedJmbg_hidesMiddleDigits() {
            user.setJmbg("1234567890123");
            String masked = user.getMaskedJmbg();
            assertEquals("123***90123", masked);
        }
    }
    
    // ==================== CAR DOCUMENT TRACKING TESTS ====================
    
    @Nested
    @DisplayName("Car Document Expiry Tracking")
    class CarExpiryTests {
        
        private Car car;
        
        @BeforeEach
        void setUp() {
            car = new Car();
            car.setId(1L);
            car.setBrand("BMW");
            car.setModel("X5");

            User owner = new User();
            owner.setId(10L);
            owner.setIsIdentityVerified(true);
            car.setOwner(owner);

            User admin = new User();
            admin.setId(11L);
            car.setDocumentsVerifiedBy(admin);
            car.setDocumentsVerifiedAt(LocalDateTime.now().minusHours(2));
            car.setAvailable(true);
        }
        
        @Test
        @DisplayName("Car with future tech inspection expiry = not expired")
        void futureTechInspectionExpiry_notExpired() {
            car.setTechnicalInspectionExpiryDate(LocalDate.now().plusMonths(3));
            
            assertFalse(car.isTechnicalInspectionExpired());
        }
        
        @Test
        @DisplayName("Car with past tech inspection expiry = expired")
        void pastTechInspectionExpiry_isExpired() {
            car.setTechnicalInspectionExpiryDate(LocalDate.now().minusDays(1));
            
            assertTrue(car.isTechnicalInspectionExpired());
        }
        
        @Test
        @DisplayName("Car with null tech inspection expiry = not expired")
        void nullTechInspectionExpiry_notExpired() {
            car.setTechnicalInspectionExpiryDate(null);
            
            assertFalse(car.isTechnicalInspectionExpired());
        }
        
        @Test
        @DisplayName("Car with future registration expiry = not expired")
        void futureRegistrationExpiry_notExpired() {
            car.setRegistrationExpiryDate(LocalDate.now().plusMonths(6));
            
            assertFalse(car.isRegistrationExpired());
        }
        
        @Test
        @DisplayName("Car with past registration expiry = expired")
        void pastRegistrationExpiry_isExpired() {
            car.setRegistrationExpiryDate(LocalDate.now().minusDays(1));
            
            assertTrue(car.isRegistrationExpired());
        }
        
        @Test
        @DisplayName("Car with past insurance expiry = expired")
        void pastInsuranceExpiry_isExpired() {
            car.setInsuranceExpiryDate(LocalDate.now().minusDays(1));
            
            assertTrue(car.isInsuranceExpired());
        }
        
        @Test
        @DisplayName("Days until tech expiry calculated correctly")
        void daysUntilTechExpiry_calculatedCorrectly() {
            car.setTechnicalInspectionExpiryDate(LocalDate.now().plusDays(30));
            
            assertEquals(30, car.getDaysUntilTechInspectionExpiry());
        }
    }
    
    // ==================== CAR DOCUMENT ENTITY TESTS ====================
    
    @Nested
    @DisplayName("CarDocument Entity")
    class CarDocumentTests {
        
        private CarDocument document;
        
        @BeforeEach
        void setUp() {
            document = new CarDocument();
            document.setId(1L);
            document.setType(DocumentType.TECHNICAL_INSPECTION);
            document.setStatus(DocumentVerificationStatus.PENDING);
        }
        
        @Test
        @DisplayName("Document with future expiry = not expired")
        void futureExpiry_notExpired() {
            document.setExpiryDate(LocalDate.now().plusMonths(3));
            
            assertFalse(document.isExpired());
        }
        
        @Test
        @DisplayName("Document with past expiry = expired")
        void pastExpiry_isExpired() {
            document.setExpiryDate(LocalDate.now().minusDays(1));
            
            assertTrue(document.isExpired());
        }
        
        @Test
        @DisplayName("Document expiring in 10 days = will expire within 30 days")
        void expiringIn10Days_willExpireWithin30() {
            document.setExpiryDate(LocalDate.now().plusDays(10));
            
            assertTrue(document.willExpireWithin(30));
            assertFalse(document.willExpireWithin(5));
        }
        
        @Test
        @DisplayName("Days until expiry calculated correctly")
        void daysUntilExpiry_calculatedCorrectly() {
            document.setExpiryDate(LocalDate.now().plusDays(45));
            
            assertEquals(45, document.getDaysUntilExpiry());
        }
        
        @Test
        @DisplayName("DocumentType required flag")
        void documentType_requiredFlag() {
            assertTrue(DocumentType.REGISTRATION.isRequired());
            assertTrue(DocumentType.TECHNICAL_INSPECTION.isRequired());
            assertTrue(DocumentType.LIABILITY_INSURANCE.isRequired());
            assertFalse(DocumentType.AUTHORIZATION.isRequired());
        }
        
        @Test
        @DisplayName("DocumentType Serbian names")
        void documentType_serbianNames() {
            assertEquals("Saobraćajna dozvola", DocumentType.REGISTRATION.getSerbianName());
            assertEquals("Tehnički pregled", DocumentType.TECHNICAL_INSPECTION.getSerbianName());
        }
    }
    
    // ==================== ENUM TESTS ====================
    
    @Nested
    @DisplayName("Enum Values")
    class EnumTests {
        
        @Test
        @DisplayName("OwnerType has correct values")
        void ownerType_hasCorrectValues() {
            assertEquals(2, OwnerType.values().length);
            assertNotNull(OwnerType.valueOf("INDIVIDUAL"));
            assertNotNull(OwnerType.valueOf("LEGAL_ENTITY"));
        }
        
        @Test
        @DisplayName("ListingStatus has DRAFT state")
        void listingStatus_hasDraftState() {
            assertEquals(5, ListingStatus.values().length);
            assertNotNull(ListingStatus.valueOf("DRAFT"));
            assertNotNull(ListingStatus.valueOf("PENDING_APPROVAL"));
            assertNotNull(ListingStatus.valueOf("APPROVED"));
            assertNotNull(ListingStatus.valueOf("REJECTED"));
            assertNotNull(ListingStatus.valueOf("SUSPENDED"));
        }
        
        @Test
        @DisplayName("DocumentVerificationStatus values")
        void documentVerificationStatus_values() {
            assertEquals(4, DocumentVerificationStatus.values().length);
            assertNotNull(DocumentVerificationStatus.valueOf("PENDING"));
            assertNotNull(DocumentVerificationStatus.valueOf("VERIFIED"));
            assertNotNull(DocumentVerificationStatus.valueOf("REJECTED"));
            assertNotNull(DocumentVerificationStatus.valueOf("EXPIRED_AUTO"));
        }
    }
}
