package com.pams.module.agent.tool;

import com.pams.common.PageResult;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 活动名称 → 活动实体解析（业务设计方案 §3.9）的确定性单测。
 *
 * <p>覆盖四条分支：ID 直取 / 名称尾缀「（ID n）」/ 精确同名唯一 / 多命中与零命中。
 * 重点断言"写类工具安全底线"——重名时<b>绝不静默挑一个</b>，必须返回候选清单。
 */
class ActivityResolverTest {

    ActivityService activityService;
    ActivityResolver resolver;

    @BeforeEach
    void setUp() {
        activityService = mock(ActivityService.class);
        resolver = new ActivityResolver(activityService);
    }

    /* ---------------------------------------------------------------- helpers */

    private Activity activity(long id, String name) {
        Activity a = new Activity();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private Map<String, Object> row(long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    @SafeVarargs
    private void stubPage(Map<String, Object>... rows) {
        PageResult<Map<String, Object>> pr = new PageResult<>();
        pr.setRecords(new ArrayList<>(List.of(rows)));
        when(activityService.page(anyString(), isNull(), isNull(), anyInt(), anyInt())).thenReturn(pr);
    }

    /* ---------------------------------------------------------------- ① ID 直取 */

    @Test
    void byId_direct() {
        when(activityService.getEntity(5L)).thenReturn(activity(5L, "迎新晚会"));

        ActivityResolver.Resolution r = resolver.resolve(5L, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.activity().getId()).isEqualTo(5L);
        assertThat(r.message()).isNull();
    }

    @Test
    void byId_notFound_fails() {
        when(activityService.getEntity(99L)).thenThrow(new RuntimeException("不存在"));

        ActivityResolver.Resolution r = resolver.resolve(99L, null);

        assertThat(r.failed()).isTrue();
        assertThat(r.message()).contains("99").contains("不存在");
    }

    /** 加固点：getEntity 若返回 null（而非抛异常），也必须判为失败，不能静默成功。 */
    @Test
    void byId_nullEntity_fails() {
        when(activityService.getEntity(7L)).thenReturn(null);

        assertThat(resolver.resolve(7L, null).failed()).isTrue();
    }

    /* -------------------------------------------- ② 名称尾缀「（ID n）」直取 */

    @Test
    void byNameSuffix_resolvesId() {
        when(activityService.getEntity(34L)).thenReturn(activity(34L, "新生党员欢迎会"));

        ActivityResolver.Resolution r = resolver.resolve(null, "新生党员欢迎会（ID 34）");

        assertThat(r.ok()).isTrue();
        assertThat(r.activity().getId()).isEqualTo(34L);
    }

    @Test
    void byNameSuffix_halfWidthParen_alsoWorks() {
        when(activityService.getEntity(34L)).thenReturn(activity(34L, "新生党员欢迎会"));

        assertThat(resolver.resolve(null, "新生党员欢迎会 (ID 34)").ok()).isTrue();
    }

    /* ---------------------------------------------------- ③ 精确同名唯一才拍板 */

    @Test
    void byName_exactUnique_resolves() {
        stubPage(row(3L, "迎新晚会"));
        when(activityService.getEntity(3L)).thenReturn(activity(3L, "迎新晚会"));

        ActivityResolver.Resolution r = resolver.resolve(null, "迎新晚会");

        assertThat(r.ok()).isTrue();
        assertThat(r.activity().getId()).isEqualTo(3L);
    }

    @Test
    void byName_exactUnique_ignoreCase() {
        stubPage(row(8L, "Freshman Gala"));
        when(activityService.getEntity(8L)).thenReturn(activity(8L, "Freshman Gala"));

        assertThat(resolver.resolve(null, "freshman gala").ok()).isTrue();
    }

    /* -------------------------------------------------------- ④ 多命中与零命中 */

    @Test
    void byName_duplicate_exactNeverAutoPicks() {
        stubPage(row(1L, "迎新晚会"), row(2L, "迎新晚会"));

        ActivityResolver.Resolution r = resolver.resolve(null, "迎新晚会");

        assertThat(r.failed()).isTrue();
        assertThat(r.message()).contains("同名").contains("（ID 1）").contains("请确认");
    }

    @Test
    void byName_fuzzyMultiple_listsCandidates() {
        stubPage(row(1L, "重名解析测试A"), row(2L, "重名解析测试B"));

        ActivityResolver.Resolution r = resolver.resolve(null, "重名解析测试");

        assertThat(r.failed()).isTrue();
        assertThat(r.message()).contains("匹配到 2 个活动")
                .contains("重名解析测试A（ID 1）")
                .contains("重名解析测试B（ID 2）");
    }

    @Test
    void byName_notFound_fails() {
        stubPage();

        ActivityResolver.Resolution r = resolver.resolve(null, "不存在的活动名");

        assertThat(r.failed()).isTrue();
        assertThat(r.message()).contains("没有找到");
    }

    @Test
    void neitherIdNorName_fails() {
        assertThat(resolver.resolve(null, null).failed()).isTrue();
        assertThat(resolver.resolve(null, "   ").message()).contains("需要指定活动");
    }
}
