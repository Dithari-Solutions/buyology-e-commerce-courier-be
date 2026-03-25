# Prompt: Implement Courier Creation Proxy in Ecommerce Backend

Copy and paste this entire prompt into the ecommerce backend project.

---

## Context

We have a separate microservice called `buyology-courier-service` running at an internal URL (configured via env var `COURIER_SERVICE_URL`, default `http://localhost:8081`). Couriers cannot self-register — only an admin can create a courier account.

The architecture is:

```
Admin Browser
     │
     │  POST /api/admin/couriers   (this service — ecommerce backend)
     │  multipart/form-data + Cookie/session auth
     │
     ▼
Ecommerce Backend  ──────────────────────────────────→  buyology-courier-service
                    POST /api/auth/admin/couriers
                    Authorization: Bearer <admin Keycloak JWT forwarded>
                    Content-Type: multipart/form-data
```

The admin's Keycloak JWT must be forwarded as-is to the courier service so the courier service can validate the admin's identity and write it to its audit log. The admin browser must never call the courier service directly.

---

## What to implement

### 1. Add dependency (if not already present)

You need Spring WebFlux `WebClient` for the HTTP proxy call (non-blocking). If the project is servlet-based (Spring MVC), add it without replacing the server:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

Or if using Gradle:
```groovy
implementation 'org.springframework.boot:spring-boot-starter-webflux'
```

---

### 2. Add configuration properties

```properties
# application.properties
courier.service.url=${COURIER_SERVICE_URL:http://localhost:8081}

# Timeout for courier service calls (milliseconds)
courier.service.timeout-ms=${COURIER_SERVICE_TIMEOUT_MS:10000}
```

---

### 3. Create `CourierServiceClient` (WebClient wrapper)

