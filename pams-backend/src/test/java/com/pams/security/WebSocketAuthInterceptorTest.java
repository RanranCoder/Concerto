package com.pams.security;

import com.pams.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthInterceptorTest {

    private WebSocketAuthInterceptor interceptor(JwtUtil jwtUtil, UserRepository userRepository) {
        return new WebSocketAuthInterceptor(jwtUtil, userRepository);
    }

    private Message<byte[]> connectMessage(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void rejectsInvalidToken() {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserRepository userRepository = mock(UserRepository.class);
        when(jwtUtil.parse("bad-token")).thenThrow(new RuntimeException("expired"));

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
}