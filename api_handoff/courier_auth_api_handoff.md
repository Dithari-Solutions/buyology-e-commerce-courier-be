# Courier Auth — API Handoff

**For:** Buyology e-commerce backend team & admin frontend team
**Service:** `buyology-courier-service`
**Base URL (dev):** `http://localhost:8081`
**Base URL (prod):** _(set by ops — replace throughout)_

---

## Overview

Couriers **cannot self-register**. Every courier account must be created by an admin from the buyology admin panel. This document covers everything you need to call the courier creation endpoint and integrate it into the admin UI.

The flow is:

```
Admin Panel (browser)
      │
      │  1. Admin logs in to Keycloak (same as today)
      │  2. Admin fills in courier form
      │  3. POST /api/auth/admin/couriers  ←── this document
      │
      ▼
buyology-courier-service
      │
      ├─ Creates courier profile
      ├─ Creates login credentials (phone + hashed password)
      ├─ Creates vehicle details (driving licence if motorized)
      └─ Returns courier summary
```

---

## Keycloak Configuration (one-time setup, ops/backend)

Before calling the endpoint, two things must be configured in Keycloak for the admin token to be accepted by the courier service.

### 1. Assign the correct role

The admin user (or their Keycloak group) must have **`COURIER_ADMIN`** role assigned in Keycloak.

> General `ADMIN` role also works but `COURIER_ADMIN` is preferred — it limits which services the admin can reach.

```
Keycloak Admin Console
  → Realm: buyology
  → Users → [admin user] → Role Mappings
  → Add role: COURIER_ADMIN
```

### 2. Add an Audience mapper to the courier service client

The courier service rejects any Keycloak token that does not contain `buyology-courier-service` in its `aud` claim. Add an audience mapper once:

```
Keycloak Admin Console
  → Realm: buyology
  → Clients → buyology-courier-service
  → Client Scopes → [dedicated scope] → Mappers → Create
      Mapper Type : Audience
      Name        : courier-service-audience
      Included Client Audience : buyology-courier-service
      Add to access token : ON
```

After this, every Keycloak access token issued to an admin for this client will include `"aud": ["buyology-courier-service"]`.

---

## Endpoint: Create Courier (Admin Only)

```
POST /api/auth/admin/couriers
Authorization: Bearer <keycloak-access-token>
Content-Type: application/json
```

### Required role

`COURIER_ADMIN` or `ADMIN` in the Keycloak token's `roles` claim.

### Request body

```json
{
  "firstName":       "John",
  "lastName":        "Smith",
  "phone":           "+994501234567",
  "email":           "john.smith@example.com",
  "profileImageUrl": "https://storage.buyology.com/couriers/john.jpg",
  "initialPassword": "Secure#Pass1",

  "vehicleType":     "SCOOTER",
  "vehicleMake":     "Honda",
  "vehicleModel":    "PCX125",
  "vehicleYear":     2022,
  "vehicleColor":    "White",
  "licensePlate":    "10 BB 456",
  "vehicleRegistrationUrl": "https://storage.buyology.com/docs/reg-456.pdf",

  "drivingLicenseNumber":   "DL-7654321",
  "drivingLicenseExpiry":   "2029-03-15",
  "drivingLicenseFrontUrl": "https://storage.buyology.com/docs/dl-front.jpg",
  "drivingLicenseBackUrl":  "https://storage.buyology.com/docs/dl-back.jpg"
}
```

### Field reference

| Field | Type | Required | Notes |
|---|---|---|---|
| `firstName` | string | ✅ | Max 100 chars |
| `lastName` | string | ✅ | Max 100 chars |
| `phone` | string | ✅ | Max 30 chars. Must be unique across all couriers. Used as login identifier. |
| `email` | string | ❌ | Must be a valid email if provided. Max 150 chars. |
| `profileImageUrl` | string | ❌ | Must be a valid HTTP/HTTPS URL. Max 2048 chars. |
| `initialPassword` | string | ✅ | 8–100 chars. Shared with the courier out-of-band. They use it to log in. |
| `vehicleType` | enum | ✅ | See vehicle type table below. |
| `vehicleMake` | string | ❌ | Max 100 chars. e.g. `Honda`. Not required for `BICYCLE`/`FOOT`. |
| `vehicleModel` | string | ❌ | Max 100 chars. e.g. `PCX125`. |
| `vehicleYear` | integer | ❌ | 1900–2100. |
| `vehicleColor` | string | ❌ | Max 50 chars. |
| `licensePlate` | string | ✅ if motorized | Max 50 chars. **Required** for `SCOOTER` and `CAR`. Must be globally unique. |
| `vehicleRegistrationUrl` | string | ❌ | Valid URL to scanned registration document. |
| `drivingLicenseNumber` | string | ✅ if motorized | Max 100 chars. **Required** for `SCOOTER` and `CAR`. |
| `drivingLicenseExpiry` | date | ✅ if motorized | Format `YYYY-MM-DD`. **Required** for `SCOOTER` and `CAR`. |
| `drivingLicenseFrontUrl` | string | ❌ | Valid URL to front scan of licence. Strongly recommended for motorized vehicles. |
| `drivingLicenseBackUrl` | string | ❌ | Valid URL to back scan of licence. Strongly recommended for motorized vehicles. |

