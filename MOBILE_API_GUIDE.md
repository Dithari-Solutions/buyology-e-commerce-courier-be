# Buyology Courier — Mobile API Integration Guide

This guide describes the end-to-end flow for courier assignments, status transitions, and photographic proof requirements.

## 1. The Delivery Lifecycle

A delivery follows a strict status sequence. Couriers move the delivery forward by responding to assignments and updating statuses.

| Status | Description | Action Required |
| :--- | :--- | :--- |
| `CREATED` | Order ingested from ecommerce. | System is searching for courier. |
| `COURIER_ASSIGNED` | **(Offer State)** A courier has been selected. | Courier must **ACCEPT** or **REJECT**. |
| `COURIER_ACCEPTED` | Courier has accepted the job. | Courier should head to pickup location. |
| `ARRIVED_AT_PICKUP` | Courier is at the store. | Courier must **SUBMIT PICKUP PROOF** (Photo). |
| `PICKED_UP` | Package is in courier's possession. | Courier should head to destination. |
| `ON_THE_WAY` | Courier is traveling to customer. | Broadcast location to ecommerce. |
| `ARRIVED_AT_DESTINATION` | Courier is at customer's address. | Courier must **SUBMIT DELIVERY PROOF** (Photo). |
| `DELIVERED` | **(Terminal)** Success. | Job complete. |
| `FAILED` | **(Terminal)** Courier reported failure. | Order is dead; customer is notified. |
| `CANCELLED` | **(Terminal)** Admin/Ecommerce cancelled. | Job aborted. |

---

## 2. Key Endpoints

### A. Accept / Reject an Assignment
When a courier is notified (FCM/WebSocket) of a new assignment, they receive an `assignmentId`.
They MUST respond before they can proceed.

**POST** `/api/v1/assignments/{assignmentId}/respond`
```json
{
  "action": "ACCEPT", // or "REJECT"
  "rejectionReason": "Too far away" // Optional for REJECT
}
```

### B. Update Status (Navigation)
Used for transitions that don't require photos (e.g. Arrived, On the Way).

**POST** `/api/v1/deliveries/{deliveryId}/status`
```json
{
  "status": "ARRIVED_AT_PICKUP",
  "latitude": 40.7128,
  "longitude": -74.0060,
  "notes": "Arrived at store"
}
```

### C. Submit Pickup Proof
Transition: `ARRIVED_AT_PICKUP` → `PICKED_UP`. Requires a photo of the package at the store.

**POST** `/api/v1/deliveries/{deliveryId}/actions/pickup-proof`
- `Content-Type: multipart/form-data`
- `photo`: File (JPEG/PNG)
- `photoTakenAt`: ISO-8601 timestamp (Optional)

### D. Submit Delivery Proof
Transition: `ARRIVED_AT_DESTINATION` → `DELIVERED`. Requires a photo of the package at the customer's door.

**POST** `/api/v1/deliveries/{deliveryId}/actions/deliver-proof`
- `Content-Type: multipart/form-data`
- `photo`: File (JPEG/PNG)
- `deliveredTo`: Name of recipient (Optional)

### E. Report Failure
Can be called from any in-progress status (including `COURIER_ASSIGNED`) if the courier cannot complete the job.

**POST** `/api/v1/deliveries/{deliveryId}/actions/fail`
```json
{
  "reason": "Vehicle breakdown",
  "latitude": 40.7128,
  "longitude": -74.0060
}
```

---

## 3. Maps Integration (Waze / Google / Apple)

The `DeliveryOrderResponse` contains `pickupLat`, `pickupLng`, `dropoffLat`, and `dropoffLng`. Use these to open navigation apps:

### Google Maps
`google.navigation:q=latitude,longitude`

### Waze
`waze://?ll=latitude,longitude&navigate=yes`

### Apple Maps (iOS)
`http://maps.apple.com/?daddr=latitude,longitude`

---

## 4. Real-time Notifications (WebSocket)

The app should connect to the STOMP endpoint to receive instant assignment offers without polling.

- **URL:** `ws://<host>/ws`
- **Topic:** `/user/queue/assignments`
- **Auth:** Pass `Authorization: Bearer <JWT>` in the STOMP CONNECT frame.

### Payload Example:
```json
{
  "assignmentId": "...",
  "deliveryId": "...",
  "pickupAddress": "123 Store St",
  "dropoffAddress": "456 Customer Ave",
  "deliveryFee": "5.00"
}
```

---

## 5. Push Notifications (FCM)

Ensure the courier's FCM token is registered via the Auth API.
The FCM payload contains the same data fields as the WebSocket notification, allowing the app to route the courier directly to the "New Offer" screen even if the app is in the background.
