# Buyology Courier — Ecommerce Backend Integration Guide

This document is the authoritative reference for the ecommerce backend team. It covers how to submit delivery orders, which RabbitMQ events to consume, how to handle live courier location for the customer map, how to cancel orders, and what REST endpoints are available for querying.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [RabbitMQ Topology](#2-rabbitmq-topology)
3. [Submit a Delivery Order (Inbound Event)](#3-submit-a-delivery-order-inbound-event)
4. [Events Published by the Courier Service (Outbound)](#4-events-published-by-the-courier-service-outbound)
   - [delivery.status.changed](#41-deliverystatuschanged)
   - [delivery.courier.assigned](#42-deliverycourierassigned)
   - [delivery.courier.assignment.accepted](#43-deliverycourierassignmentaccepted)
   - [delivery.courier.assignment.rejected](#44-deliverycourierassignmentrejected)
   - [delivery.assignment.exhausted](#45-deliveryassignmentexhausted)
   - [delivery.completed](#46-deliverycompleted)
   - [delivery.cancelled](#47-deliverycancelled)
   - [delivery.location.updated](#48-deliverylocationupdated)
5. [Cancel an Order](#5-cancel-an-order)
6. [REST Query Endpoints](#6-rest-query-endpoints)
7. [Live Courier Location — Customer Map](#7-live-courier-location--customer-map)
8. [Post-Delivery Notifications to Customer](#8-post-delivery-notifications-to-customer)
9. [Assignment Exhausted — What To Do](#9-assignment-exhausted--what-to-do)
10. [Authentication (Service-to-Service)](#10-authentication-service-to-service)
11. [Status Enum Reference](#11-status-enum-reference)
12. [Complete Event Flow](#12-complete-event-flow)

---

## 1. Architecture Overview

```
┌─────────────────────┐        RabbitMQ         ┌───────────────────────┐
│  Ecommerce Backend  │                          │   Courier Service     │
│                     │──order.delivery.requested──►  (ingests order,    │
│                     │                          │   assigns courier)    │
│                     │◄─delivery.status.changed─┤                       │
│                     │◄─delivery.completed──────┤  (notifies customer   │
│                     │◄─delivery.cancelled──────┤   email directly)     │
│                     │◄─delivery.location.updated┤                      │
│                     │◄─delivery.courier.assigned┤                      │
│                     │◄─assignment.accepted──────┤                      │
│                     │◄─assignment.rejected──────┤                      │
│                     │◄─assignment.exhausted─────┤                      │
└─────────────────────┘                          └───────────────────────┘
          │
          │  REST (service-to-service JWT)
          ▼
  GET  /api/v1/deliveries/{id}
  GET  /api/v1/deliveries/{id}/history
  POST /api/v1/deliveries/{id}/cancel
```

The courier service **also** sends the customer a direct confirmation email on `DELIVERED` and a failure email on `FAILED`. The ecommerce backend is responsible for any **in-app push notification** to the customer app using the events it receives from the queues below.

---

## 2. RabbitMQ Topology

| Property | Value |
| :--- | :--- |
| Broker | RabbitMQ (AMQP 0-9-1) |
| Virtual host | `/` (default) |
| Inbound exchange | `buyology.ecommerce.exchange` (topic, durable) |
| Outbound exchange | `buyology.delivery.exchange` (topic, durable) |
| Message encoding | JSON (UTF-8), `content-type: application/json` |
| Dead-letter exchange | `buyology.dlx` |

All queues used by the courier service are **durable** and have a dead-letter exchange configured. The ecommerce backend should declare its own consumer queues and bind them to `buyology.delivery.exchange` with the routing keys listed in [Section 4](#4-events-published-by-the-courier-service-outbound).

---

## 3. Submit a Delivery Order (Inbound Event)

Publish this event to `buyology.ecommerce.exchange` with routing key `order.delivery.requested` whenever a customer places an order that requires courier delivery.

**Routing key:** `order.delivery.requested`

**Payload:**

```json
{
  "ecommerceOrderId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "ecommerceStoreId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "customerName":     "Jane Smith",
  "customerPhone":    "+998901234567",
  "customerEmail":    "jane@example.com",
  "pickupAddress":    "123 Store Street, Tashkent",
  "pickupLat":        41.2995,
  "pickupLng":        69.2401,
  "dropoffAddress":   "456 Customer Ave, Tashkent",
  "dropoffLat":       41.3111,
  "dropoffLng":       69.2650,
  "packageSize":      "MEDIUM",
  "packageWeight":    2.5,
  "deliveryFee":      "15000.00",
  "priority":         "EXPRESS"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `ecommerceOrderId` | UUID string | **Yes** | Your order ID — used for idempotency; re-publishing the same UUID is safe |
| `ecommerceStoreId` | UUID string | **Yes** | Store the order originates from |
| `customerName` | string | No | Used in customer notification emails |
| `customerPhone` | string | No | For courier contact |
| `customerEmail` | string | No | Required for delivery confirmation email to customer |
| `pickupAddress` | string | No | Human-readable pickup address |
| `pickupLat` | number (decimal) | **Yes** | Pickup latitude — used for courier geo-search |
| `pickupLng` | number (decimal) | **Yes** | Pickup longitude |
| `dropoffAddress` | string | No | Human-readable drop-off address |
| `dropoffLat` | number (decimal) | **Yes** | Drop-off latitude |
| `dropoffLng` | number (decimal) | **Yes** | Drop-off longitude |
| `packageSize` | `SMALL` \| `MEDIUM` \| `LARGE` \| `EXTRA_LARGE` | No | |
| `packageWeight` | number (decimal, kg) | No | |
| `deliveryFee` | string (decimal) | No | Fee the courier earns; shown in the courier app |
| `priority` | `STANDARD` \| `EXPRESS` | **Yes** | `EXPRESS` is highlighted to couriers |

**Idempotency:** The courier service checks `ecommerceOrderId` before creating a delivery. Publishing the same event twice will return the existing delivery without creating a duplicate.

---

## 4. Events Published by the Courier Service (Outbound)

Declare consumer queues on your side and bind them to `buyology.delivery.exchange`.

---

### 4.1 `delivery.status.changed`

Published on **every** status transition. Use this as the primary event for keeping your order status in sync.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "status":           "ON_THE_WAY",
  "courierId":        "a1b2c3d4-...",
  "changedBy":        "COURIER",
  "occurredAt":       "2026-04-12T10:35:00Z"
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `deliveryId` | UUID string | Courier service's internal delivery ID |
| `ecommerceOrderId` | UUID string | Your order ID |
| `status` | enum | New status (see [Section 11](#11-status-enum-reference)) |
| `courierId` | UUID string \| null | Assigned courier (null if not yet assigned) |
| `changedBy` | `COURIER` \| `SYSTEM` \| `OPS` | Who triggered the change |
| `occurredAt` | ISO-8601 | Timestamp of the change |

**Recommended use:** Update your `orders.delivery_status` column and send an in-app push notification to the customer for key transitions (e.g. `COURIER_ACCEPTED`, `ON_THE_WAY`, `ARRIVED_AT_DESTINATION`).

---

### 4.2 `delivery.courier.assigned`

Published when a courier has been found and the offer sent. The courier has **not yet accepted**.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "courierId":        "a1b2c3d4-...",
  "assignmentId":     "assign-uuid",
  "attemptNumber":    1
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `attemptNumber` | int (1–3) | Which assignment attempt this is |

---

### 4.3 `delivery.courier.assignment.accepted`

Published immediately when the courier taps "Accept" in the mobile app.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "courierId":        "a1b2c3d4-...",
  "assignmentId":     "assign-uuid"
}
```

**Recommended use:** Show the customer "Your courier is on the way to pick up your order" with estimated arrival.

---

### 4.4 `delivery.courier.assignment.rejected`

Published when the courier rejects the offer. The service automatically searches for the next courier.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "courierId":        "a1b2c3d4-...",
  "assignmentId":     "assign-uuid",
  "attemptNumber":    1,
  "rejectionReason":  "Too far away"
}
```

No customer action is required — the courier service retries automatically (up to 3 attempts).

---

### 4.5 `delivery.assignment.exhausted`

Published when all 3 assignment attempts have been exhausted and no courier accepted. The delivery status is set to `FAILED`.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "totalAttempts":    3
}
```

**Required action:** Notify the customer that no courier is currently available, and either offer a retry, alternative fulfilment, or refund. See [Section 9](#9-assignment-exhausted--what-to-do).

---

### 4.6 `delivery.completed`

Published when the courier submits the final delivery proof photo and the status transitions to `DELIVERED`.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "status":           "DELIVERED",
  "courierId":        "a1b2c3d4-...",
  "changedBy":        "COURIER",
  "occurredAt":       "2026-04-12T11:00:00Z"
}
```

**Required actions:**
1. Mark the ecommerce order as **fulfilled**.
2. Send an in-app push notification to the customer: *"Your order has been delivered!"*
3. Update order timeline / tracking history.

> The courier service already sends the customer a confirmation **email** directly. Do not send a duplicate email — only handle the in-app push here.

---

### 4.7 `delivery.cancelled`

Published when the delivery transitions to either `CANCELLED` (customer/ops cancellation) or `FAILED` (courier-initiated). Both use the same routing key.

**Payload:**

```json
{
  "deliveryId":       "7c9e6679-...",
  "ecommerceOrderId": "3fa85f64-...",
  "status":           "CANCELLED",
  "courierId":        "a1b2c3d4-...",
  "changedBy":        "SYSTEM",
  "occurredAt":       "2026-04-12T10:50:00Z"
}
```

Check `status` to distinguish `CANCELLED` from `FAILED`.

**Required actions for `CANCELLED`:**
1. Mark the ecommerce order as cancelled.
2. Trigger refund if payment was captured.
3. Send customer in-app push: *"Your order has been cancelled."*

**Required actions for `FAILED`:**
1. Mark the ecommerce order as failed.
2. Retrieve the failure reason from GET `/api/v1/deliveries/{deliveryId}` → `cancelledReason` field.
3. Send customer in-app push: *"We couldn't deliver your order — [reason]."*

> The courier service already sends the customer a failure **email** directly. Only handle the in-app push here.

---

### 4.8 `delivery.location.updated`

Published on every GPS ping from the courier while an active delivery is in progress. This is the source of truth for the customer's live map view.

**Payload:**

```json
{
  "deliveryId":  "7c9e6679-...",
  "courierId":   "a1b2c3d4-...",
  "latitude":    41.3050,
  "longitude":   69.2500,
  "heading":     180.0,
  "speed":       25.5,
  "recordedAt":  "2026-04-12T10:35:00Z"
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `deliveryId` | UUID string | Which delivery this location belongs to |
| `courierId` | UUID string | The courier reporting their location |
| `latitude` / `longitude` | number | GPS coordinates |
| `heading` | number \| null | Direction in degrees (0–360) |
| `speed` | number \| null | Speed in km/h |
| `recordedAt` | ISO-8601 | Device timestamp |

**Recommended use:**
- Forward this event to the customer's WebSocket or SSE connection so the courier dot moves in real time on the customer app map.
- Cache the latest coordinates per `deliveryId` in Redis to serve to customers who connect mid-delivery.
- This event fires at up to 60 pings/minute. Use a debounce or sampling strategy on the consumer side if needed (e.g. forward every 2nd ping to reduce WS message volume).

> Location is only published when the delivery is in an active status: `COURIER_ACCEPTED`, `ARRIVED_AT_PICKUP`, `PICKED_UP`, `ON_THE_WAY`, or `ARRIVED_AT_DESTINATION`.

---

## 5. Cancel an Order

Call this endpoint when a customer cancels their order from the customer-facing app.

**POST** `/api/v1/deliveries/{deliveryId}/cancel`

**Auth:** Service-to-service JWT (see [Section 10](#10-authentication-service-to-service)).

```json
{
  "reason": "Customer requested cancellation"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `reason` | string | Yes | Reason for cancellation; shown to the courier |

**Response 200:** `DeliveryOrderResponse` with `status: "CANCELLED"`.

**Side effects triggered by this endpoint:**
- Delivery status → `CANCELLED`, appended to status history.
- `delivery.cancelled` event published via outbox.
- The assigned courier (if any) receives an FCM push notification and email informing them the order is cancelled and they should stop the job.

> You need `deliveryId` (the courier service's internal ID) for this endpoint. Map it from `ecommerceOrderId` using the query endpoint below.

---

## 6. REST Query Endpoints

All REST endpoints require a service-to-service JWT. Base URL: `https://courier.buyology.com`.

### Get delivery by courier-service ID

**GET** `/api/v1/deliveries/{deliveryId}`

**Response:** Full `DeliveryOrderResponse` including `cancelledReason`, `actualDeliveryTime`, and all address/coordinate fields.

### Find delivery by your order ID

**GET** `/api/v1/deliveries?status=DELIVERED&page=0&size=20`

Filter by status. To find a specific order, use your own `ecommerceOrderId` lookup — the event payloads always include it so you can maintain your own `ecommerceOrderId → deliveryId` mapping.

### Get status history for a delivery

**GET** `/api/v1/deliveries/{deliveryId}/history`

Returns a full audit trail of every status change:

```json
[
  {
    "id":        "hist-uuid",
    "status":    "COURIER_ASSIGNED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "SYSTEM",
    "notes":     "Auto-assigned to courier a1b2c3d4 (attempt 1)",
    "createdAt": "2026-04-12T10:00:15Z"
  }
]
```

### Get proof photos

**GET** `/api/v1/deliveries/{deliveryId}/proof`

```json
{
  "id":                 "proof-uuid",
  "deliveryId":         "7c9e6679-...",
  "pickupImageUrl":     "https://cdn.buyology.com/proofs/pickup/abc.jpg",
  "pickupPhotoTakenAt": "2026-04-12T10:15:00Z",
  "imageUrl":           "https://cdn.buyology.com/proofs/delivery/xyz.jpg",
  "signatureUrl":       null,
  "deliveredTo":        "John Doe",
  "photoTakenAt":       "2026-04-12T11:00:00Z",
  "createdAt":          "2026-04-12T10:15:02Z"
}
```

---

## 7. Live Courier Location — Customer Map

The ecommerce backend is the relay layer between the courier service and the customer app for live location. Here is the recommended implementation:

```
Courier app
  │
  └─ POST /api/v1/couriers/{id}/locations
           │
           └─ Courier service publishes delivery.location.updated to RabbitMQ
                    │
                    └─ Ecommerce backend consumes event
                             │
                             ├─ Cache latest coords in Redis:
                             │    key = "courier_location:{deliveryId}"
                             │    TTL = 60 seconds
                             │
                             └─ Forward to customer via WebSocket / SSE:
                                  channel = "/topic/delivery/{deliveryId}/location"
                                  payload = { lat, lng, heading, speed, recordedAt }
```

**Payload to forward to customer app:**

```json
{
  "deliveryId":  "7c9e6679-...",
  "latitude":    41.3050,
  "longitude":   69.2500,
  "heading":     180.0,
  "speed":       25.5,
  "recordedAt":  "2026-04-12T10:35:00Z"
}
```

**Load balancing note:** At high courier volumes this queue receives up to 60 messages/courier/minute. Use a dedicated consumer group with horizontal scaling (multiple consumer instances competing on the same queue). Consider forwarding only every 2nd or 3rd ping to WebSocket clients to reduce browser/mobile update overhead without visibly degrading map smoothness.

---

## 8. Post-Delivery Notifications to Customer

| Event | Courier service action | Ecommerce action required |
| :--- | :--- | :--- |
| `delivery.completed` | Sends **email** to `customerEmail` automatically | Send **in-app push** to customer: "Your order has been delivered!" |
| `delivery.cancelled` (status=FAILED) | Sends **email** to `customerEmail` automatically | Send **in-app push**: "We couldn't deliver your order — [reason]" |
| `delivery.cancelled` (status=CANCELLED) | No email (admin action) | Send **in-app push**: "Your order has been cancelled — [reason]" + trigger refund |
| `delivery.assignment.exhausted` | No email | Send **in-app push** or redirect to support |

To get the failure reason for `FAILED` deliveries, fetch the delivery via REST:

```
GET /api/v1/deliveries/{deliveryId}
→ response.cancelledReason  (the courier-provided reason)
```

---

## 9. Assignment Exhausted — What To Do

When you receive `delivery.assignment.exhausted`, no courier accepted the order after 3 attempts. The delivery is in `FAILED` status.

**Recommended response flow:**

1. Mark the ecommerce order as `COURIER_NOT_FOUND`.
2. Check if the customer wants to retry:
   - If yes: publish a new `order.delivery.requested` event with a new `ecommerceOrderId` (or use the same with a dedup-safe logic). The courier service will start a fresh search.
   - If no: issue a refund and mark the order as cancelled.
3. Send the customer an in-app push: *"We're having trouble finding a courier. We'll try again or you can cancel the order."*

---

## 10. Authentication (Service-to-Service)

REST calls from the ecommerce backend to the courier service use a JWT signed with your RSA-256 private key.

**Required JWT claims:**

```json
{
  "iss": "buyology-ecommerce-service",
  "roles": ["ECOMMERCE_SERVICE"],
  "exp": <unix timestamp>
}
```

Pass the token in the `Authorization` header:

```
Authorization: Bearer <jwt>
```

The courier service validates the signature using the corresponding public key configured at startup.

---

## 11. Status Enum Reference

| Status | Terminal | Description |
| :--- | :--- | :--- |
| `CREATED` | No | Order received, searching for courier |
| `COURIER_ASSIGNED` | No | Courier found, offer pending response |
| `COURIER_ACCEPTED` | No | Courier accepted, heading to pickup |
| `ARRIVED_AT_PICKUP` | No | Courier at store |
| `PICKED_UP` | No | Package collected (pickup photo submitted) |
| `ON_THE_WAY` | No | Courier travelling to customer |
| `ARRIVED_AT_DESTINATION` | No | Courier at customer address |
| `DELIVERED` | **Yes** | Successfully delivered (delivery photo submitted) |
| `FAILED` | **Yes** | Courier-initiated failure or assignment exhausted |
| `CANCELLED` | **Yes** | Customer / operations cancellation |

---

## 12. Complete Event Flow

```
Ecommerce publishes:
  buyology.ecommerce.exchange  →  order.delivery.requested
                                          │
                    ┌─────────────────────┘
                    ▼
          Courier service ingests order
                    │
         (system searches for nearest courier)
                    │
        ◄── delivery.courier.assigned          (attempt 1 sent)
                    │
              ┌─────┴─────┐
           ACCEPT       REJECT
              │             │
  ◄── assignment.accepted  ◄── assignment.rejected
              │                        │
  ◄── delivery.status.changed    (retry attempt 2 or 3)
      (status=COURIER_ACCEPTED)        │
              │               (if all 3 exhausted)
              │          ◄── delivery.assignment.exhausted
              │
  (courier travels to pickup)
  ◄── delivery.location.updated  (continuous, every 5–10s)
  ◄── delivery.status.changed    (ARRIVED_AT_PICKUP)
              │
  (courier submits pickup photo)
  ◄── delivery.status.changed    (PICKED_UP)
              │
  ◄── delivery.location.updated  (continuous)
  ◄── delivery.status.changed    (ON_THE_WAY)
  ◄── delivery.status.changed    (ARRIVED_AT_DESTINATION)
              │
  (courier submits delivery photo)
  ◄── delivery.completed         ← mark order fulfilled
  ◄── delivery.status.changed    (DELIVERED)
              │
         Send in-app push to customer: "Order delivered!"
              │
         ✓ Done

  ── OR ──

  Ecommerce cancels:
    POST /api/v1/deliveries/{id}/cancel
  ◄── delivery.cancelled         ← mark order cancelled, refund
        Courier receives FCM push immediately

  ── OR ──

  Courier fails:
  ◄── delivery.cancelled         (status=FAILED)
        Fetch cancelledReason from REST
        Send in-app push to customer with reason
```
