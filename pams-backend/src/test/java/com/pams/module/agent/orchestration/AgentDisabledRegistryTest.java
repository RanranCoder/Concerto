package com.pams.module.agent.orchestration;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentDisabledRegistryTest {
    @Test void disablingAllDefinitionsCannotReactivateBuiltins(){
        var repository=mock(AgentDefinitionRepository.class);when(repository.findByEnabledOrderBySortOrderAsc(1)).thenReturn(List.of());when(repository.count()).thenReturn(3L);
        assertThat(new AgentRegistry(repository).enabled()).isEmpty();assertThat(new AgentRegistry(repository).byName("generalist")).isEmpty();
        when(repository.count()).thenReturn(0L);assertThat(new AgentRegistry(repository).enabled()).isNotEmpty();
    }
}
