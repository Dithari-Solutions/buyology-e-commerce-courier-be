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
      │  2. Admin fills courier form + selects image files
      │  3. POST /api/auth/admin/couriers   ←── multipart/form-data
      │
      ▼
buyology-courier-service
      │
      ├─ Stores uploaded images to ./uploads/couriers/
      ├─ Creates courier profile (with image URLs)
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
Content-Type: multipart/form-data
```

### Required role

`COURIER_ADMIN` or `ADMIN` in the Keycloak token's `roles` claim.

### Request format — multipart/form-data

The request is a multipart form with one JSON part and up to four image file parts.

| Part name            | Type         | Required | Description |
|----------------------|--------------|----------|-------------|
| `data`               | JSON string  | ✅       | Courier details — see JSON fields table below |
| `profileImage`       | image file   | ❌       | Courier profile photo. JPEG, PNG, or WebP. Max 10 MB. |
| `vehicleRegistration`| image file   | ❌       | Vehicle registration document photo. JPEG, PNG, or WebP. Max 10 MB. |
| `drivingLicenceFront`| image file   | ✅ if motorized | Front of driving licence. Required for `SCOOTER`/`CAR`. |
| `drivingLicenceBack` | image file   | ✅ if motorized | Back of driving licence. Required for `SCOOTER`/`CAR`. |

### `data` part — JSON fields

```json
{
  "firstName":            "John",
  "lastName":             "Smith",
  "phone":                "+994501234567",
  "email":                "john.smith@example.com",
  "initialPassword":      "Secure#Pass1",

  "vehicleType":          "SCOOTER",
  "vehicleMake":          "Honda",
  "vehicleModel":         "PCX125",
  "vehicleYear":          2022,
  "vehicleColor":         "White",
  "licensePlate":         "10 BB 456",

  "drivingLicenseNumber": "DL-7654321",
  "drivingLicenseExpiry": "2029-03-15"
}
```

### JSON field reference

| Field | Type | Required | Notes |
|---|---|---|---|
| `firstName` | string | ✅ | Max 100 chars |
| `lastName` | string | ✅ | Max 100 chars |
| `phone` | string | ✅ | Max 30 chars. Must be unique. Used as the courier's login identifier. |
| `email` | string | ❌ | Valid email format if provided. Max 150 chars. |
| `initialPassword` | string | ✅ | 8–100 chars. Share with the courier out-of-band. Never returned in any response. |
| `vehicleType` | enum | ✅ | `BICYCLE`, `FOOT`, `SCOOTER`, or `CAR` |
| `vehicleMake` | string | ❌ | Max 100 chars. e.g. `Honda` |
| `vehicleModel` | string | ❌ | Max 100 chars. e.g. `PCX125` |
| `vehicleYear` | integer | ❌ | 1900–2100 |
| `vehicleColor` | string | ❌ | Max 50 chars |
| `licensePlate` | string | ✅ if motorized | Max 50 chars. **Required** for `SCOOTER`/`CAR`. Must be globally unique. |
| `drivingLicenseNumber` | string | ✅ if motorized | Max 100 chars. **Required** for `SCOOTER`/`CAR`. |
| `drivingLicenseExpiry` | date | ✅ if motorized | `YYYY-MM-DD`. **Required** for `SCOOTER`/`CAR`. |

### Vehicle types and driving licence rule

| `vehicleType` | Driving licence required? | License plate required? |
|---|---|---|
| `BICYCLE` | ❌ No | ❌ No |
| `FOOT` | ❌ No | ❌ No |
| `SCOOTER` | ✅ Yes | ✅ Yes |
| `CAR` | ✅ Yes | ✅ Yes |

**Frontend rule:**
```
if (vehicleType === 'SCOOTER' || vehicleType === 'CAR') {
  // show and require text fields: licensePlate, drivingLicenseNumber, drivingLicenseExpiry
  // show and require file inputs: drivingLicenceFront, drivingLicenceBack
  // show optional file input:     vehicleRegistration
} else {
  // hide all driving licence fields and file inputs
  // hide licensePlate field
}
```

### Image file constraints

| Constraint | Value |
|---|---|
| Accepted types | `image/jpeg`, `image/png`, `image/webp` |
| Max size per file | 10 MB |
| Max total request size | 50 MB |

Files are stored server-side. The stored URLs are returned in subsequent `GET /api/v1/couriers/{id}` responses.

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

## Endpoint: Get Courier Details

```
GET /api/v1/couriers/{id}
Authorization: Bearer <keycloak-access-token>
```

### Response — `200 OK`

All image URLs are relative paths on the courier service. Prepend `COURIER_SERVICE_URL` to build the full URL for display.

```json
{
  "id":                     "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "firstName":              "John",
  "lastName":               "Smith",
  "phone":                  "+994501234567",
  "email":                  "john.smith@example.com",
  "vehicleType":            "SCOOTER",
  "status":                 "OFFLINE",
  "isAvailable":            false,
  "rating":                 null,
  "profileImageUrl":        "/uploads/couriers/profile/a1b2c3d4.jpg",
  "drivingLicenceImageUrl": "/uploads/couriers/licence/e5f6g7h8.jpg",
  "createdAt":              "2026-03-25T10:00:00Z",
  "updatedAt":              "2026-03-25T10:00:00Z"
}
```

| Field | Notes |
|---|---|
| `profileImageUrl` | Relative URL. `null` if no photo was uploaded. |
| `drivingLicenceImageUrl` | Front face of driving licence. `null` for `BICYCLE`/`FOOT` couriers or if not uploaded. |

Build the full image URL in your frontend:
```javascript
const fullUrl = profileImageUrl
  ? `${process.env.COURIER_SERVICE_URL}${courier.profileImageUrl}`
  : null;
