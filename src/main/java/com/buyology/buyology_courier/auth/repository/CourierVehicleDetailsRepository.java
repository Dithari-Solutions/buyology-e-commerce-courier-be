package com.buyology.buyology_courier.auth.repository;

import com.buyology.buyology_courier.auth.domain.CourierVehicleDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CourierVehicleDetailsRepository extends JpaRepository<CourierVehicleDetails, UUID> {

    Optional<CourierVehicleDetails> findByCourierId(UUID courierId);
}
