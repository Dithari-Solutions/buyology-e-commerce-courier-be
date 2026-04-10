package com.buyology.buyology_courier.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates the courier JWT on every STOMP CONNECT frame and sets the
 * authenticated Principal so Spring's user-destination resolution works.
 *
 * <p>The mobile app must include the header in the CONNECT frame:
 * <pre>Authorization: Bearer &lt;courier-access-token&gt;</pre>
 *
 * <p>After successful auth the Principal name is set to the JWT {@code sub}
 * claim (the courier UUID). This allows the server to push to
 * {@code /user/{courierId}/queue/assignments} via
 * {@code SimpMessagingTemplate.convertAndSendToUser(courierId, "/queue/assignments", payload)}.
 */
@Component
@Slf4j
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private final NimbusJwtDecoder courierJwtDecoder;

    public WebSocketAuthChannelInterceptor(@Qualifier("courierJwtDecoder") NimbusJwtDecoder courierJwtDecoder) {
        this.courierJwtDecoder = courierJwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[WS-AUTH] CONNECT without Authorization header — connection rejected");
            throw new org.springframework.security.access.AccessDeniedException(
                    "Missing or invalid Authorization header on STOMP CONNECT");
        }

        String token = authHeader.substring(7).trim();
        try {
            Jwt jwt = courierJwtDecoder.decode(token);
            String courierId = jwt.getSubject();

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    courierId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_COURIER"))
            );
            accessor.setUser(auth);
            log.info("[WS-AUTH] STOMP CONNECT authenticated courierId={}", courierId);
        } catch (JwtException ex) {
            log.warn("[WS-AUTH] Invalid JWT on STOMP CONNECT — {}", ex.getMessage());
            throw new org.springframework.security.access.AccessDeniedException(
                    "Invalid or expired courier token: " + ex.getMessage());
        }

        return message;
    }
}
