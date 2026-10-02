package com.pams.module.agent.core;

import com.pams.module.knowledge.retriever.ScoredChunk;
import com.pams.module.knowledge.source.KnowledgeSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用回填（方案 §5.1 硬约束）的确定性回归。
 *
 * <p><b>这条链路的失败模式是"看起来正常"</b>：模型编造一个
 * {@code 《中国共产党发展党员工作细则》第 999 条}，如果服务端不做回填核验，
 * 用户看到的是一份格式完美、出处具体的答案 —— 而这比"我不知道"危险得多，
 * 它会摧毁对整个系统的信任。所以这里钉的不是"能否解析"，而是四类边界：
 * <ol>
 *   <li>越界编号<b>丢弃而不报错</b>（模型幻觉不该让整轮回答失败，但要计数）</li>
 *   <li>同一编号只出一条引用（模型常把同一依据标多次）</li>
 *   <li>编号顺序即 {@code allowed} 顺序 —— 顺序错一位，引用会整体指向错文档</li>
 *   <li>快照类来源<b>缺少数据截止时间必须拒绝出网</b>（R16：不能让用户把
 *       "某时刻的数字"当"当前值"读）</li>
 * </ol>
 */
class CitationMapperTest {

    /** 只用于提供 link() 的最小源桩 */
    private static KnowledgeSource stubSource(String type) {
        return new KnowledgeSource() {
            @Override public String sourceType() { return type; }
            @Override public String label() { return type; }
            @Override public String description() { return type; }
            @Override public Tier tier() { return Tier.T1; }
            @Override public double weight() { return 1.0; }
            @Override public List<KnowledgeDoc> extractAll() { return List.of(); }
            @Override public List<KnowledgeDoc> extractOne(Long id) { return List.of(); }
            @Override public boolean canAccess(ToolContext ctx, Long id) { return true; }
            @Override public String link(Long id) { return "/x/" + id; }
        };
    }

    private static CitationMapper mapper() {
        return new CitationMapper(List.of(stubSource("manual"), stubSource("announcement"),
                stubSource("snapshot")));
    }

    private static ScoredChunk chunk(long id, String type, String title, String path,
                                     LocalDateTime asOf) {
        return new ScoredChunk(id, type, id, type + ":" + id, 0, title, path,
                "正文内容" + id, "T1", "normal", asOf, 1.0);
    }

    @Test
    void mapsInOrderOfAppearanceAndDropsDuplicates() {
        List<ScoredChunk> allowed = List.of(
                chunk(11, "manual", "发展党员工作细则", "第三章 第二十六条", null),
                chunk(22, "announcement", "关于做好发展党员工作的通知", "第二部分", null));

        CitationMapper.MapResult r = mapper().map("申请人提出后应当…[1]，另外还需…[2]，参见[1]。",
                allowed, 100);

        assertThat(r.citations()).hasSize(2);
        assertThat(r.citations().get(0).index()).isEqualTo(1);
        assertThat(r.citations().get(0).sourceType()).isEqualTo("manual");
        assertThat(r.citations().get(0).sourceTitle()).isEqualTo("发展党员工作细则");
        assertThat(r.citations().get(0).path()).isEqualTo("第三章 第二十六条");
        assertThat(r.citations().get(0).link()).isEqualTo("/x/11");
        assertThat(r.citations().get(1).index()).isEqualTo(2);
        assertThat(r.fabricated()).isZero();
    }

    /** 越界编号丢弃而非报错 —— 这是"模型幻觉不能拖垮主链路"的具体体现 */
    @Test
    void fabricatedIndexIsDroppedNotThrown() {
        List<ScoredChunk> allowed = List.of(chunk(11, "manual", "制度", null, null));

        CitationMapper.MapResult r = mapper().map("据《某规定》第 999 条[7]，应当…[1]", allowed, 100);

        assertThat(r.citations()).hasSize(1);
        assertThat(r.citations().get(0).index()).isEqualTo(1);
        assertThat(r.fabricated()).as("越界编号要计数（质量指标：应接近 0）").isEqualTo(1);
    }

