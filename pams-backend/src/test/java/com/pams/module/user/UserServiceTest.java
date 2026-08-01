package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.entity.User;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserServiceTest {

    UserRepository userRepository;
    PasswordEncoder encoder;
    UserService userService;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, encoder);
    }

    @Test
    void resetPassword_encodesNewPassword() {
        User u = new User();
        u.setId(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        userService.resetPassword(1L);
        assertThat(u.getPassword()).isNotEqualTo("123456");
        assertThat(encoder.matches("123456", u.getPassword())).isTrue();
        verify(userRepository).save(u);
    }

    @Test
    void delete_missingUser_throws() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.deleteUser(9L))
                .isInstanceOf(BizException.class);
    }
}
