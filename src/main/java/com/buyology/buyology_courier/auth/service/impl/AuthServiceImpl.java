package com.buyology.buyology_courier.auth.service.impl;

import com.buyology.buyology_courier.auth.domain.CourierCredentials;
import com.buyology.buyology_courier.auth.domain.CourierRefreshToken;
import com.buyology.buyology_courier.auth.domain.CourierVehicleDetails;
import com.buyology.buyology_courier.auth.domain.enums.AccountStatus;
import com.buyology.buyology_courier.auth.dto.request.CourierLoginRequest;
import com.buyology.buyology_courier.auth.dto.request.CourierSignupRequest;
import com.buyology.buyology_courier.auth.dto.request.RefreshTokenRequest;
import com.buyology.buyology_courier.auth.dto.response.AuthResponse;
import com.buyology.buyology_courier.auth.dto.response.CourierSignupResponse;
import com.buyology.buyology_courier.auth.exception.*;
import com.buyology.buyology_courier.auth.repository.CourierCredentialsRepository;
import com.buyology.buyology_courier.auth.repository.CourierRefreshTokenRepository;
import com.buyology.buyology_courier.auth.repository.CourierVehicleDetailsRepository;
import com.buyology.buyology_courier.auth.service.AuthService;
import com.buyology.buyology_courier.auth.service.JwtService;
import com.buyology.buyology_courier.common.outbox.OutboxEvent;
import com.buyology.buyology_courier.common.outbox.OutboxEventRepository;
import com.buyology.buyology_courier.common.outbox.OutboxStatus;
import com.buyology.buyology_courier.courier.domain.Courier;
import com.buyology.buyology_courier.courier.domain.enums.CourierStatus;
import com.buyology.buyology_courier.courier.exception.DuplicatePhoneException;
import com.buyology.buyology_courier.courier.messaging.config.RabbitMQConfig;
import com.buyology.buyology_courier.courier.messaging.event.CourierRegisteredEvent;
import com.buyology.buyology_courier.courier.repository.CourierRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AuthServiceImpl implements AuthService {

    // Failed attempts before temporary lockout
    private static final int MAX_FAILED_ATTEMPTS = 5;
    // Lockout duration in minutes
    private static final int LOCKOUT_MINUTES = 15;
    // Admin rate limit: max courier creations per admin per hour
    private static final int MAX_ADMIN_SIGNUPS_PER_HOUR = 50;

    private final CourierRepository               courierRepository;
    private final CourierCredentialsRepository    credentialsRepository;
    private final CourierVehicleDetailsRepository vehicleDetailsRepository;
    private final CourierRefreshTokenRepository   refreshTokenRepository;
    private final OutboxEventRepository           outboxEventRepository;
    private final PasswordEncoder                 passwordEncoder;
    private final JwtService                      jwtService;
    private final ObjectMapper                    objectMapper;
    private final StringRedisTemplate             stringRedisTemplate;

    @Value("${auth.jwt.access-token-expiry-seconds:900}")
    private long accessTokenExpirySeconds;

    // ── signup ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CourierSignupResponse signup(CourierSignupRequest request,
                                        UUID adminId,
                                        String profileImageUrl,
                                        String vehicleRegistrationUrl,
                                        String drivingLicenceFrontUrl,
                                        String drivingLicenceBackUrl) {
        // Rate limit: prevent a compromised admin account from bulk-creating couriers
        enforceAdminSignupRateLimit(adminId);

        // Phone must be globally unique (couriers table has a unique constraint on phone)
        if (courierRepository.existsByPhoneAndDeletedAtIsNull(request.phone())) {
            throw new DuplicatePhoneException(request.phone());
        }
        if (credentialsRepository.existsByPhoneNumber(request.phone())) {
            throw new DuplicatePhoneException(request.phone());
        }

        // Validate driving licence fields based on vehicle type
        boolean needsLicense = request.vehicleType().requiresDrivingLicense();
        if (needsLicense) {
            if (request.drivingLicenseNumber() == null || request.drivingLicenseNumber().isBlank()
                    || request.drivingLicenseExpiry() == null) {
                throw new DrivingLicenseRequiredException(request.vehicleType());
            }
            if (request.licensePlate() == null || request.licensePlate().isBlank()) {
                throw new IllegalArgumentException(
                        "License plate is required for vehicle type: " + request.vehicleType());
            }
        }

        // 1. Create courier profile — store profile photo + licence front image for quick access
        Courier courier = Courier.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .phone(request.phone())
                .email(request.email())
                .vehicleType(request.vehicleType())
                .profileImageUrl(profileImageUrl)
                .drivingLicenceImageUrl(needsLicense ? drivingLicenceFrontUrl : null)
                .status(CourierStatus.OFFLINE)
                .isAvailable(false)
                .build();
        courier = courierRepository.save(courier);
        log.info("Courier registered via admin signup: id={}, phone={}", courier.getId(), request.phone());

        // 2. Create credentials
        CourierCredentials credentials = CourierCredentials.builder()
                .courierId(courier.getId())
                .phoneNumber(request.phone())
                .passwordHash(passwordEncoder.encode(request.initialPassword()))
                .accountStatus(AccountStatus.ACTIVE)
                .failedLoginAttempts(0)
                .createdByAdminId(adminId)
                .build();
        credentialsRepository.save(credentials);

        // 3. Create vehicle details — full front + back licence URLs stored here
        CourierVehicleDetails vehicleDetails = CourierVehicleDetails.builder()
                .courierId(courier.getId())
                .vehicleType(request.vehicleType())
                .vehicleMake(request.vehicleMake())
                .vehicleModel(request.vehicleModel())
                .vehicleYear(request.vehicleYear())
                .vehicleColor(request.vehicleColor())
                .licensePlate(needsLicense ? request.licensePlate() : null)
                .vehicleRegistrationUrl(vehicleRegistrationUrl)
                .drivingLicenseNumber(needsLicense ? request.drivingLicenseNumber() : null)
                .drivingLicenseExpiry(needsLicense ? request.drivingLicenseExpiry() : null)
                .drivingLicenseFrontUrl(needsLicense ? drivingLicenceFrontUrl : null)
                .drivingLicenseBackUrl(needsLicense ? drivingLicenceBackUrl : null)
                .requiresDrivingLicense(needsLicense)
                .build();
        vehicleDetailsRepository.save(vehicleDetails);

        // 4. Publish registered event via outbox (same transaction)
        saveOutboxEvent(
                RabbitMQConfig.COURIER_REGISTERED_KEY,
                CourierRegisteredEvent.of(
                        courier.getId(), courier.getFirstName(), courier.getLastName(),
                        courier.getPhone(), courier.getEmail(), courier.getVehicleType()
                )
        );

        return new CourierSignupResponse(
                courier.getId(),
                courier.getFirstName(),
                courier.getLastName(),
                courier.getPhone(),
                AccountStatus.ACTIVE,
                courier.getVehicleType(),
                needsLicense
        );
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse login(CourierLoginRequest request, String deviceInfo, String ipAddress) {
        log.info("Login attempt: phone={}", request.phoneNumber());

        CourierCredentials credentials;
        try {
            credentials = credentialsRepository
                    .findByPhoneNumber(request.phoneNumber())
                    // Generic message — never reveal whether the phone exists
                    .orElseThrow(InvalidCredentialsException::new);
            log.info("Credentials found: courierId={}, accountStatus={}", credentials.getCourierId(), credentials.getAccountStatus());
        } catch (InvalidCredentialsException ex) {
            log.info("Login failed — phone not found: phone={}", request.phoneNumber());
            throw ex;
        } catch (Exception ex) {
            log.error("Login failed — DB error looking up credentials: phone={}", request.phoneNumber(), ex);
            throw ex;
        }

        try {
            checkAccountAccessible(credentials);
            log.info("Account accessible: courierId={}", credentials.getCourierId());
        } catch (Exception ex) {
            log.info("Login blocked — account not accessible: courierId={}, reason={}", credentials.getCourierId(), ex.getMessage());
            throw ex;
        }

        if (!passwordEncoder.matches(request.password(), credentials.getPasswordHash())) {
            log.info("Login failed — wrong password: courierId={}", credentials.getCourierId());
            recordFailedAttempt(credentials);
            throw new InvalidCredentialsException();
        }

        // Successful login — reset lockout state
        credentials.setFailedLoginAttempts(0);
        credentials.setLockedUntil(null);
        credentials.setLastLoginAt(Instant.now());
        try {
            credentialsRepository.save(credentials);
        } catch (Exception ex) {
            log.error("Login failed — DB error saving credential state: courierId={}", credentials.getCourierId(), ex);
            throw ex;
        }

        log.info("Password verified, issuing token pair: courierId={}", credentials.getCourierId());
        return issueTokenPair(credentials.getCourierId(), deviceInfo, ipAddress);
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String hash = jwtService.hashToken(request.refreshToken());

        CourierRefreshToken stored = refreshTokenRepository
                .findByTokenHash(hash)
                .orElseThrow(TokenRevokedException::new);

        if (stored.getRevokedAt() != null) {
            throw new TokenRevokedException();
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException();
        }

        // Verify the courier account is still active
        CourierCredentials credentials = credentialsRepository
                .findByCourierId(stored.getCourierId())
                .orElseThrow(TokenRevokedException::new);
        checkAccountAccessible(credentials);

        String newAccessToken = jwtService.generateAccessToken(stored.getCourierId());

        return new AuthResponse(
                newAccessToken,
                request.refreshToken(), // refresh token is NOT rotated on every refresh
                accessTokenExpirySeconds,
                stored.getCourierId()
        );
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        String hash = jwtService.hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            refreshTokenRepository.save(token);
        });
        // If token not found: silent success — idempotent logout
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Throws the appropriate exception if the account cannot log in.
     * Order: SUSPENDED → LOCKED (with time check) → PENDING_ACTIVATION
     */
    private void checkAccountAccessible(CourierCredentials creds) {
        switch (creds.getAccountStatus()) {
            case SUSPENDED -> throw new AccountSuspendedException();
            case PENDING_ACTIVATION -> throw new AccountNotActiveException();
            case LOCKED -> {
                Instant lockedUntil = creds.getLockedUntil();
                if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
                    throw new AccountLockedException(lockedUntil);
                }
                // Lockout has expired — auto-restore to ACTIVE
                creds.setAccountStatus(AccountStatus.ACTIVE);
                creds.setFailedLoginAttempts(0);
                creds.setLockedUntil(null);
                credentialsRepository.save(creds);
            }
            case ACTIVE -> { /* allowed */ }
        }
    }

    /** Increment failed attempts and lock the account if the threshold is reached. */
    private void recordFailedAttempt(CourierCredentials creds) {
        int attempts = creds.getFailedLoginAttempts() + 1;
        creds.setFailedLoginAttempts(attempts);

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            creds.setAccountStatus(AccountStatus.LOCKED);
            creds.setLockedUntil(Instant.now().plusSeconds(LOCKOUT_MINUTES * 60L));
            log.warn("Account locked after {} failed attempts: courierId={}", attempts, creds.getCourierId());
        }

        credentialsRepository.save(creds);
    }

    /** Generate access JWT + refresh token, persist the refresh token hash, return the pair. */
    private AuthResponse issueTokenPair(UUID courierId, String deviceInfo, String ipAddress) {
        String accessToken;
        String rawRefresh;
        String refreshHash;
        try {
            accessToken = jwtService.generateAccessToken(courierId);
            rawRefresh  = jwtService.generateRawRefreshToken();
            refreshHash = jwtService.hashToken(rawRefresh);
            log.info("JWT generated: courierId={}", courierId);
        } catch (Exception ex) {
            log.error("Failed to generate JWT: courierId={}", courierId, ex);
            throw ex;
        }

        try {
            CourierRefreshToken token = CourierRefreshToken.builder()
                    .courierId(courierId)
                    .tokenHash(refreshHash)
                    .expiresAt(jwtService.refreshTokenExpiresAt())
                    .deviceInfo(deviceInfo)
                    .ipAddress(ipAddress)
                    .build();
            refreshTokenRepository.save(token);
            log.info("Refresh token persisted: courierId={}", courierId);
        } catch (Exception ex) {
            log.error("Failed to persist refresh token: courierId={}", courierId, ex);
            throw ex;
        }

        return new AuthResponse(accessToken, rawRefresh, accessTokenExpirySeconds, courierId);
    }

    /**
     * Rate-limits admin signup operations per admin ID per hour using a Redis counter.
     * Prevents a stolen admin token from being used to bulk-register fake couriers.
     * Key TTL is set on first increment so the window resets automatically.
     */
    private void enforceAdminSignupRateLimit(UUID adminId) {
        String key   = "admin_rate:courier_create:" + adminId;
        Long   count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > MAX_ADMIN_SIGNUPS_PER_HOUR) {
            log.warn("Admin signup rate limit exceeded: adminId={}", adminId);
            throw new com.buyology.buyology_courier.courier.exception.RateLimitExceededException(
                    "Too many courier registrations. Maximum " + MAX_ADMIN_SIGNUPS_PER_HOUR
                            + " per hour per admin.");
        }
    }

    private void saveOutboxEvent(String routingKey, Object event) {
        try {
            outboxEventRepository.save(OutboxEvent.builder()
                    .routingKey(routingKey)
                    .payload(objectMapper.writeValueAsString(event))
                    .eventVersion(1)
                    .status(OutboxStatus.PENDING)
                    .retryCount(0)
                    .build());
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialise outbox event: " + routingKey, ex);
        }
    }
}
