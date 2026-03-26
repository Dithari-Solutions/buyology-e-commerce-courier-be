# Buyology Courier Service - Module and Job Overview

## 1. Project purpose

This service is a dedicated courier microservice for the Buyology ecosystem. It handles courier registration, profile management, operational status, availability, assignment and tracking, pre-paid instruction processing, and integration events for downstream services.

## 2. High-level architecture

- Spring Boot 3.x application with web layer (REST controllers), JPA repositories, service layer, DTO mappers, and event-driven messaging.
- Postgres database with outbox tables and event forwarding.
- Redis for caching (enabled by `@EnableCaching`).
- Kafka / messaging as event bus (in existing messaging packages).
- Security config via Keycloak/OAuth2 and method-level `@PreAuthorize`.

## 3. Packages / modules and main responsibilities

### `com.buyology.buyology_courier.assignment`
- Manages courier assignment to delivery jobs.
- Contains controller/service/repository for assignment endpoints.
- Jobs done:
  - allocate courier to delivery order
  - compute assignment priority
  - cancel or reassign deliveries

### `com.buyology.buyology_courier.auth`
- Authentication and admin claim mapping.
- Handles OAuth2 JWT validation and user principal extraction.
- Jobs done:
  - validate JWT tokens
  - map user roles and scopes for admin/courier flows
  - integrate with `SecurityConfig` for role-based access

### `com.buyology.buyology_courier.common`
- Shared types, constants, utilities.
- Response wrappers, exception base classes, validation helpers.
- Jobs done:
  - common DTOs used across modules
  - shared error handling and exception translation

### `com.buyology.buyology_courier.config`
- Spring configuration classes.
- Includes `AsyncConfig`, `AuthConfig`, `RedisConfig`, `RequestIdFilter`, `SecurityConfig`, `EcommerceJwtProperties`.
- Jobs done:
  - custom HTTP request ID filter for tracing
  - security setup for `@EnableMethodSecurity` and authorization rules
  - configuration binding for JWT properties and external service URLs
  - caching and async thread pools

### `com.buyology.buyology_courier.courier`
- Central courier domain module.
- Subpackages: `controller`, `domain`, `dto`, `event`, `exception`, `job`, `mapper`, `messaging`, `repository`, `security`, `service`.
- Jobs done:
  - CRUD for courier entity (create, read, update, delete/soft-delete)
  - status and availability updates
  - upload and manage profile images and driving license images
  - dispatch courier onboarding and update events (`CourierCreatedEvent`, `CourierUpdatedEvent`, etc.)
  - outbox event publishing for asynchronous integration
  - validation rules (e.g., motorized vehicle license requirements)
  - conversion between entity and API DTOs using mappers (MapStruct or manual)

### `com.buyology.buyology_courier.delivery`
- Delivery domain logic and workflows.
- Subpackages: `domain`, `dto`, `event`, `mapper`, `repository`, `service`.
- Jobs done:
  - create and manage delivery lifecycle (pending, accepted, in-progress, completed, canceled)
  - calculate delivery cost based on pricing, distance, and courier availability
  - link courier and order data for tracking
  - emit delivery status events for consumers

### `com.buyology.buyology_courier.earnings`
- Courier earnings domain.
- Contains domain objects for earning events/reporting.
- Jobs done:
  - track courier income per delivery
  - calculate weekly/monthly earnings reports
  - store cut, fees, and wallet transactions

### `com.buyology.buyology_courier.messaging`
- Messaging integration layer.
- Includes `event`, `dto`, `mapper`, `repository`, `service`.
- Jobs done:
  - enqueue and process domain events through message broker
  - manage retry, poison-pill, and event transformations
  - outbox/publish patterns for reliable delivery to external systems

### `com.buyology.buyology_courier.notification`
- Notification and alert subsystem.
- Jobs done:
  - send push/email/SMS notifications for delivery status updates
  - track read/unread notification state
  - integrate with notification queue and templates

### `com.buyology.buyology_courier.pricing`
- Pricing rules and calc domain.
- Jobs done:
  - define distance/time/courier rate pricing model
  - apply surge and discounts
  - expose pricing references to delivery business logic

### `com.buyology.buyology_courier.tracking`
- Live location and delivery tracking.
- Subpackages: `domain`, `dto`, `event`, `mapper`, `repository`, `service`.
- Jobs done:
  - update courier geolocation entries, route history
  - provide location query API for map display and ETA calculations
  - integrate tracking events with notifications and delivery status

### `com.buyology.buyology_courier.util` (currently empty)
- Placeholder for utility helpers or shared cross-cutting utilities.

## 4. Key cross-module jobs

### Security
- `com.buyology.buyology_courier.config.SecurityConfig` has method security and resource protections.
- `@PreAuthorize` is used in controllers (`ADMIN`, `COURIER_ADMIN`, etc.).

### Error and exception handling
- Centralized error translation uses `common.exception` and module-specific exceptions.
- HTTP status mapping done at controller advice level (likely in `common` package).

### Data persistence
- JPA entities in `domain` subpackages and Spring Data repositories in `repository`.
- Migration scripts under `src/main/resources/db/migration/`.
- Outbox events table in V2 migration.

### Integration / proxy (e-commerce backend to courier service)
- This repo focuses on courier service operations.
- Proxy requirements in `api_handoff/ecommerce_backend_courier_proxy_prompt.md` describe how an e-commerce backend should forward admin requests as multipart or JSON to this service.

## 5. Important configuration/values

- `src/main/resources/application.properties` (and `-dev`, `-prod`) for environment-specific settings
- `courier.service.url` and `courier.service.timeout-ms`
- `spring.servlet.multipart.max-file-size=10MB`, `spring.servlet.multipart.max-request-size=50MB`
- JWT configuration for service-to-service auth with RSA private key in environment variable `COURIER_SERVICE_PRIVATE_KEY`

## 6. Tests coverage by module

- `src/test/java/com/buyology/buyology_courier/courier/service/CourierServiceImplTest.java`
- `src/test/java/com/buyology/buyology_courier/courier/controller/CourierControllerTest.java`
- `src/test/java/com/buyology/buyology_courier/courier/repository/CourierRepositoryTest.java`
- Additional tests for assignment/delivery/notification under respective packages.

## 7. How to use / verify

1. Build: `./mvnw clean package`
2. Run (local): `./mvnw spring-boot:run`
3. Health checks: `/actuator/health`
4. API call flow:
   - Courier create/update flows from admin via e-commerce proxy to `/api/v1/couriers` endpoints
   - Delivery event flows to assignment/tracking/messaging subservices

---

> NOTE: Document is a single source of truth. Update this file when module responsibilities change or new packages are introduced.
