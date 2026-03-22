package com.buyology.buyology_courier.courier.controller;

import com.buyology.buyology_courier.common.exception.GlobalExceptionHandler;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.domain.enums.VehicleType;
import com.buyology.buyology_courier.courier.dto.request.CreateCourierRequest;
import com.buyology.buyology_courier.courier.dto.request.UpdateCourierStatusRequest;
import com.buyology.buyology_courier.courier.dto.response.CourierResponse;
import com.buyology.buyology_courier.courier.exception.CourierNotFoundException;
import com.buyology.buyology_courier.courier.exception.RateLimitExceededException;
import com.buyology.buyology_courier.auth.service.AdminAuditService;
import com.buyology.buyology_courier.courier.security.CourierSecurityService;
import com.buyology.buyology_courier.courier.service.CourierService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller unit tests using standalone MockMvc (no Spring context).
 *
 * Spring Security annotations (@PreAuthorize, isOwner) are not enforced here
 * because standaloneSetup bypasses the security filter chain. Security rules are
 * covered separately in CourierSecurityService and via integration tests.
 * These tests focus on: request validation, response shape, and error mapping.
 */
@ExtendWith(MockitoExtension.class)
class CourierControllerTest {

    @Mock CourierService         courierService;
    @Mock CourierSecurityService courierSecurity;
    @Mock AdminAuditService      adminAuditService;

    private MockMvc    mockMvc;
    private ObjectMapper objectMapper;
    private static final UUID COURIER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new CourierController(courierService, adminAuditService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── POST /api/v1/couriers ──────────────────────────────────────────────────

    @Test
    void create_returns_201() throws Exception {
        var request = CreateCourierRequest.builder()
                .firstName("Ada").lastName("Lovelace")
                .phone("+1234567890").vehicleType(VehicleType.BICYCLE).build();

        when(courierService.create(any())).thenReturn(courierResponse());

        mockMvc.perform(post("/api/v1/couriers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(COURIER_ID.toString()));
    }

    @Test
    void create_returns_400_when_vehicleType_missing() throws Exception {
        String body = """
                {"firstName":"Ada","lastName":"Lovelace","phone":"+123"}
                """;

        mockMvc.perform(post("/api/v1/couriers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.vehicleType").exists());
    }

    @Test
    void create_returns_400_when_profileImageUrl_invalid() throws Exception {
        String body = """
                {"firstName":"Ada","lastName":"Lovelace","phone":"+123",
                 "vehicleType":"BICYCLE","profileImageUrl":"not-a-url"}
                """;

        mockMvc.perform(post("/api/v1/couriers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.profileImageUrl").exists());
    }

    // ── GET /api/v1/couriers/{id} ──────────────────────────────────────────────

    @Test
    void findById_returns_200() throws Exception {
        when(courierService.findById(COURIER_ID)).thenReturn(courierResponse());

        mockMvc.perform(get("/api/v1/couriers/{id}", COURIER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COURIER_ID.toString()));
    }

    @Test
    void findById_returns_404_with_generic_message() throws Exception {
        when(courierService.findById(COURIER_ID)).thenThrow(new CourierNotFoundException());

        mockMvc.perform(get("/api/v1/couriers/{id}", COURIER_ID))
                .andExpect(status().isNotFound())
                // Must NOT contain a UUID in the message — information leakage check
                .andExpect(jsonPath("$.message").value("The requested courier was not found."));
    }

    // ── PATCH /api/v1/couriers/{id}/status ────────────────────────────────────

    @Test
    void updateStatus_returns_200() throws Exception {
        when(courierService.updateStatus(any(), any())).thenReturn(courierResponse());

        mockMvc.perform(patch("/api/v1/couriers/{id}/status", COURIER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateCourierStatusRequest(CourierStatus.ACTIVE))))
                .andExpect(status().isOk());
    }

    // ── Rate limit ─────────────────────────────────────────────────────────────

    @Test
    void recordLocation_returns_429_when_rate_limited() throws Exception {
        when(courierService.recordLocation(any(), any()))
                .thenThrow(new RateLimitExceededException("Rate limit exceeded."));

        String body = """
                {"latitude":37.7749,"longitude":-122.4194}
                """;

        mockMvc.perform(post("/api/v1/couriers/{id}/locations", COURIER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    // ── DELETE ─────────────────────────────────────────────────────────────────

    @Test
    void delete_returns_204() throws Exception {
        mockMvc.perform(delete("/api/v1/couriers/{id}", COURIER_ID))
                .andExpect(status().isNoContent());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private CourierResponse courierResponse() {
        return new CourierResponse(
                COURIER_ID, "Ada", "Lovelace", "+1234567890", "ada@test.com",
                VehicleType.BICYCLE, CourierStatus.ACTIVE, true,
                new BigDecimal("4.8"), null, Instant.now(), Instant.now()
        );
    }
}
