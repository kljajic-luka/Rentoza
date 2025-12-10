package org.example.rentoza.admin.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.example.rentoza.admin.entity.DisputeResolution;

import java.util.Optional;

@Repository
public interface DisputeResolutionRepository extends JpaRepository<DisputeResolution, Long> {
    Optional<DisputeResolution> findByDamageClaimId(Long damageClaimId);
}
