package com.pams.module.knowledge.source;

import com.pams.entity.Department;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.member.dto.MemberEnums;
import com.pams.module.member.entity.Member;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberRepository;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.repository.DepartmentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识源：成员管理（{@code member}）。
 *
 * <h3>⚠️ 这是一个"投影源"——收录哪些字段是经过取舍的，不是整表倒进索引</h3>
 * 成员表里同时存在两类字段，必须分开对待（方案 §2.4 的分级判据）：
 *
 * <table>
 *   <tr><th>字段</th><th>处理</th><th>理由</th></tr>
 *   <tr><td>姓名 / 职务 / 部门 / 届别 / 状态</td><td><b>收录</b></td>
 *       <td>组织信息 —— "组织部长是谁""这一届有哪些干事"正是用户会问的，
 *           且这些内容在成员管理页面上对有权限的人本来就是可见的</td></tr>
 *   <tr><td>学号 / 手机号 / 政治面貌 / 备注</td><td><b>明确排除</b></td>
 *       <td>不可脱敏的个人标识。学号手机号是强标识；政治面貌属敏感个人信息，
 *           且"某人是什么政治面貌"这类问题应当去成员管理页看，而不是进 LLM 上下文。
 *           排除方式是<b>根本不读这些字段</b>，不是读出来再遮 ——
 *           "没进过索引"比"遮住了"可靠</td></tr>
 * </table>
 *
 * <p>这条取舍由 {@code MemberSourceSafetyTest} 硬断言兜底：给成员填上学号/手机号/政治面貌，
 * 断言抽出的文本里<b>不含</b>这些值。将来有人随手加一个 {@code m.getPhone()} 会立刻变红。
 *
 * <h3>分级：T2 字段级</h3>
 * 成员条目是"短元数据、无语境"，符合 T2 定义（不参与正文 BM25，避免稀释条款类命中，方案 R13）。
 * 但 T2 的代价是**只匹配标题** —— 所以每条成员条目的标题被显式设成
 * 「姓名 · 部门 · 职务」（见 {@link Section#chunkTitle}），否则这个源等于接了个摆设：
 * 用户问"组织部长是谁"永远匹配不到"第 40 届成员名册"这种文档级标题。
 *
 * <h3>权限口径（canAccess）</h3>
 * 两侧一致，都是权限码 {@code member:view}：
 * <pre>
 * 路由  RequirePerm(['member:view'])          （router/index.tsx）
 * 接口  MemberController 类级 @PreAuthorize   （member:view）
 * </pre>
 * 与前一处审计发现的 {@code partyMember} 问题（路由用角色、工具用权限码，且权限码授予了干事）
 * **本质不同**：这里两侧同码，不存在口径冲突。这也说明"先查页面守卫"的规矩是有效的做法 ——
 * 查了才知道哪些是真风险、哪些是虚惊。
 */
@Component
public class MemberSource implements KnowledgeSource {

    public static final String SOURCE_TYPE = "member";

    /** 与 {@code MemberController} 类级注解、前端路由守卫同码 */
    private static final String PERM_MEMBER_VIEW = "member:view";

    private final MemberRepository memberRepository;
    private final MemberSessionRepository sessionRepository;
    private final DepartmentRepository departmentRepository;

    public MemberSource(MemberRepository memberRepository,
                        MemberSessionRepository sessionRepository,
                        DepartmentRepository departmentRepository) {
        this.memberRepository = memberRepository;
        this.sessionRepository = sessionRepository;
        this.departmentRepository = departmentRepository;
    }

    @Override
    public String sourceType() { return SOURCE_TYPE; }

    @Override
    public String label() { return "成员名册"; }

    @Override
    public String description() {
        return "历届成员的组织信息投影：姓名、职务、部门、届别、状态。"
                + "学号、手机号、政治面貌、备注【不收录】——它们是不可脱敏的个人标识，"
                + "需要时应到成员管理页查看。页面入口 /members 需 member:view。";
    }

    @Override
    public Tier tier() { return Tier.T2; }

    /**
     * 权重 {@code 0.7} —— 有意低于文档类（制度 1.0 / 公告 1.0 / 稿件 0.9）。
     *
     * <p>理由：名册回答的是"谁在什么岗位"这类**事实**，不是"应当怎么做"这类**规范**。
     * 当一个问题同时命中条款与人员条目时，条款才该是主要依据。
     */
    @Override
    public double weight() { return 0.7; }

    /** 含姓名等个人信息；具体可出网内容由出网闸口复核 */
    @Override
    public String sensitivity() { return "sensitive"; }

    /* ---------------------------------------------------------------- 抽取 */

    /**
     * 全量抽取：**一个成员 = 一篇文档**（sourceId = memberId）。
     *
     * <p>为什么不按届别成组（那样文档数更少、台账更紧凑）：会破坏增量重建的契约。
     * {@code KnowledgeIndexer.reindexSource(type, id)} 的动作是
     * {@code deleteBySource(type, id)} + {@code extractOne(id)}，
     * 因此<b>抽取出来的分块，其 sourceId 必须等于传进去的 id</b>。
     * 若按届别成组（sourceId=sessionId），一次"某人改了职务"的事件会带着 memberId 进来，
     * 按 memberId 删却删不到挂在 sessionId 下的分块 —— 结果就是旧条目永远留在索引里，
     * 而且不报错。所以粒度必须与事件粒度一致：事件是"某个成员变了"，那文档就该是"某个成员"。
     *
     * <p>代价是索引里文档数变多（每人一篇、每篇一个块）。这是刻意的取舍：
     * 台账分页 + 来源筛选足以应对，而"索引悄悄不更新"是更贵的错误。
     */
    @Override
    public List<KnowledgeDoc> extractAll() {
        List<Member> members = memberRepository.findAll();
        if (members.isEmpty()) return List.of();

        // 届别名一次批量取（避免逐条查询）
        Set<Long> sessionIds = members.stream().map(Member::getSessionId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> sessionNames = sessionIds.isEmpty() ? Map.of()
                : sessionRepository.findAllById(sessionIds).stream()
                    .collect(Collectors.toMap(MemberSession::getId, s ->
                            s.getName() == null ? ("届别 " + s.getId()) : s.getName()));

        Map<Long, String> deptNames = deptNamesOf(members);

        List<KnowledgeDoc> out = new ArrayList<>();
        for (Member m : members) {
            KnowledgeDoc doc = toDoc(m, sessionNames, deptNames);
            if (doc != null) out.add(doc);
        }
        return out;
    }

    @Override
    public List<KnowledgeDoc> extractOne(Long sourceId) {
        Member m = memberRepository.findById(sourceId).orElse(null);
        if (m == null) return List.of();   // 已删除 → 返回空，索引器据此清理其分块

        Map<Long, String> sessionNames = sessionRepository.findById(m.getSessionId())
                .map(s -> Map.of(m.getSessionId(),
                        s.getName() == null ? ("届别 " + s.getId()) : s.getName()))
                .orElse(Map.of());

        KnowledgeDoc doc = toDoc(m, sessionNames, deptNamesOf(List.of(m)));
        return doc == null ? List.of() : List.of(doc);
    }

    /**
     * 权限口径：与页面、接口同码。
     *
     * <p>不做记录级判定 —— 成员管理页对持有 {@code member:view} 的人展示全量名册（可按届别/部门筛选），
     * 没有"这条记录归他"的概念。硬造一个只会凭空造出比页面更严的限制。
     */
    @Override
    public boolean canAccess(ToolContext ctx, Long sourceId) {
        return ctx != null && ctx.hasPermission(PERM_MEMBER_VIEW);
    }

    /** 成员管理页 */
    @Override
    public String link(Long sourceId) { return "/members"; }

    /* ---------------------------------------------------------------- 组装 */

    private KnowledgeDoc toDoc(Member m, Map<Long, String> sessionNames,
                               Map<Long, String> deptNames) {
        if (m == null || m.getName() == null || m.getName().isBlank()) return null;

        String position = MemberEnums.POSITION_LABELS.getOrDefault(m.getPosition(), m.getPosition());
        String deptName = m.getDeptId() == null ? null : deptNames.get(m.getDeptId());
        String status = MemberEnums.STATUS_LABELS.getOrDefault(m.getStatus(), m.getStatus());
        String sessionName = sessionNames.get(m.getSessionId());

        Section section = buildSection(m.getName(), position, deptName, sessionName, status);
        String docTitle = (sessionName == null ? "" : sessionName + " ") + "成员名册";

        // sourceId = memberId：与增量重建的事件粒度一致（见 extractAll 的说明）
        return KnowledgeDoc.of(SOURCE_TYPE, m.getId(), docTitle, Tier.T2,
                List.of(section), null);
    }

    /** 批量取部门名（避免逐条查询） */
    private Map<Long, String> deptNamesOf(List<Member> members) {
        Set<Long> deptIds = members.stream().map(Member::getDeptId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (deptIds.isEmpty()) return Map.of();
        return departmentRepository.findAllById(deptIds).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
    }

    /**
     * 单个成员的条目。
     *
     * <p><b>只读五个字段</b>（姓名/职务/部门/届别/状态）—— 这是本类的安全边界，
     * 加字段前先读类注释里的取舍表，并同步更新 {@code MemberSourceSafetyTest}。
     */
    private Section buildSection(String name, String position, String deptName,
                                 String sessionName, String status) {
        StringBuilder text = new StringBuilder();
        text.append("姓名 ").append(name);
        if (position != null) text.append("，职务 ").append(position);
        if (deptName != null) text.append("，部门 ").append(deptName);
        if (sessionName != null) text.append("，届别 ").append(sessionName);
        if (status != null) text.append("，状态 ").append(status);
        text.append('。');

        // 标题 = 检索入口（T2 只匹配标题）。用 · 分隔，让「组织部长」「组织部 张三」
        // 这类片段式提问也能通过 bigram 命中
        StringBuilder title = new StringBuilder(name);
        if (deptName != null) title.append(" · ").append(deptName);
        if (position != null) title.append(" · ").append(position);

        String path = sessionName == null ? (deptName == null ? null : deptName)
                : (deptName == null ? sessionName : sessionName + " · " + deptName);

        return Section.titled(path, text.toString(), title.toString());
    }
}
