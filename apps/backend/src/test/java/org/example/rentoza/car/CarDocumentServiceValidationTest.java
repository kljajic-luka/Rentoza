package org.example.rentoza.car;

import org.example.rentoza.exception.ValidationException;
import org.example.rentoza.storage.SupabaseStorageService;
import org.example.rentoza.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CarDocumentService validation")
class CarDocumentServiceValidationTest {

    @Mock
    private CarDocumentRepository documentRepository;

    @Mock
    private CarRepository carRepository;

    @Mock
    private SupabaseStorageService supabaseStorageService;

    private CarDocumentService service;
    private Car car;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new CarDocumentService(documentRepository, carRepository, supabaseStorageService);

        owner = new User();
        owner.setId(5L);

        car = new Car();
        car.setId(99L);
        car.setOwner(owner);

        when(carRepository.findByIdForUpdate(99L)).thenReturn(Optional.of(car));
    }

    @Test
    @DisplayName("Rejects spoofed PDF upload when magic bytes do not match declared MIME type")
    void rejectsSpoofedMimeTypeByMagicBytes() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "document.pdf",
                "application/pdf",
                "not-a-real-pdf".getBytes());

        assertThatThrownBy(() -> service.uploadDocument(
                99L,
                DocumentType.REGISTRATION,
                file,
                LocalDate.now().plusMonths(6),
                owner))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid file signature");

        verify(supabaseStorageService, never()).uploadCarDocument(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
