package com.pams.module.agent.service;

import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.entity.AgentTask;
import com.pams.module.agent.repository.AgentTaskRepository;
import com.pams.security.LoginUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test") @Transactional
class ConversationTaskHistoryTest {
    @Autowired ConversationStore conversations;
    @Autowired AgentTaskRepository tasks;
    @PersistenceContext EntityManager em;
    AgentTask task(long conversationId,String status) {
        var t=new AgentTask();t.setUserId(1L);t.setConversationId(conversationId);t.setStreamId("history-task");
        t.setToolName("generateActivitySchedule");t.setStatus(status);t.setCreatedAt(LocalDateTime.now());
        t.setPlanJson("{\"summary\":\"活动排班\",\"total\":1,\"items\":[],\"detailLines\":[],\"reversible\":true,\"skipped\":[{\"label\":\"周五下午\",\"reason\":\"人员不足\"}]}");
        t.setConfirmToken("test-token");return tasks.saveAndFlush(t);
    }
    @Test void listRestoresPendingCountWithoutExposingAnotherUsersTasks() {
        var c=conversations.create(1L,"任务状态回放");
        conversations.save(c.getId(),AgentMessage.ROLE_ASSISTANT,"已准备清单",Sensitivity.NORMAL,"",List.of(),List.of(),"history-task","stub",1);
        task(c.getId(),AgentTask.PENDING_CONFIRM);
        var outsider=task(c.getId(),AgentTask.RUNNING);outsider.setUserId(2L);tasks.saveAndFlush(outsider);
        em.clear();
        var vo=conversations.list(1L,1,50).getRecords().stream().filter(v->c.getId().equals(v.get("id"))).findFirst().orElseThrow();
        assertThat(vo.get("pendingTaskCount")).isEqualTo(1L);
        assertThat(vo.get("status")).isEqualTo("PENDING_CONFIRM");
        var user=new LoginUser(1L,"reader","读侧","DIRECTOR",4,null,null,1L);
        var ctx=new ToolContext(user,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DIRECTOR")),c.getId(),"history-task",ToolContext.MODE_STANDARD);
        var historical=conversations.messages(c.getId(),1L,1,50,ctx).getRecords().getFirst();
        assertThat(((Map<?,?>)historical.get("plan")).get("confirmToken")).isEqualTo("test-token");
    }
    @Test void reloadedTerminalTaskKeepsReceiptArtifactsAndSkippedItems() {
        var c=conversations.create(1L,"保存结果回放");
        conversations.save(c.getId(),AgentMessage.ROLE_ASSISTANT,"已准备清单",Sensitivity.NORMAL,"",List.of(),List.of(),"history-task","stub",1);
        var t=task(c.getId(),AgentTask.PARTIAL);
        t.setResultJson("{\"message\":\"已保存 1 个班次\",\"references\":[{\"type\":\"activity\",\"id\":1,\"label\":\"活动排班\",\"link\":\"/routine/activity-schedules\"}]}");
        tasks.saveAndFlush(t);em.clear();
        var user=new LoginUser(1L,"reader","读侧","DIRECTOR",4,null,null,1L);
        var ctx=new ToolContext(user,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DIRECTOR")),c.getId(),"history-task",ToolContext.MODE_STANDARD);
        var vo=conversations.messages(c.getId(),1L,1,50,ctx).getRecords().getFirst();
        var result=(Map<?,?>)vo.get("taskResult");
        assertThat(result.get("message")).isEqualTo("已保存 1 个班次");
        assertThat((List<?>)result.get("skipped")).hasSize(1);
        assertThat((List<?>)vo.get("artifacts")).hasSize(1);
        assertThat(vo.get("plan")).isNull();
    }
}
