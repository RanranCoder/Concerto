package com.pams.module.knowledge.retriever;

import com.pams.module.agent.core.ToolContext;

import java.util.List;

/**
 * 检索接口（SPI 接缝）。
 *
 * <p><b>为什么要抽接口</b>：一期是关键词检索（{@link KeywordRetriever}），
 * 二期可能接向量或混合检索。抽接口的价值不在于"将来可能换"，
 * 而在于把**替换成本限定在一个类里** —— 编排层（{@code KbPipeline}）、
 * 权限复核（{@code PermissionResolver}）、引用回填、前端契约都不感知实现。
 *
 * <p>⚠️ <b>但替换不是零成本，唯一真正的成本在权限</b>：
 * 本接口的实现必须把可见性条件**下推到数据源**（SQL 的 WHERE 或向量库的 filter）。
 * 若某个实现是"先取 TopK 再过滤"，就会把不该看的正文带进内存 ——
 * 这是方案 §6.2 闸口 A 明令禁止的。新增实现时必须自己保证这一点。
 */
public interface KnowledgeRetriever {

    /** 实现标识，用于配置切换（{@code pams.agent.kb.retriever}）与观测 */
    String name();

    /**
     * 检索。
     *
     * @param query 检索用的 query（一期 = 用户原话；P1 起可能是改写后的检索词）
     * @param ctx   用户上下文 —— 权限粗筛必须用它，不能忽略
     * @param topK  最多返回多少条
     * @return 按相关度降序的命中；**未做权限复核**，调用方必须再过
     *         {@code PermissionResolver}（方案 §6.4）
     */
    List<ScoredChunk> search(String query, ToolContext ctx, int topK);
}
