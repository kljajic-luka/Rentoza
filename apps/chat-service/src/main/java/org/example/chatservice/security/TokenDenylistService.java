package org.example.chatservice.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Read-only denylist check for logged-out JWT tokens.
 * The backend writes entries on logout; chat-service reads them here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenDenylistService {

    private final DeniedTokenRepository deniedTokenRepository;

    public boolean isTokenDenied(String accessToken) {
        String tokenHash = hashToken(accessToken);
        return deniedTokenRepository.existsByTokenHash(tokenHash);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
