package com.pams.module.agent.provider;

import com.pams.common.*;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.llm.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentProviderServiceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private AgentProviderService service; private JdbcTemplate db; private ProviderSecrets secrets;
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        db=new JdbcTemplate(ds);db.execute("create table organization(id bigint primary key)");db.update("insert into organization values(1),(2)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V59__agent_provider_settings.sql"),new ClassPathResource("db/migration/V60__agent_provider_key_state.sql")).execute(ds);
        secrets=new ProviderSecrets(Base64.getEncoder().encodeToString(new byte[32]));
        service=new AgentProviderService(db,secrets,new ProviderUrlPolicy("localhost"),new AgentProperties());OrgContext.set(1L);
    }
    @AfterEach void clear() { OrgContext.clear(); }
    private AgentProviderService.Draft draft(Long id,Long version,String name,String key) {
        return new AgentProviderService.Draft(id,version,name,"openai_responses","http://localhost:1234/v1/","model",key,true,null);
    }
    @Test void encryptedCrudNeverReturnsSecretsAndEnforcesTenantOwnership() {
        var saved=service.save(draft(null,null,"组织服务","private-test-key"));
        assertThat(saved.toString()).doesNotContain("private-test-key");assertThat(service.list().items()).hasSize(1);
        String stored=db.queryForObject("select api_key_ciphertext from agent_provider_config",String.class);
        assertThat(stored).doesNotContain("private-test-key");assertThat(secrets.decrypt(stored)).isEqualTo("private-test-key");
        var edited=service.save(draft(saved.id(),saved.version(),"新名称",""));
        assertThat(edited.version()).isEqualTo(1);
        assertThat(db.queryForObject("select api_key_ciphertext from agent_provider_config",String.class)).isEqualTo(stored);
        OrgContext.set(2L);assertThat(service.list().items()).isEmpty();
        assertThatThrownBy(()->service.save(draft(saved.id(),1L,"越权",""))).isInstanceOf(BizException.class).hasMessageContaining("不存在");
        assertThatThrownBy(()->service.delete(saved.id(),1)).isInstanceOf(BizException.class);
    }
    @Test void rejectsStaleUpdatesAndUnverifiedActivation() {
        var saved=service.save(draft(null,null,"服务","key"));service.save(draft(saved.id(),saved.version(),"服务2",""));
        assertThatThrownBy(()->service.save(draft(saved.id(),saved.version(),"覆盖",""))).hasMessageContaining("已被修改");
        assertThatThrownBy(()->service.activate(saved.id(),1,0)).hasMessageContaining("测试");
        assertThatThrownBy(()->service.save(draft(null,null,"服务2","key"))).hasMessageContaining("同名");
    }
    @Test void defaultChangesAreVersionedAndActiveDeletionIsBlocked() {
        var saved=service.save(draft(null,null,"服务","key"));
        db.update("update agent_provider_config set test_status='PASSED',tested_at=CURRENT_TIMESTAMP");
        service.activate(saved.id(),saved.version(),0);assertThat(service.list().defaultId()).isEqualTo(saved.id());
        assertThat(service.modelStatus().configured()).isTrue();assertThat(service.modelStatus().displayName()).isEqualTo("服务");
        assertThat(service.modelStatus().toString()).doesNotContain("localhost", "ciphertext", "apiKey");
        assertThat(service.resolve(1,new StubProvider(),"task-audit")).isInstanceOf(OpenAiResponsesProvider.class);
        var usage=db.queryForMap("select config_id,config_version,protocol from agent_provider_task_usage where stream_id='task-audit'");
        assertThat(((Number)usage.get("config_id")).longValue()).isEqualTo(saved.id());
        assertThat(((Number)usage.get("config_version")).longValue()).isEqualTo(saved.version());
        assertThatThrownBy(()->service.delete(saved.id(),0)).hasMessageContaining("默认服务");
        assertThatThrownBy(()->service.activate(null,0,0)).hasMessageContaining("已变更");
        assertThatThrownBy(()->service.save(draft(saved.id(),0L,"服务","new-key"))).hasMessageContaining("先测试");
        service.activate(null,0,1);service.delete(saved.id(),0);assertThat(service.list().items()).isEmpty();
        assertThat(service.modelStatus().configured()).isFalse();
    }
    @Test void taskRegistryPinsAcrossSwitchesAndNeverSharesTenants() {
        var configs=mock(AgentProviderService.class);var a=mock(LlmProvider.class);var b=mock(LlmProvider.class);var stub=new StubProvider();
        when(configs.resolve(eq(1L),eq(stub),anyString())).thenReturn(a,b);when(configs.resolve(eq(2L),eq(stub),anyString())).thenReturn(b);
        var registry=new TaskProviderRegistry(configs,stub);
        assertThat(registry.begin("task",1)).isSameAs(a);assertThat(registry.get("task")).isSameAs(a);
        assertThatThrownBy(()->registry.begin("task",2)).isInstanceOf(BizException.class);
        assertThat(registry.begin("other",2)).isSameAs(b);registry.release("task");assertThat(registry.begin("next",1)).isSameAs(b);
        verify(configs,times(2)).resolve(eq(1L),eq(stub),anyString());
    }
    @Test void rejectsUnsafeUrlsAndMissingEncryptionKey() {
        var policy=new ProviderUrlPolicy("");
        for(String url:List.of("http://localhost/v1","https://127.0.0.1/v1","https://169.254.169.254/v1","https://example.com/v1/responses","https://user:pass@example.com/v1"))
            assertThatThrownBy(()->policy.validate(url)).isInstanceOf(BizException.class);
        assertThat(new ProviderSecrets("").available()).isFalse();assertThatThrownBy(()->new ProviderSecrets("").encrypt("key")).isInstanceOf(BizException.class);
    }
    @Test void successfulProbeReceiptIsBoundToExactDraftAndTenant() {
        secrets = new ProviderSecrets("",directory.resolve("provider.key").toString(),db);
        var provider=mock(LlmProvider.class);
        when(provider.chat(any())).thenReturn(LlmProvider.ChatResponse.text("OK","openai_responses"));
        when(provider.chatStream(any(),any(),any())).thenReturn(
            new LlmProvider.ChatResponse("",List.of(new LlmProvider.ToolCallRequest("call","connection_probe",Map.of())),"openai_responses",false,"summary"),
            LlmProvider.ChatResponse.text("OK","openai_responses"));
        var probing=new AgentProviderService(db,secrets,new ProviderUrlPolicy("localhost"),new AgentProperties()) {
            @Override public LlmProvider create(ModelEndpoint e) { return provider; }
        };
        var draft=draft(null,null,"测试服务","key");var tested=probing.test(draft);
        assertThat(tested.success()).isTrue();assertThat(tested.ticket()).isNotBlank();
        var verified=new AgentProviderService.Draft(null,null,draft.displayName(),draft.protocol(),draft.baseUrl(),draft.modelId(),draft.apiKey(),true,tested.ticket());
        var saved=probing.save(verified);assertThat(saved.testStatus()).isEqualTo("PASSED");
        probing.activate(saved.id(),saved.version(),0);
        var altered=new AgentProviderService.Draft(null,null,"改动模型",draft.protocol(),draft.baseUrl(),"different-model",draft.apiKey(),true,tested.ticket());
        assertThat(probing.save(altered).testStatus()).isEqualTo("UNTESTED");
        String[] parts=secrets.decrypt(tested.ticket()).split("\\|");
        var expired=new AgentProviderService.Draft(null,null,"过期测试",draft.protocol(),draft.baseUrl(),draft.modelId(),draft.apiKey(),true,secrets.encrypt(parts[0]+"|0|true|0"));
        assertThat(probing.save(expired).testStatus()).isEqualTo("UNTESTED");
        OrgContext.set(2L);var other=probing.save(verified);assertThat(other.testStatus()).isEqualTo("UNTESTED");
    }
    @Test void failedProbeUpdatesOnlyTheMatchingSavedConnection() {
        var saved=service.save(draft(null,null,"服务","key"));
        db.update("update agent_provider_config set test_status='PASSED',tested_at=CURRENT_TIMESTAMP");
        var probing=new AgentProviderService(db,secrets,new ProviderUrlPolicy("localhost"),new AgentProperties()) {
            @Override public LlmProvider create(ModelEndpoint e) { throw new IllegalStateException("upstream secret must not escape"); }
        };
        var failed=probing.test(draft(saved.id(),saved.version(),"服务",""));
        assertThat(failed.success()).isFalse();assertThat(failed.message()).doesNotContain("secret");
        assertThat(service.list().items().getFirst().testStatus()).isEqualTo("FAILED");
        db.update("update agent_provider_config set test_status='PASSED'");
        probing.test(draft(saved.id(),saved.version(),"服务","different-key"));
        assertThat(service.list().items().getFirst().testStatus()).isEqualTo("PASSED");
    }
}
