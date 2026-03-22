package com.buyology.buyology_courier.auth.dto.response;

import java.util.UUID;

public record AuthResponse(
        String  accessToken,
        String  refreshToken,
        long    expiresIn,    // access token TTL in seconds
        UUID    courierId
) {}
