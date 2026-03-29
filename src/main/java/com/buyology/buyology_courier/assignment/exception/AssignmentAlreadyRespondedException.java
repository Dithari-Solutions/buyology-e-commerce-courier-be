package com.buyology.buyology_courier.assignment.exception;

import com.buyology.buyology_courier.assignment.domain.enums.AssignmentStatus;

import java.util.UUID;

public class AssignmentAlreadyRespondedException extends RuntimeException {

    public AssignmentAlreadyRespondedException(UUID assignmentId, AssignmentStatus currentStatus) {
        super("Assignment " + assignmentId + " has already been responded to (status: " + currentStatus + ")");
    }
}
