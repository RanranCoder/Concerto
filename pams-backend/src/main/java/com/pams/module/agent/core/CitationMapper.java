package com.pams.module.agent.core;

import com.pams.module.knowledge.retriever.ScoredChunk;
import com.pams.module.knowledge.source.KnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用回填（方案 §5.1 的<b>硬约束</b>）。
 *
 * <pre>
 * 决定"有哪些出处"      服务端（检索命中的 chunk 列表，模型看不到也没法改）
 * 写答案正文            模型（允许写 [1] [3] 这类编号占位）
 * 解析编号 → 出处元数据  服务端 ← 本类
 * 编号越界（模型编造 [9]）服务端丢弃，不报错、不中断，只计数
 * </pre>
 *
 * <p><b>为什么这条必须由服务端做</b>：只要允许模型自己写文件名、条款号，
 * 就一定会出现编造出处 —— 而这比"不知道"严重得多，它会摧毁用户对整个系统的信任。
 * 采用本设计后，模型**物理上无法编造出处**：它最多只能"引用第几条已有依据"，
 * 选错是可监控的质量问题，不是安全问题。
 *
 * <p>⚠️ <b>入参只接受"复核通过"的集合</b>：{@code allowed} 必须来自
 * {@code PermissionResolver.FilteredChunks}。若从粗筛结果构造 citations，
 * 会出现"答案正文干净、出处带毒"（方案 G2）—— 答案看起来合规，
 * 但依据片段里可能含着用户无权看到的内容。
 *
 * <p>这也是本类不提供"按 sourceType 自己去查 chunk"这类便利方法的原因：
 * 入口越少，越不容易有人绕过来。
 */
@Slf4j
@Component
public class CitationMapper {

    /** 与 {@code KbChunk.asOf} 配套：这个源类型必须有数据截止时间（方案 R16） */
    private static final String SNAPSHOT_SOURCE_TYPE = "snapshot";

    private static final Pattern REF = Pattern.compile("\\[(\\d{1,3})]");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Map<String, KnowledgeSource> sources = new LinkedHashMap<>();

    public CitationMapper(List<KnowledgeSource> all) {
        for (KnowledgeSource s : all) sources.put(s.sourceType(), s);
    }

    /**
     * 从答案里解析编号并回填成引用列表。
     *
     * @param answer           模型产出的答案正文
     * @param allowed          **复核通过**的命中（顺序即编号 1..n）
     * @param maxSnippetChars  片段摘要截断长度（控制响应体大小，不影响给 LLM 的完整正文）
     */
    public MapResult map(String answer, List<ScoredChunk> allowed, int maxSnippetChars) {
        if (allowed == null || allowed.isEmpty()) return new MapResult(List.of(), 0, 0);

        List<KnowledgeCitation> out = new ArrayList<>();
        int fabricated = 0;
        int asOfRejected = 0;

        Set<Integer> seen = new LinkedHashSet<>();   // 去重且保序：同一编号只出一条引用
        Matcher m = REF.matcher(answer == null ? "" : answer);
        while (m.find()) {
            int no;
            try {
                no = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (no < 1 || no > allowed.size()) {
                fabricated++;   // 越界编号：丢弃而非报错 —— 模型幻觉不该让整轮回答失败
                continue;
            }
            if (!seen.add(no)) continue;

            ScoredChunk c = allowed.get(no - 1);

            // R16 硬断言：快照类引用必须带数据截止时间，否则用户会把"某时刻的数字"
            // 当成"当前值"读 —— 拿着过期数字做决策比不答更糟
            if (SNAPSHOT_SOURCE_TYPE.equals(c.sourceType()) && c.asOf() == null) {
                asOfRejected++;
                log.warn("知识库引用被拒：快照类来源 {}:{} 缺少 asOf（方案 R16 硬约束）",
                        c.sourceType(), c.sourceId());
                continue;
            }

            KnowledgeSource src = sources.get(c.sourceType());
            out.add(new KnowledgeCitation(
                    no,
                    c.sourceType(),
                    c.sourceId(),
                    c.title(),
                    c.path(),
                    c.snippet(maxSnippetChars),
                    src == null ? null : src.link(c.sourceId()),
                    c.asOf(),
                    false));
        }

        if (fabricated > 0) {
            // 这条计数是质量指标（方案 §11「编造编号次数」应接近 0）
            log.info("知识库引用回填：模型输出了 {} 个越界编号，已丢弃", fabricated);
        }
        return new MapResult(out, fabricated, asOfRejected);
    }

    /**
     * 答案是否带了引用编号。
     *
     * <p>用于闸口 C：<b>零引用的答案不能静默通过</b>。
     * 注意这里只判"有没有编号"，不判"编号对不对"—— 后者由 {@link #map} 的越界计数负责。
     */
    public boolean hasCitation(String answer) {
        return answer != null && REF.matcher(answer).find();
    }

    /**
     * 引用条目。字段与前端 {@code AgentCitation} 一一对应。
     *
     * @param index       答案里的编号（前端渲染成可点角标）
     * @param sourceType  出处类型 —— 前端**不能假设一定是人工文档**（D12）
     * @param asOf        数据截止时间；快照类必填，前端要显式展示"数据截至…"
     * @param sourceDeleted 源已删除（历史引用回看时源可能已被删）
     */
    public record KnowledgeCitation(int index,
                                    String sourceType,
                                    Long sourceId,
                                    String sourceTitle,
                                    String path,
                                    String snippet,
                                    String link,
                                    LocalDateTime asOf,
                                    boolean sourceDeleted) {

        /** 前端展示用：{@code 2026-09-14}；无截止时间时为 null */
        public String asOfDate() {
            return asOf == null ? null : asOf.format(DATE);
        }
    }

    /**
     * @param fabricated   模型编造的越界编号数（应接近 0）
     * @param asOfRejected 因缺少数据截止时间而被拒的快照引用数（应为 0，非 0 是缺陷）
     */
    public record MapResult(List<KnowledgeCitation> citations, int fabricated, int asOfRejected) {
        public boolean empty() { return citations.isEmpty(); }
    }
}
