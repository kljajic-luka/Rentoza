package org.example.rentoza.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Base64;

@Component
@Converter
public class AttributeEncryptor implements AttributeConverter<String, String> {

    private static final String AES = "AES";
    
    // MVP: Hardcoded key (128-bit/16-byte for simplicity and broad compatibility)
    // Production TODO: Load from environment/vault variable
    private static final String SECRET_KEY = "SecretKeyToEncryptPIIData1234567"; // 32 chars = 256 bits?
    // "SecretKeyToEncryptPIIData1234567" length is 32. 32 bytes = 256 bits.
    // Ensure JVM allows 256-bit keys (modern OpenJDK does).

    private final Key key;

    public AttributeEncryptor() {
        this.key = new SecretKeySpec(SECRET_KEY.getBytes(), AES);
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(AES);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(attribute.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("Error encrypting attribute", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(AES);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)));
        } catch (Exception e) {
            // FALLBACK FOR LEGACY DATA:
            // If data in DB is plain text (not Base64 or not encrypted), decryption fails.
            // We return the raw data so the app still works. 
            // When this entity is collected and saved again, it will be encrypted.
            // In a real enterprise scenario, we'd log this as a warning or run a migration script.
            // System.out.println("Warning: Could not decrypt data, assuming legacy plain text: " + e.getMessage());
            return dbData;
        }
    }
}
