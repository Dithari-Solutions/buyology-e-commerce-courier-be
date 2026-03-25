# Prompt: Implement Courier Proxy in Ecommerce Backend

Copy and paste this entire prompt into the ecommerce backend project.

---

## Context

We have a separate microservice called `buyology-courier-service` running at an internal URL (configured via env var `COURIER_SERVICE_URL`, default `http://localhost:8081`). Couriers cannot self-register — only an admin can create a courier account.

The architecture is:

```
Admin Browser
     │
     │  /api/admin/couriers/**   (this service — ecommerce backend)
     │  Cookie/session auth
     │
     ▼
Ecommerce Backend  ──────────────────────────────────→  buyology-courier-service
                    /api/auth/admin/couriers  (create)
                    /api/v1/couriers/**       (read / update / delete)
                    Authorization: Bearer <admin Keycloak JWT forwarded>
```

The admin's Keycloak JWT must be forwarded as-is to the courier service. The admin browser must never call the courier service directly.

---

## Endpoints to proxy

| Ecommerce backend route | Courier service route | Notes |
|---|---|---|
| `POST   /api/admin/couriers` | `POST /api/auth/admin/couriers` | **multipart/form-data** — full onboarding with credentials |
| `GET    /api/admin/couriers` | `GET /api/v1/couriers` | List couriers with optional filters |
| `GET    /api/admin/couriers/{id}` | `GET /api/v1/couriers/{id}` | Get courier by ID, includes image URLs |
| `PATCH  /api/admin/couriers/{id}` | `PATCH /api/v1/couriers/{id}` | **multipart/form-data** — update profile fields and/or images |
| `PATCH  /api/admin/couriers/{id}/status` | `PATCH /api/v1/couriers/{id}/status` | JSON body — update operational status |
| `PATCH  /api/admin/couriers/{id}/availability` | `PATCH /api/v1/couriers/{id}/availability` | JSON body — toggle availability |
| `DELETE /api/admin/couriers/{id}` | `DELETE /api/v1/couriers/{id}` | Soft delete |

---

## What to implement

