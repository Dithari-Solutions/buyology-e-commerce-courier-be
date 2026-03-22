# Courier Auth — Database Structure

This document describes the three tables added in **V3** migration that implement
self-contained phone + password authentication for couriers.

---

## Overview

| Table | Purpose |
|---|---|
| `courier_credentials` | Phone number + hashed password; account status and lockout |
| `courier_vehicle_details` | Vehicle info; driving licence required conditionally |
| `courier_refresh_tokens` | Hashed JWT refresh tokens for session management |

**Who can create a courier account?** Admin only. Signup is an admin-only API
endpoint protected by `ROLE_ADMIN` in the caller's JWT (issued by Keycloak).
Couriers cannot self-register.

**How does a courier log in?** Phone number + password → short-lived access JWT +
long-lived refresh token.

---

## 1. `courier_credentials`

Holds one row per courier. Created by an admin during onboarding.

```
courier_credentials
├── id                    UUID (PK)
├── courier_id            UUID (FK → couriers.id, UNIQUE)
├── phone_number          VARCHAR(30) UNIQUE NOT NULL
├── password_hash         VARCHAR(255) NOT NULL
├── account_status        VARCHAR(30) NOT NULL  DEFAULT 'PENDING_ACTIVATION'
├── failed_login_attempts INTEGER NOT NULL      DEFAULT 0
├── locked_until          TIMESTAMPTZ
├── last_login_at         TIMESTAMPTZ
├── password_changed_at   TIMESTAMPTZ
├── created_by_admin_id   UUID
├── created_at            TIMESTAMPTZ
└── updated_at            TIMESTAMPTZ
```

### Column details

| Column | Notes |
|---|---|
| `courier_id` | 1-to-1 with `couriers`. UNIQUE constraint guarantees one credential set per courier. |
| `phone_number` | The login identifier. Mirrors `couriers.phone` to avoid a JOIN on every authentication request. Kept in sync by the application layer at signup. |
| `password_hash` | BCrypt hash with strength ≥ 12. **Never** the plain-text password. Set by the courier on first login (activation flow). |
| `account_status` | See the state machine below. |
| `failed_login_attempts` | Incremented on each wrong password attempt; reset to 0 on successful login. |
| `locked_until` | When non-NULL and in the future, the account is temporarily locked regardless of `account_status`. Null it out to unlock before expiry. |
| `last_login_at` | Updated to `now()` on every successful authentication. |
| `password_changed_at` | Updated whenever the password hash is replaced. Useful for expiry policies. |
| `created_by_admin_id` | UUID of the Keycloak admin who created the courier. Not a FK — admins live outside this service. |

### Account status state machine

```
              Admin creates account
                      │
                      ▼
            PENDING_ACTIVATION
           (courier not yet set password)
                      │
         Courier completes activation
                      │
                      ▼
                   ACTIVE ◄──────────────── Admin reinstates
                   /    \                          │
    Too many failed  \    Admin suspends      SUSPENDED
        attempts      \   (manual action)     (cannot log in)
             │         \
             ▼          ──────────────────►
          LOCKED                       (admin or auto-unlock)
      (temporary; locked_until set)
             │
     locked_until expires OR admin clears
             │
             ▼
           ACTIVE
```

| Status | Can log in? | How to reach it |
|---|---|---|
| `PENDING_ACTIVATION` | No | Admin creates account |
| `ACTIVE` | Yes | Courier activates account / admin reinstates |
| `LOCKED` | No | `failed_login_attempts` exceeds threshold (e.g. 5) |
| `SUSPENDED` | No | Admin suspends via management API |

### Lockout policy (recommended application-layer rules)

- After **5** consecutive failed attempts → set `locked_until = now() + 15 minutes`, `account_status = 'LOCKED'`.
- After **10** consecutive attempts (if auto-unlock keeps failing) → set `account_status = 'SUSPENDED'` for manual review.
- Each successful login resets `failed_login_attempts = 0` and clears `locked_until`.

### Indexes

| Index | Purpose |
|---|---|
| `idx_courier_credentials_phone` | Primary lookup path on every login request |
| `idx_courier_credentials_courier_id` | Used when fetching credentials by courier profile ID |
| `idx_courier_credentials_status` | Used for admin dashboards filtering by account state |

---

## 2. `courier_vehicle_details`

Stores the vehicle information provided during admin-managed signup. The most
important aspect is the **conditional driving licence requirement**.

