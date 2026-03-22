package com.buyology.buyology_courier.auth.service;

import com.buyology.buyology_courier.auth.domain.AdminAuditLog;
import com.buyology.buyology_courier.auth.domain.enums.AdminAction;
import com.buyology.buyology_courier.auth.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditService {

    private final AdminAuditLogRepository auditLogRepository;

    /**
     * Persist an audit entry in a REQUIRES_NEW transaction so it is committed
     * independently of the caller's transaction. This ensures the audit record
     * survives even if the outer transaction is rolled back, e.g. after a
     * constraint violation that is caught at a higher layer.
     *
     * Call this AFTER the admin action succeeds to avoid logging rolled-back actions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AdminAction action, UUID resourceId, String details, HttpServletRequest request) {
        UUID   adminId    = null;
        String adminEmail = null;

        // Extract admin identity from the current security context
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                adminId    = UUID.fromString(jwt.getSubject());
                adminEmail = jwt.getClaimAsString("email");
            } catch (Exception e) {
                log.warn("Could not extract admin identity from JWT for audit log: {}", e.getMessage());
            }
        }

        AdminAuditLog entry = AdminAuditLog.builder()
                .adminId(adminId)
                .adminEmail(adminEmail)
                .action(action)
                .resourceId(resourceId)
                .details(details)
                .ipAddress(resolveClientIp(request))
                .userAgent(request.getHeader("User-Agent"))
                .build();

        auditLogRepository.save(entry);

        log.info("ADMIN_AUDIT admin={} email={} action={} resource={} ip={}",
                adminId, adminEmail, action, resourceId, entry.getIpAddress());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
