package com.pams.module.knowledge.source;

import com.pams.module.agent.core.ToolContext;

import java.util.List;

/**
 * 知识源适配器（D12 全域知识源 —— <b>本方案唯一的架构形状要求</b>）。
 *
 * <pre>
 * 新增一个知识源 = 实现本接口 + 声明一条准入配置，其余层全部不动：
 *   KbPipeline（编排）/ KeywordRetriever（检索）/ PermissionResolver（权限）/ 引用回填 / 前端
 * </pre>
 *
 * <p><b>三条不能破的约定</b>：
 *
 * <h3>① canAccess 必须回答"这份数据谁应该能看到"，不能回答"页面有没有挂权限码"</h3>
 * 权限审计实测：225 个端点里 88 个（39.1%）没有 {@code @PreAuthorize}，
 * 所以"页面上挂了什么码"根本不是可靠的口径来源。权威口径在**前端路由守卫与菜单映射**里
 * （{@code RequirePerm} / {@code RequireRole} / {@code MainLayout}）。
 *
 * <p>更具体的一个教训（审计 A9）：{@code party:view} 权限码在种子里**授予了 STAFF**，
 * 而前端 {@code /party/*} 路由用的是 {@code RequireRole(LEADER_ROLES)}。
 * 若某源把 {@code canAccess} 实现成"有没有 party:view"，就会**比页面更松**，
 * 出现"看不到页面却能在问答里问出内容"。所以实现前先问：
 * <b>这份数据的页面入口被谁挡住了？</b>
 *
 * <h3>② 权限结论只能来自 canAccess，不能来自 kb_chunk 的权限字段</h3>
 * 索引期的 {@code ownerDeptId / visibility / requiredPermission} 是**性能用的保守缓存**。
 * 正确性必须每次检索回源判定（{@link #canAccess}），否则权限口径一变，
 * 知识库就成了漂移的越权入口（方案 §6.4 / R5）。
 *
 * <h3>③ tier 与 weight 必须显式声明，没有默认值</h3>
 * T2（只有短元数据的源）如果参与正文打分，会**稀释 BM25**，
 * 把真正该命中的条款挤下去（方案 R13）。强制声明是为了让"这个源算不算正文语料"
 * 这个决策被真正做出来，而不是让它默默取一个 1.0。详见 {@link #tier()} 与 {@link #weight()}。
 */
public interface KnowledgeSource {

    /** 数据分级（D12 §2.4） */
    enum Tier {
        /** T1 全文级：有连续正文，语义自足 → 参与 BM25 正文打分 */
        T1,
        /** T2 字段级：只有短元数据 → **只做标题匹配，正文权重 0** */
        T2
    }

    /** 源标识，落到 {@code kb_chunk.source_type}。取值必须稳定 —— 改它等于换了一个源 */
    String sourceType();

    /** 中文展示名（知识库页面的源清单用） */
    String label();

    /** 说明：这个源收录了什么、为什么是当前分级（供评审时一眼看懂） */
    String description();

    Tier tier();

    /**
     * 源权重 —— 表达<b>权威度</b>，不表达篇幅或热度。
     *
     * <p>取值约定（一期）：
     * <table>
     *   <tr><td>制度与公告</td><td>1.0</td><td>权威规范类，"该怎么规定"以它们为准</td></tr>
     *   <tr><td>稿件与策划书</td><td>0.9</td><td>实践记录类，有参考价值，但不该带偏规范类问题</td></tr>
     * </table>
     *
     * <p>它与 {@link #tier()} 是两个正交的维度，别混：
     * <pre>
     * tier  决定"参与哪种匹配"   T1 → BM25 正文+标题；T2 → 只做标题匹配（正文权重天然为 0）
     * weight 决定"算出来乘多少"   两种 tier 都要乘 —— T2 的标题命中同样按权威度缩放
     * </pre>
     *
     * <p><b>不提供默认值</b>：新增源必须显式声明。默认 1.0 会让一个没想清楚的源
     * "看起来接好了"，而权重是拍脑袋定的 —— 这类隐性假设比编译错误难查得多。
     * 未声明的源权重按 0 处理（检索不出来 + 启动期 WARN），宁可不动也不要错动。
     */
    double weight();

    /** 全量抽取（重建索引用）。实现里要自己排除不该进索引的记录 */
    List<KnowledgeDoc> extractAll();

    /**
     * 按源主键抽取单篇（增量重建用，AFTER_COMMIT 领域事件触发）。
     *
     * @return 该记录的文档切片；源记录已被删除或不该进索引时返回空列表
     *         —— <b>返回空表示"清理它的分块"，不是"什么都没发生"</b>，
     *         索引器据此删除旧分块，否则源被删后知识库会答出已不存在的内容
     */
    List<KnowledgeDoc> extractOne(Long sourceId);

    /**
     * 检索期权限复核 —— <b>唯一正确的权限结论来源</b>。
     *
     * @param ctx      当前用户上下文（权限码、roleCode、userId、deptId）
     * @param sourceId 源记录主键
     * @return 当前用户能否看到这条源记录
     */
    boolean canAccess(ToolContext ctx, Long sourceId);

