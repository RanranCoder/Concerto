package com.pams.module.user;

import com.pams.common.BizException;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.user.dto.UserSaveRequest;
import com.pams.repository.RoleRepository;
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

    // ==================== Task 26：角色级别防提权 ====================

    @Test
    void isDeptScoped_trueWhenRoleDataScopeDEPT() {
        Role staff = new Role();
        staff.setCode("STAFF");
        staff.setDataScope("DEPT");
        assertThat(userService.isDeptScoped(staff)).isTrue();
    }

    @Test
    void isDeptScoped_falseForAllScopeOrNull() {
        Role leader = new Role();
        leader.setCode("ORG_LEADER");
        leader.setDataScope("ALL");
        assertThat(userService.isDeptScoped(leader)).isFalse();
        assertThat(userService.isDeptScoped(null)).isFalse();
    }

    @Test
    void createUser_withHigherLevelRole_rejects() {
        RoleRepository roleRepo = mock(RoleRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserService svc = new UserService(userRepo, new BCryptPasswordEncoder(), null, roleRepo);

        Role director = new Role();
        director.setId(10L);
        director.setCode("DIRECTOR");
        director.setLevel(4);
        when(roleRepo.findById(10L)).thenReturn(Optional.of(director));

        UserSaveRequest req = new UserSaveRequest();
        req.setUsername("someone");
        req.setRealName("某人");
        req.setRoleId(10L);

        // 部长（level 3）不能创建主任（level 4）
        assertThatThrownBy(() -> svc.createUser(req, 3))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能授予高于自己级别的角色");
        verify(userRepo, never()).save(any());
    }

    @Test
    void updateUser_withHigherLevelRole_rejects() {
        RoleRepository roleRepo = mock(RoleRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserService svc = new UserService(userRepo, new BCryptPasswordEncoder(), null, roleRepo);

        User existing = new User();
        existing.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(existing));

        Role teacher = new Role();
        teacher.setId(20L);
        teacher.setCode("TEACHER");
        teacher.setLevel(5);
        when(roleRepo.findById(20L)).thenReturn(Optional.of(teacher));

        UserSaveRequest req = new UserSaveRequest();
        req.setUsername("u");
        req.setRealName("某人");
        req.setRoleId(20L);

        // 主任（level 4）不能把用户改成指导老师（level 5）
        assertThatThrownBy(() -> svc.updateUser(1L, req, 4))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能授予高于自己级别的角色");
        verify(userRepo, never()).save(any());
    }

    @Test
    void createUser_sameOrLowerLevel_allowed() {
        RoleRepository roleRepo = mock(RoleRepository.class);
        UserRepository userRepo = mock(UserRepository.class);
        UserService svc = new UserService(userRepo, new BCryptPasswordEncoder(), null, roleRepo);

        Role staff = new Role();
        staff.setId(30L);
        staff.setCode("STAFF");
        staff.setLevel(1);
        when(roleRepo.findById(30L)).thenReturn(Optional.of(staff));

        UserSaveRequest req = new UserSaveRequest();
        req.setUsername("newbie");
        req.setRealName("新干事");
        req.setRoleId(30L);

        when(userRepo.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(99L);
            return u;
        });

        Long id = svc.createUser(req, 3);
        assertThat(id).isEqualTo(99L);
        verify(userRepo).save(any());
    }
}
