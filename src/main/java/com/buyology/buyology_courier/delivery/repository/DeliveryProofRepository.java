package com.buyology.buyology_courier.delivery.repository;

import com.buyology.buyology_courier.delivery.domain.DeliveryProof;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryProofRepository extends JpaRepository<DeliveryProof, UUID> {

    Optional<DeliveryProof> findByDeliveryId(UUID deliveryId);
}
