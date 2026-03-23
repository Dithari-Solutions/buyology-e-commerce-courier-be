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
     │  Cookie / session auth (existing admin auth)
     │
     ▼
Ecommerce Backend  ──────────────────────────────────→  buyology-courier-service
                    POST /api/auth/admin/couriers
                    Authorization: Bearer <admin Keycloak JWT forwarded>
                    Content-Type: application/json
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
courier.service.timeout-ms=${COURIER_SERVICE_TIMEOUT_MS:5000}
```

---

### 3. Create `CourierServiceClient` (WebClient wrapper)

Create a new file: `src/main/java/.../courier/CourierServiceClient.java`

```java
@Component
@Slf4j
public class CourierServiceClient {

    private final WebClient webClient;

    @Value("${courier.service.timeout-ms:5000}")
    private long timeoutMs;

    public CourierServiceClient(@Value("${courier.service.url}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Forward a courier creation request to the courier service.
     *
     * @param request       the courier signup payload
     * @param bearerToken   the admin's Keycloak JWT (full "Bearer <token>" header value)
     * @param clientIp      original client IP — forwarded for audit log accuracy
     * @return ResponseEntity with the courier service response body and status code
     */
    public ResponseEntity<String> createCourier(
            Object request,
            String bearerToken,
            String clientIp
    ) {
        try {
            return webClient.post()
                    .uri("/api/auth/admin/couriers")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .header("X-Forwarded-For", clientIp)
                    .bodyValue(request)
                    .retrieve()
                    // Pass through 4xx/5xx responses — don't throw, let the controller handle them
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
     * The request is validated here first, then forwarded to the courier service.
     * The admin's Keycloak JWT is forwarded so the courier service can record
     * which admin performed the action in its audit log.
     *
     * Required role: ADMIN (or COURIER_ADMIN if your Keycloak setup uses that role).
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'COURIER_ADMIN')")
    @Operation(summary = "Create a new courier (admin only)")
    public ResponseEntity<Object> createCourier(
            @Valid @RequestBody CreateCourierRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerToken,
            HttpServletRequest httpRequest
    ) {
        String clientIp = resolveClientIp(httpRequest);

        ResponseEntity<String> upstream = courierServiceClient.createCourier(
                request, bearerToken, clientIp);

        // Parse the raw JSON string from courier service into a typed object
        // so the response is clean JSON, not an escaped string
        try {
            Object parsed = objectMapper.readValue(upstream.getBody(), Object.class);
            return ResponseEntity.status(upstream.getStatusCode()).body(parsed);
        } catch (Exception e) {
            return ResponseEntity.status(upstream.getStatusCode()).body(upstream.getBody());
        }
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

### 5. Create `CreateCourierRequest` DTO

Create a new file: `src/main/java/.../courier/dto/CreateCourierRequest.java`

This mirrors the courier service's `CourierSignupRequest` exactly. Do not remove any fields — the courier service validates them.

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

        @org.hibernate.validator.constraints.URL @Size(max = 2048)
        String profileImageUrl,

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

        @org.hibernate.validator.constraints.URL @Size(max = 2048)
        String vehicleRegistrationUrl,

        // Driving licence — required only for SCOOTER and CAR
        @Size(max = 100) String drivingLicenseNumber,
        LocalDate drivingLicenseExpiry,

        @org.hibernate.validator.constraints.URL @Size(max = 2048)
        String drivingLicenseFrontUrl,

        @org.hibernate.validator.constraints.URL @Size(max = 2048)
        String drivingLicenseBackUrl
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

---

### 7. Add `@EnableWebMvc` / `@EnableMethodSecurity` if not already present

The `@PreAuthorize` annotation requires method security to be enabled. Check your existing config — if you already have `@EnableMethodSecurity` somewhere, skip this.

---

## Expected request/response

### Request

```
POST /api/admin/couriers
Authorization: Bearer <keycloak-token>   ← sent automatically by browser if using httpOnly cookie flow, or attach manually
Content-Type: application/json

{
  "firstName":             "John",
  "lastName":              "Smith",
  "phone":                 "+994501234567",
  "email":                 "john.smith@example.com",
  "initialPassword":       "Secure#Pass1",
  "vehicleType":           "SCOOTER",
  "licensePlate":          "10 BB 456",
  "drivingLicenseNumber":  "DL-7654321",
  "drivingLicenseExpiry":  "2029-03-15"
}
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

### Errors — passed through as-is from courier service

| Status | Meaning |
|--------|---------|
| `400`  | Validation failed — missing fields or driving licence not provided for motorized vehicle |
| `401`  | Admin token expired or missing |
| `403`  | Admin token valid but role is insufficient |
| `409`  | Phone number already registered |
| `429`  | Admin rate limit exceeded (50 couriers/hour) |
| `502`  | Courier service unreachable |

---

## Driving licence rule — apply in frontend form

```
if (vehicleType === 'SCOOTER' || vehicleType === 'CAR') {
    // require: licensePlate, drivingLicenseNumber, drivingLicenseExpiry
} else {
    // hide all driving licence + license plate fields
}
```

---

## Environment variables to set

| Variable | Dev default | Description |
|----------|------------|-------------|
| `COURIER_SERVICE_URL` | `http://localhost:8081` | Internal URL of buyology-courier-service |
| `COURIER_SERVICE_TIMEOUT_MS` | `5000` | Max ms to wait for courier service response |

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
