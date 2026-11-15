package com.pams.security;

import com.pams.entity.User;
import com.pams.module.permission.service.PermissionService;
import com.pams.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PermissionService permissions;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(Long userId, Long orgId, Date issuedAt, Instant expiresAt) {}

    public WebSocketAuthInterceptor(JwtUtil jwtUtil, UserRepository userRepository, PermissionService permissions) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.permissions = permissions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            return connect(accessor) ? message : null;
        }
        if (command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
            String prefix = command == StompCommand.SUBSCRIBE ? "/user/queue/" : "/app/";
            String destination = accessor.getDestination();
            if (destination == null || !destination.startsWith(prefix) || !valid(accessor.getSessionId())) return null;
        }
        return message;
    }

    private boolean connect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        String sessionId = accessor.getSessionId();
        if (sessionId == null || header == null || !header.startsWith("Bearer ")) return false;
        try {
            var claims = jwtUtil.parse(header.substring(7));
            if (claims.getSubject() == null || claims.getExpiration() == null) return false;
            User user = userRepository.findByUsername(claims.getSubject()).orElse(null);
            if (user == null) return false;
            var session = new Session(user.getId(), user.getOrgId(), claims.getIssuedAt(),
                    claims.getExpiration().toInstant());
            if (!valid(session, user) || sessions.putIfAbsent(sessionId, session) != null) return false;
            accessor.setUser(new UsernamePasswordAuthenticationToken(user.getUsername(), null, List.of()));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean valid(String sessionId) {
        if (sessionId == null) return false;
        Session session = sessions.get(sessionId);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) return false;
        return userRepository.findById(session.userId()).filter(user -> valid(session, user)).isPresent();
    }

    private boolean valid(Session session, User user) {
        return session.expiresAt().isAfter(Instant.now())
                && Objects.equals(session.orgId(), user.getOrgId())
                && permissions.validUser(user.getId())
                && !JwtUtil.isRevoked(session.issuedAt(), user.getTokenInvalidBefore());
    }

    public Message<?> authorizeDelivery(Message<?> message) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) return message;
        return valid(SimpMessageHeaderAccessor.getSessionId(headers)) ? message : null;
    }

    @EventListener
    public void disconnect(SessionDisconnectEvent event) {
        sessions.remove(event.getSessionId());
    }
}
