package com.pams.module.knowledge.listener;

import com.pams.module.knowledge.service.KnowledgeIndexer;
import com.pams.module.member.event.MemberChangedEvent;
import com.pams.module.notification.event.AnnouncementPublishedEvent;
import com.pams.module.notification.event.ArticlePublishedEvent;
import com.pams.module.notification.event.ArticleReviewedEvent;
import com.pams.module.notification.event.PlanEditedEvent;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import com.pams.module.knowledge.source.ActivityPlanSource;
import com.pams.module.knowledge.source.AnnouncementSource;
import com.pams.module.knowledge.source.ArticleSource;
import com.pams.module.knowledge.source.MemberSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库增量重建监听器（方案 §2.3「索引必须跟随源」的主机制）。
 *
 * <pre>
 * 源写入提交 ──AFTER_COMMIT 事件──▶ 本类 ──▶ agentExecutor ──▶ KnowledgeIndexer.reindexSource
 * </pre>
 *
 * <h3>为什么必须 AFTER_COMMIT</h3>
 * <b>索引更新不能与业务事务耦合。</b>如果放在事务内，索引写失败的后果是
 * <b>业务操作跟着回滚</b> —— 发布一条通知因为"索引写失败"而失败，是本末倒置。
 * 放在提交之后，业务已经落定，索引失败最多是"知识库暂时少一条内容"，
 * 由每日兜底重建补齐（{@code KnowledgeRebuildTask}）。
 *
 * <h3>为什么加 {@code fallbackExecution = true}</h3>
 * {@code @TransactionalEventListener} 的默认行为是：<b>发布时没有事务就静默不执行</b>。
 * 那是一种极难发现的失效 —— 事件发出去了、日志什么都没有、索引就是不更新。
 * 当前几个发布方（{@code AnnouncementService.create}、{@code ArticleService.publish}、
 * {@code PlanService.*}）都在 {@code @Transactional} 内，但将来有人在不带事务的方法里发事件时，
 * 打开 fallback 至少能保证"照常重建"，而不是无声消失。
 *
 * <h3>为什么投递到 agentExecutor 而不是同步执行</h3>
 * 提交后同步重建会把索引耗时加到用户的请求上（导入、批量删除这类操作尤其明显）。
 * 投给已有的 {@code agentExecutor}（有界队列 + 已包 SecurityContext 传播）而不是新起线程池，
 * 是为了不引入第二套并发控制。队列满时<b>放弃本次增量</b>并记日志 ——
 * 这比阻塞业务请求好，且每日兜底会补齐。
 *
 * <h3>覆盖面：哪些变更不会触发增量</h3>
 * 只监听<b>已存在的事件</b>。以下情况依赖每日兜底重建：
 * <ul>
 *   <li>稿件被删除 / 从已发布退回（没有对应事件）</li>
 *   <li>公告被修改（只有 {@code AnnouncementPublishedEvent}，没有 updated 事件）</li>
 *   <li>成员批量变更走的是"整源重建"，粒度较粗但正确</li>
 * </ul>
 * 这是刻意不扩面：为每种变更补一个事件要动多个业务 Service，
 * 而每日兜底已经能保证"最终一致"。先把主路径做对，长尾靠兜底。
 */
@Slf4j
@Component
public class KnowledgeIndexListener {
    @org.springframework.beans.factory.annotation.Autowired
    private com.pams.module.notification.service.NotificationIncidentService incidents;

    private final KnowledgeIndexer indexer;
    private final Executor executor;

    public KnowledgeIndexListener(KnowledgeIndexer indexer,
                                  @Qualifier("agentExecutor") Executor executor) {
        this.indexer = indexer;
        this.executor = executor;
    }

    /* ---------------------------------------------------------------- 公告 */

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnnouncementPublished(AnnouncementPublishedEvent e) {
        enqueueOne(AnnouncementSource.SOURCE_TYPE, e.getAnnouncementId());
    }

    /* ---------------------------------------------------------------- 稿件 */

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onArticlePublished(ArticlePublishedEvent e) {
        enqueueOne(ArticleSource.SOURCE_TYPE, e.getArticleId());
    }

