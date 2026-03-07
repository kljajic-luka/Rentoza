package org.example.chatservice.repository;

import org.example.chatservice.model.AdminAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminAuditRepository extends JpaRepository<AdminAuditEntry, Long> {
}