### 1. Add dependency (if not already present)

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Gradle:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-webflux'
```

Also add multipart size limits:
```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=50MB
```

---

### 2. Add configuration properties

```properties
courier.service.url=${COURIER_SERVICE_URL:http://localhost:8081}
courier.service.timeout-ms=${COURIER_SERVICE_TIMEOUT_MS:10000}
```

---

### 3. Create `CourierServiceClient`

`src/main/java/.../courier/CourierServiceClient.java`

```java
@Component
@Slf4j
public class CourierServiceClient {

    private final WebClient webClient;

    @Value("${courier.service.timeout-ms:10000}")
    private long timeoutMs;

    public CourierServiceClient(@Value("${courier.service.url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /** Forward a multipart request (create or update). */
    public ResponseEntity<String> forwardMultipart(
            String uri,
            MultiValueMap<String, HttpEntity<?>> body,
            String bearerToken,
            String clientIp
    ) {
        return execute(
                webClient.post()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .bodyValue(body)
        );
    }

    /** Forward a multipart PATCH request. */
    public ResponseEntity<String> forwardMultipartPatch(
            String uri,
            MultiValueMap<String, HttpEntity<?>> body,
            String bearerToken,
            String clientIp
    ) {
        return execute(
                webClient.patch()
                        .uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .bodyValue(body)
        );
    }

    /** Forward a JSON body request (POST/PATCH). */
    public ResponseEntity<String> forwardJson(
            String method,
            String uri,
            Object body,
            String bearerToken,
            String clientIp
    ) {
        WebClient.RequestBodySpec spec = switch (method.toUpperCase()) {
            case "POST"  -> webClient.post().uri(uri);
            case "PATCH" -> webClient.patch().uri(uri);
            default      -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return execute(
                spec.header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .header("X-Forwarded-For", clientIp)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
        );
    }

    /** Forward a no-body request (GET / DELETE). */
    public ResponseEntity<String> forwardNoBody(
            String method,
            String uri,
            String queryString,
            String bearerToken,
            String clientIp
    ) {
        String fullUri = (queryString != null && !queryString.isBlank()) ? uri + "?" + queryString : uri;
        WebClient.RequestHeadersSpec<?> spec = switch (method.toUpperCase()) {
            case "GET"    -> webClient.get().uri(fullUri);
            case "DELETE" -> webClient.delete().uri(fullUri);
            default       -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return execute(
                spec.header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .header("X-Forwarded-For", clientIp)
        );
    }

    // ── shared execute ────────────────────────────────────────────────────────

    private ResponseEntity<String> execute(WebClient.RequestHeadersSpec<?> spec) {
        try {
            return spec.retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            r -> r.bodyToMono(String.class)
                                    .map(b -> new CourierServiceException(r.statusCode().value(), b)))
                    .toEntity(String.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
        } catch (CourierServiceException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(ex.getBody());
        } catch (Exception ex) {
            log.error("Courier service call failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"status\":502,\"error\":\"Bad Gateway\"," +
                          "\"message\":\"Courier service is temporarily unavailable.\"}");
        }
    }
}
```

Exception class (same package):

```java
public class CourierServiceException extends RuntimeException {
    private final int statusCode;
    private final String body;

    public CourierServiceException(int statusCode, String body) {
        super("Courier service returned " + statusCode);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody()    { return body; }
}
```

---

### 4. Create `AdminCourierController`

`src/main/java/.../courier/AdminCourierController.java`

```java
@RestController
@RequestMapping("/api/admin/couriers")
@RequiredArgsConstructor
@Tag(name = "Admin — Couriers", description = "Admin proxy to buyology-courier-service.")
public class AdminCourierController {

    private final CourierServiceClient courierServiceClient;
    private final ObjectMapper         objectMapper;

    // ── POST /api/admin/couriers ───────────────────────────────────────────────
    // Proxies → POST /api/auth/admin/couriers
    //
    // multipart/form-data parts:
    //   "data"                — JSON (CourierSignupRequest fields, see DTO section)
    //   "profileImage"        — profile photo       (JPEG/PNG/WebP, ≤10 MB, optional)
    //   "vehicleRegistration" — registration doc    (JPEG/PNG/WebP, ≤10 MB, optional)
    //   "drivingLicenceFront" — licence front image (JPEG/PNG/WebP, ≤10 MB, required for SCOOTER/CAR)
    //   "drivingLicenceBack"  — licence back image  (JPEG/PNG/WebP, ≤10 MB, required for SCOOTER/CAR)

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Create a new courier — multipart form")
    public ResponseEntity<Object> createCourier(
            @RequestPart("data") String dataJson,
            @RequestPart(value = "profileImage",        required = false) MultipartFile profileImage,
            @RequestPart(value = "vehicleRegistration", required = false) MultipartFile vehicleRegistration,
            @RequestPart(value = "drivingLicenceFront", required = false) MultipartFile drivingLicenceFront,
            @RequestPart(value = "drivingLicenceBack",  required = false) MultipartFile drivingLicenceBack,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) throws IOException {
        MultiValueMap<String, HttpEntity<?>> body = new LinkedMultiValueMap<>();
        body.add("data", new HttpEntity<>(dataJson, jsonHeaders()));
        addFilePart(body, "profileImage",        profileImage);
        addFilePart(body, "vehicleRegistration", vehicleRegistration);
        addFilePart(body, "drivingLicenceFront", drivingLicenceFront);
        addFilePart(body, "drivingLicenceBack",  drivingLicenceBack);

        return parsed(courierServiceClient.forwardMultipart(
                "/api/auth/admin/couriers", body, bearerToken, clientIp(httpRequest)));
    }

    // ── GET /api/admin/couriers ────────────────────────────────────────────────
    // Proxies → GET /api/v1/couriers
    // Query params: status, vehicleType, isAvailable, page, size, sort  (forwarded as-is)

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "List couriers with optional filters")
    public ResponseEntity<Object> listCouriers(
            HttpServletRequest httpRequest,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken
    ) {
        return parsed(courierServiceClient.forwardNoBody(
                "GET", "/api/v1/couriers",
                httpRequest.getQueryString(), bearerToken, clientIp(httpRequest)));
    }

    // ── GET /api/admin/couriers/{id} ───────────────────────────────────────────
    // Proxies → GET /api/v1/couriers/{id}
    // Response includes profileImageUrl and drivingLicenceImageUrl (relative paths)

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Get courier by ID — includes image URLs")
    public ResponseEntity<Object> getCourier(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) {
        return parsed(courierServiceClient.forwardNoBody(
                "GET", "/api/v1/couriers/" + id,
                null, bearerToken, clientIp(httpRequest)));
    }

    // ── PATCH /api/admin/couriers/{id} ─────────────────────────────────────────
    // Proxies → PATCH /api/v1/couriers/{id}
    //
    // multipart/form-data parts (all optional — only provided fields are updated):
    //   "data"               — JSON (UpdateCourierRequest fields, see DTO section)
    //   "profileImage"       — new profile photo        (JPEG/PNG/WebP, ≤10 MB)
    //   "drivingLicenceImage"— new driving licence image (JPEG/PNG/WebP, ≤10 MB)

    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Update courier profile fields and/or images — multipart form")
    public ResponseEntity<Object> updateCourier(
            @PathVariable UUID id,
            @RequestPart("data") String dataJson,
            @RequestPart(value = "profileImage",        required = false) MultipartFile profileImage,
            @RequestPart(value = "drivingLicenceImage", required = false) MultipartFile drivingLicenceImage,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) throws IOException {
        MultiValueMap<String, HttpEntity<?>> body = new LinkedMultiValueMap<>();
        body.add("data", new HttpEntity<>(dataJson, jsonHeaders()));
        addFilePart(body, "profileImage",        profileImage);
        addFilePart(body, "drivingLicenceImage", drivingLicenceImage);

        return parsed(courierServiceClient.forwardMultipartPatch(
                "/api/v1/couriers/" + id, body, bearerToken, clientIp(httpRequest)));
    }

    // ── PATCH /api/admin/couriers/{id}/status ──────────────────────────────────
    // Proxies → PATCH /api/v1/couriers/{id}/status
    // Body: { "status": "ACTIVE" | "OFFLINE" | "SUSPENDED" }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Update courier operational status")
    public ResponseEntity<Object> updateStatus(
            @PathVariable UUID id,
            @RequestBody Object body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) {
        return parsed(courierServiceClient.forwardJson(
                "PATCH", "/api/v1/couriers/" + id + "/status",
                body, bearerToken, clientIp(httpRequest)));
    }

    // ── PATCH /api/admin/couriers/{id}/availability ────────────────────────────
    // Proxies → PATCH /api/v1/couriers/{id}/availability
    // Body: { "available": true | false }

    @PatchMapping("/{id}/availability")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Toggle courier availability")
    public ResponseEntity<Object> updateAvailability(
            @PathVariable UUID id,
            @RequestBody Object body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) {
        return parsed(courierServiceClient.forwardJson(
                "PATCH", "/api/v1/couriers/" + id + "/availability",
                body, bearerToken, clientIp(httpRequest)));
    }

    // ── DELETE /api/admin/couriers/{id} ────────────────────────────────────────
    // Proxies → DELETE /api/v1/couriers/{id}
    // Returns 204 No Content

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Soft-delete a courier")
    public ResponseEntity<Object> deleteCourier(
            @PathVariable UUID id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) {
        return parsed(courierServiceClient.forwardNoBody(
                "DELETE", "/api/v1/couriers/" + id,
                null, bearerToken, clientIp(httpRequest)));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void addFilePart(MultiValueMap<String, HttpEntity<?>> body,
                              String partName, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) return;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                file.getContentType() != null ? file.getContentType() : "application/octet-stream"));
        headers.setContentDispositionFormData(partName, file.getOriginalFilename());
        body.add(partName, new HttpEntity<>(file.getResource(), headers));
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<Object> parsed(ResponseEntity<String> upstream) {
        try {
            Object parsed = objectMapper.readValue(upstream.getBody(), Object.class);
            return ResponseEntity.status(upstream.getStatusCode()).body(parsed);
        } catch (Exception e) {
            return ResponseEntity.status(upstream.getStatusCode()).body(upstream.getBody());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
```

---

### 5. DTOs

#### `CreateCourierRequest` — used as the `"data"` JSON part of `POST /api/admin/couriers`

```java
@Builder
public record CreateCourierRequest(

        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 30)  String phone,
        @Email    @Size(max = 150) String email,

        @NotBlank @Size(min = 8, max = 100) String initialPassword,

        @NotNull VehicleType vehicleType,

        @Size(max = 100) String vehicleMake,
        @Size(max = 100) String vehicleModel,
        @Min(1900) @Max(2100) Integer vehicleYear,
        @Size(max = 50) String vehicleColor,
        @Size(max = 50) String licensePlate,          // required for SCOOTER/CAR

        @Size(max = 100) String drivingLicenseNumber, // required for SCOOTER/CAR
        LocalDate drivingLicenseExpiry                // required for SCOOTER/CAR
) {}
```

#### `UpdateCourierRequest` — used as the `"data"` JSON part of `PATCH /api/admin/couriers/{id}`

All fields are optional — only non-null values are applied.

```java
@Builder
public record UpdateCourierRequest(
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Email @Size(max = 150) String email,
        VehicleType vehicleType
) {}
```

#### `VehicleType` enum (used by both DTOs)

```java
public enum VehicleType { BICYCLE, FOOT, SCOOTER, CAR }
```

---

### 6. SecurityConfig

No path-based rule change needed — `@PreAuthorize` on each method handles access control. Verify `@EnableMethodSecurity` is present on your security config class.

---

## Request / Response Reference

### `POST /api/admin/couriers` — Create courier

**Request** — `multipart/form-data`:

| Part | Type | Required | Notes |
|---|---|---|---|
| `data` | JSON string | ✅ | Fields from `CreateCourierRequest` above |
| `profileImage` | image file | ❌ | JPEG, PNG, or WebP — max 10 MB |
| `vehicleRegistration` | image file | ❌ | JPEG, PNG, or WebP — max 10 MB |
| `drivingLicenceFront` | image file | ✅ if SCOOTER/CAR | JPEG, PNG, or WebP — max 10 MB |
| `drivingLicenceBack` | image file | ✅ if SCOOTER/CAR | JPEG, PNG, or WebP — max 10 MB |

**Success `201 Created`**:
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

---

### `GET /api/admin/couriers/{id}` — Get courier details

**Success `200 OK`**:
```json
{
  "id":                     "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "firstName":              "John",
  "lastName":               "Smith",
  "phone":                  "+994501234567",
  "email":                  "john@example.com",
  "vehicleType":            "SCOOTER",
  "status":                 "OFFLINE",
  "isAvailable":            false,
  "rating":                 null,
  "profileImageUrl":        "/uploads/couriers/profile/a1b2c3.jpg",
  "drivingLicenceImageUrl": "/uploads/couriers/licence/d4e5f6.jpg",
  "createdAt":              "2026-03-25T10:00:00Z",
  "updatedAt":              "2026-03-25T10:00:00Z"
}
```

`profileImageUrl` and `drivingLicenceImageUrl` are relative paths on the courier service. Build full URLs for display:
```javascript
const src = courier.profileImageUrl
  ? `${process.env.COURIER_SERVICE_URL}${courier.profileImageUrl}`
  : null;
```

---

### `PATCH /api/admin/couriers/{id}` — Update courier

**Request** — `multipart/form-data`:

| Part | Type | Required | Notes |
|---|---|---|---|
| `data` | JSON string | ✅ | Fields from `UpdateCourierRequest` above — omit or null any field to leave it unchanged |
| `profileImage` | image file | ❌ | Replaces existing profile photo |
| `drivingLicenceImage` | image file | ❌ | Replaces existing driving licence image |

**Success `200 OK`** — same shape as GET response above.

---

### `PATCH /api/admin/couriers/{id}/status` — Update status

**Request** — `application/json`:
```json
{ "status": "ACTIVE" }
```
Valid values: `ACTIVE`, `OFFLINE`, `SUSPENDED`

---

### `PATCH /api/admin/couriers/{id}/availability` — Toggle availability

**Request** — `application/json`:
```json
{ "available": true }
```
Availability can only be set to `true` when status is `ACTIVE`. The courier service enforces this.

---

### `DELETE /api/admin/couriers/{id}` — Soft delete

**Success `204 No Content`** — no body.

---

### `GET /api/admin/couriers` — List couriers

**Query parameters** (all optional, forwarded as-is to courier service):

| Param | Type | Example |
|---|---|---|
| `status` | enum | `ACTIVE`, `OFFLINE`, `SUSPENDED` |
| `vehicleType` | enum | `BICYCLE`, `FOOT`, `SCOOTER`, `CAR` |
| `isAvailable` | boolean | `true` |
| `page` | integer | `0` |
| `size` | integer | `20` |
| `sort` | string | `createdAt,desc` |

**Success `200 OK`** — paginated list:
```json
{
  "content": [ /* CourierResponse objects */ ],
  "totalElements": 42,
  "totalPages": 3,
  "size": 20,
  "number": 0
}
```

---

## Error responses — passed through as-is

| Status | When |
|---|---|
| `400` | Validation failed — missing required field, invalid format, driving licence not provided for motorized vehicle, or invalid image type/size |
| `401` | Admin token expired or missing |
| `403` | Token valid but role insufficient (`COURIER_ADMIN` or `ADMIN` required) |
| `409` | Phone number or license plate already registered |
| `429` | More than 50 courier creations per hour by the same admin |
| `502` | Courier service unreachable |

---

## Driving licence rule — apply in frontend form

```javascript
const motorized = vehicleType === 'SCOOTER' || vehicleType === 'CAR';

if (motorized) {
  // require fields:      licensePlate, drivingLicenseNumber, drivingLicenseExpiry
  // require file inputs: drivingLicenceFront, drivingLicenceBack
} else {
  // hide all driving licence fields and file inputs
}
```

---

## Environment variables

| Variable | Dev default | Description |
|---|---|---|
| `COURIER_SERVICE_URL` | `http://localhost:8081` | Internal URL of buyology-courier-service |
| `COURIER_SERVICE_TIMEOUT_MS` | `10000` | Max ms to wait for courier service response |

Docker Compose:
```yaml
environment:
  COURIER_SERVICE_URL: http://buyology-courier-service:8081
```

---

## What NOT to do

- Do not call the courier service from the browser — this proxy is the only caller
- Do not add `COURIER_SERVICE_URL` to any frontend config or `.env` file
- Do not catch and hide errors from the courier service — pass the status code and body through
- Do not send image URLs as JSON fields — images must always be sent as multipart file parts

---

## Service-to-service authentication — RSA-256 JWT

The courier service validates every request using an RSA-256 JWT that **you generate and sign** with your private key. The courier service holds only the corresponding public key — no shared secret to sync.

### How it works

```
Ecommerce Backend                       Courier Service
─────────────────                       ───────────────
RSA private key  ──signs JWT──────────► RSA public key (in repo)
(GitHub secret)                         verifies signature ✓
```

### Step 1 — Generate a key pair (run once, locally)

```bash
# Private key — keep this secret (goes into your GitHub secrets)
openssl genrsa -out courier-private.pem 2048

# Public key — not sensitive (send this file to the courier team to commit)
openssl rsa -in courier-private.pem -pubout -out courier-public.pem
```

Send `courier-public.pem` to the courier service team. They commit it as
`src/main/resources/ecommerce-public.pem` — no secrets involved on their side.

Store `courier-private.pem` content as a GitHub secret: `COURIER_SERVICE_PRIVATE_KEY`.

### Step 2 — Add JJWT dependency

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

Gradle:
```groovy
implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly    'io.jsonwebtoken:jjwt-jackson:0.12.6'
```

### Step 3 — Add configuration property

```properties
# application.properties
courier.service.jwt.private-key=${COURIER_SERVICE_PRIVATE_KEY}
courier.service.jwt.issuer=${COURIER_SERVICE_JWT_ISSUER:buyology-ecommerce-service}
courier.service.jwt.ttl-seconds=${COURIER_SERVICE_JWT_TTL:900}
```

### Step 4 — Create `CourierServiceTokenProvider`

`src/main/java/.../courier/CourierServiceTokenProvider.java`

```java
@Component
@Slf4j
public class CourierServiceTokenProvider {

    private final PrivateKey privateKey;

    @Value("${courier.service.jwt.issuer:buyology-ecommerce-service}")
    private String issuer;

    @Value("${courier.service.jwt.ttl-seconds:900}")
    private long ttlSeconds;

    public CourierServiceTokenProvider(
            @Value("${courier.service.jwt.private-key}") String privateKeyPem
    ) throws Exception {
        this.privateKey = parsePrivateKey(privateKeyPem);
        log.info("[COURIER-TOKEN] RSA private key loaded successfully");
    }

    /**
     * Generates a short-lived RS256 JWT for service-to-service calls to the courier service.
     *
     * @param adminId  the authenticated admin's ID (becomes the {@code sub} claim)
     * @param roles    list of roles to include (e.g. ["COURIER_ADMIN"])
     */
    public String generateToken(String adminId, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(adminId)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }
}
```

Required imports:
```java
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
```

### Step 5 — Use the token in `CourierServiceClient`

Inject `CourierServiceTokenProvider` and generate a token per request:

```java
@Component
@Slf4j
public class CourierServiceClient {

    private final WebClient webClient;
    private final CourierServiceTokenProvider tokenProvider;

    @Value("${courier.service.timeout-ms:10000}")
    private long timeoutMs;

    public CourierServiceClient(
            @Value("${courier.service.url}") String baseUrl,
            CourierServiceTokenProvider tokenProvider
    ) {
        this.webClient    = WebClient.builder().baseUrl(baseUrl).build();
        this.tokenProvider = tokenProvider;
    }

    /** Builds a fresh Bearer token for the given admin. */
    private String bearerToken(String adminId) {
        return "Bearer " + tokenProvider.generateToken(adminId, List.of("COURIER_ADMIN"));
    }

    // ── no-body requests (GET / DELETE) ───────────────────────────────────────

    public ResponseEntity<String> forwardNoBody(
            String method, String uri, String queryString,
            String adminId, String clientIp
    ) {
        String fullUri = (queryString != null && !queryString.isBlank()) ? uri + "?" + queryString : uri;
        WebClient.RequestHeadersSpec<?> spec = switch (method.toUpperCase()) {
            case "GET"    -> webClient.get().uri(fullUri);
            case "DELETE" -> webClient.delete().uri(fullUri);
            default       -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return execute(spec
                .header(HttpHeaders.AUTHORIZATION, bearerToken(adminId))
                .header("X-Forwarded-For", clientIp));
    }

    // ── JSON body requests (PATCH status / availability) ──────────────────────

    public ResponseEntity<String> forwardJson(
            String method, String uri, Object body,
            String adminId, String clientIp
    ) {
        WebClient.RequestBodySpec spec = switch (method.toUpperCase()) {
            case "POST"  -> webClient.post().uri(uri);
            case "PATCH" -> webClient.patch().uri(uri);
            default      -> throw new IllegalArgumentException("Unsupported method: " + method);
        };
        return execute(spec
                .header(HttpHeaders.AUTHORIZATION, bearerToken(adminId))
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body));
    }

    // ── multipart requests (create / update courier) ──────────────────────────

    public ResponseEntity<String> forwardMultipart(
            String uri, MultiValueMap<String, HttpEntity<?>> body,
            String adminId, String clientIp
    ) {
        return execute(webClient.post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(adminId))
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body));
    }

    public ResponseEntity<String> forwardMultipartPatch(
            String uri, MultiValueMap<String, HttpEntity<?>> body,
            String adminId, String clientIp
    ) {
        return execute(webClient.patch().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(adminId))
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body));
    }

    // ── shared execute ────────────────────────────────────────────────────────

    private ResponseEntity<String> execute(WebClient.RequestHeadersSpec<?> spec) {
        try {
            return spec.retrieve()
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            r -> r.bodyToMono(String.class)
                                    .map(b -> new CourierServiceException(r.statusCode().value(), b)))
                    .toEntity(String.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
        } catch (CourierServiceException ex) {
            log.warn("[COURIER-CLIENT] → {} body={}", ex.getStatusCode(), ex.getBody());
            return ResponseEntity.status(ex.getStatusCode()).body(ex.getBody());
        } catch (Exception ex) {
            log.error("Courier service call failed: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"status\":502,\"error\":\"Bad Gateway\"," +
                          "\"message\":\"Courier service is temporarily unavailable.\"}");
        }
    }
}
```

### Step 6 — Update `AdminCourierController` to pass `adminId`

Extract the admin's ID from their session/security context and pass it to the client:

```java
// In each controller method, get the admin ID from the security context:
String adminId = SecurityContextHolder.getContext()
        .getAuthentication().getName();  // returns the principal name / user ID

// Then call the client:
courierServiceClient.forwardNoBody("GET", "/api/v1/couriers", queryString, adminId, clientIp);
```

### Step 7 — Add environment variable to deployment

```yaml
# docker-compose.yml or GitHub Actions secret
COURIER_SERVICE_PRIVATE_KEY: |
  -----BEGIN PRIVATE KEY-----
  MIIEvQ...
  -----END PRIVATE KEY-----
```

As a GitHub secret: set `COURIER_SERVICE_PRIVATE_KEY` to the full content of `courier-private.pem` (including the `-----BEGIN/END-----` lines).

### JWT claims the courier service expects

| Claim | Type | Required | Description |
|---|---|---|---|
| `iss` | string | yes | Must be `buyology-ecommerce-service` |
| `sub` | string | yes | Admin user ID |
| `roles` | string[] | yes | Must include `COURIER_ADMIN` |
| `iat` | number | yes | Issued-at timestamp |
| `exp` | number | yes | Expiry timestamp (recommend 15 min) |

### Key rotation

1. Generate a new key pair with `openssl genrsa`
2. Send the new public key to the courier team — they commit `ecommerce-public.pem` and deploy
3. Update `COURIER_SERVICE_PRIVATE_KEY` in your GitHub secrets and redeploy
4. No coordination on secret values needed — only the public key file needs updating
- Do not hard-code the courier service path prefix — keep it behind `COURIER_SERVICE_URL`
