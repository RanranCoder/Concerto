package com.pams.module.knowledge.task;

import com.pams.module.knowledge.service.KnowledgeIndexer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库每日兜底全量重建（方案 §2.3 的第三层保障）。
 *
 * <pre>
 * 主    AFTER_COMMIT 领域事件驱动增量重建（KnowledgeIndexListener）
 * 兜底  本任务：每日全量重建
 * </pre>
 *
 * <h3>为什么必须有兜底，而不是"事件就够了"</h3>
 * 领域事件有三类天然丢失场景，且都<b>不会报错</b>：
 * <ol>
 *   <li><b>没有对应事件</b>：稿件删除、公告修改、成员批量变更等路径当前不发事件
 *       （见 {@code KnowledgeIndexListener} 的覆盖面说明）</li>
 *   <li><b>进程重启窗口</b>：事件投给了内存队列，重启时队列里的任务就没了</li>
 *   <li><b>队列满被丢弃</b>：{@code RejectedExecutionException} 时我们选择丢弃而不是阻塞业务</li>
 * </ol>
 * 三者共同的结果都是"知识库停更且无人知晓"—— 而全量重建用一个廉价的定时任务
 * 把状态拉回与源一致，这就是它存在的全部理由。
 *
 * <h3>时间选择</h3>
 * 凌晨 3:40：避开 08:30 的稿件截止提醒任务（{@code ArticleDeadlineTask}），
 * 也避开工作时间。全量重建会清空并重写全部分块，
 * 期间检索到的是"重建中"的中间态（部分源已重建、部分未），
 * 由于每篇文档都是先删后插，最坏情况是短暂少给几条 —— 方向安全，可接受。
 */
@Slf4j
@Component
public class KnowledgeRebuildTask {

    private final KnowledgeIndexer indexer;

    /** 关闭开关：需要人工控制重建时机（如排查期）时设 false */
    @Value("${pams.agent.kb.daily-rebuild-enabled:true}")
    private boolean enabled;

    public KnowledgeRebuildTask(KnowledgeIndexer indexer) {
        this.indexer = indexer;
    }

    @Scheduled(cron = "${pams.agent.kb.daily-rebuild-cron:0 40 3 * * ?}")
    public void dailyRebuild() {
        if (!enabled) {
            log.info("知识库每日兜底重建已关闭（pams.agent.kb.daily-rebuild-enabled=false）");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            Map<String, Integer> report = indexer.reindexAll();
            log.info("知识库每日兜底重建完成：{}（耗时 {}ms）", report, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            // 定时任务抛出会把异常吞进调度器日志，显式记录并保持任务存活
            log.error("知识库每日兜底重建失败", e);
        }
    }
}
