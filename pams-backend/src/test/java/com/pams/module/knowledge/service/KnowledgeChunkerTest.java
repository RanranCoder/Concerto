package com.pams.module.knowledge.service;

import com.pams.module.knowledge.source.KnowledgeSource;
import com.pams.module.knowledge.source.PlainTextSectioning;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分块与标题切分的确定性回归。
 *
 * <p>这条链路不出"错误答案"，出的是**引用的可核验性**：切错了，
 * 用户看到的出处就定位不到原文（"第三章 第二十六条"变成"文档第 3 块"）。
 * 所以断言的重点是 {@code path} 的稳定性，而不是块数的精确值。
 */
class KnowledgeChunkerTest {

    private final KnowledgeChunker chunker = new KnowledgeChunker();

    private static KnowledgeSource.Section sec(String path, String content) {
        return KnowledgeSource.Section.of(path, content);
    }

    private static KnowledgeSource.KnowledgeDoc doc(KnowledgeSource.Section... sections) {
        return KnowledgeSource.KnowledgeDoc.of("manual", 1L, "测试制度",
                KnowledgeSource.Tier.T1, List.of(sections), null);
    }

    /* ------------------------------------------------------------ 标题切分 */

    @Test
    void chapterAndArticleBecomePath() {
        String body = """
                第三章 发展对象的确定
                第二十五条 党支部应当认真听取各方面意见。
                第二十六条 入党介绍人应当由正式党员担任。
                """;

        List<KnowledgeSource.Section> sections = PlainTextSectioning.split(body);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).path()).isEqualTo("第三章 第二十五条");
        assertThat(sections.get(0).content()).contains("认真听取");
        assertThat(sections.get(1).path()).isEqualTo("第三章 第二十六条");
        // 章标题本身不该单独成段（否则会产出一条没有正文的空块）
        assertThat(sections).extracting(KnowledgeSource.Section::path)
                .doesNotContain("第三章");
    }

    @Test
    void textWithoutHeadingsYieldsSingleSectionWithNullPath() {
        String body = "这是一段没有任何编号标题的说明文字。它应该整段保留。";

        List<KnowledgeSource.Section> sections = PlainTextSectioning.split(body);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).path()).as("无标题时路径留空，不要伪造一个").isNull();
        assertThat(sections.get(0).content()).contains("整段保留");
    }

    @Test
    void longLineIsNotMistakenForHeading() {
        String longLine = "第一条" + "很长的内容".repeat(30);   // 以"第一条"开头但明显是正文

        List<KnowledgeSource.Section> sections = PlainTextSectioning.split(longLine);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).path()).isNull();
    }

    @Test
    void blankBodyYieldsNoSections() {
        assertThat(PlainTextSectioning.split(null)).isEmpty();
        assertThat(PlainTextSectioning.split("   \n\n  ")).isEmpty();
    }

    /* ------------------------------------------------------------ 段内切分 */

    @Test
    void shortSectionStaysSingleChunk() {
        List<KnowledgeChunker.ChunkDraft> drafts =
                chunker.chunk(doc(sec("第一条", "简短内容。")));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).seq()).isZero();
        assertThat(drafts.get(0).title()).isEqualTo("测试制度");
        assertThat(drafts.get(0).path()).isEqualTo("第一条");
    }

    @Test
    void longSectionIsSplitAndKeepsPath() {
        String longText = "党支部应当认真做好各项工作。".repeat(80);   // ≈ 1120 字

        List<KnowledgeChunker.ChunkDraft> drafts = chunker.chunk(doc(sec("第二章 第八条", longText)));

        assertThat(drafts.size()).as("长段必须被切开").isGreaterThan(1);
        assertThat(drafts).allSatisfy(d -> {
            assertThat(d.path()).as("同一段切出的块必须继承同一个标题路径").isEqualTo("第二章 第八条");
            assertThat(d.content().length())
                    .as("单块不得超过上限")
                    .isLessThanOrEqualTo(KnowledgeChunker.TARGET_MAX);
        });
        // seq 在文档内全局递增，不能每段从 0 重来（否则唯一键 uk_kb_chunk_source 会冲突）
        assertThat(drafts).extracting(KnowledgeChunker.ChunkDraft::seq)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .range(0, drafts.size()).boxed().toList());
    }

    @Test
    void seqIsGlobalAcrossSections() {
        String longText = "内容内容内容。".repeat(100);

        List<KnowledgeChunker.ChunkDraft> drafts = chunker.chunk(doc(
                sec("第一条", longText), sec("第二条", longText)));

        List<Integer> seqs = drafts.stream().map(KnowledgeChunker.ChunkDraft::seq).toList();
        assertThat(seqs).as("跨段必须继续递增，否则唯一键冲突").isSorted();
        assertThat(seqs).doesNotHaveDuplicates();
        assertThat(seqs.get(0)).isZero();
    }

    @Test
    void emptySectionsAreSkipped() {
        assertThat(chunker.chunk(doc(sec("第一条", "  "), sec("第二条", "")))).isEmpty();
        assertThat(chunker.chunk(doc())).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void chunkCountIsCapped() {
        // 单段足够长，足以触发上限
        String huge = "句子句子句子。".repeat(3000);
        List<KnowledgeSource.Section> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(sec("第" + i + "条", huge));

        List<KnowledgeChunker.ChunkDraft> drafts = chunker.chunk(
                KnowledgeSource.KnowledgeDoc.of("manual", 1L, "超大文档",
                        KnowledgeSource.Tier.T1, many, null));

        assertThat(drafts).hasSize(KnowledgeChunker.MAX_CHUNKS_PER_DOC);
    }
}
