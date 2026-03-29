# Courier Backend — Ecommerce Integration Guide

This document describes everything the ecommerce backend must implement to integrate with the courier backend for delivery assignment, tracking, and status sync.

---

## Overview

```
┌─────────────────────┐      RabbitMQ       ┌─────────────────────┐
│  Ecommerce Backend  │ ──── publishes ────► │  Courier Backend    │
│                     │                      │                      │
│                     │ ◄─── consumes ─────  │  publishes status   │
│                     │                      │  & assignment events│
└─────────────────────┘                      └─────────────────────┘
         │                                            │
         └────────── REST (service-to-service) ───────┘
              (JWT signed with ecommerce private key)
```

All delivery requests travel **ecommerce → courier** over RabbitMQ.
All delivery status updates travel **courier → ecommerce** over RabbitMQ.
The REST API is available for on-demand queries (order status, cancellation).

---

## 1. RabbitMQ Topology

### Exchanges

| Exchange | Type | Durable | Direction |
|---|---|---|---|
| `buyology.ecommerce.exchange` | topic | yes | **Ecommerce publishes** here |
| `buyology.delivery.exchange` | topic | yes | **Courier publishes** here; ecommerce consumes |

### Dead-letter

Failed messages (after retries) are routed to `buyology.dlq` via `buyology.dlx`. Monitor this queue for processing errors.

---

## 2. Ecommerce → Courier: Publishing a Delivery Request

After a payment succeeds, publish the following message to trigger courier assignment.

**Exchange:** `buyology.ecommerce.exchange`
**Routing key:** `order.delivery.requested`
**Content-Type:** `application/json`

### Payload

```json
{
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "ecommerceStoreId":  "a1b2c3d4-e5f6-7890-abcd-ef1234567890",

  "customerName":  "Jane Smith",
  "customerPhone": "+998901234567",

  "pickupAddress": "1 Amir Temur Ave, Tashkent",
  "pickupLat":     41.2995,
  "pickupLng":     69.2401,

  "dropoffAddress": "45 Navoi St, Tashkent",
  "dropoffLat":    41.3111,
  "dropoffLng":    69.2550,

  "packageSize":   "SMALL",
  "packageWeight": 1.50,
  "deliveryFee":   15000.00,

  "priority":    "EXPRESS",
  "occurredAt":  "2026-03-30T10:15:00Z"
}
```

### Field Reference

| Field | Type | Required | Notes |
|---|---|---|---|
| `ecommerceOrderId` | UUID | yes | Must be globally unique. Used as idempotency key — duplicate messages are silently skipped. |
| `ecommerceStoreId` | UUID | yes | Store/merchant the order belongs to. |
| `customerName` | string | yes | Snapshotted at creation; not a foreign key. |
| `customerPhone` | string | yes | Used for courier-customer contact. |
| `pickupAddress` | string | yes | Human-readable address of the merchant/warehouse. |
| `pickupLat` | decimal | yes | Latitude, up to 7 decimal places. |
| `pickupLng` | decimal | yes | Longitude, up to 7 decimal places. |
| `dropoffAddress` | string | yes | Customer delivery address. |
| `dropoffLat` | decimal | yes | |
| `dropoffLng` | decimal | yes | |
| `packageSize` | enum | no | `SMALL` \| `MEDIUM` \| `LARGE` \| `EXTRA_LARGE` |
| `packageWeight` | decimal | no | Kilograms. |
| `deliveryFee` | decimal | no | Amount to pay the courier (in local currency). |
| `priority` | enum | yes | `STANDARD` \| `EXPRESS`. EXPRESS orders are prioritised in assignment. |
| `occurredAt` | ISO 8601 | yes | When the payment was confirmed. |

### Idempotency

The courier backend deduplicates on `ecommerceOrderId`. Re-publishing the same UUID is safe — the second message is acked and ignored without creating a duplicate delivery.

---

## 3. Courier → Ecommerce: Status Events to Consume

Create a durable queue in your service, bind it to `buyology.delivery.exchange`, and subscribe to the routing keys below.

### Recommended queue setup

