package com.pams.module.agent.provider;

import com.pams.common.OrgContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AgentProviderPermissionTest {
    @Autowired AgentProviderController controller;
    @Autowired AgentModelStatusController modelStatus;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); OrgContext.clear(); }
    @Test void adminPermissionIsEnforcedAtControllerBoundary() {
        OrgContext.set(1L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("member","",List.of(new SimpleGrantedAuthority("agent:use"))));
        assertThatThrownBy(controller::list).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(modelStatus.status().getData()).isNotNull();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","",List.of(new SimpleGrantedAuthority("org:manage"))));
        assertThat(controller.list().getData().encryptionReady()).isTrue();
    }
}