### Vehicle types and driving licence rule

| `vehicleType` | Driving licence required? | License plate required? |
|---|---|---|
| `BICYCLE` | ❌ No | ❌ No |
| `FOOT` | ❌ No | ❌ No |
| `SCOOTER` | ✅ Yes | ✅ Yes |
| `CAR` | ✅ Yes | ✅ Yes |

**Frontend validation rule:**
```
if (vehicleType === 'SCOOTER' || vehicleType === 'CAR') {
  // show and require: licensePlate, drivingLicenseNumber, drivingLicenseExpiry
  // show optional:    vehicleRegistrationUrl, drivingLicenseFrontUrl, drivingLicenseBackUrl
} else {
  // hide all driving licence and license plate fields
}
```

### Success response — `201 Created`

```json
{
  "courierId":              "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "firstName":              "John",
  "lastName":               "Smith",
  "phone":                  "+994501234567",
  "accountStatus":          "ACTIVE",
  "vehicleType":            "SCOOTER",
  "requiresDrivingLicense": true
}
```

> **Important:** `initialPassword` is **never returned** in any response. Share it with the courier through a secure out-of-band channel (SMS, internal messenger, etc.).

---

## Error Responses

All errors follow the same shape:

```json
{
  "status":    409,
  "error":     "Conflict",
  "message":   "Phone number is already registered: +994501234567",
  "path":      "/api/auth/admin/couriers",
  "timestamp": "2026-03-23T10:15:30Z",
  "fieldErrors": null
}
```

For validation failures (`400`), `fieldErrors` is populated:

```json
{
  "status": 400,
  "error":  "Validation Failed",
  "message": "Request validation failed",
  "path":    "/api/auth/admin/couriers",
  "timestamp": "2026-03-23T10:15:30Z",
  "fieldErrors": {
    "phone":               "must not be blank",
    "vehicleType":         "must not be null",
    "drivingLicenseNumber": "Driving licence details are required for vehicle type: SCOOTER"
  }
}
```

### Error code reference

| HTTP Status | When |
|---|---|
| `400 Bad Request` | Missing required field, invalid format, or driving licence not provided for motorized vehicle |
| `401 Unauthorized` | Missing or expired Keycloak token |
| `403 Forbidden` | Token valid but role is insufficient (not `COURIER_ADMIN` or `ADMIN`) |
| `409 Conflict` | Phone number is already registered |
| `429 Too Many Requests` | More than 50 courier creations within 1 hour by the same admin |
| `500 Internal Server Error` | Unexpected failure — retry once; if persists, contact the courier service team |

---

## Integration Examples

### Backend (Node.js / Express proxy — recommended)

The ecommerce backend forwards the admin's Keycloak token to the courier service. The browser never calls the courier service directly.

```typescript
// POST /admin/couriers  (ecommerce backend route)
async function createCourier(req: Request, res: Response) {
  const keycloakToken = req.headers.authorization; // forwarded from browser

  const courierRes = await fetch(
    `${process.env.COURIER_SERVICE_URL}/api/auth/admin/couriers`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': keycloakToken,          // forward as-is
        'X-Forwarded-For': req.ip,               // preserve original IP for audit log
      },
      body: JSON.stringify(req.body),
    }
  );

  const data = await courierRes.json();

  if (!courierRes.ok) {
    return res.status(courierRes.status).json(data); // pass error through
  }

  return res.status(201).json(data);
}
```

### Frontend (React/TypeScript — calls ecommerce backend proxy)

