package com.pams.module.member;

import com.pams.common.BizException;
import com.pams.entity.Department;
import com.pams.entity.Role;
import com.pams.entity.User;
import com.pams.module.member.dto.AccountImportRequest;
import com.pams.module.member.entity.Member;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.service.MemberAccountImportService;
import com.pams.repository.RoleRepository;
import com.pams.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemberAccountImportServiceTest {

    MemberRepository memberRepo;
    UserRepository userRepo;
    RoleRepository roleRepo;
    PasswordEncoder encoder;
    MemberAccountImportService service;

    @BeforeEach
    void setup() {
        memberRepo = mock(MemberRepository.class);
        userRepo = mock(UserRepository.class);
        roleRepo = mock(RoleRepository.class);
        encoder = mock(PasswordEncoder.class);
        service = new MemberAccountImportService(memberRepo, userRepo, roleRepo, encoder);
        when(encoder.encode(any())).thenReturn("hashed");
    }

    @Test
    void unregistered_excludesMembersWithoutStudentNoAndAlreadyRegistered() {
        Member hasNo = new Member(); hasNo.setId(1L); hasNo.setName("无学号"); hasNo.setStudentNo(null);
        Member ok = new Member(); ok.setId(2L); ok.setName("张三"); ok.setStudentNo("20250101"); ok.setPosition("STAFF");
        Member reg = new Member(); reg.setId(3L); reg.setName("李四"); reg.setStudentNo("20250102"); reg.setPosition("STAFF");
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(hasNo, ok, reg));
        when(userRepo.findByStudentNo("20250102")).thenReturn(List.of(new User()));

        var list = service.unregistered(1L);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).id()).isEqualTo(2L);
    }

    @Test
    void importAccounts_createsUsersAndSkipsExisting() {
        Member a = new Member(); a.setId(2L); a.setName("张三"); a.setStudentNo("20250101");
        a.setPosition("STAFF"); a.setDeptId(null);
        Member b = new Member(); b.setId(3L); b.setName("李四"); b.setStudentNo("20250102");
        b.setPosition("DIRECTOR"); b.setDeptId(null);
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(a, b));
        when(userRepo.existsByUsername("20250101")).thenReturn(false);
        when(userRepo.existsByUsername("20250102")).thenReturn(true);   // 已注册用户名 → skip
        Role staff = new Role(); staff.setCode("STAFF"); staff.setName("干事"); staff.setLevel(1);
        Role dir = new Role(); dir.setCode("DIRECTOR"); dir.setName("主任"); dir.setLevel(4);
        when(roleRepo.findByCode("STAFF")).thenReturn(Optional.of(staff));
        when(roleRepo.findByCode("DIRECTOR")).thenReturn(Optional.of(dir));

        var r = service.importAccounts(new AccountImportRequest(1L, List.of(2L, 3L), null), 99L, null);

        assertThat(r.created()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        verify(userRepo).save(any(User.class));
    }

    @Test
    void importAccounts_teacherOverride_rejected() {
        Member a = new Member(); a.setId(2L); a.setName("张三"); a.setStudentNo("20250101");
        a.setPosition("STAFF"); a.setDeptId(null);
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(a));

        assertThatThrownBy(() -> service.importAccounts(
                new AccountImportRequest(1L, List.of(2L), Map.of(2L, "TEACHER")), 99L, null))
                .isInstanceOf(BizException.class).hasMessageContaining("不允许授予该角色");
        verify(userRepo, never()).save(any(User.class));
    }

    @Test
    void importAccounts_directorOverride_byLevel4Caller_allowed() {
        Member a = new Member(); a.setId(2L); a.setName("张三"); a.setStudentNo("20250101");
        a.setPosition("STAFF"); a.setDeptId(null);
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(a));
        when(userRepo.existsByUsername("20250101")).thenReturn(false);
        when(userRepo.findByStudentNo("20250101")).thenReturn(List.of());
        Role dir = new Role(); dir.setCode("DIRECTOR"); dir.setLevel(4);
        when(roleRepo.findByCode("DIRECTOR")).thenReturn(Optional.of(dir));

        var r = service.importAccounts(
                new AccountImportRequest(1L, List.of(2L), Map.of(2L, "DIRECTOR")), 99L, 4);

        assertThat(r.created()).isEqualTo(1);
        verify(userRepo).save(any(User.class));
    }

    @Test
    void importAccounts_directorOverride_byLevel3Caller_rejected() {
        Member a = new Member(); a.setId(2L); a.setName("张三"); a.setStudentNo("20250101");
        a.setPosition("STAFF"); a.setDeptId(null);
        when(memberRepo.findBySessionId(1L)).thenReturn(List.of(a));
        Role dir = new Role(); dir.setCode("DIRECTOR"); dir.setLevel(4);
        when(roleRepo.findByCode("DIRECTOR")).thenReturn(Optional.of(dir));

        assertThatThrownBy(() -> service.importAccounts(
                new AccountImportRequest(1L, List.of(2L), Map.of(2L, "DIRECTOR")), 99L, 3))
                .isInstanceOf(BizException.class).hasMessageContaining("不能授予高于自己级别的角色");
        verify(userRepo, never()).save(any(User.class));
    }
}
