package com.pams.module.knowledge.snapshot;

import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.member.dto.MemberStatsVO;
import com.pams.module.member.entity.MemberSession;
import com.pams.module.member.repository.MemberSessionRepository;
import com.pams.module.member.service.MemberService;
import com.pams.module.party.repository.PartyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 事实快照模板注册表的契约回归。
 *
 * <p>这个注册表是**隐私与权限的评审入口** —— 它决定"灵思会自动把哪些业务数据写进知识库"。
 * 所以这里钉的是它的**声明契约**，而不是某一次算出来的数字：
 *
 * <ol>
 *   <li><b>可见性必须显式声明且取值合法</b>（没有默认值 —— 默认值会让
 *       "这个快照该给谁看"这个决策被跳过）；</li>
 *   <li><b>党务快照必须是 LEADER</b> —— 这是权限审计 A9 的直接钉子：
 *       {@code party:view} 在种子里授予了干事，而前端 {@code /party/*} 用的是
 *       {@code RequireRole(LEADER_ROLES)}。谁把它改回 {@code party:view}，
 *       干事就能在灵思里问出党务统计 —— 这条测试会立刻变红；</li>
 *   <li><b>叙述只含计数</b>：对代表性输入断言输出是"共 N 人 / 某标签 x 人"的形态，
 *       且不含任何"姓名"类字段；</li>
 *   <li><b>空数据不生成</b>：生成"本届成员共 0 人"这种条目既无用又污染召回。</li>
 * </ol>
 */
class SnapshotSpecRegistryTest {

    private MemberService memberService;
    private MemberSessionRepository memberSessionRepository;
    private ActivityRepository activityRepository;
    private PartyMemberRepository partyMemberRepository;
    private SnapshotSpecRegistry registry;

    @BeforeEach
    void setUp() {
        memberService = Mockito.mock(MemberService.class);
        memberSessionRepository = Mockito.mock(MemberSessionRepository.class);
        activityRepository = Mockito.mock(ActivityRepository.class);
        partyMemberRepository = Mockito.mock(PartyMemberRepository.class);
        registry = new SnapshotSpecRegistry(memberService, memberSessionRepository,
                activityRepository, partyMemberRepository);
    }

    /* ------------------------------------------------------------ ① 声明契约 */

    @Test
    void codesAreUniqueAndDescriptionsArePresent() {
        List<SnapshotSpec> all = registry.all();

        assertThat(all).as("至少要有三个预注册快照").hasSizeGreaterThanOrEqualTo(3);
        assertThat(all).extracting(SnapshotSpec::code).doesNotHaveDuplicates();
        assertThat(all).extracting(SnapshotSpec::description)
                .allSatisfy(d -> assertThat(d).as("说明是评审入口，不能空").isNotBlank());
        assertThat(all).extracting(SnapshotSpec::narrator).doesNotContainNull();
        assertThat(all).extracting(SnapshotSpec::scopes).doesNotContainNull();
    }

    /** 可见性必须显式声明，且只允许两个合法取值 —— 没有默认值 */
    @Test
    void visibilityIsAlwaysExplicitAndLegal() {
        for (SnapshotSpec s : registry.all()) {
            assertThat(s.visibility())
                    .as("快照 %s 的可见性必须显式声明", s.code())
                    .isIn("ALL", "LEADER");
        }
    }

    /**
     * A9 的直接钉子：党务快照的可见性必须是角色口径（LEADER），
     * <b>绝不能</b>用 {@code party:view} —— 该权限码在 V7 种子里授予了干事（STAFF），
     * 而前端 {@code /party/*} 用的是 {@code RequireRole(LEADER_ROLES)}。
     * 混用两套口径的后果：干事看不到党务台账页面，却能在灵思里问出党务统计。
     */
    @Test
    void partyCompositionIsLeaderOnlyNotPermissionBased() {
        SnapshotSpec spec = registry.byCode("party_composition");

        assertThat(spec).isNotNull();
        assertThat(spec.visibility())
                .as("党务统计的可见性必须与 /party/* 路由守卫一致（角色口径）")
                .isEqualTo("LEADER");
        assertThat(spec.leaderOnly()).isTrue();
        assertThat(spec.requiredPermission())
                .as("不能再叠加 party:view —— 那等于把口径又改回权限码，重蹈 A9")
                .isNull();
    }

    /** 成员构成的口径与 /members 页面一致：路由与接口两侧都是 member:view */
    @Test
    void memberCompositionUsesMemberViewWhichMatchesThePage() {
        SnapshotSpec spec = registry.byCode("member_composition");

        assertThat(spec.visibility()).isEqualTo("ALL");
        assertThat(spec.requiredPermission())
                .as("与 /members 的 RequirePerm(['member:view']) 及 MemberController 类级注解同码")
                .isEqualTo("member:view");
    }

    /** 活动概览不设权限码：/activities 无路由守卫（干事可看），实际门槛是登录 */
    @Test
    void activityOverviewMatchesUnguardedPage() {
        SnapshotSpec spec = registry.byCode("activity_overview");

        assertThat(spec.visibility()).isEqualTo("ALL");
        assertThat(spec.requiredPermission()).isNull();
    }

    /** 状态标签必须覆盖枚举全部取值 —— 漏一个就少统计一种状态，且不报错 */
    @Test
    void activityStatusLabelsCoverEveryEnumValue() {
        assertThat(SnapshotSpecRegistry.coversAllActivityStatuses())
                .as("活动状态标签必须覆盖 ActivityStatus 的全部取值")
                .isTrue();
        assertThat(SnapshotSpecRegistry.activityStatusLabels())
                .containsEntry("EXECUTING", "执行中")
                .containsEntry("FINISHED", "已完成");
    }

    /* ------------------------------------------------------------ ② 叙述内容 */

    private void stubMemberStats() {
        MemberSession s = new MemberSession();
        s.setId(40L);
        s.setName("第四十届");
        when(memberSessionRepository.findAllByOrderByIsCurrentDescSortOrderAscIdAsc())
                .thenReturn(List.of(s));
        // NameCount 的 name 是**部门名 / 职务名 / 状态名**，不是人名 —— 这正是取值口收窄的结果
        when(memberService.stats(40L)).thenReturn(new MemberStatsVO(
                12,
                List.of(new MemberStatsVO.NameCount("组织部", 5), new MemberStatsVO.NameCount("宣传部", 7)),
                List.of(new MemberStatsVO.NameCount("部长", 1), new MemberStatsVO.NameCount("干事", 11)),
                List.of(new MemberStatsVO.NameCount("在职", 12))));
    }

    @Test
    void memberNarrationContainsCountsAndLabelsOnly() {
        stubMemberStats();
        SnapshotSpec spec = registry.byCode("member_composition");

        SnapshotSpec.ScopeRef scope = spec.scopes().get().get(0);
        String text = spec.narrator().apply(scope.id());

        assertThat(text).contains("共 12 人").contains("组织部 5 人").contains("干事 11 人");
        // 标题带作用域名：多届快照在引用列表里要能分清是哪一份
        assertThat(spec.titleOf(scope.label())).isEqualTo("第四十届成员构成");
    }

    /** 零数据不生成 —— "本届成员共 0 人"既无用又会污染召回 */
    @Test
    void zeroMemberYieldsNoSnapshotText() {
        // 届别存在、但该届没有任何成员 —— 这才是"空届别"的真实形态
        MemberSession s = new MemberSession();
        s.setId(40L);
        s.setName("第四十届");
        when(memberSessionRepository.findAllByOrderByIsCurrentDescSortOrderAscIdAsc())
                .thenReturn(List.of(s));
        when(memberService.stats(40L)).thenReturn(
                new MemberStatsVO(0, List.of(), List.of(), List.of()));

        SnapshotSpec spec = registry.byCode("member_composition");
        SnapshotSpec.ScopeRef scope = spec.scopes().get().get(0);

        assertThat(spec.narrator().apply(scope.id())).as("空届别不生成快照").isNull();
    }

    @Test
    void activityNarrationUsesFrontendLabelsAndAppendsUnknownStatuses() {
        // 显式声明 List.<Object[]>of —— 否则 varargs 会被推断成 List<Object>，类型对不上
        when(activityRepository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{ActivityStatus.EXECUTING, 3L},
                new Object[]{ActivityStatus.FINISHED, 8L},
                new Object[]{"SOMETHING_NEW", 2L}));   // 模拟将来新增的状态

        String text = registry.byCode("activity_overview").narrator().apply(0L);

        assertThat(text).contains("共有活动 13 场").contains("执行中 3 场").contains("已完成 8 场");
        assertThat(text)
                .as("新增状态不能被静默丢掉 —— 即使标签还没同步，也要以枚举码出现")
                .contains("SOMETHING_NEW 2 场");
    }

    @Test
    void partyNarrationCountsStagesOnly() {
        when(partyMemberRepository.countGroupByPoliticalStatus()).thenReturn(List.<Object[]>of(
                new Object[]{"入党申请人", 4L},
                new Object[]{"预备党员", 2L},
                new Object[]{"正式党员", 9L}));

        String text = registry.byCode("party_composition").narrator().apply(0L);

        assertThat(text).contains("共 15 人").contains("入党申请人 4 人").contains("正式党员 9 人");
        // 结构性说明：narrator 只拿到 (阶段名, 人数)，类型上没有可以放个人材料的入口
    }

    /** 无任何党员数据时不生成 —— 避免"共 0 人"出现在党务统计里 */
    @Test
    void emptyPartyDataYieldsNoSnapshotText() {
        when(partyMemberRepository.countGroupByPoliticalStatus()).thenReturn(List.of());

        assertThat(registry.byCode("party_composition").narrator().apply(0L)).isNull();
    }

    /** 空数据（无数）与"有数据但为 0"要区分：后者仍然要生成，前者不生成 */
    @Test
    void activityWithZeroRowsYieldsNoSnapshotText() {
        when(activityRepository.countGroupByStatus()).thenReturn(List.of());

        assertThat(registry.byCode("activity_overview").narrator().apply(0L)).isNull();
    }

    /** byCode 对未知 code 返回 null 而不是抛异常（调用方需要判断而非崩溃） */
    @Test
    void unknownCodeReturnsNull() {
        assertThat(registry.byCode("not_exist")).isNull();
    }

    /** Mockito 未打桩时 stats 返回 null —— narrator 必须能容忍，不能 NPE */
    @Test
    void nullStatsIsTolerated() {
        when(memberSessionRepository.findAllByOrderByIsCurrentDescSortOrderAscIdAsc())
                .thenReturn(List.of());

        // memberService.stats 未打桩 → Mockito 默认返回 null
        assertThat(registry.byCode("member_composition").narrator().apply(40L)).isNull();
    }

    /**
     * Map.of 不接受 null 值，这里模拟"聚合结果里有一行 null 标签"的脏数据。
     *
     * <p>⚠️ 写法注意：{@code List.of(new Object[]{...})} 只有一个实参时，
     * 那个数组会被当成 varargs 数组本身，推断成 {@code List<Object>} 而不是
     * {@code List<Object[]>} —— 必须显式写类型见证 {@code List.<Object[]>of(...)}。
     * 这个坑在本文件里已经踩过两次。
     */
    @Test
    void nullLabelInAggregateIsRenderedAsUnmarked() {
        when(partyMemberRepository.countGroupByPoliticalStatus())
                .thenReturn(List.<Object[]>of(new Object[]{null, 3L}));

        String text = registry.byCode("party_composition").narrator().apply(0L);

        assertThat(text).contains("未标注 3 人");
    }
}
