package com.buyology.buyology_courier.courier.repository.spec;

import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class CourierSpecification {

    private CourierSpecification() {}

    public static Specification<Courier> filter(
            CourierStatus status,
            VehicleType vehicleType,
            Boolean isAvailable
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Soft-delete gate — always applied
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (vehicleType != null) {
                predicates.add(cb.equal(root.get("vehicleType"), vehicleType));
            }
            if (isAvailable != null) {
                predicates.add(cb.equal(root.get("isAvailable"), isAvailable));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