```typescript
// types
type VehicleType = 'BICYCLE' | 'FOOT' | 'SCOOTER' | 'CAR';

interface CreateCourierPayload {
  firstName:       string;
  lastName:        string;
  phone:           string;
  email?:          string;
  profileImageUrl?: string;
  initialPassword: string;
  vehicleType:     VehicleType;
  vehicleMake?:    string;
  vehicleModel?:   string;
  vehicleYear?:    number;
  vehicleColor?:   string;
  licensePlate?:   string;           // required if SCOOTER/CAR
  vehicleRegistrationUrl?: string;
  drivingLicenseNumber?:   string;   // required if SCOOTER/CAR
  drivingLicenseExpiry?:   string;   // YYYY-MM-DD, required if SCOOTER/CAR
  drivingLicenseFrontUrl?: string;
  drivingLicenseBackUrl?:  string;
}

interface CourierSignupResponse {
  courierId:              string;
  firstName:              string;
  lastName:               string;
  phone:                  string;
  accountStatus:          'ACTIVE' | 'PENDING_ACTIVATION' | 'LOCKED' | 'SUSPENDED';
  vehicleType:            VehicleType;
  requiresDrivingLicense: boolean;
}

// api call
async function createCourier(
  payload: CreateCourierPayload
): Promise<CourierSignupResponse> {
  const res = await fetch('/api/admin/couriers', {   // your ecommerce backend route
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',                          // sends session cookie to ecommerce backend
    body: JSON.stringify(payload),
  });

  if (!res.ok) {
    const err = await res.json();
    throw new CourierApiError(err.message, err.status, err.fieldErrors);
  }

  return res.json();
}

// custom error class
class CourierApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public fieldErrors?: Record<string, string>
  ) {
    super(message);
  }
}
```

### Frontend — form validation helper

```typescript
function validateCourierForm(data: Partial<CreateCourierPayload>): Record<string, string> {
  const errors: Record<string, string> = {};
  const motorized = data.vehicleType === 'SCOOTER' || data.vehicleType === 'CAR';

  if (!data.firstName?.trim())       errors.firstName       = 'First name is required';
  if (!data.lastName?.trim())        errors.lastName        = 'Last name is required';
  if (!data.phone?.trim())           errors.phone           = 'Phone number is required';
  if (!data.vehicleType)             errors.vehicleType     = 'Vehicle type is required';
  if (!data.initialPassword)         errors.initialPassword = 'Initial password is required';
  if ((data.initialPassword?.length ?? 0) < 8)
                                     errors.initialPassword = 'Password must be at least 8 characters';

  if (motorized) {
    if (!data.licensePlate?.trim())
      errors.licensePlate = 'License plate is required for motorized vehicles';
    if (!data.drivingLicenseNumber?.trim())
      errors.drivingLicenseNumber = 'Driving licence number is required';
    if (!data.drivingLicenseExpiry)
      errors.drivingLicenseExpiry = 'Driving licence expiry date is required';
    if (data.drivingLicenseExpiry && new Date(data.drivingLicenseExpiry) <= new Date())
      errors.drivingLicenseExpiry = 'Driving licence must not be expired';
  }

  return errors;
}
```

---

## Admin UI — Recommended Form Layout

```
┌─────────────────────────────────────────────────────────────┐
│  Add New Courier                                            │
├─────────────────────────────────────────────────────────────┤
│  PERSONAL DETAILS                                           │
│  First Name *          Last Name *                          │
│  Phone *               Email                                │
│  Profile Image URL     Initial Password *                   │
│                                                             │
│  VEHICLE                                                    │
│  Vehicle Type *  [ BICYCLE | FOOT | SCOOTER | CAR ]         │
│  Make            Model           Year      Color            │
│                                                             │
│  ── shown only when SCOOTER or CAR is selected ──           │
│  License Plate *                                            │
│  Registration Document URL                                  │
│  Driving Licence Number *   Expiry Date *                   │
│  Licence Front Scan URL     Licence Back Scan URL           │
│                                                             │
│  [ Cancel ]                         [ Create Courier ]      │
└─────────────────────────────────────────────────────────────┘
```

**UX notes:**
- Hide the driving licence section entirely when `BICYCLE` or `FOOT` is selected
- Show a password strength indicator — the courier will use this password to log in to the courier mobile app
- After success, display the `courierId` and a reminder to share the initial password with the courier through a secure channel (never show the password again after submission)
- On `409 Conflict`, show: _"A courier with this phone number already exists"_
- On `429 Too Many Requests`, show: _"Too many couriers created recently. Please wait before adding more."_

---

## Courier Login (for reference — handled by courier mobile app)

Once the admin creates a courier, the courier logs into the **courier mobile app** using:

```
POST /api/auth/courier/login
Content-Type: application/json

{
  "phoneNumber": "+994501234567",
  "password":    "Secure#Pass1"
}
```

Response:
```json
{
  "accessToken":  "<jwt>",
  "refreshToken": "<opaque-token>",
  "expiresIn":    900,
  "courierId":    "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

The ecommerce admin panel does **not** need to call this endpoint — it is used exclusively by the courier mobile app.

---

## Questions & Contact

| Topic | Contact |
|---|---|
| Endpoint behaviour / bugs | Courier service team |
| Keycloak role setup | DevOps / IAM team |
| File upload URLs (licence scans) | Storage service team |
| Mobile app integration | Courier mobile team |
