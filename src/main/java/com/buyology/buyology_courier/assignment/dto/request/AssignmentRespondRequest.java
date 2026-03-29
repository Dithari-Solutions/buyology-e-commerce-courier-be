package com.buyology.buyology_courier.assignment.dto.request;

import com.buyology.buyology_courier.assignment.dto.enums.AssignmentAction;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for accepting or rejecting a courier assignment.
 *
 * <p>When {@code action} is {@code REJECT}, a non-blank {@code rejectionReason} is required.
 */
public record AssignmentRespondRequest(

        @NotNull(message = "action must be ACCEPT or REJECT")
        AssignmentAction action,

        String rejectionReason

) {
    @AssertTrue(message = "rejectionReason is required when action is REJECT")
    public boolean isRejectionReasonValid() {
        if (action == AssignmentAction.REJECT) {
            return rejectionReason != null && !rejectionReason.isBlank();
        }
        return true;
    }
}
