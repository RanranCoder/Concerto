package com.pams.security;

import com.pams.entity.User;
import com.pams.module.permission.service.PermissionService;
import com.pams.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthInterceptorTest {

    private WebSocketAuthInterceptor interceptor(JwtUtil jwtUtil, UserRepository userRepository) {
        return new WebSocketAuthInterceptor(jwtUtil, userRepository, mock(PermissionService.class));
    }

    private Message<byte[]> connectMessage(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session");
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void rejectsInvalidToken() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(jwtUtil.parse("bad-token")).thenThrow(new JwtException("expired"));

        Message<?> result = interceptor(jwtUtil, userRepository)
                .preSend(connectMessage("Bearer bad-token"), mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void rejectsMissingToken() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserRepository userRepository = mock(UserRepository.class);

        Message<?> result = interceptor(jwtUtil, userRepository)
                .preSend(connectMessage(null), mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void rechecksExistingDeliveryAndReconnectAfterRevocation() {
        JwtUtil jwt = mock(JwtUtil.class);
        Claims claims = mock(Claims.class);
        when(jwt.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user");
        when(claims.getIssuedAt()).thenReturn(Date.from(Instant.now().minusSeconds(60)));
        when(claims.getExpiration()).thenReturn(Date.from(Instant.now().plusSeconds(60)));
        User user = new User();
        user.setId(5L);
        user.setOrgId(2L);
        user.setUsername("user");
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("user")).thenReturn(Optional.of(user));
        when(users.findById(5L)).thenReturn(Optional.of(user));
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.validUser(5L)).thenReturn(true);
        var auth = new WebSocketAuthInterceptor(jwt, users, permissions);
        var channel = mock(MessageChannel.class);
        var outbound = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        outbound.setSessionId("session");
        var event = MessageBuilder.createMessage(new byte[0], outbound.getMessageHeaders());

        assertThat(auth.preSend(connectMessage("Bearer token"), channel)).isNotNull();
        assertThat(auth.authorizeDelivery(event)).isSameAs(event);
        when(permissions.validUser(5L)).thenReturn(false);
        assertThat(auth.authorizeDelivery(event)).isNull();
        when(permissions.validUser(5L)).thenReturn(true);
        user.setTokenInvalidBefore(LocalDateTime.now());
        assertThat(auth.authorizeDelivery(event)).isNull();
        assertThat(new WebSocketAuthInterceptor(jwt, users, permissions)
                .preSend(connectMessage("Bearer token"), channel)).isNull();
    }
}
