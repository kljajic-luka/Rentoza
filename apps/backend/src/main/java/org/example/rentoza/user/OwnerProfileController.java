package org.example.rentoza.user;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.dto.OwnerPublicProfileDTO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/owners")
@RequiredArgsConstructor
public class OwnerProfileController {

    private final OwnerProfileService ownerProfileService;

    /**
     * Get public profile for an owner.
     * Publicly accessible.
     * Cached for 5 minutes to improve performance (unless filtered).
     */
    @GetMapping("/{id}/public-profile")
    @PreAuthorize("permitAll()")
    public ResponseEntity<OwnerPublicProfileDTO> getOwnerPublicProfile(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        if ((start != null && end == null) || (start == null && end != null)) {
            throw new IllegalArgumentException("Both start and end dates must be provided together");
        }

        OwnerPublicProfileDTO profile = ownerProfileService.getOwnerPublicProfile(id, start, end);

        if (start != null) {
            // Do not cache filtered results as they are highly dynamic
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(profile);
        }
        
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(profile);
    }
}
