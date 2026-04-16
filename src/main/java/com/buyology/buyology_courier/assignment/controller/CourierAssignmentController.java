package com.buyology.buyology_courier.assignment.controller;

import com.buyology.buyology_courier.assignment.dto.request.AssignmentRespondRequest;
import com.buyology.buyology_courier.assignment.dto.response.AssignmentResponse;
import com.buyology.buyology_courier.assignment.service.CourierAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for courier assignment accept/reject flow.
 *
 * <h3>Auth</h3>
 * All endpoints require a {@code COURIER} role JWT and ownership of the assignment.
 * The {@code @assignmentSecurity.isAssignee} check prevents one courier from
 * accepting/rejecting another courier's assignment.
 */
@RestController
@RequestMapping("/api/v1/assignments")
@RequiredArgsConstructor
@Tag(name = "Assignments", description = "Courier delivery assignment accept/reject")
public class CourierAssignmentController {

    private final CourierAssignmentService courierAssignmentService;

    /**
     * Returns the courier's current PENDING assignment, or 204 No Content if there is none.
     *
     * <p>The app must call this on every startup and immediately after re-establishing
     * the WebSocket connection. This recovers any assignment offer that was sent via
     * WebSocket/FCM while the device was offline or the connection was dropped — without
     * this, a courier would never see orders that timed out while they were reconnecting.
     */
    @GetMapping("/my-pending")
    @PreAuthorize("hasRole('COURIER')")
    @Operation(summary = "Poll for a pending assignment — call on app start and after WebSocket reconnect")
    public ResponseEntity<AssignmentResponse> getMyPendingAssignment(Authentication authentication) {
        UUID courierId = UUID.fromString(authentication.getName());
        return courierAssignmentService.getPendingAssignment(courierId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Returns assignment details including the embedded pickup/dropoff addresses.
     * The courier app calls this when notified of a new assignment (push or poll).
     */
    @GetMapping("/{assignmentId}")
    @PreAuthorize("hasRole('COURIER') and @assignmentSecurity.isAssignee(#assignmentId, authentication)")
    @Operation(summary = "Get assignment details for the authenticated courier")
    public AssignmentResponse getAssignment(
            @PathVariable UUID assignmentId,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        return courierAssignmentService.getAssignment(assignmentId, courierId);
    }

    /**
     * Courier accepts or rejects a PENDING assignment.
     * On ACCEPT: delivery moves to COURIER_ACCEPTED; ecommerce backend is notified.
     * On REJECT: reassignment is triggered (up to 3 attempts total).
     */
    @PostMapping("/{assignmentId}/respond")
    @PreAuthorize("hasRole('COURIER') and @assignmentSecurity.isAssignee(#assignmentId, authentication)")
    @Operation(summary = "Accept or reject the assignment")
    public ResponseEntity<AssignmentResponse> respond(
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AssignmentRespondRequest request,
            Authentication authentication
    ) {
        UUID courierId = UUID.fromString(authentication.getName());
        AssignmentResponse response =
                courierAssignmentService.respondToAssignment(assignmentId, courierId, request);
        return ResponseEntity.ok(response);
    }
}
