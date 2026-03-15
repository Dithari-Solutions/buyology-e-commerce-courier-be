package com.buyology.buyology_courier.delivery.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "delivery_proofs",
        indexes = {
                @Index(name = "idx_delivery_proofs_delivery_id", columnList = "delivery_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryProof {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false, unique = true, foreignKey = @ForeignKey(name = "fk_delivery_proofs_order"))
    private DeliveryOrder delivery;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "signature_url", columnDefinition = "TEXT")
    private String signatureUrl;

    // Name of the person who received the package
    @Column(name = "delivered_to", length = 255)
    private String deliveredTo;

    // Timestamp when the photo/signature was captured (may differ from created_at)
    @Column(name = "photo_taken_at")
    private Instant photoTakenAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
