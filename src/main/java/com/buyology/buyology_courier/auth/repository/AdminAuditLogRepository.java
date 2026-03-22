package com.buyology.buyology_courier.auth.repository;

import com.buyology.buyology_courier.auth.domain.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
}