```
Queue name:   ecommerce.delivery.status.queue
Exchange:     buyology.delivery.exchange
Binding keys: delivery.status.changed
              delivery.completed
              delivery.cancelled
              delivery.courier.assigned
              delivery.courier.assignment.accepted
              delivery.courier.assignment.rejected
              delivery.assignment.exhausted
```

---

### 3.1 `delivery.status.changed`

Published on every delivery status transition that does not have a more specific routing key.

```json
{
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "status":           "ARRIVED_AT_PICKUP",
  "courierId":        "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "changedBy":        "COURIER",
  "changedAt":        "2026-03-30T10:22:00Z"
}
```

**Status values (in order):**

```
CREATED → COURIER_ASSIGNED → COURIER_ACCEPTED → ARRIVED_AT_PICKUP
→ PICKED_UP → ON_THE_WAY → ARRIVED_AT_DESTINATION → DELIVERED
```

`CANCELLED` can appear at any point.

---

### 3.2 `delivery.completed`

Published when the final status is `DELIVERED`.

```json
{
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "status":           "DELIVERED",
  "courierId":        "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "changedBy":        "COURIER",
  "changedAt":        "2026-03-30T10:45:00Z"
}
```

Use this to mark the ecommerce order as **fulfilled**.

---

### 3.3 `delivery.cancelled`

Published when the delivery is cancelled (by admin or ops).

```json
{
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "status":           "CANCELLED",
  "courierId":        null,
  "changedBy":        "SYSTEM",
  "changedAt":        "2026-03-30T10:20:00Z"
}
```

---

### 3.4 `delivery.courier.assigned`

Published when the courier backend auto-assigns a courier. Use this to update the order view ("Courier is on the way to pick up your order").

```json
{
  "eventVersion":     1,
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "courierId":        "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "assignmentId":     "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
  "attemptNumber":    1,
  "assignedAt":       "2026-03-30T10:16:00Z",
  "occurredAt":       "2026-03-30T10:16:00Z"
}
```

---

### 3.5 `delivery.courier.assignment.accepted`

Published when the courier explicitly accepts the assignment in their app.

```json
{
  "eventVersion":     1,
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "courierId":        "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "assignmentId":     "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
  "acceptedAt":       "2026-03-30T10:17:00Z",
  "occurredAt":       "2026-03-30T10:17:00Z"
}
```

---

### 3.6 `delivery.courier.assignment.rejected`

Published when a courier rejects. A reassignment is automatically triggered (up to 3 attempts). The ecommerce backend can surface this as "Finding another courier…".

```json
{
  "eventVersion":     1,
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "courierId":        "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "assignmentId":     "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
  "attemptNumber":    1,
  "rejectionReason":  "Too far from pickup",
  "rejectedAt":       "2026-03-30T10:16:45Z",
  "occurredAt":       "2026-03-30T10:16:45Z"
}
```

---

### 3.7 `delivery.assignment.exhausted`

Published after 3 consecutive rejections with no courier found. This requires **manual admin intervention** on the courier side. Ecommerce should notify the customer that delivery is delayed and alert ops.

```json
{
  "eventVersion":     1,
  "deliveryId":       "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "totalAttempts":    3,
  "occurredAt":       "2026-03-30T10:22:00Z"
}
```

---

## 4. REST API (Service-to-Service)

Use the REST API for on-demand queries and mutations that do not fit the async event model (e.g. admin dashboards, cancellations, status polling).

### Base URL

```
https://<courier-backend-host>/api/v1
```

### Authentication

All REST calls must include a Bearer JWT signed with the **ecommerce backend's RSA-256 private key**.

The courier backend validates the token against:
- `iss` claim = `buyology-ecommerce-service` (configurable)
- Signature verified with `ecommerce-public.pem` (the public key stored in the courier backend)

**Token generation (example — Spring Boot):**

