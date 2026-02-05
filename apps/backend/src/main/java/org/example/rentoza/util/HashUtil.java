package org.example.rentoza.util;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class HashUtil {

    /**
     * Creates a SHA-256 hash of the input string.
     * Used for creating searchable indexes of encrypted data (PIB, JMBG).
     * 
     * @param input Plain text input
     * @return Base64 encoded SHA-256 hash
     */
    public String hash(String input) {
         if (input == null) return null;
         try {
             MessageDigest digest = MessageDigest.getInstance("SHA-256");
             byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
             return Base64.getEncoder().encodeToString(hash);
         } catch (NoSuchAlgorithmException e) {
             throw new RuntimeException("SHA-256 algorithm not found", e);
         }
    }
}
