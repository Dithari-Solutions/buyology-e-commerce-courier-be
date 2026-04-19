package com.buyology.buyology_courier.delivery.service;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.dto.request.CancelDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.FailDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.UpdateDeliveryStatusRequest;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryOrderResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryProofResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryStatusHistoryResponse;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryOrderReceivedEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeliveryService {

    /**
     * Idempotently ingests a delivery order received from the ecommerce backend.
     * If a {@code DeliveryOrder} for the same {@code ecommerceOrderId} already
     * exists the call is a no-op and returns the existing record.
     */
    DeliveryOrderResponse ingest(DeliveryOrderReceivedEvent event);

    DeliveryOrderResponse findById(UUID deliveryId);

    /**
     * @param status optional filter — pass {@code null} to return all statuses
     */
    Page<DeliveryOrderResponse> findByStatus(DeliveryStatus status, Pageable pageable);

    /**
     * Returns all non-terminal deliveries assigned to the given courier.
     */
    Page<DeliveryOrderResponse> findAssignedToCourier(UUID courierId, Pageable pageable);

    /**
     * Full delivery history for the courier (all statuses, including terminal).
     * Pass {@code null} for status to return all.
     */
    Page<DeliveryOrderResponse> findAllByCourier(UUID courierId, DeliveryStatus status, Pageable pageable);

    /**
     * Courier updates the delivery status (e.g. PICKED_UP → ON_THE_WAY).
     * Only the assigned courier may call this.
     */
    DeliveryOrderResponse updateStatus(UUID deliveryId, UUID courierId,
                                       UpdateDeliveryStatusRequest request);

    /**
     * Saves the pickup proof photo and transitions status ARRIVED_AT_PICKUP → PICKED_UP.
     * Only the assigned courier may call this.
     */
    DeliveryProofResponse submitPickupProof(UUID deliveryId, UUID courierId,
                                            String imageUrl, Instant photoTakenAt);

    /**
     * Saves the delivery proof photo and transitions status ARRIVED_AT_DESTINATION → DELIVERED.
     * Only the assigned courier may call this.
     */
    DeliveryProofResponse submitDeliveryProof(UUID deliveryId, UUID courierId,
                                              String imageUrl, String deliveredTo,
                                              Instant photoTakenAt);

    /**
     * Marks the delivery as FAILED. Valid from any in-progress status.
     * Only the assigned courier may call this.
     */
    DeliveryOrderResponse failDelivery(UUID deliveryId, UUID courierId, FailDeliveryRequest request);

    /**
     * Cancels a delivery. Only callable by admin / ecommerce service.
     * Terminal state — cannot be undone.
     */
    DeliveryOrderResponse cancel(UUID deliveryId, CancelDeliveryRequest request);

    /**
     * Cancels a delivery by ecommerce order ID. Called when the ecommerce backend
     * publishes an {@code order.delivery.cancelled} event. No-op if no delivery
     * exists or the delivery is already in a terminal state.
     */
    void cancelByEcommerceOrderId(UUID ecommerceOrderId, String reason);

    List<DeliveryStatusHistoryResponse> getStatusHistory(UUID deliveryId);

    /**
     * Returns the delivery proof for a given delivery, if it exists.
     */
    DeliveryProofResponse getProof(UUID deliveryId);
}