```java
// Generate using your RSA private key
JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(jwkSet));
JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
JwtClaimsSet claims = JwtClaimsSet.builder()
    .issuer("buyology-ecommerce-service")
    .subject("ecommerce-service")
    .claim("roles", List.of("ECOMMERCE_SERVICE"))
    .issuedAt(Instant.now())
    .expiresAt(Instant.now().plusSeconds(300))
    .build();
String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

**Key requirement:** The `roles` claim must contain `"ECOMMERCE_SERVICE"`.

> **Key exchange:** Share your RSA **public key** with the courier backend team. They store it as `ecommerce-public.pem` in `src/main/resources/`. Keep your private key secret.

---

### 4.1 Get delivery order

```
GET /api/v1/deliveries/{deliveryId}
Authorization: Bearer <ecommerce-service-jwt>
```

**Response 200:**
```json
{
  "id":               "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "ecommerceOrderId": "550e8400-e29b-41d4-a716-446655440000",
  "ecommerceStoreId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "customerName":     "Jane Smith",
  "customerPhone":    "+998901234567",
  "pickupAddress":    "1 Amir Temur Ave, Tashkent",
  "pickupLat":        41.2995,
  "pickupLng":        69.2401,
  "dropoffAddress":   "45 Navoi St, Tashkent",
  "dropoffLat":       41.3111,
  "dropoffLng":       69.2550,
  "packageSize":      "SMALL",
  "packageWeight":    1.50,
  "deliveryFee":      15000.00,
  "priority":         "EXPRESS",
  "status":           "COURIER_ACCEPTED",
  "assignedCourierId":"7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "estimatedDeliveryTime": "2026-03-30T10:46:00Z",
  "actualDeliveryTime":    null,
  "cancelledReason":       null,
  "createdAt":        "2026-03-30T10:15:05Z",
  "updatedAt":        "2026-03-30T10:17:00Z"
}
```

---

### 4.2 List delivery orders (with optional status filter)

```
GET /api/v1/deliveries?status=COURIER_ASSIGNED&page=0&size=20&sort=createdAt,desc
Authorization: Bearer <ecommerce-service-jwt>
```

---

### 4.3 Get full status history for a delivery

```
GET /api/v1/deliveries/{deliveryId}/history
Authorization: Bearer <ecommerce-service-jwt>
```

**Response 200:**
```json
[
  {
    "id":         "aabbcc00-...",
    "status":     "CREATED",
    "latitude":   null,
    "longitude":  null,
    "changedBy":  "SYSTEM",
    "notes":      null,
    "createdAt":  "2026-03-30T10:15:05Z"
  },
  {
    "id":         "aabbcc01-...",
    "status":     "COURIER_ASSIGNED",
    "latitude":   null,
    "longitude":  null,
    "changedBy":  "SYSTEM",
    "notes":      "Auto-assigned to courier 7c9e6679-... (attempt 1)",
    "createdAt":  "2026-03-30T10:16:00Z"
  }
]
```

---

### 4.4 Cancel a delivery

```
POST /api/v1/deliveries/{deliveryId}/cancel
Authorization: Bearer <ecommerce-service-jwt>
Content-Type: application/json

{
  "reason": "Customer cancelled the order"
}
```

**Response 200:** Updated `DeliveryOrderResponse` with `status: "CANCELLED"`.

> Cancellation is terminal. It cannot be undone. A `delivery.cancelled` event is published to `buyology.delivery.exchange` automatically.

---

## 5. Delivery Status Flow Reference

```
CREATED
  │
  ▼ (auto-assignment, within seconds)
COURIER_ASSIGNED ──► courier rejects ──► COURIER_ASSIGNED (reassigned)
  │                                              │
  │                                     3 failures → EXHAUSTED event
  ▼ (courier accepts in app)
COURIER_ACCEPTED
  │
  ▼ (courier arrives at merchant)
ARRIVED_AT_PICKUP
  │
  ▼ (courier collects package)
PICKED_UP
  │
  ▼
ON_THE_WAY
  │
  ▼ (courier arrives at customer)
ARRIVED_AT_DESTINATION
  │
  ▼ (courier delivers, uploads photo)
DELIVERED  ◄── terminal

