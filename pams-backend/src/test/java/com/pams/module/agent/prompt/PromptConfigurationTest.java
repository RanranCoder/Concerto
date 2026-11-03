package com.pams.module.agent.prompt;

import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptConfigurationTest {
    @Test void customizedConfigurationIsReportedWithoutWritingDatabase() {
        var repo=mock(AgentDefinitionRepository.class);
        var def=new AgentDefinition();def.setName("writing");def.setRolePrompt("自定义角色");def.setToolScope("[]");
        when(repo.findByEnabledOrderBySortOrderAsc(1)).thenReturn(List.of(def));
        var report=new AgentRegistry(repo).promptConfigurations();
        assertThat(report).singleElement().satisfies(row->{
            assertThat(row.roleStatus()).isEqualTo("CUSTOMIZED_PRESERVED");
            assertThat(row.configuredScope()).isEmpty();
            assertThat(row.roleSha256()).hasSize(64);
        });
        verify(repo).findByEnabledOrderBySortOrderAsc(1);verifyNoMoreInteractions(repo);
        assertThat(def.getRolePrompt()).isEqualTo("自定义角色");
    }
}
