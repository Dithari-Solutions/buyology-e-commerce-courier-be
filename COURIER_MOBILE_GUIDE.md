# Buyology Courier — Mobile App Integration Guide

This document is the authoritative reference for the courier mobile app team. It covers every API endpoint, payload field, status transition, real-time channel, and notification type the app must handle.

---

## Table of Contents

1. [Base URL & Auth](#1-base-url--auth)
2. [Authentication Flow](#2-authentication-flow)
3. [Register FCM Push Token](#3-register-fcm-push-token)
4. [Delivery Lifecycle & Status Reference](#4-delivery-lifecycle--status-reference)
5. [Real-time Assignment Offers](#5-real-time-assignment-offers)
   - [WebSocket / STOMP](#51-websocket--stomp)
   - [FCM Push Notifications](#52-fcm-push-notifications)
6. [Accept or Reject an Assignment](#6-accept-or-reject-an-assignment)
7. [Status Updates (No Photo Required)](#7-status-updates-no-photo-required)
8. [Submit Pickup Proof (Photo)](#8-submit-pickup-proof-photo)
9. [Submit Delivery Proof (Photo)](#9-submit-delivery-proof-photo)
10. [Report Delivery Failure](#10-report-delivery-failure)
11. [Live Location Reporting](#11-live-location-reporting)
12. [Maps / Navigation Deep-links](#12-maps--navigation-deep-links)
13. [Courier History](#13-courier-history)
14. [Cancellation — What the App Must Handle](#14-cancellation--what-the-app-must-handle)
15. [Post-Delivery Notifications](#15-post-delivery-notifications)
16. [Courier Profile & Availability](#16-courier-profile--availability)
17. [Error Response Format](#17-error-response-format)
18. [Complete Flow Diagram](#18-complete-flow-diagram)

---

## 1. Base URL & Auth

| Environment | Base URL |
| :--- | :--- |
| Production | `https://courier.buyology.com` |
| Staging | `https://courier-staging.buyology.com` |

All endpoints require a JWT Bearer token unless noted otherwise.

```
Authorization: Bearer <accessToken>
```

Tokens expire. Use the refresh endpoint to get a new one before expiry. The `expiresIn` field in the login response is in **seconds**.

---

## 2. Authentication Flow

### Login

**POST** `/api/auth/courier/login`

```json
{
  "phone": "+998901234567",
  "password": "your_password"
}
```

**Response 200:**

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "expiresIn": 3600,
  "courierId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

> Store `courierId` locally — it is required by the WebSocket connection.

### Refresh Token

**POST** `/api/auth/courier/refresh`

```json
{
  "refreshToken": "eyJ..."
}
```

**Response 200:** Same shape as login response.

### Logout

**POST** `/api/auth/courier/logout`

```json
{
  "refreshToken": "eyJ..."
}
```

**Response 204 No Content.**

---

## 3. Register FCM Push Token

Call this **immediately after login** and whenever the OS issues a new FCM token.

**POST** `/api/v1/couriers/push-token`

```json
{
  "fcmToken": "fxzk3..."
}
```

**Response 204 No Content.**

> If the token is not registered, the courier will not receive background push notifications when the app is closed.

---

## 4. Delivery Lifecycle & Status Reference

```
CREATED ──(system assigns)──► COURIER_ASSIGNED
                                     │
                         ┌──────────┴──────────┐
                    ACCEPT                  REJECT
                         │                       │
                  COURIER_ACCEPTED      (system finds next courier,
                         │               up to 3 attempts)
                  ARRIVED_AT_PICKUP
                         │  (submit pickup photo)
                    PICKED_UP
                         │
                    ON_THE_WAY
                         │
               ARRIVED_AT_DESTINATION
                         │  (submit delivery photo)
                     DELIVERED ◄── Terminal ✓

         Any in-progress state ──► FAILED    ◄── Terminal ✗ (courier reports)
         Any non-terminal state ──► CANCELLED ◄── Terminal ✗ (customer/ops)
```

| Status | Who Changes It | App Action |
| :--- | :--- | :--- |
| `CREATED` | System | — |
| `COURIER_ASSIGNED` | System | Show "New order offer" — courier must ACCEPT or REJECT |
| `COURIER_ACCEPTED` | Courier | Navigate to pickup |
| `ARRIVED_AT_PICKUP` | Courier | Show camera — take pickup photo |
| `PICKED_UP` | Courier (via photo) | Navigate to drop-off |
| `ON_THE_WAY` | Courier | Report location every 5–10 seconds |
| `ARRIVED_AT_DESTINATION` | Courier | Show camera — take delivery photo |
| `DELIVERED` | Courier (via photo) | Show success screen |
| `FAILED` | Courier | Show failure confirmation |
| `CANCELLED` | Customer / Ops | Show cancellation banner, stop job |

---

## 5. Real-time Assignment Offers

The backend sends assignment offers through **two parallel channels**. The app must handle both.

### 5.1 WebSocket / STOMP

Connect when the app is in the foreground or background (keep-alive recommended).

| Property | Value |
| :--- | :--- |
| URL | `ws://<host>/ws` |
| Protocol | STOMP over WebSocket |
| Subscribe to | `/user/queue/assignments` |
| Auth | `Authorization: Bearer <accessToken>` in the STOMP CONNECT frame headers |

**Connection example (pseudocode):**

```js
const client = Stomp.over(new WebSocket("wss://courier.buyology.com/ws"));
client.connect(
  { Authorization: "Bearer " + accessToken },
  () => {
    client.subscribe("/user/queue/assignments", (frame) => {
      const offer = JSON.parse(frame.body);
      showNewOfferScreen(offer);
    });
  }
);
```

**Incoming payload:**

```json
{
  "assignmentId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "deliveryId":   "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "pickupAddress":  "123 Store Street, Tashkent",
  "pickupLat":      41.2995,
  "pickupLng":      69.2401,
  "dropoffAddress": "456 Customer Ave, Tashkent",
  "dropoffLat":     41.3111,
  "dropoffLng":     69.2650,
  "deliveryFee":    "15000.00",
  "priority":       "EXPRESS"
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `assignmentId` | UUID string | ID to use when accepting or rejecting |
| `deliveryId` | UUID string | The delivery this assignment is for |
| `pickupAddress` | string | Human-readable pickup location |
| `pickupLat` / `pickupLng` | number | Coordinates for navigation |
| `dropoffAddress` | string | Human-readable drop-off location |
| `dropoffLat` / `dropoffLng` | number | Coordinates for navigation |
| `deliveryFee` | string (decimal) | Fee the courier earns |
| `priority` | `STANDARD` \| `EXPRESS` | EXPRESS orders should be highlighted |

### 5.2 FCM Push Notifications

Received when the app is in the background or killed.

**Notification payload:**

```json
{
  "notification": {
    "title": "New delivery order",
    "body":  "123 Store Street → 456 Customer Ave"
  },
  "data": {
    "type":           "NEW_ASSIGNMENT",
    "assignmentId":   "3fa85f64-...",
    "deliveryId":     "7c9e6679-...",
    "pickupAddress":  "123 Store Street, Tashkent",
    "dropoffAddress": "456 Customer Ave, Tashkent",
    "deliveryFee":    "15000.00",
    "priority":       "EXPRESS"
  }
}
```

When the user taps the notification, use `data.assignmentId` and `data.deliveryId` to deep-link directly to the offer screen.

---

## 6. Accept or Reject an Assignment

The courier **must** respond before proceeding. The backend retries with the next nearest courier if rejected.

**POST** `/api/v1/assignments/{assignmentId}/respond`

**Accept:**

```json
{
  "action": "ACCEPT"
}
```

**Reject:**

```json
{
  "action": "REJECT",
  "rejectionReason": "Too far away"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `ACCEPT` \| `REJECT` | Yes | Courier's decision |
| `rejectionReason` | string | No (but recommended for REJECT) | Reason shown to operations |

**Response 200:**

```json
{
  "assignmentId":    "3fa85f64-...",
  "deliveryId":      "7c9e6679-...",
  "courierId":       "a1b2c3d4-...",
  "status":          "ACCEPTED",
  "attemptNumber":   1,
  "assignedAt":      "2026-04-12T10:00:00Z",
  "acceptedAt":      "2026-04-12T10:01:30Z",
  "rejectedAt":      null,
  "rejectionReason": null,
  "createdAt":       "2026-04-12T10:00:00Z",
  "pickupAddress":   "123 Store Street, Tashkent",
  "pickupLat":       41.2995,
  "pickupLng":       69.2401,
  "dropoffAddress":  "456 Customer Ave, Tashkent",
  "dropoffLat":      41.3111,
  "dropoffLng":      69.2650
}
```

After **ACCEPT**, navigate to the pickup location. After **REJECT**, close the offer screen — the backend will reassign.

---

## 7. Status Updates (No Photo Required)

Used for transitions that do not require a photo. Always include current GPS coordinates.

**POST** `/api/v1/deliveries/{deliveryId}/status`

```json
{
  "status":    "ARRIVED_AT_PICKUP",
  "latitude":  41.2995,
  "longitude": 69.2401,
  "notes":     "Arrived at store, waiting for package"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `status` | enum (see below) | Yes | Target status |
| `latitude` | number | Recommended | Current GPS latitude |
| `longitude` | number | Recommended | Current GPS longitude |
| `notes` | string | No | Optional free-text note |

**Allowed status values via this endpoint:**

| Call when… | Send `status` |
| :--- | :--- |
| Courier has accepted and arrived at store | `ARRIVED_AT_PICKUP` |
| Courier is heading to customer | `ON_THE_WAY` |
| Courier has reached customer address | `ARRIVED_AT_DESTINATION` |

> `COURIER_ACCEPTED` is set automatically on assignment accept. `PICKED_UP` and `DELIVERED` are set via the proof endpoints.

**Response 200:** `DeliveryOrderResponse` (see [Section 9](#9-submit-delivery-proof-photo) for field list).

---

## 8. Submit Pickup Proof (Photo)

Transition: `ARRIVED_AT_PICKUP` → `PICKED_UP`

The courier must photograph the package at the store before it is considered in their possession.

**POST** `/api/v1/deliveries/{deliveryId}/actions/pickup-proof`

`Content-Type: multipart/form-data`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `photo` | File (JPEG / PNG) | Yes | Photo of the package at the pickup location |
| `photoTakenAt` | ISO-8601 string | No | Timestamp when the photo was taken; defaults to server time |

**Response 200:**

```json
{
  "id":                "proof-uuid",
  "deliveryId":        "7c9e6679-...",
  "pickupImageUrl":    "https://cdn.buyology.com/proofs/pickup/abc.jpg",
  "pickupPhotoTakenAt":"2026-04-12T10:15:00Z",
  "imageUrl":          null,
  "signatureUrl":      null,
  "deliveredTo":       null,
  "photoTakenAt":      null,
  "createdAt":         "2026-04-12T10:15:02Z"
}
```

After success, the delivery status becomes `PICKED_UP`. Navigate to the drop-off location.

---

## 9. Submit Delivery Proof (Photo)

Transition: `ARRIVED_AT_DESTINATION` → `DELIVERED`

The courier must photograph the delivered package at the customer's door.

**POST** `/api/v1/deliveries/{deliveryId}/actions/deliver-proof`

`Content-Type: multipart/form-data`

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `photo` | File (JPEG / PNG) | Yes | Photo of the delivered package at destination |
| `deliveredTo` | string | No | Name of the person who received the package |
| `photoTakenAt` | ISO-8601 string | No | Timestamp when the photo was taken; defaults to server time |

**Response 200:**

```json
{
  "id":                "proof-uuid",
  "deliveryId":        "7c9e6679-...",
  "pickupImageUrl":    "https://cdn.buyology.com/proofs/pickup/abc.jpg",
  "pickupPhotoTakenAt":"2026-04-12T10:15:00Z",
  "imageUrl":          "https://cdn.buyology.com/proofs/delivery/xyz.jpg",
  "signatureUrl":      null,
  "deliveredTo":       "John Doe",
  "photoTakenAt":      "2026-04-12T10:45:00Z",
  "createdAt":         "2026-04-12T10:15:02Z"
}
```

After success:
- Delivery status becomes `DELIVERED`.
- The **customer** receives an email confirmation.
- The **courier** receives an FCM push and email (see [Section 15](#15-post-delivery-notifications)).
- The ecommerce backend is notified via RabbitMQ.

### DeliveryOrderResponse Fields (referenced by multiple endpoints)

```json
{
  "id":                    "7c9e6679-...",
  "ecommerceOrderId":      "ord-uuid",
  "ecommerceStoreId":      "store-uuid",
  "customerName":          "Jane Smith",
  "customerPhone":         "+998901234567",
  "customerEmail":         "jane@example.com",
  "pickupAddress":         "123 Store Street, Tashkent",
  "pickupLat":             41.2995,
  "pickupLng":             69.2401,
  "dropoffAddress":        "456 Customer Ave, Tashkent",
  "dropoffLat":            41.3111,
  "dropoffLng":            69.2650,
  "packageSize":           "MEDIUM",
  "packageWeight":         2.5,
  "deliveryFee":           "15000.00",
  "priority":              "EXPRESS",
  "status":                "ON_THE_WAY",
  "assignedCourierId":     "a1b2c3d4-...",
  "estimatedDeliveryTime": "2026-04-12T11:00:00Z",
  "actualDeliveryTime":    null,
  "cancelledReason":       null,
  "createdAt":             "2026-04-12T10:00:00Z",
  "updatedAt":             "2026-04-12T10:30:00Z"
}
```

---

## 10. Report Delivery Failure

Can be called from **any in-progress status** (including `COURIER_ASSIGNED`) if the courier cannot complete the job (vehicle breakdown, customer unreachable, etc.).

**POST** `/api/v1/deliveries/{deliveryId}/actions/fail`

```json
{
  "reason":    "Vehicle breakdown — cannot continue",
  "latitude":  41.3050,
  "longitude": 69.2500
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `reason` | string | Yes | Human-readable failure description |
| `latitude` | number | Recommended | Current GPS latitude at time of failure |
| `longitude` | number | Recommended | Current GPS longitude at time of failure |

**Response 200:** `DeliveryOrderResponse` with `status: "FAILED"`.

After success:
- The customer receives a failure email with the reason.
- Operations are alerted via RabbitMQ.

---

## 11. Live Location Reporting

The courier's location must be reported continuously while an active delivery is in progress. The ecommerce backend relays this to the customer for live map tracking.

**POST** `/api/v1/couriers/{courierId}/locations`

```json
{
  "latitude":       41.3050,
  "longitude":      69.2500,
  "heading":        180.0,
  "speed":          25.5,
  "accuracyMeters": 5.0,
  "recordedAt":     "2026-04-12T10:35:00Z"
}
```

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `latitude` | number | Yes | GPS latitude |
| `longitude` | number | Yes | GPS longitude |
| `heading` | number | No | Direction of travel in degrees (0–360) |
| `speed` | number | No | Speed in km/h |
| `accuracyMeters` | number | No | GPS accuracy radius in metres |
| `recordedAt` | ISO-8601 string | Yes | When this reading was taken on the device |

**Response 200:**

```json
{
  "id":             "loc-uuid",
  "courierId":      "a1b2c3d4-...",
  "latitude":       41.3050,
  "longitude":      69.2500,
  "heading":        180.0,
  "speed":          25.5,
  "accuracyMeters": 5.0,
  "recordedAt":     "2026-04-12T10:35:00Z"
}
```

**Recommended reporting frequency:** Every 5 seconds while `ON_THE_WAY`, every 10 seconds otherwise.

**Rate limit:** Max 60 pings per minute per courier. Exceeding this returns `429 Too Many Requests`.

> Location is automatically broadcast to the ecommerce backend (and therefore the customer) only when the delivery is in one of the active statuses: `COURIER_ACCEPTED`, `ARRIVED_AT_PICKUP`, `PICKED_UP`, `ON_THE_WAY`, `ARRIVED_AT_DESTINATION`.

---

## 12. Maps / Navigation Deep-links

Use `pickupLat` / `pickupLng` for the pickup leg and `dropoffLat` / `dropoffLng` for the delivery leg.

### Google Maps

```
google.navigation:q=<lat>,<lng>
```

### Waze

```
waze://?ll=<lat>,<lng>&navigate=yes
```

### Apple Maps (iOS)

```
http://maps.apple.com/?daddr=<lat>,<lng>
```

---

## 13. Courier History

### Active (non-terminal) deliveries

Returns all deliveries currently assigned to the authenticated courier that are not yet complete.

**GET** `/api/v1/deliveries/my-deliveries`

Optional query params: `page` (default 0), `size` (default 20).

**Response:** Paginated list of `DeliveryOrderResponse`.

### Full delivery history

Returns all deliveries (all statuses) for the authenticated courier. Use to show the completed jobs list.

**GET** `/api/v1/deliveries/my-history`

Optional query params:

| Param | Type | Description |
| :--- | :--- | :--- |
| `status` | enum | Filter by a specific status (e.g. `DELIVERED`, `FAILED`) |
| `page` | int | Page number (default 0) |
| `size` | int | Page size (default 20) |

**Response:** Paginated list of `DeliveryOrderResponse`.

### Status history for a single delivery

Returns the full audit trail of every status change for a delivery.

**GET** `/api/v1/deliveries/{deliveryId}/history`

**Response:**

```json
[
  {
    "id":        "hist-uuid-1",
    "status":    "CREATED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "SYSTEM",
    "notes":     null,
    "createdAt": "2026-04-12T10:00:00Z"
  },
  {
    "id":        "hist-uuid-2",
    "status":    "COURIER_ASSIGNED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "SYSTEM",
    "notes":     "Auto-assigned to courier a1b2c3d4 (attempt 1)",
    "createdAt": "2026-04-12T10:00:15Z"
  },
  {
    "id":        "hist-uuid-3",
    "status":    "COURIER_ACCEPTED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "COURIER",
    "notes":     "Courier accepted the assignment",
    "createdAt": "2026-04-12T10:01:30Z"
  }
]
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `status` | enum | The status at this point in time |
| `latitude` / `longitude` | number \| null | Courier location at time of change (if provided) |
| `changedBy` | `COURIER` \| `SYSTEM` \| `OPS` | Who triggered the change |
| `notes` | string \| null | Additional context |
| `createdAt` | ISO-8601 | When this state was entered |

---

## 14. Cancellation — What the App Must Handle

When an order is cancelled by the customer or operations, the courier receives **two signals**:

### FCM Push (data fields)

```json
{
  "notification": {
    "title": "Order cancelled",
    "body":  "The order to 456 Customer Ave has been cancelled."
  },
  "data": {
    "type":             "ORDER_CANCELLED",
    "deliveryId":       "7c9e6679-...",
    "ecommerceOrderId": "ord-uuid",
    "reason":           "Customer requested cancellation"
  }
}
```

### Email

A cancellation email is sent to the courier's registered email address with the drop-off address, reason, and order reference.

### App behaviour on cancellation

1. Listen for `type: "ORDER_CANCELLED"` in FCM data or a WebSocket update where `status === "CANCELLED"`.
2. Dismiss any active navigation / camera screens.
3. Show a banner: *"This order has been cancelled — [reason]"*.
4. If the courier has already picked up the package, instruct them to contact operations immediately.
5. Remove the delivery from the active jobs list.

---

## 15. Post-Delivery Notifications

When the courier successfully completes a delivery (`DELIVERED`):

### Courier — FCM Push

```json
{
  "notification": {
    "title": "Delivery completed!",
    "body":  "Job done — order <ecommerceOrderId> delivered successfully."
  },
  "data": {
    "type":             "DELIVERY_COMPLETED",
    "deliveryId":       "7c9e6679-...",
    "ecommerceOrderId": "ord-uuid"
  }
}
```

### Courier — Email

Sent to the courier's registered email. Contains:
- Pickup address
- Drop-off address
- Delivery fee earned
- Order reference

### Customer — Email

Sent automatically by the courier backend to `customerEmail`. Contains:
- Drop-off address
- Order reference

> The ecommerce backend additionally receives a `delivery.completed` RabbitMQ event and is responsible for sending any in-app push notification to the customer via the customer-facing app.

---

## 16. Courier Profile & Availability

### Get own profile

**GET** `/api/v1/couriers/{courierId}`

**Response:** Full `CourierResponse` including `profileImageUrl`, `drivingLicenceImageUrl`, vehicle type, status, and availability.

### Toggle availability

Set `true` before starting a shift, `false` when going offline. The courier will not receive new assignments when `available: false`.

**PATCH** `/api/v1/couriers/{courierId}/availability`

```json
{
  "available": true
}
```

**Response:** Updated `CourierResponse`.

### Update status

**PATCH** `/api/v1/couriers/{courierId}/status`

```json
{
  "status": "ACTIVE"
}
```

| Value | Meaning |
| :--- | :--- |
| `ACTIVE` | Courier is on shift and can receive orders |
| `OFFLINE` | Courier is not working |
| `SUSPENDED` | Blocked by operations — courier cannot change this themselves |

---

## 17. Error Response Format

All errors follow this structure:

```json
{
  "timestamp": "2026-04-12T10:00:00Z",
  "status":    400,
  "error":     "Bad Request",
  "message":   "Invalid status transition: ON_THE_WAY → COURIER_ACCEPTED"
}
```

| HTTP Status | Meaning |
| :--- | :--- |
| `400 Bad Request` | Invalid input or illegal state transition |
| `401 Unauthorized` | Missing or expired token |
| `403 Forbidden` | Token valid but courier does not own this resource |
| `404 Not Found` | Delivery or assignment does not exist |
| `409 Conflict` | Assignment already responded to |
| `429 Too Many Requests` | Location ping rate limit exceeded (60/min) |

---

## 18. Complete Flow Diagram

```
App Launch
  │
  ├─ POST /api/auth/courier/login
  ├─ POST /api/v1/couriers/push-token  (register FCM token)
  ├─ PATCH /api/v1/couriers/{id}/availability  { available: true }
  └─ Connect WebSocket ws://<host>/ws, subscribe /user/queue/assignments
         │
         │   ◄── WS push or FCM: new assignment offer
         │
  POST /api/v1/assignments/{assignmentId}/respond  { action: "ACCEPT" }
         │
         ├─ PATCH /api/v1/deliveries/{id}/status  { status: "ARRIVED_AT_PICKUP" }
         │
         ├─ POST /api/v1/deliveries/{id}/actions/pickup-proof  (photo)
         │        └─ status becomes PICKED_UP
         │
         ├─ PATCH /api/v1/deliveries/{id}/status  { status: "ON_THE_WAY" }
         │        └─ Start reporting location every 5s
         │        └─ POST /api/v1/couriers/{id}/locations  (loop)
         │
         ├─ PATCH /api/v1/deliveries/{id}/status  { status: "ARRIVED_AT_DESTINATION" }
         │
         ├─ POST /api/v1/deliveries/{id}/actions/deliver-proof  (photo)
         │        └─ status becomes DELIVERED
         │        └─ FCM push + email sent to courier
         │        └─ Email sent to customer
         │        └─ ecommerce backend notified
         │
         └─ Show success screen, add to /my-history
```

**If anything goes wrong (at any in-progress step):**

```
POST /api/v1/deliveries/{id}/actions/fail  { reason, latitude, longitude }
  └─ status becomes FAILED
  └─ Customer email sent with reason
  └─ ecommerce backend notified
```

**If the order is cancelled externally:**

```
FCM push received:  data.type == "ORDER_CANCELLED"
  └─ Dismiss active screens
  └─ Show cancellation banner
  └─ Remove from active jobs
```
