package com.pams.module.agent.service;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.knowledge.service.KnowledgeQuery;
import com.pams.module.knowledge.retriever.ScoredChunk;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeEvidenceToolTest {
    final KnowledgeQuery query=mock(KnowledgeQuery.class);
    final org.springframework.jdbc.core.JdbcTemplate db=mock(org.springframework.jdbc.core.JdbcTemplate.class);
    final AgentExtensionReadService service=new AgentExtensionReadService(db,null,query);
    final ToolContext ctx=new ToolContext(new LoginUser(1L,"u","u","TEACHER",5,null,null,1L),List.of(),null,null);
    @BeforeEach void org(){OrgContext.set(1L);}@AfterEach void clear(){OrgContext.clear();}
    ScoredChunk fragment(long id,String level,String text){return new ScoredChunk(id,"kb_document",id,"kb_document:"+id,0,"制度","第一条",text,"T1",level,null,1);}
    @Test void allowedEvidenceIsMaskedBoundedAndMappedToExactSource(){
        when(query.retrieve("制度",ctx)).thenReturn(new KnowledgeQuery.RetrievalOutcome(List.of(fragment(1,"normal","原文".repeat(600)),fragment(2,"restricted","秘密"),fragment(3,"sensitive","联系13812345678")),3,0,1,0,null));
        var result=service.read("searchKnowledgeEvidence",ctx,Map.of("query","制度"));var rows=(List<?>)result.payload().get("evidence");assertThat(rows).hasSize(2);
        assertThat(result.payload().toString()).contains("kb_document:1:0","片段已截断","138****5678").doesNotContain("秘密","13812345678");
        assertThat(result.payload()).containsEntry("complete",false).containsEntry("scope","AUTHORIZED_RETRIEVAL_FRAGMENTS");
    }
    @Test void noHitAndDeniedHaveIdenticalPublicOutput(){
        when(query.retrieve("制度",ctx)).thenReturn(new KnowledgeQuery.RetrievalOutcome(List.of(),0,0,0,0,"NO_HIT"));var empty=service.read("searchKnowledgeEvidence",ctx,Map.of("query","制度"));
        when(query.retrieve("制度",ctx)).thenReturn(new KnowledgeQuery.RetrievalOutcome(List.of(),3,3,1,0,"NO_HIT"));assertThat(service.read("searchKnowledgeEvidence",ctx,Map.of("query","制度"))).isEqualTo(empty);
    }
    @Test void evidenceRereadIsBoundToConversationAndRejectsRevokedOrChangedChunks(){
        var chunks=mock(com.pams.module.knowledge.repository.KbChunkRepository.class);var permissions=mock(com.pams.module.knowledge.service.PermissionResolver.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"chunks",chunks);org.springframework.test.util.ReflectionTestUtils.setField(service,"permissions",permissions);
        String ref=UUID.randomUUID().toString();var row=new HashMap<String,Object>();row.put("chunk_id",7L);row.put("conversation_id",null);row.put("expires_at",java.time.LocalDateTime.now().plusMinutes(10));row.put("content_sha256",PlanDocumentReader.hash("完整证据"));
        when(db.queryForList(anyString(),eq(ref),eq(1L),eq(1L))).thenReturn(List.of(row));
        var chunk=new com.pams.module.knowledge.entity.KbChunk();chunk.setId(7L);chunk.setOrgId(1L);chunk.setSourceId(3L);chunk.setSourceType("kb_document");chunk.setSourceKey("kb_document:3");chunk.setSeq(0);chunk.setContent("完整证据");
        when(chunks.findById(7L)).thenReturn(Optional.of(chunk));when(permissions.filter(anyList(),eq(ctx))).thenReturn(new com.pams.module.knowledge.service.PermissionResolver.FilteredChunks(List.of(fragment(7,"normal","完整证据")),0));
        var result=service.read("readKnowledgeEvidence",ctx,Map.of("evidenceRef",ref));assertThat(result.payload()).containsEntry("text","完整证据").containsEntry("sourceDocumentComplete",false);
        when(permissions.filter(anyList(),eq(ctx))).thenReturn(new com.pams.module.knowledge.service.PermissionResolver.FilteredChunks(List.of(),1));assertThatThrownBy(()->service.read("readKnowledgeEvidence",ctx,Map.of("evidenceRef",ref))).hasMessageContaining("不可见");
        row.put("conversation_id",55L);assertThatThrownBy(()->service.read("readKnowledgeEvidence",ctx,Map.of("evidenceRef",ref))).hasMessageContaining("不可见");
    }
}
