package com.pams.module.agent.service;

import com.pams.common.OrgContext;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.tool.DraftArticleFromActivityTool;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class ScopedInternalWriteIntegrationTest {
    @Autowired private ActivityRepository activities;
    @Autowired private ArticleRepository articles;
    @Autowired private AgentRequestService requests;
    @Autowired private ConversationStore conversations;
    @Autowired private ToolOutcomeHandler outcomes;
    @Autowired private DraftArticleFromActivityTool tool;
    @Autowired private ApplicationEvents events;
    private Long activityId;

    @BeforeEach
    void createActivity() {
        OrgContext.set(1L);
        var activity = new Activity();
        activity.setName("可逆内部操作测试活动");
        activity.setType("OTHER");
        activity.setStatus(ActivityStatus.EXECUTING);
        activity.setStartDate(LocalDate.of(2026, 11, 16));
        activity.setDeleted(0);
        activity.setCreatedBy(1L);
        activityId = activities.saveAndFlush(activity).getId();
    }

    @AfterEach
    void clearTenant() { OrgContext.clear(); }

    @Test
    void exactCurrentUserInstructionCreatesOneQuietDraftAndRecordsTheAuthorizingMessage() {
        var ctx = currentTurn("为活动 #" + activityId + " 保存一份本人报道草稿");
        long before = articles.count();
        var result = prepare(ctx, Map.of("activityId", activityId));
        assertThat(result.waiting()).isFalse();
        assertThat(result.receipt()).containsEntry("status", "SUCCESS");
        var receipt = (Map<?, ?>) result.receipt().get("result");
        var provenance = (Map<?, ?>) receipt.get("authorization");
        assertThat(provenance.get("type")).isEqualTo("SCOPED_USER_MESSAGE");
        long articleId = ((Number) ((Map<?, ?>) receipt.get("detail")).get("articleId")).longValue();
        var article = articles.findById(articleId).orElseThrow();
        assertThat(article.getStatus()).isEqualTo(Article.ArticleStatus.DRAFT);
        assertThat(article.getAuthorId()).isEqualTo(1L);
        assertThat(article.getDeadline()).isNull();
        assertThat(events.stream(com.pams.module.notification.event.ArticleAssignedEvent.class)).isEmpty();
        assertThat(requests.owned(ctx.getRequestId(), 1L).getDelegatedTaskId())
                .isEqualTo(((Number) result.receipt().get("id")).longValue());

        var second = prepare(ctx, Map.of("activityId", activityId));
        assertThat(second.plan()).isNotNull();
        assertThat(second.receipt()).isNull();
        assertThat(articles.count()).isEqualTo(before + 1);
    }

    @Test
    void previewOnlyAndDifferentOwnerRemainUnexecuted() {
        var preview = currentTurn("先预览活动 #" + activityId + " 的报道草稿");
        long before = articles.count();
        assertThat(prepare(preview, Map.of("activityId", activityId, "saveDraft", true)).plan()).isNotNull();
        assertThat(requests.owned(preview.getRequestId(), 1L).getDelegatedTaskId()).isNull();
        var save = currentTurn("为活动 #" + activityId + " 保存一份本人报道草稿");
        assertThat(prepare(save, Map.of("activityId", activityId, "authorId", 999L)).plan()).isNotNull();
        assertThat(requests.owned(save.getRequestId(), 1L).getDelegatedTaskId()).isNull();
        assertThat(articles.count()).isEqualTo(before);
    }

    private ToolContext currentTurn(String text) {
        var user = new LoginUser(1L, "teacher", "测试", "TEACHER", 5, null, null, 1L);
        var authorities = List.of(new SimpleGrantedAuthority("article:create"), new SimpleGrantedAuthority("activity:view"));
        Long conversationId = conversations.create(1L, text).getId();
        String stream = UUID.randomUUID().toString();
        var ctx = new ToolContext(user, authorities, conversationId, stream).withRequestId(UUID.randomUUID().toString());
        requests.begin(ctx.getRequestId(), stream, ctx);
        conversations.save(conversationId, AgentMessage.ROLE_USER, text, Sensitivity.NORMAL, text, null,
                List.of(), stream, null, null, ctx.getMode(), null);
        return ctx;
    }

    private ToolOutcomeHandler.PendingAction prepare(ToolContext ctx, Map<String, Object> args) {
        var result = tool.execute(ctx, args);
        assertThat(result.plan()).as(result.summary()).isNotNull();
        return outcomes.prepare(ctx, tool, result, new LlmProvider.ToolCallRequest("scoped-write", tool.name(), args));
    }
}
