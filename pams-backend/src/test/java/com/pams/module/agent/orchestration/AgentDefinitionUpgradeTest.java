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
    @Test void upgradesBothKnownSchedulingDefaultsWithoutOverwritingRole() {
        for(String scope:java.util.List.of("[\"generateActivitySchedule\",\"generateActivityTasks\",\"createSeatLayout\"]",
                "[\"generateActivitySchedule\",\"generateActivityTasks\",\"createSeatLayout\",\"recallConversation\"]")) {
            var repo=mock(AgentDefinitionRepository.class);when(repo.existsByName(anyString())).thenReturn(true);
            var definition=new AgentDefinition();definition.setName("scheduling");definition.setEnabled(1);definition.setRolePrompt("自定义语气");definition.setToolScope(scope);
            when(repo.findByName("scheduling")).thenReturn(Optional.of(definition));new AgentDefinitionSeeder(repo).run(null);
            assertThat(definition.getToolScope()).contains("prepareTaskProgressUpdate","queryActivityTasks");
            assertThat(definition.getRolePrompt()).isEqualTo("自定义语气");verify(repo).save(definition);
        }
    }
    @Test void preservesDisabledDefinitionWithoutScopeMigration() {
        var repo=mock(AgentDefinitionRepository.class); when(repo.existsByName(anyString())).thenReturn(true);
        var definition=new AgentDefinition(); definition.setName("writing"); definition.setEnabled(0); definition.setRolePrompt("自定义语气");
        definition.setToolScope("[\"draftArticleFromActivity\",\"draftNewsFromActivity\",\"draftAnnouncement\",\"publishAnnouncement\"]");
        when(repo.findByName("writing")).thenReturn(Optional.of(definition));
        new AgentDefinitionSeeder(repo).run(null);
        assertThat(definition.getToolScope()).isEqualTo("[\"draftArticleFromActivity\",\"draftNewsFromActivity\",\"draftAnnouncement\",\"publishAnnouncement\"]");
        assertThat(definition.getEnabled()).isZero(); assertThat(definition.getRolePrompt()).isEqualTo("自定义语气");
        verify(repo,never()).save(any());
    }
    @Test void doesNotOverwriteCustomizedScope() {
        var repo=mock(AgentDefinitionRepository.class); when(repo.existsByName(anyString())).thenReturn(true);
        var definition=new AgentDefinition(); definition.setName("writing"); definition.setToolScope("[\"searchArticles\"]");
        when(repo.findByName("writing")).thenReturn(Optional.of(definition));
        new AgentDefinitionSeeder(repo).run(null);
        assertThat(definition.getToolScope()).isEqualTo("[\"searchArticles\"]"); verify(repo,never()).save(any());
    }
    @Test void upgradesOnlyTheExactPriorPlanningDefault() {
        var repo=mock(AgentDefinitionRepository.class); when(repo.existsByName(anyString())).thenReturn(true);
        var definition=new AgentDefinition();definition.setName("planning");definition.setEnabled(1);
        definition.setRolePrompt("自定义语气");definition.setToolScope("[\"createActivity\",\"draftPlanSections\",\"recallConversation\"]");
        when(repo.findByName("planning")).thenReturn(Optional.of(definition));
        new AgentDefinitionSeeder(repo).run(null);
        assertThat(definition.getToolScope()).contains("getActivityDetail","listPlanVersions","readPlanDocument");
        assertThat(definition.getRolePrompt()).isEqualTo("自定义语气");verify(repo).save(definition);
    }
}
