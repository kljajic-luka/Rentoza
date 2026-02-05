package org.example.rentoza.stats;

import lombok.RequiredArgsConstructor;
import org.example.rentoza.dto.HomeStatsDTO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicStatsController {

    private final PublicStatsService publicStatsService;

    @GetMapping("/home-stats")
    @PreAuthorize("permitAll()")
    public ResponseEntity<HomeStatsDTO> getHomeStats() {
        HomeStatsDTO stats = publicStatsService.getHomeStats();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(stats);
    }
}
