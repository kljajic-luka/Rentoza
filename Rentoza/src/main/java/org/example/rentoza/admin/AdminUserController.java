package org.example.rentoza.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.rentoza.admin.dto.AdminUserDetailDto;
import org.example.rentoza.admin.dto.AdminUserDto;
import org.example.rentoza.admin.dto.BanUserRequest;
import org.example.rentoza.admin.repository.AdminUserRepository;
import org.example.rentoza.admin.service.AdminUserService;
import org.example.rentoza.config.HateoasAssembler;
import org.example.rentoza.exception.ResourceNotFoundException;
import org.example.rentoza.security.CurrentUser;
import org.example.rentoza.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final CurrentUser currentUser;
    private final AdminUserRepository adminUserRepository;
    private final HateoasAssembler hateoasAssembler;

    @GetMapping
    public PagedModel<EntityModel<AdminUserDto>> listUsers(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        
        Page<AdminUserDto> users;
        if (search != null && !search.trim().isEmpty()) {
            users = adminUserService.searchUsers(search.trim(), pageable);
        } else {
            users = adminUserService.listUsers(pageable);
        }
        
        return hateoasAssembler.toModel(users);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetailDto> getUserDetail(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.getUserDetail(id));
    }

    @PutMapping("/{id}/ban")
    public ResponseEntity<Void> banUser(
            @PathVariable Long id,
            @RequestBody BanUserRequest request) {
        
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
                
        adminUserService.banUser(id, request.getReason(), admin);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/unban")
    public ResponseEntity<Void> unbanUser(@PathVariable Long id) {
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
                
        adminUserService.unbanUser(id, admin);
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable Long id,
            @RequestParam String reason) {
        User admin = adminUserRepository.findById(currentUser.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
                
        adminUserService.deleteUser(id, reason, admin);
        return ResponseEntity.ok().build();
    }
    
    @GetMapping("/banned")
    public PagedModel<EntityModel<AdminUserDto>> getBannedUsers(Pageable pageable) {
        Page<AdminUserDto> bannedUsers = adminUserService.listBannedUsers(pageable);
        return hateoasAssembler.toModel(bannedUsers);
    }
}
