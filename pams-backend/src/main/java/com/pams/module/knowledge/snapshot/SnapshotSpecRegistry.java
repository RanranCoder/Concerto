package com.pams.module.knowledge.snapshot;

import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.member.dto.MemberStatsVO;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.member.service.MemberService;
import com.pams.module.party.repository.PartyMemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实快照模板注册表 —— <b>本项目的隐私与权限评审入口</b>。
 *
 * <h3>读这一个类就够</h3>
 * 想知道"灵思会自动把哪些业务数据写进知识库"，看这里：
 * 每个 spec 都写明了<b>收什么、给谁看、为什么</b>。
 * 加新快照必须在这里显式声明 —— 没有"顺手加一个"的可能。
 *
 * <h3>三条口径纪律</h3>
 * <ol>
 *   <li><b>visibility 必须来自来源页面的守卫</b>，不是"猜一个合理的范围"。
 *       判断方法见 {@code KnowledgeSource} 的约定 ①。</li>
 *   <li><b>党务类快照用 {@code LEADER} 而不是 {@code party:view}</b> ——
 *       权限码 {@code party:view} 在 V7 种子里**授予了 STAFF**，
 *       而前端 {@code /party/*} 用的是 {@code RequireRole(LEADER_ROLES)}。
 *       这是权限审计 A9 的确凿教训：同一资源混用两套口径会造成
 *       "看不到页面却能在问答里问出来"。所以这里一律按页面口径写。</li>
 *   <li><b>narrator 只用聚合计数</b>，见 {@link SnapshotSpec} 的说明。</li>
 * </ol>
 *
 * <h3>与设计文档的一处偏离（此处如实说明）</h3>
 * 方案 §2.2 原文写的是"快照模板写在配置文件里（{@code pams.agent.kb.snapshots[]}）"。
 * 实现时改为**代码内注册表**，理由：
 * <ul>
 *   <li>narrator 是函数，YAML 表达不了，最终必然要在代码里建一张
 *       {@code code → 计算函数} 的映射表；那还不如把整张表放在一处。</li>
 *   <li>方案要的是"预注册且可评审"这个**结果**，配置化只是手段。
 *       代码内注册表同样满足：集中、显式、可评审，而且少了 YAML→Java 的映射对不上号这类新失效面。</li>
 * </ul>
 * 当 spec 数量继续增长、或需要非开发人员调整时，再把**元数据部分**（可见性/开关/频率）
 * 外置到 YAML，narrator 仍留在代码里 —— 那时才是拆分的合适时机。
 */
@Slf4j
@Component
public class SnapshotSpecRegistry {

    /**
     * 活动状态的中文标签。
     *
     * <p><b>权威来源在前端</b>：{@code pams-web/src/api/activityStatus.ts} 里写着"唯一来源"。
     * 后端 {@code ActivityStatus} 枚举只有枚举码、没有中文（它只负责状态机）。
     * 这里复制一份是无奈之举 —— 加新状态时**两边都要改**，
     * 所以列为需要登记的语义同步项（D11 第 ③ 类）。
     */
    private static final Map<String, String> ACTIVITY_STATUS_LABELS = new LinkedHashMap<>();

    static {
        ACTIVITY_STATUS_LABELS.put("ASSIGNED", "已下达");
        ACTIVITY_STATUS_LABELS.put("PLANNING", "排期中");
        ACTIVITY_STATUS_LABELS.put("PLAN_REVIEW", "策划审核");
        ACTIVITY_STATUS_LABELS.put("EXECUTING", "执行中");
        ACTIVITY_STATUS_LABELS.put("FINISHED", "已完成");
        ACTIVITY_STATUS_LABELS.put("ARCHIVED", "已归档");
    }

    private final MemberService memberService;
    private final MemberSessionRepository memberSessionRepository;
    private final ActivityRepository activityRepository;
    private final PartyMemberRepository partyMemberRepository;

    private final List<SnapshotSpec> specs;

    public SnapshotSpecRegistry(MemberService memberService,
                               MemberSessionRepository memberSessionRepository,
                               ActivityRepository activityRepository,
                               PartyMemberRepository partyMemberRepository) {
        this.memberService = memberService;
        this.memberSessionRepository = memberSessionRepository;
        this.activityRepository = activityRepository;
        this.partyMemberRepository = partyMemberRepository;

        this.specs = List.of(
                memberComposition(),
                activityOverview(),
                partyComposition());

        // 启动期自检：code 必须唯一 —— 重复 code 会让唯一键与"取最新一份"的语义同时失效，
        // 而且表现为"快照生成成功但检索永远拿到旧的"，很难查
        long distinct = specs.stream().map(SnapshotSpec::code).distinct().count();
        if (distinct != specs.size()) {
            throw new IllegalStateException("知识库快照 spec_code 重复，请检查 SnapshotSpecRegistry");
        }
        log.info("知识库事实快照模板注册完成，共 {} 个：{}", specs.size(),
                specs.stream().map(SnapshotSpec::code).toList());
    }

    public List<SnapshotSpec> all() { return specs; }

    public SnapshotSpec byCode(String code) {
        return specs.stream().filter(s -> s.code().equals(code)).findFirst().orElse(null);
    }

    /* ==================================================================== ① 成员构成 */

    /**
     * 成员构成（按届别）。
     *
     * <pre>
     * 可见性  全员的依据：/members 路由用 RequirePerm(['member:view'])、
     *         MemberController 类级 @PreAuthorize 也是 member:view —— 两侧同码，
     *         所以这里写 requiredPermission = member:view，与页面完全一致。
     * 数据源  MemberService.stats(sessionId)，返回 MemberStatsVO
     *         = (总数, 按部门计数, 按职务计数, 按状态计数) —— **只有 NameCount，没有人名**。
     * </pre>
     */
    private SnapshotSpec memberComposition() {
        return new SnapshotSpec(
                "member_composition",
                "按届别汇总成员总数与部门/职务/状态分布。"
                        + "数据源是 counts-only 的统计结果（只有部门名、职务名与人数，不含个人姓名）；"
                        + "可见性与 /members 页面一致，需 member:view。",
                "MEMBER_SESSION",
                "%s成员构成",
                "ALL",
                "member:view",
                "/members",
                "每日一次（成员变更时由事件即时重建）",
                () -> {
                    List<SnapshotSpec.ScopeRef> refs = new ArrayList<>();
                    for (MemberSession s : memberSessionRepository
                            .findAllByOrderByIsCurrentDescSortOrderAscIdAsc()) {
                        refs.add(new SnapshotSpec.ScopeRef(s.getId(),
                                s.getName() == null ? ("届别 " + s.getId()) : s.getName()));
                    }
                    return refs;
                },
                scopeId -> {
                    MemberStatsVO st = memberService.stats(scopeId);
                    if (st == null || st.total() == 0) return null;   // 空届别不生成快照

                    StringBuilder sb = new StringBuilder();
                    sb.append("本届成员共 ").append(st.total()).append(" 人。");
                    appendGroup(sb, "按部门", st.byDept());
                    appendGroup(sb, "按职务", st.byPosition());
                    appendGroup(sb, "按状态", st.byStatus());
                    return sb.toString();
                });
    }

    /* ==================================================================== ② 活动开展情况 */

    /**
     * 活动开展情况（全局）。
     *
     * <pre>
     * 可见性  不要求权限码的依据：/activities 路由**没有守卫**（代码注释写明"干事可看"），
     *         GET /api/activities 也只补了 activity:view，而 7 个角色的权限种子**全都含**该码
     *         —— 即实际门槛为"登录即可"。所以这里 visibility=ALL、不设 requiredPermission。
     * 数据源  ActivityRepository.countGroupByStatus()（已有方法，按状态分组计数）
     * </pre>
     */
    private SnapshotSpec activityOverview() {
        return new SnapshotSpec(
                "activity_overview",
                "汇总全部活动的状态分布（已下达/排期中/策划审核/执行中/已完成/已归档各多少场）。"
                        + "只有计数，不涉及任何个人信息；"
                        + "可见性与 /activities 页面一致（该页无路由守卫，登录即可看）。",
                "GLOBAL",
                "%s活动开展情况",
                "ALL",
                null,
                "/activities",
                "每日一次",
                () -> List.of(SnapshotSpec.ScopeRef.global()),
                scopeId -> {
                    List<Object[]> rows = activityRepository.countGroupByStatus();
                    if (rows == null || rows.isEmpty()) return null;

                    Map<String, Long> byStatus = new LinkedHashMap<>();
                    long total = 0;
                    for (Object[] r : rows) {
                        String code = r[0] == null ? null : String.valueOf(r[0]);
                        long c = r[1] == null ? 0L : ((Number) r[1]).longValue();
                        if (code == null) continue;
                        byStatus.merge(code, c, Long::sum);
                        total += c;
                    }
                    if (total == 0) return null;

                    StringBuilder sb = new StringBuilder();
                    sb.append("目前共有活动 ").append(total).append(" 场。");
                    // 按状态机顺序输出，与前端列表的认知一致；未知状态放最后
                    for (Map.Entry<String, String> e : ACTIVITY_STATUS_LABELS.entrySet()) {
                        Long c = byStatus.get(e.getKey());
                        if (c != null && c > 0) sb.append(e.getValue()).append(' ').append(c).append(" 场，");
                    }
                    for (Map.Entry<String, Long> e : byStatus.entrySet()) {
                        if (!ACTIVITY_STATUS_LABELS.containsKey(e.getKey()) && e.getValue() > 0) {
                            sb.append(e.getKey()).append(' ').append(e.getValue()).append(" 场，");
                        }
                    }
                    // 去掉末尾顿号
                    if (sb.charAt(sb.length() - 1) == '，') sb.setLength(sb.length() - 1);
                    sb.append('。');
                    return sb.toString();
                });
    }

    /* ==================================================================== ③ 党员发展结构 */

    /**
     * 党员发展结构（全局）。
     *
     * <pre>
     * 可见性  LEADER，**不是 party:view**：
     *        前端 /party/* 路由用 RequireRole(LEADER_ROLES) 守卫、菜单对干事隐藏；
     *        而 party:view 这个权限码在 V7 种子里**授予了 STAFF**（PermissionService.restoreDefault）。
     *        若这里按权限码写，干事就能通过灵思问出党务统计 —— 正是审计 A9 那个缺口。
     * 数据源  PartyMemberRepository.countGroupByPoliticalStatus()（按政治面貌分组计数）
     *         —— 只有阶段名与人数，**不含任何个人材料**
     * </pre>
     */
    private SnapshotSpec partyComposition() {
        return new SnapshotSpec(
                "party_composition",
                "汇总党员队伍的发展阶段结构（入党申请人/积极分子/重点发展对象/预备党员/正式党员各多少人）。"
                        + "只有阶段计数，不含任何个人材料；"
                        + "可见性按 /party/* 路由的守卫口径设为「部长及以上」—— "
                        + "刻意不用 party:view，因为该权限码在种子里授予了干事（审计 A9）。",
                "GLOBAL",
                "%s党员发展结构",
                "LEADER",
                null,
                "/party/members",
                "每日一次",
                () -> List.of(new SnapshotSpec.ScopeRef(0L, "全校")),
                scopeId -> {
                    List<Object[]> rows = partyMemberRepository.countGroupByPoliticalStatus();
                    if (rows == null || rows.isEmpty()) return null;

                    Map<String, Long> byStatus = new LinkedHashMap<>();
                    long total = 0;
                    for (Object[] r : rows) {
                        String label = r[0] == null ? "未标注" : String.valueOf(r[0]);
                        long c = r[1] == null ? 0L : ((Number) r[1]).longValue();
                        byStatus.merge(label, c, Long::sum);
                        total += c;
                    }
                    if (total == 0) return null;

                    StringBuilder sb = new StringBuilder();
                    sb.append("目前党员队伍共 ").append(total).append(" 人。");
                    for (Map.Entry<String, Long> e : byStatus.entrySet()) {
                        if (e.getValue() > 0) sb.append(e.getKey()).append(' ').append(e.getValue()).append(" 人，");
                    }
                    if (sb.charAt(sb.length() - 1) == '，') sb.setLength(sb.length() - 1);
                    sb.append('。');
                    return sb.toString();
                });
    }

    /* ==================================================================== 工具 */

    /**
     * 追加一组"标签 数量"。
     *
     * <p>刻意只接收 {@code NameCount}（name = 部门/职务/状态名），
     * 不接受任何可能含人名的结构 —— 这是取值口收窄的具体体现。
     */
    private void appendGroup(StringBuilder sb, String groupLabel, List<MemberStatsVO.NameCount> items) {
        if (items == null || items.isEmpty()) return;
        sb.append(groupLabel).append("：");
        for (MemberStatsVO.NameCount it : items) {
            if (it.count() <= 0) continue;
            sb.append(it.name() == null ? "未标注" : it.name())
              .append(' ').append(it.count()).append(" 人，");
        }
        if (sb.charAt(sb.length() - 1) == '，') sb.setLength(sb.length() - 1);
        sb.append('；');
    }

    /** 供测试与文档：活动状态标签映射的快照 */
    static Map<String, String> activityStatusLabels() {
        return Map.copyOf(ACTIVITY_STATUS_LABELS);
    }

    /** 供测试：确认状态映射覆盖了枚举的全部取值（漏一个就少统计一种状态） */
    static boolean coversAllActivityStatuses() {
        for (ActivityStatus s : ActivityStatus.values()) {
            if (!ACTIVITY_STATUS_LABELS.containsKey(s.name())) return false;
        }
        return true;
    }
}
