package org.example.rentoza.user;

import jakarta.validation.Valid;
import org.example.rentoza.security.JwtUtil;
import org.example.rentoza.user.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    private final UserService service;
    private final JwtUtil jwtUtil;

    public UserController(UserService service, JwtUtil jwtUtil) {
        this.service = service;
        this.jwtUtil = jwtUtil;
    }
    @PostMapping("/register")
    public ResponseEntity<UserResponseDTO> register(@Valid @RequestBody UserRegisterDTO dto) {
        System.out.println("Received user: " + dto.getEmail());
        User user = service.register(dto);

        return ResponseEntity.ok(new UserResponseDTO(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole().name()
        ));
    }

    @GetMapping("/{email}")
    public ResponseEntity<User> getUserByEmail(@PathVariable String email) {
        return service.getUserByEmail(email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    @PostMapping("/login")
    public ResponseEntity<AuthResponseDTO> login(@Valid @RequestBody UserLoginDTO dto) {
        var userOpt = service.getUserByEmail(dto.getEmail());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(401).build();
        }

        var user = userOpt.get();
        if (!service.passwordMatches(dto.getPassword(), user.getPassword())) {
            return ResponseEntity.status(401).build();
        }

        String token = jwtUtil.generateToken(user.getEmail());
        return ResponseEntity.ok(new AuthResponseDTO(token, user.getEmail(), user.getRole().name()));
    }

    @PutMapping("/profile/{email}")
    public ResponseEntity<UserResponseDTO> updateProfile(
            @PathVariable String email,
            @Valid @RequestBody UserProfileDTO dto
    ) {
        User updated = service.updateProfile(email, dto);
        return ResponseEntity.ok(new UserResponseDTO(
                updated.getId(),
                updated.getFullName(),
                updated.getEmail(),
                updated.getPhone(),
                updated.getRole().name()
        ));
    }
//    @GetMapping("/me")
//    public ResponseEntity<User> getCurrentUser(@AuthenticationPrincipal UserDetails userDetails) {
//        return service.getUserByEmail(userDetails.getUsername())
//                .map(ResponseEntity::ok)
//                .orElse(ResponseEntity.notFound().build());
//    }
}
