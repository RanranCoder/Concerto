package com.pams.module.knowledge.retriever;

import com.pams.common.OrgContext;
import com.pams.module.agent.core.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 分块检索的 SQL 层（JdbcTemplate）。
 *
 * <p><b>为什么不用 JPA</b>：{@code MATCH ... AGAINST} 是 MySQL 专有语法，
 * 而 JPQL/Criteria 无法表达全文检索，也拿不到 BM25 相关度分数。这里必须裸 SQL。
 *
 * <h3>权限为什么必须下推到 SQL（而不是检索后过滤）</h3>
 * "先全量捞回来再筛掉不该看的"意味着不该看的正文**已经进了应用内存、已经算过分、
 * 可能已被日志打印**。方案 §6.2 把这条列为闸口 A，是这次设计里最不能妥协的一处。
 *
 * <p>本层做的是**保守粗筛**（性能）；正确性由 {@code PermissionResolver}
 * 在检索期回源复核提供。判据：粗筛字段过期只会"少给"、不会"多给"。
 *
 * <h3>T2 为什么要单独一条 SQL</h3>
 * FULLTEXT 索引建在 {@code (title, content)} 上，{@code MATCH} 必须完整列出索引列，
 * 无法只匹配 title。而 T2 的设计要求是"**只做标题匹配、不参与正文打分**"
 * （短元数据参与正文打分会把该命中的条款挤下去，方案 R13）。
 * 所以 T2 走一条独立的 {@code title LIKE} 查询，分数由调用方给固定低值。
 */
@Repository
public class ChunkSearchRepository {

    private final JdbcTemplate jdbc;
    private final QueryTokenizer tokenizer;

    public ChunkSearchRepository(JdbcTemplate jdbc, QueryTokenizer tokenizer) {
        this.jdbc = jdbc;
        this.tokenizer = tokenizer;
    }

    private static final String SELECT_COLS = """
            SELECT c.id, c.source_type, c.source_id, c.source_key, c.seq,
                   c.title, c.path, c.content, c.tier, c.sensitivity, c.as_of, %s AS score
            FROM kb_chunk c
            WHERE %s
            """;

    /** T1 全文检索（BM25 类相关度） */
    public List<ScoredChunk> fulltextT1(String query, ToolContext ctx, int limit) {
        List<Object> args = new ArrayList<>();
        // ⚠️ 参数顺序 = SQL 里占位符出现的顺序，不能想当然。
        // SELECT 子句里的 MATCH ... AGAINST (?) 在整条 SQL 中**最先出现**，
        // 因此查询词必须先入参，之后才是 permClause 追加的权限码。
        // 曾经把权限码先加进来，导致查询词被绑到权限码的占位符上 ——
        // 现象是"检索永远零命中"，但不报任何错（都是字符串绑定），极难定位。
        args.add(query);

        String where = permClause(ctx, args);

        String sql = String.format(SELECT_COLS,
                "MATCH(c.title, c.content) AGAINST (? IN NATURAL LANGUAGE MODE)",
                where + " AND c.tier = 'T1' AND MATCH(c.title, c.content) AGAINST (? IN NATURAL LANGUAGE MODE)");
        sql += " ORDER BY score DESC LIMIT ?";

        args.add(query);
        args.add(limit);
        return jdbc.query(sql, this::map, args.toArray());
    }

