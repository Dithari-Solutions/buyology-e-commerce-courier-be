-- Composite index for reject/reassign flow: WHERE delivery_id = ? AND status = 'PENDING'
-- Also supports attempt-count lookups ordered by attempt_number DESC.
CREATE INDEX idx_courier_assignments_delivery_status
    ON courier_assignments (delivery_id, status);
