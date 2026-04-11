# Buyology Courier — Mobile App Integration Guide

Complete backend reference for the courier mobile app. Covers every API call,
the startup sequence, WebSocket/STOMP connection, FCM push notifications,
photo uploads, all status transitions, history, and error handling.

**Stack used in examples:** Expo (React Native) · TypeScript · Axios · TanStack Query  
**Backend base URL (dev):** `http://localhost:8081`  
**Backend base URL (prod):** replace with your deployed host

---

## Table of Contents

1. [Base URL & Required Headers](#1-base-url--required-headers)
2. [Authentication](#2-authentication)
3. [Startup Sequence — Must Run in Order](#3-startup-sequence--must-run-in-order)
4. [Token Management — Auto-refresh](#4-token-management--auto-refresh)
5. [WebSocket — Real-time Assignment Notifications](#5-websocket--real-time-assignment-notifications)
6. [FCM — Background Push Notifications](#6-fcm--background-push-notifications)
7. [Complete Delivery Flow](#7-complete-delivery-flow)
8. [API Reference](#8-api-reference)
9. [Status Transition Map](#9-status-transition-map)
10. [TypeScript Types](#10-typescript-types)
11. [Error Handling](#11-error-handling)

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
Content-Type:  multipart/form-data     ← for photo uploads (set automatically)
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

### 2.2 Logout

```
POST /auth/courier/logout
{ "refreshToken": "550e8400-e29b-41d4-a716-446655440000" }
```

**Response 204** — Token revoked server-side. Clear both tokens locally.

---

## 3. Startup Sequence — Must Run in Order

**This sequence must complete every time the courier opens the app or starts a shift.**
Skipping any step will break the assignment pipeline silently.

```
Step 1  POST /auth/courier/login
        → store accessToken, refreshToken, courierId

Step 2  POST /v1/couriers/push-token
        → register FCM device token so background pushes work

Step 3  PATCH /v1/couriers/{courierId}/availability   body: {"available":true}
        → marks the courier as accepting orders in the DB

Step 4  POST /v1/couriers/{courierId}/locations
        → first GPS ping — adds the courier to the Redis GEO index
        → WITHOUT this step, the assignment service cannot find the courier

Step 5  Connect WebSocket  ws://<host>/ws  (STOMP)
        → subscribe to /user/queue/assignments for foreground notifications
```

### Why each step matters

| Step | What breaks if skipped |
|------|------------------------|
| Push token | FCM notifications never arrive when app is in background |
| Availability | DB filter in assignment service rejects the courier |
| First location | Redis GEO index is empty — assignment service finds no couriers |
| WebSocket | No foreground real-time notification |

### Step 2 — Register FCM token

```
POST /v1/couriers/push-token
Authorization: Bearer <accessToken>
Content-Type: application/json

{ "fcmToken": "<device-token-from-firebase>" }
```

**Response 204** — call this every login (token may rotate).

```typescript
import messaging from '@react-native-firebase/messaging';

async function registerPushToken(accessToken: string) {
  const fcmToken = await messaging().getToken();
  await api.post('/v1/couriers/push-token',
    { fcmToken },
    { headers: { Authorization: `Bearer ${accessToken}` } }
  );
}
```

### Step 3 — Set availability

```
PATCH /v1/couriers/{courierId}/availability
Authorization: Bearer <accessToken>

{ "available": true }
```

**Response 200** — CourierResponse with updated `isAvailable`.

### Step 4 — Post first location

```
POST /v1/couriers/{courierId}/locations
Authorization: Bearer <accessToken>

{
  "latitude":      40.5905539,
  "longitude":     49.6768040,
  "heading":       270.0,
  "speed":         0.0,
  "accuracyMeters": 5.0,
  "recordedAt":    "2026-04-11T13:00:00Z"
}
```

**Response 201** — Continue posting every **5 seconds during active delivery**, every **15–30 seconds when idle**.  
**Rate limit:** 60 pings/minute per courier. On HTTP 429 — back off for 60 seconds.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `latitude` | `number` | ✅ | WGS-84, −90 to 90 |
| `longitude` | `number` | ✅ | WGS-84, −180 to 180 |
| `heading` | `number` | ❌ | Degrees from north, 0–360 |
| `speed` | `number` | ❌ | Metres per second |
| `accuracyMeters` | `number` | ❌ | GPS accuracy radius |
| `recordedAt` | `string` | ❌ | ISO-8601, must not be in the future |

---

## 4. Token Management — Auto-refresh

Access tokens expire after **15 minutes**. Refresh proactively at 12 minutes.

```
POST /auth/courier/refresh
{ "refreshToken": "550e8400-..." }
```

**Response 200**
```json
{
  "accessToken":  "eyJhbGci...",
  "refreshToken": "new-refresh-token-uuid",
  "expiresIn":    900
}
```

Refresh tokens are **single-use** — store the new one after each refresh.
On HTTP 401 from any request, attempt one refresh then retry. If refresh also fails, send the user to login.

---

## 5. WebSocket — Real-time Assignment Notifications

Used for **foreground** notifications when the app is open.

### 5.1 Connect

```
ws://<host>/ws
```

STOMP CONNECT frame must include:
```
Authorization: Bearer <accessToken>
```

### 5.2 Subscribe

After CONNECTED, subscribe to:
```
/user/queue/assignments
```

### 5.3 Message payload

```json
{
  "assignmentId":  "asgn-uuid",
  "deliveryId":    "del-uuid",
  "attemptNumber": 1,
  "pickupAddress": "Sulh 189, Sumgait",
  "pickupLat":     40.5905539,
  "pickupLng":     49.6768040,
  "dropoffAddress": "Baku Street, Sumgait",
  "dropoffLat":    40.5861705,
  "dropoffLng":    49.6665901,
  "packageSize":   "SMALL",
  "packageWeight": 1.0,
  "deliveryFee":   15000,
  "priority":      "EXPRESS",
  "assignedAt":    "2026-04-11T13:22:44Z"
}
```

On receiving this message → navigate to the **Accept/Reject screen** with order details.

### 5.4 Token expiry during WebSocket session

When the access token expires, the STOMP session keeps working until the courier disconnects.
On reconnect, use the refreshed token in the new CONNECT frame.

---

## 6. FCM — Background Push Notifications

Used when the app is **in the background or killed**.

### 6.1 Setup (React Native Firebase)

```bash
npx expo install @react-native-firebase/app @react-native-firebase/messaging
```

Follow the [React Native Firebase setup guide](https://rnfirebase.io/) to add `google-services.json` (Android) and `GoogleService-Info.plist` (iOS).

### 6.2 FCM notification payload

The backend sends a **combined notification + data message**:

**Notification part** (shown in system tray):
```
Title: "New delivery order"
Body:  "Sulh 189, Sumgait → Baku Street, Sumgait"
```

**Data part** (available to the app in background/foreground):
```json
{
  "assignmentId":  "asgn-uuid",
  "deliveryId":    "del-uuid",
  "pickupAddress": "Sulh 189, Sumgait",
  "dropoffAddress": "Baku Street, Sumgait",
  "deliveryFee":   "15000",
  "priority":      "EXPRESS"
}
```

### 6.3 Handle FCM in app

```typescript
import messaging from '@react-native-firebase/messaging';

// Background / quit state — tap on notification opens the app
messaging().setBackgroundMessageHandler(async remoteMessage => {
  // Store the assignment data locally so the app can show the accept/reject
  // screen when the user taps the notification
  await AsyncStorage.setItem('pendingAssignment',
    JSON.stringify(remoteMessage.data));
});

// Foreground — app is open (WebSocket handles this, but FCM fires too)
messaging().onMessage(async remoteMessage => {
  // Optionally show an in-app banner; the WebSocket message is the primary source
});

// When app opens from notification tap
const initialMessage = await messaging().getInitialNotification();
if (initialMessage?.data?.assignmentId) {
  navigation.navigate('AssignmentRespond', initialMessage.data);
}
```

---

## 7. Complete Delivery Flow

```
[App opens]
    │
    ├─ POST /auth/courier/login
    ├─ POST /v1/couriers/push-token        (FCM token)
    ├─ PATCH /v1/couriers/{id}/availability {"available":true}
    ├─ POST /v1/couriers/{id}/locations    (first GPS ping → enters GEO index)
    └─ Connect WebSocket → subscribe /user/queue/assignments

[Order created by ecommerce]
    │
    └─ Backend finds nearest courier → creates PENDING assignment
         │
         ├─ WebSocket push → /user/queue/assignments  (foreground)
         └─ FCM push → device notification            (background)

[Courier sees notification]
    │
    └─ POST /v1/assignments/{assignmentId}/respond
         {"action": "ACCEPT"}
         → delivery status: COURIER_ACCEPTED
         → START location pings every 5 seconds
         │
         OR
         │
         {"action": "REJECT", "rejectionReason": "Too far"}
         → backend finds next nearest courier (up to 3 attempts)

[After ACCEPT — status transitions]
    │
    ├─ PATCH /v1/deliveries/{id}/status  {"status":"ARRIVED_AT_PICKUP"}
    │
    ├─ POST  /v1/deliveries/{id}/actions/pickup-proof   (photo upload)
    │    → status auto-advances to PICKED_UP
    │
    ├─ PATCH /v1/deliveries/{id}/status  {"status":"ON_THE_WAY"}
    │
    ├─ PATCH /v1/deliveries/{id}/status  {"status":"ARRIVED_AT_DESTINATION"}
    │
    └─ POST  /v1/deliveries/{id}/actions/deliver-proof  (photo upload)
         → status auto-advances to DELIVERED
         → STOP location pings

[If delivery fails]
    └─ POST /v1/deliveries/{id}/actions/fail
         {"reason": "Customer not reachable", "latitude": ..., "longitude": ...}
         → status: FAILED
```

---

## 8. API Reference

### Auth Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/auth/courier/login` | ❌ | Login with phone + password |
| POST | `/auth/courier/refresh` | ❌ | Refresh access token |
| POST | `/auth/courier/logout` | ✅ | Revoke refresh token |

### Courier Profile & Device

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/v1/couriers/{id}` | ✅ | Get courier profile |
| POST | `/v1/couriers/push-token` | ✅ COURIER | Register FCM device token |
| PATCH | `/v1/couriers/{id}/availability` | ✅ COURIER | Set available true/false |

### Location Tracking

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/v1/couriers/{id}/locations` | ✅ COURIER | Record GPS ping (updates GEO index) |
| GET | `/v1/couriers/{id}/locations/latest` | ✅ | Get latest known position |

### Assignments

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/v1/assignments/{id}` | ✅ COURIER | Get assignment details |
| POST | `/v1/assignments/{id}/respond` | ✅ COURIER | Accept or reject assignment |

**Accept**
```json
{ "action": "ACCEPT" }
```

**Reject** (`rejectionReason` is required)
```json
{ "action": "REJECT", "rejectionReason": "Vehicle breakdown" }
```

### Deliveries — Status Transitions

| Method | Path | Auth | Body |
|--------|------|------|------|
| PATCH | `/v1/deliveries/{id}/status` | ✅ COURIER | `{"status":"ARRIVED_AT_PICKUP"}` |
| PATCH | `/v1/deliveries/{id}/status` | ✅ COURIER | `{"status":"ON_THE_WAY"}` |
| PATCH | `/v1/deliveries/{id}/status` | ✅ COURIER | `{"status":"ARRIVED_AT_DESTINATION"}` |

Optional fields on status update:
```json
{
  "status":    "ON_THE_WAY",
  "latitude":  40.5905539,
  "longitude": 49.6768040,
  "notes":     "Traffic delay on highway"
}
```

### Deliveries — Photo Uploads

**Pickup proof** (advances status to `PICKED_UP`)
```
POST /v1/deliveries/{id}/actions/pickup-proof
Content-Type: multipart/form-data

photo:       <image file>  (JPEG/PNG/WebP, max 10 MB)
photoTakenAt: 2026-04-11T13:30:00Z   (optional, ISO-8601)
```

**Delivery proof** (advances status to `DELIVERED`)
```
POST /v1/deliveries/{id}/actions/deliver-proof
Content-Type: multipart/form-data

photo:        <image file>
deliveredTo:  "John Smith"            (optional — name of person who received)
photoTakenAt: 2026-04-11T14:05:00Z
```

### Deliveries — Failed Delivery

```
POST /v1/deliveries/{id}/actions/fail
{
  "reason":    "Customer not reachable after 3 attempts",
  "latitude":  40.5861705,
  "longitude": 49.6665901
}
```

### Deliveries — History

```
GET /v1/deliveries?courierId={id}&status=DELIVERED&page=0&size=20
GET /v1/deliveries/{id}
GET /v1/deliveries/{id}/status-history
GET /v1/deliveries/{id}/proof
```

---

## 9. Status Transition Map

```
CREATED
  └─ (auto) ──────────────────────────────► COURIER_ASSIGNED
                                                  │
                                    ┌─────── ACCEPT ────────┐
                                    │                       │
                               COURIER_ACCEPTED         REJECTED
                                    │                (backend reassigns,
                          ARRIVED_AT_PICKUP           up to 3 attempts)
                                    │
                    [POST pickup-proof photo]
                                    │
                               PICKED_UP
                                    │
                               ON_THE_WAY
                                    │
                        ARRIVED_AT_DESTINATION
                                    │
                   [POST deliver-proof photo]
                                    │
                               DELIVERED ✓

At any in-progress status:
  └─ POST /actions/fail ──────────────────► FAILED
  └─ (admin only) ─────────────────────► CANCELLED
```

---

## 10. TypeScript Types

```typescript
export type DeliveryStatus =
  | 'CREATED' | 'COURIER_ASSIGNED' | 'COURIER_ACCEPTED'
  | 'ARRIVED_AT_PICKUP' | 'PICKED_UP' | 'ON_THE_WAY'
  | 'ARRIVED_AT_DESTINATION' | 'DELIVERED' | 'FAILED' | 'CANCELLED';

export type AssignmentStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';
export type Priority = 'STANDARD' | 'EXPRESS' | 'URGENT';
export type PackageSize = 'SMALL' | 'MEDIUM' | 'LARGE' | 'EXTRA_LARGE';

export interface AuthResponse {
  accessToken:  string;
  refreshToken: string;
  expiresIn:    number;
  tokenType:    string;
  courierId:    string;
}

export interface AssignmentNotification {
  assignmentId:   string;
  deliveryId:     string;
  attemptNumber:  number;
  pickupAddress:  string;
  pickupLat:      number;
  pickupLng:      number;
  dropoffAddress: string;
  dropoffLat:     number;
  dropoffLng:     number;
  packageSize:    PackageSize;
  packageWeight:  number;
  deliveryFee:    number;
  priority:       Priority;
  assignedAt:     string;
}

export interface LocationRequest {
  latitude:      number;
  longitude:     number;
  heading?:      number;
  speed?:        number;
  accuracyMeters?: number;
  recordedAt?:   string;
}

export interface UpdateStatusRequest {
  status:     DeliveryStatus;
  latitude?:  number;
  longitude?: number;
  notes?:     string;
}
```

---

## 11. Error Handling

| HTTP | Meaning | Action |
|------|---------|--------|
| 400 | Validation error | Show field errors from `errors[]` array in response |
| 401 | Token expired or invalid | Refresh token, retry once; if still 401 → re-login |
| 403 | Wrong courier for this resource | Log out and re-login |
| 404 | Assignment or delivery not found | Show error, go back |
| 409 | Already responded to assignment | Show message, refresh state |
| 422 | Business rule violation (e.g. wrong status transition) | Show `message` field |
| 429 | Location ping rate limit (60/min) | Back off 60 seconds |
| 5xx | Server error | Retry with exponential backoff (1s, 2s, 4s) |

**Error response shape:**
```json
{
  "status":    422,
  "error":     "Unprocessable Entity",
  "message":   "Invalid status transition: PICKED_UP → ARRIVED_AT_PICKUP",
  "path":      "/api/v1/deliveries/abc/status",
  "timestamp": "2026-04-11T13:22:44Z"
}
```
