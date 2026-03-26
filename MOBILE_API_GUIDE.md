# Buyology Courier — Mobile App Integration Guide

This guide covers everything the courier mobile app needs: authentication, token management, displaying the courier's profile (including profile image and driving licence), and shift/availability management.

---

## Base URL

```
https://<your-domain>/
```

All endpoints are prefixed with `/api/auth` or `/api/v1`.

---

## Table of Contents

1. [Authentication Flow](#1-authentication-flow)
2. [Login](#2-login)
3. [Refresh Access Token](#3-refresh-access-token)
4. [Logout](#4-logout)
5. [Get Courier Profile](#5-get-courier-profile)
6. [Profile Image & Driving Licence Images](#6-profile-image--driving-licence-images)
7. [Start / Stop Shift (Availability)](#7-start--stop-shift-availability)
8. [Location Reporting](#8-location-reporting)
9. [Error Reference](#9-error-reference)
10. [Security Notes](#10-security-notes)

---

## 1. Authentication Flow

```
App Launch
    │
    ├─ Has refresh token stored? ──No──► Show Login Screen
    │
    Yes
    │
    ▼
Try /api/auth/courier/refresh
    │
    ├─ 200 OK ──► Store new access token ──► Go to Home Screen
    │
    └─ 401/400 ──► Clear tokens ──► Show Login Screen
```

**Token storage recommendation (mobile)**

| Token         | Where to store                          | TTL      |
|---------------|-----------------------------------------|----------|
| Access token  | In-memory only (not persisted to disk)  | 15 min   |
| Refresh token | Secure storage (Keychain / Keystore)    | 30 days  |
| Courier ID    | Secure storage alongside refresh token  | —        |

---

## 2. Login

### `POST /api/auth/courier/login`

No authentication header required.

**Request**

```http
POST /api/auth/courier/login
Content-Type: application/json

{
  "phoneNumber": "+998901234567",
  "password": "courier_password"
}
```

| Field         | Type   | Required | Notes                 |
|---------------|--------|----------|-----------------------|
| `phoneNumber` | string | Yes      | Max 30 chars          |
| `password`    | string | Yes      | Max 100 chars         |

**Response — 200 OK**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "dGhpcyBpcyBhIHJhbmRvbSByZWZyZXNoIHRva2Vu",
  "expiresIn": 900,
  "courierId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

| Field          | Description                                        |
|----------------|----------------------------------------------------|
| `accessToken`  | JWT — attach to every protected request as Bearer  |
| `refreshToken` | Opaque token — store securely, used to renew JWT   |
| `expiresIn`    | Seconds until access token expires (900 = 15 min)  |
| `courierId`    | UUID — use to fetch the courier's own profile      |

**Account lockout** — after 5 consecutive failed logins the account is locked for 15 minutes. Surface the error message from the response to the user.

---

## 3. Refresh Access Token

Call this automatically before any request when the access token is expired (or about to expire). No user interaction required.

### `POST /api/auth/courier/refresh`

No authentication header required.

**Request**

```http
POST /api/auth/courier/refresh
Content-Type: application/json

{
  "refreshToken": "<stored_refresh_token>"
}
```

**Response — 200 OK**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "dGhpcyBpcyBhIHJhbmRvbSByZWZyZXNoIHRva2Vu",
  "expiresIn": 900,
  "courierId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

> The refresh token itself is **not rotated** — you will get back the same refresh token. Replace the stored access token with the new one.

**On 401 / 400** — the refresh token is expired or revoked. Wipe both tokens from storage and redirect to the login screen.

---

## 4. Logout

Call this when the courier taps "Log out". It invalidates the refresh token on the server.

### `POST /api/auth/courier/logout`

No authentication header required.

**Request**

```http
POST /api/auth/courier/logout
Content-Type: application/json

{
  "refreshToken": "<stored_refresh_token>"
}
```

**Response — 204 No Content**

After receiving this response, delete both the access token and the refresh token from local storage.

> The access token will remain technically valid until its 15-minute TTL expires, but since the courier app only keeps it in memory, clearing memory is sufficient.

---

## 5. Get Courier Profile

Fetch all details to display on the profile screen: name, phone, email, vehicle info, status, rating, profile image URL, and driving licence image URL.

### `GET /api/v1/couriers/{courierId}`

**Request**

```http
GET /api/v1/couriers/3fa85f64-5717-4562-b3fc-2c963f66afa6
Authorization: Bearer <accessToken>
```

Use the `courierId` returned from login or refresh.

**Response — 200 OK**

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "firstName": "Ali",
  "lastName": "Karimov",
  "phone": "+998901234567",
  "email": "ali.karimov@example.com",
  "vehicleType": "SCOOTER",
  "status": "ACTIVE",
  "isAvailable": true,
  "rating": 4.8,
  "profileImageUrl": "/uploads/profile/abc123.jpg",
  "drivingLicenceImageUrl": "/uploads/licence/def456.jpg",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2025-03-20T08:15:00Z"
}
```

**Profile fields to display in the courier app**

| Field                  | Where to show                              |
|------------------------|--------------------------------------------|
| `firstName` + `lastName` | Profile header, navigation drawer        |
| `phone`                | Profile details screen                     |
| `email`                | Profile details screen                     |
| `vehicleType`          | Profile details screen                     |
| `status`               | Status badge (ACTIVE / OFFLINE / SUSPENDED)|
| `isAvailable`          | Shift toggle (true = on shift)             |
| `rating`               | Star rating display                        |
| `profileImageUrl`      | Avatar / profile picture                   |
| `drivingLicenceImageUrl` | Documents section                        |

**Vehicle types**

| Value     | Driving licence required |
|-----------|--------------------------|
| `BICYCLE` | No                       |
| `FOOT`    | No                       |
| `SCOOTER` | Yes                      |
| `CAR`     | Yes                      |

**Courier status meanings**

| Status      | Meaning                                        |
|-------------|------------------------------------------------|
| `ACTIVE`    | Account is active, can go on shift             |
| `OFFLINE`   | Not currently working                          |
| `SUSPENDED` | Account suspended by admin — show warning      |

---

## 6. Profile Image & Driving Licence Images

The `profileImageUrl` and `drivingLicenceImageUrl` fields in the courier response are **relative paths**. Prepend the base URL to display them.

```
Full URL = <BASE_URL> + <imageUrl field>

Example:
  profileImageUrl      = "/uploads/profile/abc123.jpg"
  Full URL             = "https://<your-domain>/uploads/profile/abc123.jpg"
```

**Loading the profile picture (pseudo-code)**

```
if courier.profileImageUrl != null:
    load image from BASE_URL + courier.profileImageUrl
    show with Authorization header (the image endpoint is on the same server)
else:
    show placeholder avatar
```

**Documents section — driving licence**

Only show the driving licence section if `vehicleType` is `SCOOTER` or `CAR`.

```
if courier.vehicleType in ["SCOOTER", "CAR"]:
    show drivingLicenceImageUrl as a tappable card
    on tap → open full-screen image viewer
```

The `drivingLicenceImageUrl` may be `null` if no image was uploaded — show a "Not uploaded" placeholder in that case.

---

## 7. Start / Stop Shift (Availability)

The courier app controls shift status through the **availability** endpoint. When `isAvailable` is `true` the courier is "on shift" and will receive delivery assignments. When `false` the courier is "off shift".

### `PATCH /api/v1/couriers/{courierId}/availability`

The courier can only update their **own** availability.

**Start shift — set available to true**

```http
PATCH /api/v1/couriers/3fa85f64-5717-4562-b3fc-2c963f66afa6/availability
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "available": true
}
```

**Stop shift — set available to false**

```http
PATCH /api/v1/couriers/3fa85f64-5717-4562-b3fc-2c963f66afa6/availability
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "available": false
}
```

**Response — 200 OK** (updated `CourierResponse` — same shape as [Get Courier Profile](#5-get-courier-profile))

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "isAvailable": true,
  ...
}
```

**Recommended UI flow**

```
Home Screen
    │
    ├─ isAvailable = false → Show "Start Shift" button (green)
    │     On tap → PATCH availability { available: true }
    │             → Update local state with response
    │             → Begin location reporting loop
    │
    └─ isAvailable = true  → Show "End Shift" button (red)
          On tap → PATCH availability { available: false }
                  → Update local state with response
                  → Stop location reporting loop
```

**Important:** Only couriers with `status = ACTIVE` should be shown the shift toggle. If status is `SUSPENDED`, disable the button and show an appropriate message (e.g. "Your account has been suspended. Contact support.").

---

## 8. Location Reporting

While the courier is on shift (`isAvailable = true`), report location updates to the server.

### `POST /api/v1/couriers/{courierId}/locations`

**Request**

```http
POST /api/v1/couriers/3fa85f64-5717-4562-b3fc-2c963f66afa6/locations
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "latitude": 41.2995,
  "longitude": 69.2401,
  "heading": 90.0,
  "speed": 5.5,
  "accuracyMeters": 10.0,
  "recordedAt": "2025-03-26T10:00:00Z"
}
```

| Field           | Type    | Required | Notes                              |
|-----------------|---------|----------|------------------------------------|
| `latitude`      | decimal | Yes      | -90.0 to 90.0                      |
| `longitude`     | decimal | Yes      | -180.0 to 180.0                    |
| `heading`       | decimal | No       | 0.0–360.0, degrees clockwise from north |
| `speed`         | decimal | No       | Metres per second                  |
| `accuracyMeters`| decimal | No       | GPS accuracy radius in metres      |
| `recordedAt`    | string  | No       | ISO 8601 UTC — omit to use server time |

**Response — 201 Created**

```json
{
  "id": "uuid",
  "courierId": "uuid",
  "latitude": 41.2995,
  "longitude": 69.2401,
  "heading": 90.0,
  "speed": 5.5,
  "accuracyMeters": 10.0,
  "recordedAt": "2025-03-26T10:00:00Z"
}
```

**Recommended reporting interval:** every 10–15 seconds while on shift. Stop reporting when `isAvailable` is set to `false`.

---

## 9. Error Reference

All error responses follow a consistent shape:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Human-readable description",
  "timestamp": "2025-03-26T10:00:00Z"
}
```

| HTTP Status | Scenario                                                         | Action                                      |
|-------------|------------------------------------------------------------------|---------------------------------------------|
| `400`       | Missing / invalid request fields                                 | Show field-level validation errors          |
| `401`       | Missing, expired, or invalid access token                        | Attempt token refresh; if that fails → login|
| `403`       | Courier attempting to access another courier's resource          | Show "Access denied" message                |
| `404`       | Courier ID not found                                             | Show error, refresh profile                 |
| `409`       | Account locked after too many failed login attempts              | Show lockout message with retry time        |
| `429`       | Too many requests                                                | Back off and retry after delay              |
| `500`       | Server error                                                     | Show generic error, retry later             |

**Login-specific error codes to handle**

| Message in response                          | UI action                                             |
|----------------------------------------------|-------------------------------------------------------|
| `Invalid credentials`                        | "Incorrect phone number or password"                  |
| `Account is locked`                          | "Account locked. Try again in 15 minutes"             |
| `Account is suspended`                       | "Your account has been suspended. Contact support."   |
| `Account is pending activation`              | "Account not yet activated. Contact your admin."      |

---

## 10. Security Notes

- **Always use HTTPS** — never send tokens over plain HTTP.
- **Access token in memory only** — do not write the JWT to shared preferences, AsyncStorage, or any persistent storage. Keep it in an in-memory variable only.
- **Refresh token in secure storage** — use iOS Keychain or Android Keystore. Do not store in SharedPreferences or AsyncStorage without encryption.
- **Attach Bearer token to every protected request:**
  ```http
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
  ```
- **Proactive token renewal** — refresh the access token when it is within 60 seconds of expiry (`expiresIn - elapsed < 60`), not after it has already expired.
- **On logout**, always call `POST /api/auth/courier/logout` before clearing local state so the refresh token is revoked server-side.
- **On 401 during any request**, attempt one silent refresh. If the refresh also fails (401/400), clear tokens and navigate to the login screen.

---

## Quick Reference — Endpoint Summary

| Purpose                | Method  | Path                                               | Auth required |
|------------------------|---------|----------------------------------------------------|---------------|
| Login                  | `POST`  | `/api/auth/courier/login`                          | No            |
| Refresh token          | `POST`  | `/api/auth/courier/refresh`                        | No            |
| Logout                 | `POST`  | `/api/auth/courier/logout`                         | No            |
| Get own profile        | `GET`   | `/api/v1/couriers/{courierId}`                     | Yes (Bearer)  |
| Start / stop shift     | `PATCH` | `/api/v1/couriers/{courierId}/availability`        | Yes (Bearer)  |
| Report location        | `POST`  | `/api/v1/couriers/{courierId}/locations`           | Yes (Bearer)  |
| Get latest location    | `GET`   | `/api/v1/couriers/{courierId}/locations/latest`    | Yes (Bearer)  |
