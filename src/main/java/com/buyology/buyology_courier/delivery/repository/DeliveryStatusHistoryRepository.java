package com.buyology.buyology_courier.delivery.repository;

import com.buyology.buyology_courier.delivery.domain.DeliveryStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryStatusHistoryRepository extends JpaRepository<DeliveryStatusHistory, UUID> {

    List<DeliveryStatusHistory> findByDeliveryIdOrderByCreatedAtAsc(UUID deliveryId);
}
