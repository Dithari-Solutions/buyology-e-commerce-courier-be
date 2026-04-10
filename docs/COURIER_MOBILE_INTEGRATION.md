# Buyology Courier — Mobile App Integration Guide

Complete backend reference for the courier mobile app. Covers every API call,
the WebSocket/STOMP connection, photo uploads, all status transitions, history,
and error handling in delivery order.

**Stack used in examples:** Expo (React Native) · TypeScript · Axios · TanStack Query  
**Backend base URL (dev):** `http://localhost:8081`  
**Backend base URL (prod):** replace with your deployed host

---

## Table of Contents

1. [Base URL & Required Headers](#1-base-url--required-headers)
2. [Authentication](#2-authentication)
3. [Token Management — Auto-refresh](#3-token-management--auto-refresh)
4. [WebSocket — Real-time Assignment Notifications](#4-websocket--real-time-assignment-notifications)
5. [Complete Delivery Flow (Step by Step)](#5-complete-delivery-flow-step-by-step)
6. [API Reference](#6-api-reference)
   - [Auth Endpoints](#auth-endpoints)
   - [Assignments — Accept / Reject](#assignments--accept--reject)
   - [Deliveries — Status Transitions (no photo)](#deliveries--status-transitions-no-photo)
   - [Deliveries — Pickup Proof Photo](#deliveries--pickup-proof-photo)
   - [Deliveries — Delivery Proof Photo](#deliveries--delivery-proof-photo)
   - [Deliveries — Failed Delivery](#deliveries--failed-delivery)
   - [Deliveries — History & Detail](#deliveries--history--detail)
   - [Location Tracking](#location-tracking)
   - [Courier Profile](#courier-profile)
7. [Status Transition Map](#7-status-transition-map)
8. [TypeScript Types](#8-typescript-types)
9. [Error Handling](#9-error-handling)
10. [Environment Config Checklist](#10-environment-config-checklist)

---

## 1. Base URL & Required Headers

```
Base URL (dev):  http://localhost:8081/api
Base URL (prod): https://<your-domain>/api
```

Every **authenticated** request must include:

```
Authorization: Bearer <accessToken>
Content-Type:  application/json        ← for JSON bodies
Content-Type:  multipart/form-data     ← for photo uploads (set automatically by the HTTP client)
```

---

## 2. Authentication

Couriers cannot self-register. An admin creates the account via the admin panel.
The courier logs in with the phone number and password issued to them.

### 2.1 Login

```
POST /auth/courier/login
```

**Request**
```json
{
  "phone":    "+998901234567",
  "password": "your-password"
}
```

**Response 200**
```json
{
  "accessToken":  "eyJhbGci...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "expiresIn":    900,
  "tokenType":    "Bearer",
  "courierId":    "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

Store **both** tokens securely — Keychain on iOS, EncryptedSharedPreferences on Android.  
`courierId` is used as a URL segment in the location and profile endpoints.

### 2.2 Logout

```
POST /auth/courier/logout
```

```json
{ "refreshToken": "550e8400-e29b-41d4-a716-446655440000" }
```

**Response 204** — Token revoked server-side. Clear both tokens locally and navigate
to the login screen.

---

## 3. Token Management — Auto-refresh

Access tokens expire in **15 minutes**. Refresh proactively — do not wait for a 401.

```
POST /auth/courier/refresh
```

```json
{ "refreshToken": "550e8400-e29b-41d4-a716-446655440000" }
```

Returns a new `AuthResponse` (same shape as login) with new tokens.

### Axios interceptor (copy-paste ready)

```ts
// src/lib/api/client.ts
import axios from 'axios';
import { tokenStore } from '@/stores/token-store';   // your Zustand / MMKV store

export const api = axios.create({
  baseURL: process.env.EXPO_PUBLIC_API_URL,
});

// Attach token to every request
api.interceptors.request.use((config) => {
  const token = tokenStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// Silent refresh on 401
api.interceptors.response.use(
  (res) => res,
  async (err) => {
    const original = err.config;
    if (err.response?.status === 401 && !original._retry) {
      original._retry = true;
      try {
        const refreshToken = tokenStore.getState().refreshToken;
        const { data } = await axios.post(
          `${process.env.EXPO_PUBLIC_API_URL}/auth/courier/refresh`,
          { refreshToken }
        );
        tokenStore.getState().setTokens(data);
        original.headers.Authorization = `Bearer ${data.accessToken}`;
        return api(original);
      } catch {
        tokenStore.getState().clear();
        // Navigate to login screen
      }
    }
    return Promise.reject(err);
  }
);
```

---

## 4. WebSocket — Real-time Assignment Notifications

When a new order is assigned, the server pushes a notification instantly over
WebSocket/STOMP. The app **must** maintain a live connection while the courier is
online — this is how new orders arrive without polling.

### 4.1 Connection details

| Property | Value |
|---|---|
| Endpoint (dev) | `ws://localhost:8081/ws` |
| Endpoint (prod) | `wss://<your-domain>/ws` |
| Protocol | STOMP over WebSocket |
| Auth header on CONNECT frame | `Authorization: Bearer <accessToken>` |
| Subscribe destination | `/user/queue/assignments` |

The server maps `/user/queue/assignments` to `/user/{courierId}/queue/assignments`
using the JWT subject. Each courier only receives their own messages.

If the token is missing or expired on CONNECT, the server closes the connection
immediately. Always refresh the token before reconnecting.

### 4.2 Assignment notification payload

Received as a JSON string on the subscription:

```json
{
  "assignmentId":   "a1b2c3d4-0000-0000-0000-000000000001",
  "deliveryId":     "d5e6f7a8-0000-0000-0000-000000000002",
  "attemptNumber":  1,
  "pickupAddress":  "1 Amir Temur Ave, Tashkent",
  "pickupLat":      41.2995,
  "pickupLng":      69.2401,
  "dropoffAddress": "45 Navoi St, Tashkent",
  "dropoffLat":     41.3111,
  "dropoffLng":     69.2550,
  "packageSize":    "SMALL",
  "packageWeight":  1.5,
  "deliveryFee":    15000.00,
  "priority":       "STANDARD",
  "assignedAt":     "2026-04-10T08:32:00Z"
}
```

On receipt, immediately show the "New order" screen with a countdown timer.

### 4.3 STOMP client setup (React Native / Expo)

```bash
npm install @stomp/stompjs
```

```ts
// src/lib/ws/assignment-socket.ts
import { Client, type IMessage } from '@stomp/stompjs';
import { tokenStore } from '@/stores/token-store';

export type AssignmentPayload = {
  assignmentId:   string;
  deliveryId:     string;
  attemptNumber:  number;
  pickupAddress:  string;
  pickupLat:      number;
  pickupLng:      number;
  dropoffAddress: string;
  dropoffLat:     number;
  dropoffLng:     number;
  packageSize:    string | null;
  packageWeight:  number | null;
  deliveryFee:    number | null;
  priority:       'STANDARD' | 'EXPRESS';
  assignedAt:     string;
};

let client: Client | null = null;

export function connectAssignmentSocket(
  onAssignment: (payload: AssignmentPayload) => void
) {
  const token = tokenStore.getState().accessToken;

  client = new Client({
    brokerURL: process.env.EXPO_PUBLIC_WS_URL + '/ws',
    connectHeaders: {
      Authorization: `Bearer ${token}`,   // IMPORTANT: JWT goes here, not in the URL
    },
    reconnectDelay: 5000,                 // auto-reconnect with 5 s back-off
    onConnect: () => {
      client!.subscribe('/user/queue/assignments', (msg: IMessage) => {
        const payload = JSON.parse(msg.body) as AssignmentPayload;
        onAssignment(payload);
      });
    },
    onStompError: (frame) => {
      console.error('[WS] STOMP error', frame.headers['message']);
    },
  });

  client.activate();
}

export function disconnectAssignmentSocket() {
  client?.deactivate();
  client = null;
}
```

### 4.4 React hook

```ts
// src/hooks/use-assignment-socket.ts
import { useEffect } from 'react';
import {
  connectAssignmentSocket,
  disconnectAssignmentSocket,
  type AssignmentPayload,
} from '@/lib/ws/assignment-socket';

export function useAssignmentSocket(
  isOnline: boolean,
  onNewAssignment: (p: AssignmentPayload) => void
) {
  useEffect(() => {
    if (!isOnline) return;
    connectAssignmentSocket(onNewAssignment);
    return () => disconnectAssignmentSocket();
  }, [isOnline]);
}
```

---

## 5. Complete Delivery Flow (Step by Step)

```
 SYSTEM / ECOMMERCE                COURIER APP                    SERVER
        │                               │                             │
        │  publishes order via RabbitMQ │                             │
        │──────────────────────────────►│ ◄── WS push (assignment)───│
        │                               │                             │
        │     ┌─── "New order" screen ──┘                            │
        │     │    (show countdown timer)                             │
        │     │                                                        │
        │     ├── [ACCEPT] ──────────────────────────────────────────►│
        │     │   POST /api/v1/assignments/{assignmentId}/respond     │
        │     │   {"action":"ACCEPT"}                                 │
        │     │                                           ┌───────────┘
        │     │                       status → COURIER_ACCEPTED       │
        │     │                       ecommerce notified via RabbitMQ │
        │     │                                                        │
        │     │── [GPS pings every 5s] ─────────────────────────────►│
        │     │   POST /api/v1/couriers/{courierId}/locations         │
        │     │                          ↳ stored for assignment geo   │
        │     │                                                        │
        │     │── [Arrived at pickup] ──────────────────────────────►│
        │     │   PATCH /api/v1/deliveries/{deliveryId}/status        │
        │     │   {"status":"ARRIVED_AT_PICKUP"}                      │
        │     │                                                        │
        │     │── [Take photo of package]                             │
        │     │   POST /api/v1/deliveries/{deliveryId}/actions/pickup-proof
        │     │   multipart: photo file                               │
        │     │                           status → PICKED_UP          │
        │     │                                                        │
        │     │── [Start delivery] ────────────────────────────────► │
        │     │   PATCH /api/v1/deliveries/{deliveryId}/status        │
        │     │   {"status":"ON_THE_WAY"}                             │
        │     │                                                        │
        │     │── [GPS pings every 5s] ─────────────────────────────►│
        │     │                    ↳ server broadcasts to ecommerce ──►│ customer sees courier on map
        │     │                                                        │
        │     │── [Arrived at destination] ────────────────────────► │
        │     │   PATCH /api/v1/deliveries/{deliveryId}/status        │
        │     │   {"status":"ARRIVED_AT_DESTINATION"}                 │
        │     │                                                        │
        │     │── [Take photo + confirm delivery] ─────────────────► │
        │     │   POST /api/v1/deliveries/{deliveryId}/actions/deliver-proof
        │     │   multipart: photo + optional deliveredTo             │
        │     │                           status → DELIVERED ✓        │
        │     │                           customer confirmation email sent automatically
        │     │                                                        │
        │     └── Show success screen                                  │
        │                                                              │
        │  ── OR if delivery cannot be completed ─────────────────────│
        │     [Can't deliver] ──────────────────────────────────────► │
        │     POST /api/v1/deliveries/{deliveryId}/actions/fail       │
        │     {"reason":"Customer not home","latitude":...}           │
        │                              status → FAILED                │
        │                              customer failure email with reason sent automatically
```

---

## 6. API Reference

All endpoints require `Authorization: Bearer <accessToken>` unless marked **Public**.

---

### Auth Endpoints

#### Login **[Public]**
```
POST /auth/courier/login
Body: { "phone": string, "password": string }
```

#### Refresh **[Public]**
```
POST /auth/courier/refresh
Body: { "refreshToken": string }
```

#### Logout
```
POST /auth/courier/logout
Body: { "refreshToken": string }
Response: 204 No Content
```

---

### Assignments — Accept / Reject

#### Get assignment details
```
GET /api/v1/assignments/{assignmentId}
```

Use this to fetch full details if the app was backgrounded when the WebSocket
push arrived and the payload was lost.

**Response 200**
```json
{
  "id":             "a1b2c3d4-...",
  "deliveryId":     "d5e6f7a8-...",
  "courierId":      "f47ac10b-...",
  "status":         "PENDING",
  "attemptNumber":  1,
  "assignedAt":     "2026-04-10T08:32:00Z",
  "acceptedAt":     null,
  "rejectedAt":     null,
  "rejectionReason": null,
  "createdAt":      "2026-04-10T08:32:00Z",
  "pickupAddress":  "1 Amir Temur Ave, Tashkent",
  "pickupLat":      41.2995,
  "pickupLng":      69.2401,
  "dropoffAddress": "45 Navoi St, Tashkent",
  "dropoffLat":     41.3111,
  "dropoffLng":     69.2550
}
```

#### Accept assignment
```
POST /api/v1/assignments/{assignmentId}/respond
```
```json
{ "action": "ACCEPT" }
```

#### Reject assignment
```
POST /api/v1/assignments/{assignmentId}/respond
```
```json
{
  "action": "REJECT",
  "rejectionReason": "Too far from my current location"
}
```

`rejectionReason` is **required** when action is `REJECT` — validation returns 422
without it. On reject, the server reassigns to the next nearest courier
(up to 3 total attempts before escalating to an admin).

**TanStack Query mutation**

```ts
import { useMutation } from '@tanstack/react-query';
import { api } from '@/lib/api/client';

type RespondBody =
  | { action: 'ACCEPT' }
  | { action: 'REJECT'; rejectionReason: string };

export function useRespondToAssignment(assignmentId: string) {
  return useMutation({
    mutationFn: (body: RespondBody) =>
      api.post(`/api/v1/assignments/${assignmentId}/respond`, body)
        .then(r => r.data),
  });
}
```

---

### Deliveries — Status Transitions (no photo)

Use for transitions that do **not** require a photo.

```
PATCH /api/v1/deliveries/{deliveryId}/status
```

**Body**
```json
{
  "status":    "ARRIVED_AT_PICKUP",
  "latitude":  41.2995,
  "longitude": 69.2401,
  "notes":     "Optional note"
}
```

`latitude`, `longitude`, and `notes` are optional but recommended — they are
recorded in the status history for the audit trail.

**Allowed transitions via this endpoint**

| Current status | Target status | When to call |
|---|---|---|
| `COURIER_ACCEPTED` | `ARRIVED_AT_PICKUP` | Courier arrives at the merchant/store |
| `PICKED_UP` | `ON_THE_WAY` | Courier starts driving to the customer |
| `ON_THE_WAY` | `ARRIVED_AT_DESTINATION` | Courier arrives at customer's address |

> Do **not** use this endpoint for `PICKED_UP` or `DELIVERED` — those require
> a photo upload. `FAILED` has its own dedicated endpoint.

**Response 200** — Full `DeliveryOrderResponse` (see Types section).

---

### Deliveries — Pickup Proof Photo

Triggered when the courier taps **"I have the package"** in the app.  
Transitions: `ARRIVED_AT_PICKUP` → `PICKED_UP`.

```
POST /api/v1/deliveries/{deliveryId}/actions/pickup-proof
Content-Type: multipart/form-data
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `photo` | file (form part) | **Yes** | JPEG or PNG, max 10 MB |
| `photoTakenAt` | query param, ISO-8601 | No | Defaults to server time |

**Expo / React Native example**

```ts
import * as ImagePicker from 'expo-image-picker';
import { api } from '@/lib/api/client';

async function submitPickupProof(deliveryId: string) {
  const result = await ImagePicker.launchCameraAsync({ quality: 0.8 });
  if (result.canceled) return;

  const asset = result.assets[0];
  const form = new FormData();
  form.append('photo', {
    uri:  asset.uri,
    name: 'pickup.jpg',
    type: 'image/jpeg',
  } as any);

  const { data } = await api.post(
    `/api/v1/deliveries/${deliveryId}/actions/pickup-proof`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return data; // DeliveryProofResponse
}
```

**Response 200 — DeliveryProofResponse**
```json
{
  "id":                 "proof-uuid",
  "deliveryId":         "d5e6f7a8-...",
  "pickupImageUrl":     "https://<host>/uploads/pickup-proof/abc123.jpg",
  "pickupPhotoTakenAt": "2026-04-10T09:15:30Z",
  "imageUrl":           null,
  "signatureUrl":       null,
  "deliveredTo":        null,
  "photoTakenAt":       null,
  "createdAt":          "2026-04-10T09:15:31Z"
}
```

After a successful response, delivery status is `PICKED_UP`.
Show the navigation screen to the dropoff address.

---

### Deliveries — Delivery Proof Photo

Triggered when the courier taps **"Package delivered"** in the app.  
Transitions: `ARRIVED_AT_DESTINATION` → `DELIVERED`.  
A confirmation email is sent to the customer automatically.

```
POST /api/v1/deliveries/{deliveryId}/actions/deliver-proof
Content-Type: multipart/form-data
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `photo` | file (form part) | **Yes** | JPEG or PNG, max 10 MB |
| `deliveredTo` | query param | No | Name of the person who received the package |
| `photoTakenAt` | query param, ISO-8601 | No | Defaults to server time |

**Expo / React Native example**

```ts
async function submitDeliveryProof(
  deliveryId: string,
  deliveredTo: string | null
) {
  const result = await ImagePicker.launchCameraAsync({ quality: 0.8 });
  if (result.canceled) return;

  const asset = result.assets[0];
  const form = new FormData();
  form.append('photo', {
    uri:  asset.uri,
    name: 'delivery.jpg',
    type: 'image/jpeg',
  } as any);

  let url = `/api/v1/deliveries/${deliveryId}/actions/deliver-proof`;
  if (deliveredTo) url += `?deliveredTo=${encodeURIComponent(deliveredTo)}`;

  const { data } = await api.post(url, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data; // DeliveryProofResponse
}
```

**Response 200 — DeliveryProofResponse**
```json
{
  "id":                 "proof-uuid",
  "deliveryId":         "d5e6f7a8-...",
  "pickupImageUrl":     "https://<host>/uploads/pickup-proof/abc123.jpg",
  "pickupPhotoTakenAt": "2026-04-10T09:15:30Z",
  "imageUrl":           "https://<host>/uploads/delivery-proof/xyz789.jpg",
  "signatureUrl":       null,
  "deliveredTo":        "Alisher Umarov",
  "photoTakenAt":       "2026-04-10T09:45:00Z",
  "createdAt":          "2026-04-10T09:15:31Z"
}
```

After a successful response, delivery status is `DELIVERED`. Show the
"Delivery complete" success screen. The customer receives an email confirmation.

---

### Deliveries — Failed Delivery

Triggered when the courier cannot complete the delivery.  
The courier **must** provide a reason. The reason is included in a failure
notification email sent to the customer automatically.

Valid from any in-progress status: `COURIER_ACCEPTED`, `ARRIVED_AT_PICKUP`,
`PICKED_UP`, `ON_THE_WAY`, or `ARRIVED_AT_DESTINATION`.

```
POST /api/v1/deliveries/{deliveryId}/actions/fail
```

**Body**
```json
{
  "reason":    "Customer not home after multiple attempts",
  "latitude":  41.3111,
  "longitude": 69.2550
}
```

| Field | Required | Constraint |
|---|---|---|
| `reason` | **Yes** | 1–500 characters. Displayed to the customer in the email. |
| `latitude` | No | Courier's GPS position at the time of failure |
| `longitude` | No | Courier's GPS position at the time of failure |

**Response 200** — `DeliveryOrderResponse` with `"status": "FAILED"`.

```ts
// TanStack Query mutation
export function useFailDelivery(deliveryId: string) {
  return useMutation({
    mutationFn: (body: { reason: string; latitude?: number; longitude?: number }) =>
      api.post(`/api/v1/deliveries/${deliveryId}/actions/fail`, body)
        .then(r => r.data),
  });
}
```

---

### Deliveries — History & Detail

#### Active deliveries (non-terminal statuses)
```
GET /api/v1/deliveries/my-deliveries?page=0&size=20&sort=createdAt,desc
```

Returns all deliveries currently in progress: `COURIER_ASSIGNED`,
`COURIER_ACCEPTED`, `ARRIVED_AT_PICKUP`, `PICKED_UP`, `ON_THE_WAY`,
`ARRIVED_AT_DESTINATION`. Use on the main "active orders" tab.

#### Full delivery history (all statuses)
```
GET /api/v1/deliveries/my-history?page=0&size=20&sort=createdAt,desc
```

Returns every delivery ever assigned to this courier — including `DELIVERED`,
`FAILED`, and `CANCELLED`. Use on the history / earnings screen.

#### Filter history by status
```
GET /api/v1/deliveries/my-history?status=DELIVERED&page=0&size=20
```

Valid `status` values: `CREATED`, `COURIER_ASSIGNED`, `COURIER_ACCEPTED`,
`ARRIVED_AT_PICKUP`, `PICKED_UP`, `ON_THE_WAY`, `ARRIVED_AT_DESTINATION`,
`DELIVERED`, `FAILED`, `CANCELLED`.

**Paginated response shape**
```json
{
  "content": [ /* array of DeliveryOrderResponse */ ],
  "totalElements": 47,
  "totalPages":    3,
  "size":          20,
  "number":        0
}
```

#### Get delivery by ID
```
GET /api/v1/deliveries/{deliveryId}
```

Use to refresh the order state after returning from the background.

#### Status history timeline for a delivery
```
GET /api/v1/deliveries/{deliveryId}/history
```

Returns a chronological list of every status change — great for a detail screen
timeline. Includes who changed the status (`COURIER` / `SYSTEM`), optional notes,
and the lat/lng at time of change.

```json
[
  {
    "id":        "hist-1",
    "status":    "CREATED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "SYSTEM",
    "notes":     null,
    "createdAt": "2026-04-10T08:30:00Z"
  },
  {
    "id":        "hist-2",
    "status":    "COURIER_ASSIGNED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "SYSTEM",
    "notes":     "Auto-assigned to courier f47ac10b (attempt 1)",
    "createdAt": "2026-04-10T08:32:00Z"
  },
  {
    "id":        "hist-3",
    "status":    "COURIER_ACCEPTED",
    "latitude":  null,
    "longitude": null,
    "changedBy": "COURIER",
    "notes":     "Courier accepted the assignment",
    "createdAt": "2026-04-10T08:32:45Z"
  }
]
```

#### Get proof photos for a delivery
```
GET /api/v1/deliveries/{deliveryId}/proof
```

Returns `DeliveryProofResponse` with both pickup and delivery photo URLs.
Use on the delivery detail screen for the evidence chain.

---

### Location Tracking

Send GPS pings **every 5 seconds** while the courier has an active delivery.
The server automatically broadcasts the position to the ecommerce backend
so the customer can see the courier on a map in real time.

While idle (no active delivery), ping every 15–30 seconds so the assignment
engine can find the courier for nearby order matching.

```
POST /api/v1/couriers/{courierId}/locations
```

`{courierId}` is the UUID returned at login.

**Body**
```json
{
  "latitude":       41.3001,
  "longitude":      69.2450,
  "heading":        275.5,
  "speed":          14.2,
  "accuracyMeters": 8.0,
  "recordedAt":     "2026-04-10T09:20:05Z"
}
```

| Field | Required | Description |
|---|---|---|
| `latitude` | **Yes** | WGS-84 decimal degrees |
| `longitude` | **Yes** | WGS-84 decimal degrees |
| `heading` | No | Degrees from north, 0–360 |
| `speed` | No | Metres per second |
| `accuracyMeters` | No | GPS accuracy radius |
| `recordedAt` | No | Device timestamp; defaults to server time |

**Response 201**
```json
{
  "id":             "loc-uuid",
  "courierId":      "f47ac10b-...",
  "latitude":       41.3001,
  "longitude":      69.2450,
  "heading":        275.5,
  "speed":          14.2,
  "accuracyMeters": 8.0,
  "recordedAt":     "2026-04-10T09:20:05Z"
}
```

**Rate limit:** 60 pings per courier per minute. Above the limit returns **HTTP 429**.
Handle gracefully — drop the next ping and resume on the following interval.

**Background tracking (React Native)**

Use `expo-location` with background task registration so pings continue when
the app is minimised:

```ts
import * as Location from 'expo-location';
import * as TaskManager from 'expo-task-manager';
import { api } from '@/lib/api/client';
import { tokenStore } from '@/stores/token-store';

const LOCATION_TASK = 'BACKGROUND_LOCATION';

TaskManager.defineTask(LOCATION_TASK, async ({ data, error }) => {
  if (error || !data) return;
  const { locations } = data as any;
  const loc = locations[0];
  const courierId = tokenStore.getState().courierId;
  if (!courierId) return;

  await api.post(`/api/v1/couriers/${courierId}/locations`, {
    latitude:  loc.coords.latitude,
    longitude: loc.coords.longitude,
    heading:   loc.coords.heading,
    speed:     loc.coords.speed,
    accuracyMeters: loc.coords.accuracy,
    recordedAt: new Date(loc.timestamp).toISOString(),
  }).catch(() => { /* swallow — next ping will retry */ });
});

export async function startLocationTracking() {
  await Location.startLocationUpdatesAsync(LOCATION_TASK, {
    accuracy:         Location.Accuracy.High,
    timeInterval:     5000,   // 5 s
    distanceInterval: 10,     // or on 10 m movement
    showsBackgroundLocationIndicator: true,
    foregroundService: {
      notificationTitle:   'Buyology Courier',
      notificationBody:    'Sharing your location for active delivery',
    },
  });
}

export async function stopLocationTracking() {
  await Location.stopLocationUpdatesAsync(LOCATION_TASK);
}
```

---

### Courier Profile

#### Get own profile
```
GET /api/v1/couriers/{courierId}
```

#### Toggle availability (go online / offline)
```
PATCH /api/v1/couriers/{courierId}/availability
Body: { "available": true }
```

Setting `available: false` removes the courier from the geo-index immediately.
No new assignments will arrive until set back to `true`. Call this when the courier
explicitly taps "Go offline" in the app settings.

---

## 7. Status Transition Map

```
            ┌─────────────────────────────────────────────────────────┐
            │                     Status values                       │
            └─────────────────────────────────────────────────────────┘

                ┌─────────┐
                │ CREATED │  (server: on ingest from ecommerce)
                └────┬────┘
                     │ server auto-assigns nearest courier
                     ▼
          ┌──────────────────┐
          │ COURIER_ASSIGNED │  (server: assignment created, WS push sent)
          └────────┬─────────┘
                   │ POST /assignments/{id}/respond  {"action":"ACCEPT"}
                   ▼
          ┌──────────────────┐
          │ COURIER_ACCEPTED │  ← GPS pings start (assignment matching only)
          └────────┬─────────┘
                   │ PATCH /deliveries/{id}/status  {"status":"ARRIVED_AT_PICKUP"}
                   ▼
         ┌────────────────────┐
         │ ARRIVED_AT_PICKUP  │  ← courier at merchant
         └──────────┬─────────┘
                    │ POST /deliveries/{id}/actions/pickup-proof  (photo required)
                    ▼
           ┌────────────────┐
           │   PICKED_UP    │  ← photo saved, package confirmed
           └───────┬────────┘
                   │ PATCH /deliveries/{id}/status  {"status":"ON_THE_WAY"}
                   ▼
           ┌────────────────┐
           │   ON_THE_WAY   │  ← GPS pings broadcast to customer in real-time
           └───────┬────────┘
                   │ PATCH /deliveries/{id}/status  {"status":"ARRIVED_AT_DESTINATION"}
                   ▼
    ┌──────────────────────────────┐
    │  ARRIVED_AT_DESTINATION      │  ← courier at customer's door
    └──────────────┬───────────────┘
                   │ POST /deliveries/{id}/actions/deliver-proof  (photo required)
                   ▼
            ┌───────────┐
            │ DELIVERED │  ← customer confirmation email sent  ✓
            └───────────┘


  From any in-progress status (COURIER_ACCEPTED through ARRIVED_AT_DESTINATION):

  POST /deliveries/{id}/actions/fail   {"reason":"..."}
  ──────────────────────────────────────────────────────►  FAILED  ← customer failure email with reason  ✗
```

---

## 8. TypeScript Types

```ts
// ── Enums ────────────────────────────────────────────────────────────────────

type DeliveryStatus =
  | 'CREATED'
  | 'COURIER_ASSIGNED'
  | 'COURIER_ACCEPTED'
  | 'ARRIVED_AT_PICKUP'
  | 'PICKED_UP'
  | 'ON_THE_WAY'
  | 'ARRIVED_AT_DESTINATION'
  | 'DELIVERED'
  | 'FAILED'
  | 'CANCELLED';

type DeliveryPriority  = 'STANDARD' | 'EXPRESS';
type PackageSize       = 'SMALL' | 'MEDIUM' | 'LARGE' | 'EXTRA_LARGE';
type AssignmentStatus  = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';

// ── Auth ─────────────────────────────────────────────────────────────────────

type AuthResponse = {
  accessToken:  string;
  refreshToken: string;
  expiresIn:    number;   // seconds
  tokenType:    'Bearer';
  courierId:    string;
};

// ── Assignment ────────────────────────────────────────────────────────────────

type AssignmentPayload = {        // received over WebSocket
  assignmentId:   string;
  deliveryId:     string;
  attemptNumber:  number;
  pickupAddress:  string;
  pickupLat:      number;
  pickupLng:      number;
  dropoffAddress: string;
  dropoffLat:     number;
  dropoffLng:     number;
  packageSize:    PackageSize | null;
  packageWeight:  number | null;
  deliveryFee:    number | null;
  priority:       DeliveryPriority;
  assignedAt:     string;         // ISO-8601
};

type AssignmentResponse = {       // returned by GET/POST assignment endpoints
  id:             string;
  deliveryId:     string;
  courierId:      string;
  status:         AssignmentStatus;
  attemptNumber:  number;
  assignedAt:     string;
  acceptedAt:     string | null;
  rejectedAt:     string | null;
  rejectionReason: string | null;
  createdAt:      string;
  pickupAddress:  string;
  pickupLat:      number;
  pickupLng:      number;
  dropoffAddress: string;
  dropoffLat:     number;
  dropoffLng:     number;
};

// ── Delivery ──────────────────────────────────────────────────────────────────

type DeliveryOrderResponse = {
  id:                    string;
  ecommerceOrderId:      string;
  ecommerceStoreId:      string;
  customerName:          string;
  customerPhone:         string;
  customerEmail:         string | null;
  pickupAddress:         string;
  pickupLat:             number;
  pickupLng:             number;
  dropoffAddress:        string;
  dropoffLat:            number;
  dropoffLng:            number;
  packageSize:           PackageSize | null;
  packageWeight:         number | null;
  deliveryFee:           number | null;
  priority:              DeliveryPriority;
  status:                DeliveryStatus;
  assignedCourierId:     string | null;
  estimatedDeliveryTime: string | null;
  actualDeliveryTime:    string | null;
  cancelledReason:       string | null;   // also populated on FAILED
  createdAt:             string;
  updatedAt:             string;
};

type DeliveryProofResponse = {
  id:                 string;
  deliveryId:         string;
  pickupImageUrl:     string | null;   // set after pickup-proof upload
  pickupPhotoTakenAt: string | null;
  imageUrl:           string | null;   // set after deliver-proof upload
  signatureUrl:       string | null;
  deliveredTo:        string | null;
  photoTakenAt:       string | null;
  createdAt:          string;
};

type DeliveryStatusHistoryItem = {
  id:        string;
  status:    DeliveryStatus;
  latitude:  number | null;
  longitude: number | null;
  changedBy: 'COURIER' | 'SYSTEM' | 'OPS';
  notes:     string | null;
  createdAt: string;
};

// ── Pagination ────────────────────────────────────────────────────────────────

type Page<T> = {
  content:       T[];
  totalElements: number;
  totalPages:    number;
  size:          number;
  number:        number;    // zero-based page index
};

// ── Location ──────────────────────────────────────────────────────────────────

type RecordLocationRequest = {
  latitude:       number;
  longitude:      number;
  heading?:       number;
  speed?:         number;
  accuracyMeters?: number;
  recordedAt?:    string;   // ISO-8601; omit to use server time
};
```

---

## 9. Error Handling

All error responses follow this shape:

```json
{
  "status":  400,
  "error":   "Bad Request",
  "message": "Pickup proof can only be submitted when status is ARRIVED_AT_PICKUP. Current: ON_THE_WAY",
  "path":    "/api/v1/deliveries/d5e6f7a8-.../actions/pickup-proof"
}
```

| HTTP Status | Meaning | Common cause in the courier app |
|---|---|---|
| 400 | Bad Request | Wrong status transition, missing required field |
| 401 | Unauthorized | Access token expired or missing |
| 403 | Forbidden | Courier not assigned to this delivery |
| 404 | Not Found | Invalid delivery or assignment ID |
| 409 | Conflict | Assignment already responded to |
| 422 | Unprocessable Entity | Validation failed (e.g. missing `rejectionReason`) |
| 429 | Too Many Requests | Location ping rate limit exceeded (60/min) |
| 500 | Internal Server Error | Unexpected backend error |

**Handling 401 — automatic token refresh (see §3)**

1. Interceptor calls `POST /auth/courier/refresh`.
2. If successful, retries the original request once with the new token.
3. If the refresh fails (revoked or expired after 30 days), clears tokens and
   navigates to the login screen.

**Handling 429 on location pings**

Do not show an error to the user. Silently skip the next ping and resume on the
following interval.

---

## 10. Environment Config Checklist

| Item | Dev value | Prod |
|---|---|---|
| `EXPO_PUBLIC_API_URL` | `http://localhost:8081` | `https://<your-domain>` |
| `EXPO_PUBLIC_WS_URL` | `ws://localhost:8081` | `wss://<your-domain>` |
| STOMP subscribe destination | `/user/queue/assignments` | same |
| Location ping interval (active delivery) | 5 s | 5 s |
| Location ping interval (idle) | 15–30 s | 15–30 s |
| Access token TTL | 900 s (15 min) | same |
| Refresh token TTL | 30 days | same |
| Max photo file size | 10 MB | same |
| Accepted photo formats | JPEG, PNG | same |
| Location rate limit | 60 pings/min | same |

---

## Quick Endpoint Summary

| Action | Method | Path |
|---|---|---|
| **Auth** | | |
| Login | POST | `/auth/courier/login` |
| Refresh token | POST | `/auth/courier/refresh` |
| Logout | POST | `/auth/courier/logout` |
| **Assignments** | | |
| Get assignment | GET | `/api/v1/assignments/{assignmentId}` |
| Accept / Reject | POST | `/api/v1/assignments/{assignmentId}/respond` |
| **Deliveries** | | |
| Update status (no photo) | PATCH | `/api/v1/deliveries/{deliveryId}/status` |
| Pickup proof photo | POST | `/api/v1/deliveries/{deliveryId}/actions/pickup-proof` |
| Delivery proof photo | POST | `/api/v1/deliveries/{deliveryId}/actions/deliver-proof` |
| Report failed delivery | POST | `/api/v1/deliveries/{deliveryId}/actions/fail` |
| Active deliveries | GET | `/api/v1/deliveries/my-deliveries` |
| Full history | GET | `/api/v1/deliveries/my-history` |
| History filtered by status | GET | `/api/v1/deliveries/my-history?status=DELIVERED` |
| Status history timeline | GET | `/api/v1/deliveries/{deliveryId}/history` |
| Get delivery by ID | GET | `/api/v1/deliveries/{deliveryId}` |
| Get proof photos | GET | `/api/v1/deliveries/{deliveryId}/proof` |
| **Location** | | |
| Send GPS ping | POST | `/api/v1/couriers/{courierId}/locations` |
| **Courier profile** | | |
| Get profile | GET | `/api/v1/couriers/{courierId}` |
| Toggle availability | PATCH | `/api/v1/couriers/{courierId}/availability` |
