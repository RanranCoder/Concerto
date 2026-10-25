package com.pams.module.agent.orchestration;
import com.pams.module.agent.core.*;
import com.pams.module.agent.registry.*;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.repository.AgentDefinitionRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AgentScopeTest {
    @Test void nullScopeMeansAllButEmptyAndMalformedScopesNeverExpandPermissions() {
        var repo=mock(AgentDefinitionRepository.class);
        var registry=new AgentRegistry(repo);
        var definition=new AgentDefinition(); definition.setName("custom");
        assertThat(registry.toolScopeOf(definition)).isNull();
        definition.setToolScope("[]"); assertThat(registry.toolScopeOf(definition)).isEmpty();
        definition.setToolScope("broken"); assertThat(registry.toolScopeOf(definition)).isEmpty();
        definition.setName("reviewer"); definition.setToolScope(null); assertThat(registry.toolScopeOf(definition)).isEmpty();
    }
    @Test void defaultDefinitionsContainNoUnimplementedTools() {
        var all=AgentCatalog.defaults().stream().filter(d->d.toolScope()!=null).flatMap(d->d.toolScope().stream()).toList();
        assertThat(all).contains("queryPartyMemberProgress").doesNotContain("draftNewsFromActivity","draftAnnouncement","generateAgendaFromPlan");
    }
}