Create a new file: `src/main/java/.../courier/CourierServiceClient.java`

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

    /**
     * Forward a courier creation multipart request to the courier service.
     *
     * @param multipartBody  the MultiValueMap built from the admin's form submission
     * @param bearerToken    the admin's Keycloak JWT (full "Bearer <token>" header value)
     * @param clientIp       original client IP — forwarded for audit log accuracy
     * @return ResponseEntity with the courier service response body and status code
     */
    public ResponseEntity<String> createCourier(
            MultiValueMap<String, HttpEntity<?>> multipartBody,
            String bearerToken,
            String clientIp
    ) {
        try {
            return webClient.post()
                    .uri("/api/auth/admin/couriers")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .header("X-Forwarded-For", clientIp)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(multipartBody)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(body -> new CourierServiceException(
                                            clientResponse.statusCode().value(), body)))
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

Also create the exception class in the same package:

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

Create a new file: `src/main/java/.../courier/AdminCourierController.java`

The controller accepts a multipart form from the admin browser and proxies it to the courier service.

```java
@RestController
@RequestMapping("/api/admin/couriers")
@RequiredArgsConstructor
@Tag(name = "Admin — Couriers", description = "Admin operations for managing courier accounts.")
public class AdminCourierController {

    private final CourierServiceClient courierServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * Create a new courier account.
     *
     * Request format: multipart/form-data
     *   - Part "data"                — JSON string of CreateCourierRequest fields
     *   - Part "profileImage"        — profile photo (JPEG/PNG/WebP, max 10 MB, optional)
     *   - Part "vehicleRegistration" — vehicle registration doc (optional)
     *   - Part "drivingLicenceFront" — driving licence front (required for SCOOTER/CAR)
     *   - Part "drivingLicenceBack"  — driving licence back  (required for SCOOTER/CAR)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Create a new courier (admin only) — multipart form")
    public ResponseEntity<Object> createCourier(
            @RequestPart("data") String dataJson,
            @RequestPart(value = "profileImage",        required = false) MultipartFile profileImage,
            @RequestPart(value = "vehicleRegistration", required = false) MultipartFile vehicleRegistration,
            @RequestPart(value = "drivingLicenceFront", required = false) MultipartFile drivingLicenceFront,
            @RequestPart(value = "drivingLicenceBack",  required = false) MultipartFile drivingLicenceBack,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) throws IOException {
        // Build a multipart body to forward to the courier service
        MultiValueMap<String, HttpEntity<?>> body = new LinkedMultiValueMap<>();
        body.add("data", new HttpEntity<>(dataJson,
                jsonPartHeaders()));
        addFilePart(body, "profileImage",        profileImage);
        addFilePart(body, "vehicleRegistration", vehicleRegistration);
        addFilePart(body, "drivingLicenceFront", drivingLicenceFront);
        addFilePart(body, "drivingLicenceBack",  drivingLicenceBack);

        String clientIp = resolveClientIp(httpRequest);
        ResponseEntity<String> upstream = courierServiceClient.createCourier(body, bearerToken, clientIp);

        try {
            Object parsed = objectMapper.readValue(upstream.getBody(), Object.class);
            return ResponseEntity.status(upstream.getStatusCode()).body(parsed);
        } catch (Exception e) {
            return ResponseEntity.status(upstream.getStatusCode()).body(upstream.getBody());
        }
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

    private HttpHeaders jsonPartHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
```

---

### 5. Create `CreateCourierRequest` DTO (JSON part — no image URLs)

Create a new file: `src/main/java/.../courier/dto/CreateCourierRequest.java`

This mirrors the courier service's `CourierSignupRequest` (text fields only — images come as file parts).

```java
@Builder
public record CreateCourierRequest(

        // Personal details
        @NotBlank @Size(max = 100)
        String firstName,

        @NotBlank @Size(max = 100)
        String lastName,

        @NotBlank @Size(max = 30)
        String phone,

        @Email @Size(max = 150)
        String email,

        // Auth
        @NotBlank @Size(min = 8, max = 100)
        String initialPassword,

        // Vehicle
        @NotNull
        VehicleType vehicleType,

        @Size(max = 100) String vehicleMake,
        @Size(max = 100) String vehicleModel,
        @Min(1900) @Max(2100) Integer vehicleYear,
        @Size(max = 50)  String vehicleColor,
        @Size(max = 50)  String licensePlate,

        // Driving licence text fields (required for SCOOTER / CAR)
        @Size(max = 100) String drivingLicenseNumber,
        LocalDate drivingLicenseExpiry
) {}
```

Also create the `VehicleType` enum in the same package:

```java
public enum VehicleType {
    BICYCLE,
    FOOT,
    SCOOTER,
    CAR
}
```

---

### 6. Ensure SecurityConfig permits the new endpoint correctly

In your existing `SecurityConfig`, the new endpoint `/api/admin/couriers` is already covered by `anyRequest().authenticated()` + the `@PreAuthorize` on the controller. No change needed unless you have explicit path-based rules that need updating.

Also add multipart size limits if not already present:

```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=50MB
```

---

## Expected request/response

### Request

```
POST /api/admin/couriers
Authorization: Bearer <keycloak-token>
Content-Type: multipart/form-data; boundary=----...

------...
Content-Disposition: form-data; name="data"
Content-Type: application/json

{"firstName":"John","lastName":"Smith","phone":"+994501234567",
 "email":"john@example.com","initialPassword":"Secure#Pass1",
 "vehicleType":"SCOOTER","licensePlate":"10 BB 456",
 "drivingLicenseNumber":"DL-7654321","drivingLicenseExpiry":"2029-03-15"}
------...
Content-Disposition: form-data; name="profileImage"; filename="john.jpg"
Content-Type: image/jpeg

<binary image data>
------...
Content-Disposition: form-data; name="drivingLicenceFront"; filename="licence_front.jpg"
Content-Type: image/jpeg

<binary image data>
------...--
```

### Success `201 Created`

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

### GET courier details — `200 OK`

When fetching a courier (`GET /api/v1/couriers/{id}`), the response now includes image URLs:

```json
{
  "id":                    "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "firstName":             "John",
  "lastName":              "Smith",
  "phone":                 "+994501234567",
  "email":                 "john@example.com",
  "vehicleType":           "SCOOTER",
  "status":                "OFFLINE",
  "isAvailable":           false,
  "rating":                null,
  "profileImageUrl":       "/uploads/couriers/profile/a1b2c3.jpg",
  "drivingLicenceImageUrl": "/uploads/couriers/licence/d4e5f6.jpg",
  "createdAt":             "2026-03-25T10:00:00Z",
  "updatedAt":             "2026-03-25T10:00:00Z"
}
```

Image URLs are relative paths on the courier service. Prefix with `COURIER_SERVICE_URL` to build the full URL when displaying in the admin UI:

```javascript
const imageUrl = `${COURIER_SERVICE_URL}${courier.profileImageUrl}`;
```

### Errors — passed through as-is from courier service

| Status | Meaning |
|--------|---------|
| `400`  | Validation failed — missing fields or driving licence not provided for motorized vehicle |
| `400`  | Unsupported image type (only JPEG, PNG, WebP allowed; max 10 MB) |
| `401`  | Admin token expired or missing |
| `403`  | Admin token valid but role is insufficient |
| `409`  | Phone number already registered |
| `429`  | Admin rate limit exceeded (50 couriers/hour) |
| `502`  | Courier service unreachable |

---

## Driving licence rule — apply in frontend form

```javascript
if (vehicleType === 'SCOOTER' || vehicleType === 'CAR') {
    // require: licensePlate, drivingLicenseNumber, drivingLicenseExpiry
    // require file inputs: drivingLicenceFront, drivingLicenceBack
} else {
    // hide all driving licence + license plate fields
    // hide drivingLicenceFront and drivingLicenceBack file inputs
}
```

---

## Environment variables to set

| Variable | Dev default | Description |
|----------|------------|-------------|
| `COURIER_SERVICE_URL` | `http://localhost:8081` | Internal URL of buyology-courier-service |
| `COURIER_SERVICE_TIMEOUT_MS` | `10000` | Max ms to wait for courier service response |

In Docker Compose, use the service name:
```yaml
environment:
  COURIER_SERVICE_URL: http://buyology-courier-service:8081
```

---

## What NOT to do

- Do not store or re-sign the Keycloak JWT — forward it exactly as received in the `Authorization` header
- Do not call the courier service from the browser — this proxy is the only caller
- Do not add the courier service URL to any frontend config or `.env` file
- Do not catch and hide errors from the courier service — pass the status code and body through so the frontend can show the right message
- Do not send image URLs as JSON fields — images must always be sent as multipart file parts
