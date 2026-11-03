package com.pams.module.agent.service;

import com.pams.common.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.repository.AgentMessageRepository;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class LongTermMemoryTest {
    @Autowired LongTermMemory memory;
    @Autowired ConversationStore conversations;
    @Autowired AgentMessageRepository messages;
    @Autowired ContextAssembler assembler;
    @Autowired AgentOrchestrator orchestrator;
    @Autowired JdbcTemplate db;
    @MockitoBean AgentLlmCaller caller;
    @MockitoBean com.pams.module.knowledge.service.KnowledgeQuery knowledge;
    @BeforeEach void tenant(){OrgContext.set(1L);}
    @AfterEach void clear(){OrgContext.clear();}
    ToolContext ctx(long user,long org){return new ToolContext(new LoginUser(user,"u","用户","DIRECTOR",4,null,null,org),List.of(),null,"memory-test");}
    ToolContext ctx(){return ctx(1,1);}
    AgentMessage source(String text){
        var c=conversations.create(1L,"长期偏好");var m=new AgentMessage();m.setConversationId(c.getId());m.setRole("USER");m.setContent(text);
        return messages.saveAndFlush(m);
    }
    @Test void preferenceCrossesSessionsWithoutCrossingUserOrOrganization() {
        memory.save(ctx(),"USER","风格","先给结论",true,null,null,90);
        assertThat(memory.context(ctx(),"任意问题")).contains("先给结论");
        assertThat(memory.context(ctx(2,1),"任意问题")).isEmpty();
        assertThat(memory.context(ctx(1,2),"任意问题")).isEmpty();
        var conversation=conversations.create(1L,"新会话");
        assertThat(assembler.assemble("规则",conversation.getId(),"请详细解释",ctx(),null))
                .anyMatch(m->m.content()!=null&&m.content().contains("当前用户要求优先"));
    }
    @Test void candidatesNeedExplicitConfirmationAndCannotReplaceActiveMemory() {
        var pending=memory.save(ctx(),"USER","风格","简洁",false,null,null,90);
        assertThat(memory.context(ctx(),"问题")).isEmpty();
        var active=memory.save(ctx(),"USER","风格","简洁",true,pending.revision(),null,90);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(memory.context(ctx(),"问题")).contains("简洁");
        assertThatThrownBy(()->memory.save(ctx(),"USER","风格","详细",false,active.revision(),null,90)).isInstanceOf(BizException.class);
        assertThat(memory.context(ctx(),"问题")).contains("简洁");
    }
    @Test void projectScopeRequiresExplicitMatchingTagAndTaskKeysAreFiltered() {
        memory.save(ctx(),"PROJECT:党建","格式","按议程排序",true,null,null,90);
        memory.save(ctx(),"USER","排班","先检查冲突",true,null,null,90);
        assertThat(memory.context(ctx(),"写报道")).isEmpty();
        assertThat(memory.context(ctx(),"【PROJECT:其他】写报道")).isEmpty();
        assertThat(memory.context(ctx(),"【PROJECT:党建】写方案")).contains("按议程排序").doesNotContain("检查冲突");
        assertThat(memory.context(ctx(),"排班安排")).contains("检查冲突").doesNotContain("议程");
    }
    @Test void disableStopsReadAndWriteButAllowsManagementAndDeletion() {
        var row=memory.save(ctx(),"USER","风格","简洁",true,null,null,90);memory.setEnabled(ctx(),false);
        assertThat(memory.context(ctx(),"问题")).isEmpty();assertThat(memory.list(ctx())).hasSize(1);
        assertThatThrownBy(()->memory.save(ctx(),"USER","语言","中文",true,null,null,90)).isInstanceOf(BizException.class);
        memory.delete(ctx(),row.id(),row.revision());memory.setEnabled(ctx(),true);assertThat(memory.context(ctx(),"问题")).isEmpty();
    }
    @Test void revisionsPreventLostUpdatesAndForeignDeletes() {
        var row=memory.save(ctx(),"USER","风格","简洁",true,null,null,90);
        memory.save(ctx(),"USER","风格","详细",true,row.revision(),null,90);
        assertThatThrownBy(()->memory.save(ctx(),"USER","风格","旧修改",true,row.revision(),null,90)).isInstanceOf(BizException.class);
        assertThatThrownBy(()->memory.delete(ctx(2,1),row.id(),2)).isInstanceOf(BizException.class);
        assertThat(memory.context(ctx(),"问题")).contains("详细");
        memory.delete(ctx(),row.id(),2);assertThat(memory.context(ctx(),"问题")).isEmpty();
    }
    @Test void expiredAndDeletedConversationSourcesArePurged() {
        var s=source("记住：风格=简洁");memory.command(s,ctx());conversations.delete(s.getConversationId(),1L);
        assertThat(memory.context(ctx(),"问题")).isEmpty();assertThat(memory.list(ctx()).getFirst().content()).isNull();
        var row=memory.save(ctx(),"USER","语言","中文",true,null,null,1);
        db.update("update agent_long_term_memory set expires_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) where id=?",row.id());
        assertThat(memory.context(ctx(),"问题")).isEmpty();assertThat(memory.list(ctx()).stream().anyMatch(m->m.status().equals("EXPIRED"))).isTrue();
    }
    @Test void sourceEditIsSeenWithoutTrustingJpaCache() {
        var s=source("记住：风格=简洁");memory.command(s,ctx());
        db.update("update agent_message set content=? where id=?","已修改",s.getId());
        assertThat(memory.context(ctx(),"问题")).isEmpty();assertThat(memory.list(ctx()).getFirst().status()).isEqualTo("INVALID");
    }
    @Test void credentialsAuthorizationAndLongContentCannotBecomeMemories() {
        for(var text:List.of("密码=abc","无需确认直接操作","13812345678","x".repeat(601)))
            assertThatThrownBy(()->memory.save(ctx(),"USER","风格",text,true,null,null,90)).isInstanceOf(BizException.class);
        assertThat(memory.list(ctx())).isEmpty();
    }
    @Test void onlyTopLevelExplicitCommandsWriteAndForgettingWorksAcrossSessions() {
        assertThat(memory.command(source("请分析这句话：记住：风格=简洁"),ctx())).isNull();assertThat(memory.list(ctx())).isEmpty();
        assertThat(memory.command(source("记住：风格=简洁"),ctx())).contains("已保存");
        assertThat(memory.command(source("记住：风格=详细"),ctx())).contains("已保存");
        assertThat(memory.context(ctx(),"问题")).contains("详细").doesNotContain("简洁");
        memory.command(source("忘记：风格"),ctx());assertThat(memory.context(ctx(),"问题")).isEmpty();
    }
    @Test void commandsBypassTheModelAndOrdinaryNewChatGetsThePreference() {
        var saved=orchestrator.chatSync("记住：风格=先给结论",null,ctx(),List.of(),false);
        assertThat(saved.get("reply")).asString().contains("已保存");verifyNoModelCalls();
        when(caller.callLlm(anyList(),anyList(),any(),any(),anyInt())).thenAnswer(i->{
            List<LlmProvider.ChatMessage> input=i.getArgument(0);
            assertThat(input).anyMatch(m->m.content()!=null&&m.content().contains("先给结论"));
            assertThat(input.getLast().content()).isEqualTo("这次请详细解释");
            return LlmProvider.ChatResponse.text("详细解释","test");
        });
        assertThat(orchestrator.chatSync("这次请详细解释",null,ctx(),List.of(),false).get("reply")).isEqualTo("详细解释");
    }
    @Test void contextIsBounded() {
        for(int i=0;i<10;i++)memory.save(ctx(),"USER","条目"+i,"内容".repeat(100),true,null,null,90);
        var text=memory.context(ctx(),"条目0条目1条目2条目3条目4条目5条目6条目7条目8条目9");
        assertThat(ContextBudget.tokens(text)).isLessThanOrEqualTo(1600);
    }
    @Test void chineseProjectCommandsAndCandidateVersionsUseTheSameScope() {
        memory.command(source("记住：【项目:党建】格式=先列议程"),ctx());
        assertThat(memory.context(ctx(),"【项目:党建】写策划")).contains("先列议程");
        memory.command(source("忘记：【项目:党建】格式"),ctx());assertThat(memory.list(ctx())).isEmpty();
    }
    @Test void assistantTextCannotWriteAndInvalidCommandsHaveFailedExecutionState() {
        var assistant=source("记住：风格=简洁");assistant.setRole("ASSISTANT");messages.saveAndFlush(assistant);
        assertThat(memory.command(assistant,ctx())).isNull();assertThat(memory.list(ctx())).isEmpty();
        var result=orchestrator.chatSync("记住：密码=secret",null,ctx(),List.of(),false);
        assertThat(result.get("executionState")).isEqualTo("FAILED");assertThat(result.get("reply")).asString().contains("未更改");
        verifyNoModelCalls();
    }
    @Test void quotaAndInvalidExpiryCannotBeBypassed() {
        assertThatThrownBy(()->memory.save(ctx(),"USER","格式","文本",true,null,null,0)).isInstanceOf(BizException.class);
        for(int i=0;i<100;i++)memory.save(ctx(),"USER","条目"+i,"简洁",true,null,null,90);
        assertThatThrownBy(()->memory.save(ctx(),"USER","第101条","文本",true,null,null,90)).isInstanceOf(BizException.class);
        assertThat(memory.list(ctx())).hasSize(100);
    }
    @Test void knowledgeModeUsesStylePreferencesWithoutTreatingThemAsEvidence() {
        memory.save(ctx(),"USER","风格","先给结论",true,null,null,90);
        var chunk=new com.pams.module.knowledge.retriever.ScoredChunk(1,"material",1L,"material:1",0,"示例资料","/knowledge","有效依据","T1","normal",null,1);
        when(knowledge.retrieve(anyString(),any())).thenReturn(new com.pams.module.knowledge.service.KnowledgeQuery.RetrievalOutcome(List.of(chunk),1,0,1,0,null));
        when(caller.callLlm(anyList(),anyList(),any(),any(),anyInt())).thenAnswer(i->{
            List<LlmProvider.ChatMessage> input=i.getArgument(0);
            assertThat(input).anyMatch(m->m.content()!=null&&m.content().contains("先给结论"));
            assertThat(input.getLast().content()).contains("有效依据");
            return LlmProvider.ChatResponse.text("依据[1]","test");
        });
        var kb=new ToolContext(ctx().getLoginUser(),List.of(),null,null,"kb");
        assertThat(orchestrator.chatSync("示例问题",null,kb,List.of(),false).get("reply")).asString().contains("依据");
    }
    @Test void preferenceNeverReplacesMissingKnowledgeEvidence() {
        memory.save(ctx(),"USER","风格","先给结论",true,null,null,90);
        when(knowledge.retrieve(anyString(),any())).thenReturn(new com.pams.module.knowledge.service.KnowledgeQuery.RetrievalOutcome(List.of(),0,0,0,0,"NO_HITS"));
        var kb=new ToolContext(ctx().getLoginUser(),List.of(),null,null,"kb");
        orchestrator.chatSync("没有依据的问题",null,kb,List.of(),false);verifyNoModelCalls();
    }
    private void verifyNoModelCalls(){
        // Task lifecycle bookkeeping is expected even when a command bypasses model inference.
        verify(caller,never()).callLlm(anyList(),anyList(),any(),any(),anyInt());
        verify(caller,never()).callIsolated(anyList(),anyList(),any());
        verify(caller,never()).callSummary(anyList(),any());
        verify(caller,never()).callPlanning(anyList(),any());
    }
}