    @Test
    void zeroIndexIsTreatedAsFabricated() {
        List<ScoredChunk> allowed = List.of(chunk(11, "manual", "制度", null, null));

        CitationMapper.MapResult r = mapper().map("见[0]", allowed, 100);

        assertThat(r.citations()).isEmpty();
        assertThat(r.fabricated()).isEqualTo(1);
    }

    @Test
    void textWithoutAnyIndexProducesNoCitation() {
        List<ScoredChunk> allowed = List.of(chunk(11, "manual", "制度", null, null));

        CitationMapper.MapResult r = mapper().map("我觉得应该是这样的。", allowed, 100);

        assertThat(r.empty()).isTrue();
        assertThat(mapper().hasCitation("我觉得应该是这样的。")).isFalse();
        assertThat(mapper().hasCitation("见[1]")).isTrue();
    }

    @Test
    void emptyAllowedYieldsNothing() {
        assertThat(mapper().map("见[1]", List.of(), 100).empty()).isTrue();
        assertThat(mapper().map("见[1]", null, 100).empty()).isTrue();
    }

    /**
     * R16 硬断言：快照类引用缺 {@code asOf} 必须被拒。
     *
     * <p>不做这条检查的后果：用户问"现在有多少党员"，命中一条两天前的快照，
     * 答案里写"87 人"而没有任何时间限定 —— 拿着过期数字做决策比不答更糟。
     */
    @Test
    void snapshotCitationWithoutAsOfIsRejected() {
        List<ScoredChunk> allowed = List.of(
                chunk(1, "manual", "制度", null, null),
                chunk(2, "snapshot", "支部情况快照", "数据快照", null));   // asOf 缺失

        CitationMapper.MapResult r = mapper().map("据快照[2]与制度[1]", allowed, 100);

        assertThat(r.asOfRejected()).isEqualTo(1);
        assertThat(r.citations()).extracting(CitationMapper.KnowledgeCitation::sourceType)
                .as("缺 asOf 的快照引用不应出现在结果里")
                .containsExactly("manual");
    }

    @Test
    void snapshotCitationWithAsOfIsKeptAndExposesDate() {
        List<ScoredChunk> allowed = List.of(
                chunk(3, "snapshot", "支部情况快照", "数据快照", LocalDateTime.of(2026, 9, 14, 8, 0)));

        CitationMapper.MapResult r = mapper().map("截至最近统计[1]", allowed, 100);

        assertThat(r.asOfRejected()).isZero();
        assertThat(r.citations()).hasSize(1);
        assertThat(r.citations().get(0).asOfDate())
                .as("前端要显式展示「数据截至 …」")
                .isEqualTo("2026-09-14");
    }

    @Test
    void snippetIsTruncatedAndFlattened() {
        ScoredChunk longChunk = new ScoredChunk(9, "manual", 9L, "manual:9", 0, "标题", null,
                "第一行\n第二行\n第三行" + "内容".repeat(50), "T1", "normal", null, 1.0);

        CitationMapper.MapResult r = mapper().map("[1]", List.of(longChunk), 10);

        String snippet = r.citations().get(0).snippet();
        assertThat(snippet).doesNotContain("\n");
        assertThat(snippet).endsWith("…");
        assertThat(snippet.length()).isLessThanOrEqualTo(11);
    }

    /** 未知 sourceType（适配器被删但分块还在）不应让整条链路崩，link 为 null 即可 */
    @Test
    void unknownSourceTypeStillProducesCitationWithoutLink() {
        List<ScoredChunk> allowed = List.of(chunk(5, "removed_source", "遗留源", null, null));

        CitationMapper.MapResult r = mapper().map("[1]", allowed, 50);

        assertThat(r.citations()).hasSize(1);
        assertThat(r.citations().get(0).link())
                .as("没有可跳转页面时返回 null，前端渲染成不可点（不给必然 404 的链接）")
                .isNull();
    }
}