CANCELLED  ◄── terminal (can happen from any non-terminal state)
```

---

## 6. Ecommerce-Side Implementation Checklist

### RabbitMQ Publisher (ecommerce → courier)
- [ ] Declare `buyology.ecommerce.exchange` as a durable topic exchange (idempotent — safe to declare if it exists)
- [ ] On payment success: serialize and publish `DeliveryOrderReceivedEvent` to routing key `order.delivery.requested`
- [ ] Set `content_type: application/json` and `persistent: true` on published messages
- [ ] Handle broker unavailability: use publisher confirms and a local outbox or retry queue

### RabbitMQ Consumer (courier → ecommerce)
- [ ] Declare a durable queue (e.g. `ecommerce.delivery.status.queue`) with DLX configured
- [ ] Bind it to `buyology.delivery.exchange` with the routing keys listed in [section 3](#3-courier--ecommerce-status-events-to-consume)
- [ ] Process events idempotently — the courier backend uses at-least-once delivery
- [ ] On `delivery.completed`: mark ecommerce order as fulfilled, notify customer
- [ ] On `delivery.cancelled`: update ecommerce order status, notify customer
- [ ] On `delivery.assignment.exhausted`: alert ops team, consider offering customer alternatives
- [ ] On `delivery.courier.assigned`: show customer "Courier assigned" notification
- [ ] On `delivery.courier.assignment.accepted`: show "Courier is heading to pickup"

### RSA Key Setup
- [ ] Generate RSA-2048 (or RSA-4096) key pair for the ecommerce service
- [ ] Share **public key** (PEM format) with the courier backend team to replace `ecommerce-public.pem`
- [ ] Store **private key** securely (environment variable / secrets manager — never commit)
- [ ] Set `iss` claim to `buyology-ecommerce-service` (or agree on a custom value and configure `ECOMMERCE_SERVICE_JWT_ISSUER` on the courier backend)

### Error Handling
- [ ] Log and alert on `delivery.assignment.exhausted` messages
- [ ] Monitor the `buyology.dlq` dead-letter queue for messages that failed processing on both sides
- [ ] Implement idempotency key checks on all consumed events (use `deliveryId` + `status` or event UUID as idempotency key)

---

## 7. Environment Configuration

The courier backend reads these environment variables for the ecommerce integration:

| Variable | Default | Description |
|---|---|---|
| `ECOMMERCE_SERVICE_JWT_ISSUER` | `buyology-ecommerce-service` | Expected `iss` claim in ecommerce JWTs |
| `RABBITMQ_HOST` | `localhost` | RabbitMQ host |
| `RABBITMQ_PORT` | `5672` | RabbitMQ port |
| `RABBITMQ_USERNAME` | `guest` | RabbitMQ credentials |
| `RABBITMQ_PASSWORD` | `guest` | |
| `RABBITMQ_VHOST` | `/` | Virtual host |

Share RabbitMQ credentials and host with the courier backend team for shared-broker setups, or use separate brokers with federation.

---

## 8. Quick Test (cURL)

After setting up RabbitMQ credentials, verify the integration by publishing a test delivery request:

```bash
# Publish via RabbitMQ management API
curl -u guest:guest \
  -H "Content-Type: application/json" \
  -X POST http://localhost:15672/api/exchanges/%2F/buyology.ecommerce.exchange/publish \
  -d '{
    "routing_key": "order.delivery.requested",
    "payload": "{\"ecommerceOrderId\":\"550e8400-e29b-41d4-a716-446655440000\",\"ecommerceStoreId\":\"a1b2c3d4-e5f6-7890-abcd-ef1234567890\",\"customerName\":\"Test User\",\"customerPhone\":\"+998901234567\",\"pickupAddress\":\"1 Test St\",\"pickupLat\":41.2995,\"pickupLng\":69.2401,\"dropoffAddress\":\"2 Test Ave\",\"dropoffLat\":41.3111,\"dropoffLng\":69.2550,\"packageSize\":\"SMALL\",\"packageWeight\":1.0,\"deliveryFee\":10000,\"priority\":\"STANDARD\",\"occurredAt\":\"2026-03-30T10:00:00Z\"}",
    "payload_encoding": "string",
    "properties": {"content_type": "application/json", "delivery_mode": 2}
  }'
```

Then poll the courier backend REST API to confirm the delivery was created and a courier was assigned:

```bash
# Replace <deliveryId> with the ID returned in the delivery.status.changed event
curl -H "Authorization: Bearer <ecommerce-service-jwt>" \
  https://<courier-host>/api/v1/deliveries/<deliveryId>
```

Expected `status`: `COURIER_ASSIGNED` (within a few seconds if couriers are online with a recent location ping).