    /**
     * T1 降级路径（无 ngram 索引时）。分数固定，**无排序**。
     *
     * <p>为什么无排序也接受：这是"全文索引不可用"时的兜底，目标是"还能查到"而不是"排得好"。
     * 设计上它是降级而非等价替代 —— {@code KnowledgeSearchMode} 会把模式透出来，
     * 配合"检索耗时 P95"即可发现退化（方案 R14）。
     *
     * <p><b>2026-09-14 修正</b>：改为**按候选词逐个 LIKE**（原来是拿用户整句去 LIKE，
     * 在自然语言提问下永远匹配不到 —— 详见 {@link QueryTokenizer} 的类注释）。
     */
    public List<ScoredChunk> likeT1(String query, ToolContext ctx, int limit) {
        List<String> tokens = tokenizer.tokenize(query);
        if (tokens.isEmpty()) return List.of();

        List<Object> args = new ArrayList<>();
        String where = permClause(ctx, args);

        StringBuilder cond = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) cond.append(" OR ");
            cond.append("(c.title LIKE ? OR c.content LIKE ?)");
        }

        String sql = String.format(SELECT_COLS, "1.0",
                where + " AND c.tier = 'T1' AND (" + cond + ")");
        sql += " LIMIT ?";

        for (String t : tokens) {
            String like = "%" + escapeLike(t) + "%";
            args.add(like);
            args.add(like);
        }
        args.add(limit);
        return jdbc.query(sql, this::map, args.toArray());
    }

    /**
     * T2 字段级：**只匹配标题**，不碰正文。
     *
     * <p>同样按候选词逐个 LIKE —— T2 源的"标题"就是它唯一的检索入口
     * （例如成员条目的标题是「张三 · 组织部 · 部长」），
     * 拿用户整句去匹配等于这些源永远查不到。
     */
    public List<ScoredChunk> titleMatchT2(String query, ToolContext ctx, int limit) {
        List<String> tokens = tokenizer.tokenize(query);
        if (tokens.isEmpty()) return List.of();

        List<Object> args = new ArrayList<>();
        String where = permClause(ctx, args);

        StringBuilder cond = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) cond.append(" OR ");
            cond.append("c.title LIKE ?");
        }

        String sql = String.format(SELECT_COLS, "1.0",
                where + " AND c.tier = 'T2' AND (" + cond + ")");
        sql += " ORDER BY c.id ASC LIMIT ?";

        for (String t : tokens) args.add("%" + escapeLike(t) + "%");
        args.add(limit);
        return jdbc.query(sql, this::map, args.toArray());
    }

    /* ------------------------------------------------------------------ 权限粗筛 */

    /**
     * 保守可见性条件。
     *
     * <p>两条：
     * <ol>
     *   <li>{@code visibility}：非领导只能看 {@code ALL}。写窄了最多"少给"（可容忍），
     *       写宽了会把不该看的内容捞进候选。</li>
     *   <li>{@code required_permission}：按"用户是否持有该码"过滤。
     *       无码要求的行（{@code NULL}/空串）直接放行。</li>
     * </ol>
     */
    private String permClause(ToolContext ctx, List<Object> args) {
        StringBuilder sb = new StringBuilder(" c.deleted = 0 ");
        // 租户谓词（A3）：kb_chunk 标了 @TenantId，但裸 SQL 不受 Hibernate 过滤覆盖，
        // 必须在此显式补上，否则跨组织知识会在检索中互相可见。
        sb.append(" AND c.org_id = ? ");
        args.add(OrgContext.getOrDefault());

        if (ctx != null && ctx.isLeader()) {
            sb.append(" AND c.visibility IN ('ALL','LEADER') ");
        } else {
            sb.append(" AND c.visibility = 'ALL' ");
        }

        Set<String> auths = ctx == null ? Set.of() : ctx.getAuthorities();
        if (auths.isEmpty()) {
            sb.append(" AND (c.required_permission IS NULL OR c.required_permission = '') ");
        } else {
            sb.append(" AND (c.required_permission IS NULL OR c.required_permission = ''")
              .append(" OR c.required_permission IN (")
              .append("?,".repeat(auths.size() - 1)).append("?)) ");
            args.addAll(auths);
        }
        return sb.toString();
    }

    /** LIKE 通配符转义：用户问题里出现 % 或 _ 时不能让它变成通配 */
    private String escapeLike(String q) {
        return q == null ? "" : q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private ScoredChunk map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp asOf = rs.getTimestamp("as_of");
        return new ScoredChunk(
                rs.getLong("id"),
                rs.getString("source_type"),
                rs.getLong("source_id"),
                rs.getString("source_key"),
                rs.getInt("seq"),
                rs.getString("title"),
                rs.getString("path"),
                rs.getString("content"),
                rs.getString("tier"),
                rs.getString("sensitivity"),
                asOf == null ? null : asOf.toLocalDateTime(),
                rs.getDouble("score"));
    }
}
