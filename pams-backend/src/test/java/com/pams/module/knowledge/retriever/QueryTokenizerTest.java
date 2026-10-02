package com.pams.module.knowledge.retriever;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索词切分的确定性回归。
 *
 * <p><b>它守护的是一个曾经静默失效的功能</b>：T2（字段级）源与 LIKE 降级路径
 * 原先拿"用户整句"去做 {@code LIKE '%...%'}，于是：
 * <pre>
 * 用户问「组织部长是谁」 → LIKE '%组织部长是谁%' → 命中不了「张三 · 组织部 · 部长」
 * </pre>
 * 结果是这两个路径<b>永远返回零命中且不报错</b>。切词之后改成"命中任意一词即可"。
 *
 * <p>所以这里断言的重点是"能切出会命中的片段"，而不是分词本身的准确性 ——
 * bigram 一定会产生噪声片段（"是谁"），对 {@code LIKE} 无副作用，不必苛求。
 */
class QueryTokenizerTest {

    private final QueryTokenizer tokenizer = new QueryTokenizer();

    @Test
    void producesTokensThatWouldMatchAMemberTitle() {
        List<String> tokens = tokenizer.tokenize("组织部长是谁？");

        // 标题是「张三 · 组织部 · 部长」，必须至少有一个片段能命中
        assertThat(tokens).contains("组织").contains("织部").contains("部长");
    }

    @Test
    void keepsLatinWordsAndDigits() {
        List<String> tokens = tokenizer.tokenize("2024 年 development 计划");

        assertThat(tokens).contains("2024").contains("development");
    }

    @Test
    void capsTokenCount() {
        String longQuestion = "关于发展党员工作流程以及预备党员转正条件和党费收缴标准的详细规定是什么";

        assertThat(tokenizer.tokenize(longQuestion).size())
                .as("必须限流，否则会拼出几十个 LIKE 条件把 SQL 撑爆")
                .isLessThanOrEqualTo(QueryTokenizer.MAX_TOKENS);
    }

    @Test
    void emptyOrBlankYieldsNothing() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
        assertThat(tokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    void singleCharChineseIsKept() {
        assertThat(tokenizer.tokenize("党")).containsExactly("党");
    }
}
