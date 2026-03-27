package com.buyology.buyology_courier.delivery.security;

import com.buyology.buyology_courier.delivery.repository.DeliveryOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Method-security helper for delivery endpoints.
 * Registered as a Spring bean named {@code deliverySecurity} so that
 * {@code @PreAuthorize("@deliverySecurity.isAssignedCourier(...)")} works.
 */
@Service("deliverySecurity")
@RequiredArgsConstructor
public class DeliverySecurityService {

    private final DeliveryOrderRepository deliveryOrderRepository;

    /**
     * Returns {@code true} if the authenticated principal is the courier
     * currently assigned to the given delivery order.
     */
    public boolean isAssignedCourier(UUID deliveryId, Authentication authentication) {
        try {
            UUID courierId = UUID.fromString(authentication.getName());
            return deliveryOrderRepository.findById(deliveryId)
                    .map(order -> order.getAssignedCourier() != null
                            && courierId.equals(order.getAssignedCourier().getId()))
                    .orElse(false);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
