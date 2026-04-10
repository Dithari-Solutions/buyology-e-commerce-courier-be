package com.buyology.buyology_courier.config;

import com.buyology.buyology_courier.notification.WebSocketAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP/WebSocket broker configuration.
 *
 * <p>Courier mobile apps connect to {@code /ws} and subscribe to their
 * personal queue {@code /user/{courierId}/queue/assignments}.
 * The server pushes a new-assignment notification to that queue the moment
 * a courier is selected, so the app shows "New order available" without polling.
 *
 * <h3>Connection flow</h3>
 * <ol>
 *   <li>Client opens WebSocket to {@code ws://<host>/ws}</li>
 *   <li>Client sends STOMP CONNECT with header {@code Authorization: Bearer <courier-jwt>}</li>
 *   <li>{@link WebSocketAuthChannelInterceptor} validates the JWT and sets the Principal</li>
 *   <li>Client subscribes to {@code /user/queue/assignments}</li>
 *   <li>Server calls {@code SimpMessagingTemplate.convertAndSendToUser(courierId, "/queue/assignments", payload)}</li>
 * </ol>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthChannelInterceptor webSocketAuthChannelInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // In-memory broker for user-targeted queues and topic broadcasts
        config.enableSimpleBroker("/queue", "/topic");
        // Prefix for @MessageMapping methods (not used yet, reserved for future client→server messages)
        config.setApplicationDestinationPrefixes("/app");
        // Prefix that Spring adds when resolving /user/{principal}/queue/... destinations
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Pure WebSocket endpoint — no SockJS fallback needed for native mobile apps
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Validate JWT on every STOMP CONNECT frame
        registration.interceptors(webSocketAuthChannelInterceptor);
    }
}
