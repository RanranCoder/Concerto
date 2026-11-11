package com.pams.module.agent.service;
import com.pams.common.OrgContext;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentMessage;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test") @Transactional
class ConversationRecallIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource static void database(org.springframework.test.context.DynamicPropertyRegistry r){com.pams.module.agent.testing.BatchSixDatabase.configure(r);}
    @Autowired ConversationStore store;
    @BeforeEach void org(){OrgContext.set(1L);}
    @AfterEach void clear(){OrgContext.clear();}
    @Test void keywordSearchWorksOnLongStoredTextAndRemainsOwnerScoped(){
        var c=store.create(1L,"历史回查");var ctx=new ToolContext(null,List.of(),c.getId(),null);
        store.save(c.getId(),AgentMessage.ROLE_USER,"保留".repeat(2000)+" Alpha 100%_预算",Sensitivity.NORMAL,"",null,null,"recall-fixture","fixture",0);
        assertThat(store.recall(c.getId(),1L,ctx,"alpha")).contains("messageId=");
        assertThat(store.recall(c.getId(),1L,ctx,"%_")).contains("messageId=");
        assertThat(store.recall(c.getId(),1L,ctx,"不存在的词")).doesNotContain("messageId=");
        assertThatThrownBy(()->store.recall(c.getId(),2L,ctx,"alpha")).hasMessageContaining("无权");
    }
}
