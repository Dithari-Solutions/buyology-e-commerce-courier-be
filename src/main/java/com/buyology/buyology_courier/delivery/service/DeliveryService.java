package com.buyology.buyology_courier.delivery.service;

import com.buyology.buyology_courier.delivery.domain.enums.DeliveryStatus;
import com.buyology.buyology_courier.delivery.dto.request.CancelDeliveryRequest;
import com.buyology.buyology_courier.delivery.dto.request.UpdateDeliveryStatusRequest;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryOrderResponse;
import com.buyology.buyology_courier.delivery.dto.response.DeliveryStatusHistoryResponse;
import com.buyology.buyology_courier.delivery.messaging.event.DeliveryOrderReceivedEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
     * Courier updates the delivery status (e.g. PICKED_UP → ON_THE_WAY).
     * Only the assigned courier may call this.
     */
    DeliveryOrderResponse updateStatus(UUID deliveryId, UUID courierId,
                                       UpdateDeliveryStatusRequest request);

    /**
     * Cancels a delivery. Only callable by admin / ecommerce service.
     * Terminal state — cannot be undone.
     */
    DeliveryOrderResponse cancel(UUID deliveryId, CancelDeliveryRequest request);

    List<DeliveryStatusHistoryResponse> getStatusHistory(UUID deliveryId);
}
