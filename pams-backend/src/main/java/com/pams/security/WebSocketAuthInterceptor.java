package com.pams.security;

import com.pams.entity.User;
import com.pams.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public WebSocketAuthInterceptor(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
                try {
                    Claims claims = jwtUtil.parse(token);
                    String username = claims.getSubject();
                    // 与 HTTP 链路 JwtAuthenticationFilter 口径一致：账号不存在/软删（deleted=1）/禁用（status=0）不放行
                    User u = userRepository.findByUsername(username).orElse(null);
                    if (u == null || u.getStatus() == null || u.getStatus() == 0) {
                        log.warn("WebSocket 连接拒绝：账号不存在或已禁用/删除 username={}", username);
                        return null; // 账号不可用，拒绝连接
                    }
                    UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(username, null, List.of());
                    accessor.setUser(auth);
                } catch (Exception e) {
                    log.warn("WebSocket 认证失败: {}", e.getMessage());
                    return null; // 无效令牌，拒绝连接
                }
            } else {
                return null; // 令牌缺失或格式错误，拒绝连接
            }
        }
        return message;
    }
}
