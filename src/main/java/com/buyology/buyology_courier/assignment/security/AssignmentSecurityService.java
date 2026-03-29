package com.buyology.buyology_courier.assignment.security;

import com.buyology.buyology_courier.assignment.repository.CourierAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Authorization helper used in {@code @PreAuthorize} expressions.
 * Ensures a courier can only access their own assignments.
 */
@Service("assignmentSecurity")
@RequiredArgsConstructor
public class AssignmentSecurityService {

    private final CourierAssignmentRepository assignmentRepository;

    /**
     * Returns {@code true} if the authenticated principal is the courier assigned to
     * the given assignment.
     */
    @Transactional(readOnly = true)
    public boolean isAssignee(UUID assignmentId, Authentication authentication) {
        if (authentication == null || assignmentId == null) return false;
        try {
            UUID courierId = UUID.fromString(authentication.getName());
            return assignmentRepository.findById(assignmentId)
                    .map(a -> courierId.equals(a.getCourier().getId()))
                    .orElse(false);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