```
courier_vehicle_details
├── id                          UUID (PK)
├── courier_id                  UUID (FK → couriers.id, UNIQUE)
├── vehicle_type                VARCHAR(30) NOT NULL
├── vehicle_make                VARCHAR(100)
├── vehicle_model               VARCHAR(100)
├── vehicle_year                SMALLINT
├── vehicle_color               VARCHAR(50)
├── license_plate               VARCHAR(50) UNIQUE
├── vehicle_registration_url    TEXT
├── driving_license_number      VARCHAR(100)
├── driving_license_expiry      DATE
├── driving_license_front_url   TEXT
├── driving_license_back_url    TEXT
├── requires_driving_license    BOOLEAN NOT NULL
├── created_at                  TIMESTAMPTZ
└── updated_at                  TIMESTAMPTZ
```

### Driving licence requirement by vehicle type

The `VehicleType` enum drives all validation logic:

| `vehicle_type` | `requires_driving_license` | Licence fields |
|---|---|---|
| `BICYCLE` | `FALSE` | Must be `NULL` |
| `FOOT` | `FALSE` | Must be `NULL` |
| `SCOOTER` | `TRUE` | `driving_license_number` + `driving_license_expiry` required; scan URLs strongly recommended |
| `CAR` | `TRUE` | Same as SCOOTER |

This rule is enforced at **three levels**:

1. **Java enum** — `VehicleType.requiresDrivingLicense()` returns `true` for
   `SCOOTER` and `CAR`. The service layer calls this method before building the
   entity to set `requires_driving_license` and to validate the presence of
   licence fields.

2. **Database CHECK constraint** (`chk_vehicle_driving_license`) — if the
   application layer fails to validate, the DB rejects the insert:
   ```sql
   (requires_driving_license = TRUE
        AND driving_license_number IS NOT NULL
        AND driving_license_expiry IS NOT NULL)
   OR
   (requires_driving_license = FALSE
        AND driving_license_number IS NULL
        AND driving_license_expiry IS NULL)
   ```

3. **Plate presence constraint** (`chk_vehicle_plate_required`) — motorized
   vehicles must have a license plate; BICYCLE/FOOT leave it NULL.

### Column details

| Column | Notes |
|---|---|
| `vehicle_type` | Copied from `couriers.vehicle_type`. Kept here so vehicle details are self-contained and no join is needed. |
| `vehicle_make / model / year / color` | NULL for BICYCLE and FOOT — they have no registered vehicle. |
| `license_plate` | UNIQUE when present. NULL for BICYCLE/FOOT. Prevents the same physical vehicle being registered under two couriers. |
| `vehicle_registration_url` | URL pointing to a scanned copy of the vehicle registration document stored in object storage (e.g. S3). |
| `driving_license_number` | Official licence number as printed on the card. |
| `driving_license_expiry` | Application layer must reject expired licences at signup. |
| `driving_license_front_url / back_url` | Both sides of the licence card scanned and uploaded to object storage before the record is created. |
| `requires_driving_license` | Computed by `VehicleType.requiresDrivingLicense()` at creation time. Read-only after insert. |

### Typical signup payloads

**BICYCLE courier** (no licence required):
```json
{
  "vehicleType": "BICYCLE",
  "vehicleColor": "Red"
}
```

**SCOOTER courier** (licence required):
```json
{
  "vehicleType": "SCOOTER",
  "vehicleMake": "Honda",
  "vehicleModel": "PCX125",
  "vehicleYear": 2021,
  "vehicleColor": "White",
  "licensePlate": "10 BB 456",
  "vehicleRegistrationUrl": "https://storage.example.com/reg/abc.pdf",
  "drivingLicenseNumber": "DL-1234567",
  "drivingLicenseExpiry": "2028-06-30",
  "drivingLicenseFrontUrl": "https://storage.example.com/dl/front.jpg",
  "drivingLicenseBackUrl":  "https://storage.example.com/dl/back.jpg"
}
```

### Indexes

| Index | Purpose |
|---|---|
| `idx_vehicle_details_courier_id` | Join with courier profile |
| `idx_vehicle_details_vehicle_type` | Filtering by vehicle type in dispatching |
| `idx_vehicle_details_license_plate` | Partial index (non-null plates only) for uniqueness lookups |

---

## 3. `courier_refresh_tokens`

Manages JWT refresh tokens. The raw token is **never stored** — only its
SHA-256 hex digest is persisted.

```
courier_refresh_tokens
├── id           UUID (PK)
├── courier_id   UUID (FK → couriers.id)
├── token_hash   VARCHAR(64) UNIQUE NOT NULL   ← SHA-256(raw_token) hex string
├── issued_at    TIMESTAMPTZ NOT NULL           DEFAULT now()
├── expires_at   TIMESTAMPTZ NOT NULL
├── revoked_at   TIMESTAMPTZ                   ← NULL = valid; non-NULL = revoked
├── device_info  VARCHAR(255)
└── ip_address   INET
```

### Token lifecycle

