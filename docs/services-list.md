# Buyology Courier Service - Services List

This document lists all service classes and interfaces currently present in the project, their package location, and short responsibility summaries.

## 1. Auth services

- `com.buyology.buyology_courier.auth.service.AuthService`
  - Interface for authentication-related operations (e.g., login, user details, role checks).

- `com.buyology.buyology_courier.auth.service.impl.AuthServiceImpl`
  - Implementation of `AuthService` that interacts with repository/Keycloak mappings and session state.

- `com.buyology.buyology_courier.auth.service.JwtService`
  - JWT token creation/validation helpers for internal auth flows.

- `com.buyology.buyology_courier.auth.service.AdminAuditService`
  - Tracks admin actions in the system and writes audit events/log entries.

## 2. Courier services

- `com.buyology.buyology_courier.courier.service.CourierService`
  - Interface defining courier business operations (create, update, get, delete, status, availability).

- `com.buyology.buyology_courier.courier.service.CourierLookupService`
  - Lookup support module for getting couriers by filters, UI listing, and non-mutating reads.

- `com.buyology.buyology_courier.courier.service.impl.CourierServiceImpl`
  - Core service implementation. Coordinates repository actions, validations, domain events, and persistence.

## 3. Courier security service

- `com.buyology.buyology_courier.courier.security.CourierSecurityService`
  - Domain security checks specifically for courier operations, like permission guarding and self-access rules.

## 4. Common + storage services

- `com.buyology.buyology_courier.common.storage.FileStorageService`
  - File upload/download and storage helper for courier profile images, license images, and deliverable attachments.

## 5. Test coverage for services

- `src/test/java/com/buyology/buyology_courier/courier/service/CourierServiceImplTest.java`
  - Unit tests for courier service business logic.

## 6. Notes

- If a service is present in other packages, add to this file in the same format.
- The above set is authoritative from `**/*Service*.java` search results at date of generation.