```

---

## Error Responses

All errors follow the same shape:

```json
{
  "status":    409,
  "error":     "Conflict",
  "message":   "Phone number is already registered: +994501234567",
  "path":      "/api/auth/admin/couriers",
  "timestamp": "2026-03-25T10:15:30Z",
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
  "timestamp": "2026-03-25T10:15:30Z",
  "fieldErrors": {
    "vehicleType":          "must not be null",
    "drivingLicenseNumber": "Driving licence details are required for vehicle type: SCOOTER"
  }
}
```

### Error code reference

| HTTP Status | When |
|---|---|
| `400 Bad Request` | Missing required field, invalid format, driving licence not provided for motorized vehicle, or invalid image type/size |
| `401 Unauthorized` | Missing or expired Keycloak token |
| `403 Forbidden` | Token valid but role is insufficient (`COURIER_ADMIN` or `ADMIN` required) |
| `409 Conflict` | Phone number or license plate already registered |
| `429 Too Many Requests` | More than 50 courier creations within 1 hour by the same admin |
| `500 Internal Server Error` | Unexpected failure — retry once; if persists, contact the courier service team |

---

## Integration Examples

### Backend proxy — Spring Boot (Java)

See [ecommerce_backend_courier_proxy_prompt.md](./ecommerce_backend_courier_proxy_prompt.md) for the full Java proxy implementation. The proxy receives multipart from the browser and forwards it to the courier service.

### Backend proxy — Node.js / Express

```typescript
import multer from 'multer';
import FormData from 'form-data';
import fetch from 'node-fetch';

const upload = multer({ storage: multer.memoryStorage() });

router.post(
  '/api/admin/couriers',
  requireAdmin,
  upload.fields([
    { name: 'profileImage',        maxCount: 1 },
    { name: 'vehicleRegistration', maxCount: 1 },
    { name: 'drivingLicenceFront', maxCount: 1 },
    { name: 'drivingLicenceBack',  maxCount: 1 },
  ]),
  async (req, res) => {
    const form = new FormData();

    // JSON part — must be sent with Content-Type: application/json
    form.append('data', JSON.stringify(req.body), {
      contentType: 'application/json',
      filename: 'data.json',
    });

    // File parts — forward each uploaded file as-is
    const files = req.files as Record<string, Express.Multer.File[]>;
    for (const partName of ['profileImage', 'vehicleRegistration', 'drivingLicenceFront', 'drivingLicenceBack']) {
      if (files[partName]?.[0]) {
        const f = files[partName][0];
        form.append(partName, f.buffer, {
          filename: f.originalname,
          contentType: f.mimetype,
        });
      }
    }

    const upstream = await fetch(
      `${process.env.COURIER_SERVICE_URL}/api/auth/admin/couriers`,
      {
        method: 'POST',
        headers: {
          ...form.getHeaders(),
          Authorization: req.headers.authorization,   // forward Keycloak token as-is
          'X-Forwarded-For': req.ip,
        },
        body: form,
      }
    );

    const data = await upstream.json();
    res.status(upstream.status).json(data);
  }
);
```

### Frontend — React / TypeScript

```typescript
type VehicleType = 'BICYCLE' | 'FOOT' | 'SCOOTER' | 'CAR';

interface CreateCourierFields {
  firstName:             string;
  lastName:              string;
  phone:                 string;
  email?:                string;
  initialPassword:       string;
  vehicleType:           VehicleType;
  vehicleMake?:          string;
  vehicleModel?:         string;
  vehicleYear?:          number;
  vehicleColor?:         string;
  licensePlate?:         string;   // required if SCOOTER/CAR
  drivingLicenseNumber?: string;   // required if SCOOTER/CAR
  drivingLicenseExpiry?: string;   // YYYY-MM-DD, required if SCOOTER/CAR
}