    /**
     * 审核结果会改变稿件能否进索引：只有 PUBLISHED 才收录（未定稿不作为依据，
     * 见 {@code ArticleSource} 的说明），所以审核动作必须触发重建 ——
     * 通过审核后若不等发布就直接重建，条目仍会被排除，行为是正确的。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onArticleReviewed(ArticleReviewedEvent e) {
        enqueueOne(ArticleSource.SOURCE_TYPE, e.getArticleId());
    }

    /* ---------------------------------------------------------------- 策划书 */

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlanEdited(PlanEditedEvent e) {
        enqueueOne(ActivityPlanSource.SOURCE_TYPE, e.getPlanId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlanSubmitted(PlanSubmittedEvent e) {
        enqueueOne(ActivityPlanSource.SOURCE_TYPE, e.getPlanId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPlanReviewed(PlanReviewedEvent e) {
        enqueueOne(ActivityPlanSource.SOURCE_TYPE, e.getPlanId());
    }

    /* ---------------------------------------------------------------- 成员 */

    /**
     * 成员变更。
     *
     * <p>{@code memberId == null} 表示批量变更（导入/批量删除/归档）→ 整源重建。
     * 不逐条发事件是因为导入 500 人会往队列塞 500 个任务，反而把队列打满。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMemberChanged(MemberChangedEvent e) {
        if (e.getMemberId() == null) {
            enqueueAll(MemberSource.SOURCE_TYPE);
        } else {
            enqueueOne(MemberSource.SOURCE_TYPE, e.getMemberId());
        }
    }

    /* ---------------------------------------------------------------- 投递 */

    private void enqueueOne(String sourceType, Long sourceId) {
        if (sourceId == null) return;
        submit(() -> indexer.reindexSource(sourceType, sourceId), sourceType + ":" + sourceId);
    }

    private void enqueueAll(String sourceType) {
        submit(() -> indexer.reindexSourceType(sourceType), sourceType + "（整源）");
    }

    private void submit(Runnable task, String what) {
        Long org=com.pams.common.OrgContext.get();
        if(org==null) { log.warn("知识库增量重建缺少组织上下文，跳过提交");return; }
        try {
            executor.execute(() -> {
                Long previous=com.pams.common.OrgContext.get();com.pams.common.OrgContext.set(org);
                try {
                    task.run();
                } catch (Exception e) {
                    // 索引失败绝不影响业务（业务事务早已提交），只留日志；每日兜底会补齐
                    log.warn("知识库增量重建失败（将由每日兜底重建补齐）：{}", what, e);
                    incident(org,com.pams.module.notification.service.NotificationIncidentService.Category.KB_INDEX_FAILURE);
                } finally {
                    if(previous==null)com.pams.common.OrgContext.clear();else com.pams.common.OrgContext.set(previous);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("知识库增量重建被丢弃（执行队列已满）：{} —— 每日兜底重建会补齐", what);
            incident(org,com.pams.module.notification.service.NotificationIncidentService.Category.KB_QUEUE_REJECTED);
        }
    }
    private void incident(long org,com.pams.module.notification.service.NotificationIncidentService.Category category) {
        try { if(incidents!=null)incidents.record(org,category); }
        catch(RuntimeException e) { log.warn("知识库故障台账写入失败 org={} category={}",org,category,e); }
    }

    /** 供测试与排障：把事件类型映射成源类型，避免各处硬编码字符串 */
    static String sourceTypeOf(Object event) {
        if (event instanceof AnnouncementPublishedEvent) return AnnouncementSource.SOURCE_TYPE;
        if (event instanceof ArticlePublishedEvent || event instanceof ArticleReviewedEvent) {
            return ArticleSource.SOURCE_TYPE;
        }
        if (event instanceof PlanEditedEvent || event instanceof PlanSubmittedEvent
                || event instanceof PlanReviewedEvent) {
            return ActivityPlanSource.SOURCE_TYPE;
        }
        if (event instanceof MemberChangedEvent) return MemberSource.SOURCE_TYPE;
        return null;
    }
}
