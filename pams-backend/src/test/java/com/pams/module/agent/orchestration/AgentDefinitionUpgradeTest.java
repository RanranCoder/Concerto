package com.pams.module.agent.orchestration;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.registry.AgentDefinitionSeeder;
import com.pams.module.agent.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class AgentDefinitionUpgradeTest {
    @Test void updatesKnownLegacyScopeAndPreservesCustomPromptAndEnabledState() {
        var repo=mock(AgentDefinitionRepository.class); when(repo.existsByName(anyString())).thenReturn(true);
        var definition=new AgentDefinition(); definition.setName("writing"); definition.setEnabled(0); definition.setRolePrompt("自定义语气");
        definition.setToolScope("[\"draftArticleFromActivity\",\"draftNewsFromActivity\",\"draftAnnouncement\",\"publishAnnouncement\"]");
        when(repo.findByName("writing")).thenReturn(Optional.of(definition));
        new AgentDefinitionSeeder(repo).run(null);
        assertThat(definition.getToolScope()).isEqualTo("[\"draftArticleFromActivity\",\"publishAnnouncement\"]");
        assertThat(definition.getEnabled()).isZero(); assertThat(definition.getRolePrompt()).isEqualTo("自定义语气");
        verify(repo).save(definition);
    }
    @Test void doesNotOverwriteCustomizedScope() {
        var repo=mock(AgentDefinitionRepository.class); when(repo.existsByName(anyString())).thenReturn(true);
        var definition=new AgentDefinition(); definition.setName("writing"); definition.setToolScope("[\"searchArticles\"]");
        when(repo.findByName("writing")).thenReturn(Optional.of(definition));
        new AgentDefinitionSeeder(repo).run(null);
        assertThat(definition.getToolScope()).isEqualTo("[\"searchArticles\"]"); verify(repo,never()).save(any());
    }
}
