package com.buyology.buyology_courier.auth.repository;

import com.buyology.buyology_courier.auth.domain.CourierCredentials;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CourierCredentialsRepository extends JpaRepository<CourierCredentials, UUID> {

    Optional<CourierCredentials> findByPhoneNumber(String phoneNumber);

    Optional<CourierCredentials> findByCourierId(UUID courierId);

    boolean existsByPhoneNumber(String phoneNumber);
}