interface CreateCourierFiles {
  profileImage?:        File;
  vehicleRegistration?: File;
  drivingLicenceFront?: File;      // required if SCOOTER/CAR
  drivingLicenceBack?:  File;      // required if SCOOTER/CAR
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

async function createCourier(
  fields: CreateCourierFields,
  files:  CreateCourierFiles,
): Promise<CourierSignupResponse> {
  const form = new FormData();

  // JSON fields go in the "data" part
  form.append('data', new Blob([JSON.stringify(fields)], { type: 'application/json' }));

  // Attach files only if provided
  if (files.profileImage)        form.append('profileImage',        files.profileImage);
  if (files.vehicleRegistration) form.append('vehicleRegistration', files.vehicleRegistration);
  if (files.drivingLicenceFront) form.append('drivingLicenceFront', files.drivingLicenceFront);
  if (files.drivingLicenceBack)  form.append('drivingLicenceBack',  files.drivingLicenceBack);

  const res = await fetch('/api/admin/couriers', {   // ecommerce backend proxy
    method: 'POST',
    credentials: 'include',                          // session cookie → ecommerce backend
    body: form,
    // Do NOT set Content-Type manually — the browser sets the boundary automatically
  });

  if (!res.ok) {
    const err = await res.json();
    throw new CourierApiError(err.message, err.status, err.fieldErrors);
  }

  return res.json();
}

class CourierApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public fieldErrors?: Record<string, string>,
  ) {
    super(message);
  }
}
```

### Frontend — form validation helper

```typescript
function validateCourierForm(
  fields: Partial<CreateCourierFields>,
  files:  Partial<CreateCourierFiles>,
): Record<string, string> {
  const errors: Record<string, string> = {};
  const motorized = fields.vehicleType === 'SCOOTER' || fields.vehicleType === 'CAR';

  if (!fields.firstName?.trim())       errors.firstName       = 'First name is required';
  if (!fields.lastName?.trim())        errors.lastName        = 'Last name is required';
  if (!fields.phone?.trim())           errors.phone           = 'Phone number is required';
  if (!fields.vehicleType)             errors.vehicleType     = 'Vehicle type is required';
  if (!fields.initialPassword)         errors.initialPassword = 'Initial password is required';
  if ((fields.initialPassword?.length ?? 0) < 8)
                                       errors.initialPassword = 'Password must be at least 8 characters';

  if (motorized) {
    if (!fields.licensePlate?.trim())
      errors.licensePlate = 'License plate is required for motorized vehicles';
    if (!fields.drivingLicenseNumber?.trim())
      errors.drivingLicenseNumber = 'Driving licence number is required';
    if (!fields.drivingLicenseExpiry)
      errors.drivingLicenseExpiry = 'Driving licence expiry date is required';
    if (fields.drivingLicenseExpiry && new Date(fields.drivingLicenseExpiry) <= new Date())
      errors.drivingLicenseExpiry = 'Driving licence must not be expired';
    if (!files.drivingLicenceFront)
      errors.drivingLicenceFront = 'Driving licence front image is required';
    if (!files.drivingLicenceBack)
      errors.drivingLicenceBack = 'Driving licence back image is required';
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
│  Initial Password *                                         │
│  Profile Photo    [ Choose file ]  (optional, JPEG/PNG/WebP)│
│                                                             │
│  VEHICLE                                                    │
│  Vehicle Type *  [ BICYCLE | FOOT | SCOOTER | CAR ]         │
│  Make            Model           Year      Color            │
│                                                             │
│  ── shown only when SCOOTER or CAR is selected ──           │
│  License Plate *                                            │
│  Vehicle Registration  [ Choose file ]  (optional)         │
│  Driving Licence Number *   Expiry Date *                   │
│  Licence Front *  [ Choose file ]   (required, JPEG/PNG/WebP│
│  Licence Back  *  [ Choose file ]   (required, JPEG/PNG/WebP│
│                                                             │
│  [ Cancel ]                         [ Create Courier ]      │
└─────────────────────────────────────────────────────────────┘
```

**UX notes:**
- Hide the entire driving licence section when `BICYCLE` or `FOOT` is selected
- Show a password strength indicator — the courier uses this password to log in to the courier mobile app
- After success, display the `courierId` and remind the operator to share the initial password through a secure out-of-band channel (never show the password again after submission)
- On `409 Conflict`, show: _"A courier with this phone number already exists"_
- On `429 Too Many Requests`, show: _"Too many couriers created recently. Please wait before adding more."_
- On `400` with `fieldErrors`, map each field error back to its form field and highlight it

---

## Viewing Courier Images

After a courier is created, image URLs are returned by `GET /api/v1/couriers/{id}`:

```typescript
interface CourierResponse {
  id:                     string;
  firstName:              string;
  lastName:               string;
  phone:                  string;
  email:                  string | null;
  vehicleType:            VehicleType;
  status:                 'ACTIVE' | 'OFFLINE' | 'SUSPENDED';
  isAvailable:            boolean;
  rating:                 number | null;
  profileImageUrl:        string | null;   // relative path, e.g. /uploads/couriers/profile/abc.jpg
  drivingLicenceImageUrl: string | null;   // relative path, front face only
  createdAt:              string;          // ISO-8601
  updatedAt:              string;          // ISO-8601
}
```

Build full image URLs by prepending the courier service base URL:

```typescript
const profileSrc = courier.profileImageUrl
  ? `${COURIER_SERVICE_URL}${courier.profileImageUrl}`
  : '/assets/default-avatar.png';

const licenceSrc = courier.drivingLicenceImageUrl
  ? `${COURIER_SERVICE_URL}${courier.drivingLicenceImageUrl}`
  : null;
```

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
| Image storage / CDN migration | Courier service team |
| Mobile app integration | Courier mobile team |
