package org.example.rentoza.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralError(Exception ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", "Server error");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(io.jsonwebtoken.JwtException.class)
    public ResponseEntity<Map<String, String>> handleJwtError(io.jsonwebtoken.JwtException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("error", "Invalid or expired token");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}