    /**
     * 出处跳转链接（引用卡上的"查看原文"）。
     *
     * <p>为什么由源提供而不是在引用层按 {@code sourceType} 拼：**只有源知道自己的页面长什么样**。
     * 例如策划书要跳到 {@code /activities/{activityId}}，而 chunk 里只存了 {@code planId}，
     * 需要源自己去解析活动 ID。把这些差异收在源里，引用层就只需调一次方法。
     *
     * <p>返回 {@code null} 表示"没有可跳转的页面"，前端应把引用渲染成不可点的纯文本
     * —— 这比给一个必然 404 的链接诚实。
     *
     * <p>⚠️ 前端**不得自行拼路径**（`PolicyGuard.reference` 的同一条约定）：
     * 路径规则一变，前端散落的拼接就成了死链。
     */
    default String link(Long sourceId) { return null; }

    /* ---------------------------------------------------------------- 索引期元数据 */

    /**
     * 索引期写入的保守可见性：{@code ALL} / {@code LEADER}。
     *
     * <p><b>宁严不宽</b>：这个字段只用于 SQL 粗筛保住性能，写窄了最多"少给"（可容忍），
     * 写宽了会把不该看的内容捞进候选（虽然后面还有回源复核兜底，但那是最后一道）。
     */
    default String visibility() { return "ALL"; }

    /** 索引期写入的附加权限码要求（如 {@code "material:view"}）；无则 null */
    default String requiredPermission() { return null; }

    /** 数据敏感级：{@code normal} / {@code sensitive}。{@code restricted} 的源不应实现本接口 */
    default String sensitivity() { return "normal"; }

    /* ---------------------------------------------------------------- 值对象 */

    /**
     * 一次抽取产出的文档切片。
     *
     * <p>{@code sections} 而不是一整块正文：分块需要**标题路径**（"第三章 第二十六条"），
     * 而路径只有源自己知道。让源按自己的结构切好标题、把粒度留给
     * {@code KnowledgeChunker} 去做，职责才清晰。
     */
    record KnowledgeDoc(String sourceType,
                        Long sourceId,
                        String title,
                        Tier tier,
                        List<Section> sections,
                        String visibility,
                        String requiredPermission,
                        String sensitivity,
                        String publishedAt,
                        java.time.LocalDateTime asOf) {

        public String sourceKey() { return sourceType + ":" + sourceId; }

        /** 便于适配器少写样板（文档类源：asOf 为 null） */
        public static KnowledgeDoc of(String sourceType, Long sourceId, String title, Tier tier,
                                      List<Section> sections, String publishedAt) {
            return new KnowledgeDoc(sourceType, sourceId, title, tier, sections,
                    "ALL", null, "normal", publishedAt, null);
        }

        public KnowledgeDoc withVisibility(String visibility, String requiredPermission,
                                           String sensitivity) {
            return new KnowledgeDoc(sourceType, sourceId, title, tier, sections,
                    visibility, requiredPermission, sensitivity, publishedAt, asOf);
        }

        /**
         * 设置数据截止时间（快照类源专用）。
         *
         * <p>为什么这个字段必须从源一路传到 chunk：方案 R16 要求
         * snapshot 类引用**强制携带 asOf**（"截至 2026-09-14 在册 87 人"）。
         * 若引用回填时还要回查来源表才能拿到截止时间，就把"引用构造"的
         * 正确性分散到了更多地方 —— G2（答案干净、出处带毒）的成因就是这种分散。
         */
        public KnowledgeDoc withAsOf(java.time.LocalDateTime asOf) {
            return new KnowledgeDoc(sourceType, sourceId, title, tier, sections,
                    visibility, requiredPermission, sensitivity, publishedAt, asOf);
        }
    }

    /**
     * 带标题路径的正文段。
     *
     * @param heading 本级标题（如"第二十六条"），可为空
     * @param path    标题路径（如"第三章 第二十六条"），**引用展示靠它**——
     *                只给文档标题，用户点进去还得自己找，等于没有出处
     * @param content 正文
     * @param chunkTitle 该段分块的标题覆盖值；{@code null} = 用文档标题。
     *
     *        <p><b>为什么需要这个字段（T2 源的生死线）</b>：T2 源**只参与标题匹配、不参与正文打分**
     *        （短元数据进正文索引会稀释 BM25，方案 R13）。所以对 T2 源来说，
     *        <b>落进 title 列的内容就是它唯一的检索入口</b>。
     *        例如成员条目要把「张三 · 组织部 · 部长」放进标题，用户问"组织部长是谁"
     *        才可能命中；若沿用文档级标题（"第 40 届成员名册"），这个源等于接了个摆设。
     */
    record Section(String heading, String path, String content, String chunkTitle) {

        public static Section of(String path, String content) {
            return new Section(path, path, content, null);
        }

        /** 带分块标题的段（T2 源用；见 {@link #chunkTitle} 的说明） */
        public static Section titled(String path, String content, String chunkTitle) {
            return new Section(path, path, content, chunkTitle);
        }
    }
}