```
Login
 └─► Generate secure random token R (32 bytes)
     Compute hash H = SHA-256(R) → store H in this table
     Return R to client (only time it is visible in plain text)

Refresh
 └─► Client sends R in Authorization header
     Compute H = SHA-256(R)
     SELECT * FROM courier_refresh_tokens WHERE token_hash = H
     Validate: revoked_at IS NULL AND expires_at > now()
     Issue new short-lived access JWT (15 min)
     Optional: rotate — revoke old row, issue new refresh token

Logout
 └─► UPDATE courier_refresh_tokens SET revoked_at = now()
     WHERE token_hash = SHA-256(R) AND courier_id = <id>
```

### Column details

| Column | Notes |
|---|---|
| `token_hash` | 64-character hex string (256-bit SHA-256). UNIQUE constraint prevents duplicate tokens. |
| `expires_at` | Typically `issued_at + 30 days`. Client must re-authenticate after expiry. |
| `revoked_at` | Set on logout or on token rotation. A revoked token is never accepted even if `expires_at` is in the future. |
| `device_info` | e.g. `"Android 13 / Buyology Courier App 2.3.1"`. Aids session management UI. |
| `ip_address` | Client IP captured at login. PostgreSQL `INET` type stores both IPv4 and IPv6. |

### Maintenance

A scheduled job should periodically delete rows where:
```sql
expires_at < now() - INTERVAL '7 days'
```
This keeps the table lean without deleting recently-expired tokens that may
still be referenced in audit queries.

### Indexes

| Index | Purpose |
|---|---|
| `idx_refresh_tokens_token_hash` | Fast lookup on every refresh request (hot path) |
| `idx_refresh_tokens_courier_id` | List all sessions for a courier (session management) |
| `idx_refresh_tokens_expires_at` | Maintenance job scans for expired tokens |
| `idx_refresh_tokens_active` | Partial index on `(courier_id, expires_at)` WHERE `revoked_at IS NULL` — used to count or list active sessions per courier |

---

## Entity Relationship Diagram

```
couriers (existing)
    │  1
    │──────────────────────────────────────────────────────────────────────────
    │  1          │  1                             │  0..*
    ▼             ▼                                ▼
courier_         courier_vehicle_details     courier_refresh_tokens
credentials      ─────────────────────────  ──────────────────────
──────────       courier_id (FK, UNIQUE)     courier_id (FK)
courier_id       vehicle_type               token_hash (UNIQUE)
(FK, UNIQUE)     requires_driving_license   expires_at
phone_number     driving_license_number*    revoked_at
password_hash    driving_license_expiry*
account_status   license_plate (UNIQUE)

* Required when requires_driving_license = TRUE
```

---

## Auth Flow Summary

### Admin creates courier (signup)

```
POST /api/admin/couriers
Authorization: Bearer <admin-jwt>   ← ROLE_ADMIN required

Body:
  personal details  → INSERT INTO couriers
  vehicle details   → INSERT INTO courier_vehicle_details
                      (driving licence fields validated by VehicleType enum)
  credentials       → INSERT INTO courier_credentials
                      account_status = 'PENDING_ACTIVATION'
                      password_hash  = BCrypt(temporaryPassword or empty)
```

### Courier logs in

```
POST /api/auth/courier/login
Body: { "phoneNumber": "+994...", "password": "..." }

1. SELECT * FROM courier_credentials WHERE phone_number = ?
2. Check account_status = 'ACTIVE' and (locked_until IS NULL OR locked_until < now())
3. BCrypt.verify(password, password_hash)
   ├─ Fail → increment failed_login_attempts; lock if threshold exceeded
   └─ Pass → reset failed_login_attempts; update last_login_at
4. Issue access JWT  (sub = courier_id, exp = 15 min)
5. Generate refresh token R; store SHA-256(R) in courier_refresh_tokens
6. Return { accessToken, refreshToken, expiresIn }
```

### Courier refreshes token

```
POST /api/auth/courier/refresh
Body: { "refreshToken": "R" }

1. Compute H = SHA-256(R)
2. SELECT * FROM courier_refresh_tokens WHERE token_hash = H
3. Validate: revoked_at IS NULL AND expires_at > now()
4. Issue new access JWT
5. (Optional rotation) Revoke old token; issue new refresh token
```

---

## Security Notes

- **Password hashing** — BCrypt with `strength = 12`. Never SHA-256 or MD5.
- **Refresh token storage** — SHA-256 of the raw token. Even a full DB dump
  cannot be used to replay sessions.
- **Lockout** — 5 failed attempts triggers a 15-minute `locked_until`. Prevents
  online brute force without annoying legitimate users.
- **Token rotation** — Recommended on every refresh to detect stolen tokens
  (refresh token reuse detection).
- **HTTPS only** — Access and refresh tokens must only be transmitted over TLS.
- **Short access token TTL** — 15 minutes limits the window of a stolen access
  token. Refresh token TTL of 30 days balances security and UX.
