package com.pams.module.knowledge.retriever;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索词切分（仅供 {@code LIKE} 类匹配使用）。
 *
 * <h3>为什么必须有它</h3>
 * 最初的实现是 {@code title LIKE '%' || 用户整句 || '%'}。这在真实提问下**永远匹配不到**：
 * <pre>
 * 用户问  「组织部长是谁？」
 * 标题是  「张三 · 组织部 · 部长」
 * LIKE '%组织部长是谁？%'  → 不命中（标题里不可能包含整句问话）
 * </pre>
 * 也就是说 T2（字段级）源在接入后**实际是不可检索的**，而这个失效是静默的 ——
 * 没有报错、没有日志，只是"问什么都答没有依据"。
 *
 * <p>所以把整句切成候选词，改成"命中任意一个词即可"。切分策略刻意简单：
 * <ul>
 *   <li><b>中文取二元组（bigram）</b>：与 MySQL ngram 解析器的默认
 *       {@code ngram_token_size=2} 对齐。不引入分词器 —— 分词器的词典在本领域
 *       （党务专有名词）上并不比 bigram 更准，却会带来一个需要维护的依赖。</li>
 *   <li><b>西文/数字取长度 ≥ 2 的整词</b>：英文与编号（如 {@code 2024}）按整词匹配更精确。</li>
 *   <li><b>去重并限流</b>：最多 {@link #MAX_TOKENS} 个，避免拼出几十个 {@code LIKE} 把 SQL 撑爆。</li>
 * </ul>
 *
 * <p><b>它只服务于 LIKE 匹配</b>：MySQL 的 {@code MATCH ... AGAINST} 内部有 ngram 分词，
 * 传整句给它才是正确用法，不要拿本类的结果去喂 {@code MATCH}（那会把 BM25 的
 * IDF 计算搞乱，反而降低 T1 的召回质量）。
 *
 * <p>缺点要说清楚：bigram 会产生大量噪声片段（"是谁"、"长的"）。
 * 对 {@code LIKE %..%} 来说它们只是"匹配不到任何东西"，无副作用；
 * 但对排序没有帮助 —— 所以 LIKE 路径本来就被定位为**降级兜底**，
 * 且 T2 命中只给固定低分（见 {@code KeywordRetriever}）。
 */
@Component
public class QueryTokenizer {

    /** 最多产出多少个候选词。再多说明问题太长，应该走查询改写而不是继续堆 LIKE */
    public static final int MAX_TOKENS = 12;

    /** 中文连续段落 */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");
    /** 西文 / 数字整词 */
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z0-9_]{2,}");

    /**
     * 切出候选检索词。
     *
     * <p>本方法**不做停用词过滤**：bigram 天然会产生"是谁""哪些"这类片段，
     * 过滤它们需要一张停用词表，而漏掉一个词的代价（查不到）大于多一个词的代价
     * （多一个匹配不到的 LIKE 条件）。简单可靠优先。
     */
    public List<String> tokenize(String query) {
        Set<String> out = new LinkedHashSet<>();
        if (query == null || query.isBlank()) return List.of();

        String q = query.strip();

        // 西文/数字整词优先入集（顺序对 LIKE 匹配无影响，但稳定顺序便于测试）
        Matcher latin = LATIN_WORD.matcher(q);
        while (latin.find() && out.size() < MAX_TOKENS) {
            out.add(latin.group());
        }

        Matcher cjk = CJK_RUN.matcher(q);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                // 单字中文：本身也可能有意义（如"党"），保留但优先级低
                if (out.size() < MAX_TOKENS) out.add(run);
                continue;
            }
            for (int i = 0; i + 2 <= run.length(); i++) {
                if (out.size() >= MAX_TOKENS) break;
                out.add(run.substring(i, i + 2));
            }
        }

        return new ArrayList<>(out);
    }

    /** 供日志与测试观测：切分结果（不截断） */
    public int size(String query) {
        return tokenize(query).size();
    }
}
