package com.pams.module.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Sensitivity#max} 的纯单测（留存分档策略的地基）。
 *
 * <p><b>为什么这条策略值得单独锁</b>：它是"一轮里执行了敏感工具 → 这一轮的答复与思维链
 * 都只能按摘要留存"的唯一裁决点。此前 {@code AgentOrchestrator} 里写的是
 * {@code pendingPlan != null ? NORMAL : NORMAL}（两个分支同值），策略写了但从未生效 ——
 * 这类缺陷<b>没有任何运行期症状</b>：请求成功、回复正常，只是敏感轮的原文被全量写进了库。
 * 因此这里逐个组合钉死，尤其是"取更严"而不是"取最后一个/第一个"。
 */
class SensitivityMaxTest {

    /** 三档两两组合（含自身）：结果必须是最严的那一档 */
    @Test
    void picksTheStricterOfEveryCombination() {
        // 声明顺序即由松到严：NORMAL < SENSITIVE < RESTRICTED
        Sensitivity[] levels = Sensitivity.values();

        for (Sensitivity a : levels) {
            for (Sensitivity b : levels) {
                Sensitivity expected = a.ordinal() >= b.ordinal() ? a : b;
                assertThat(Sensitivity.max(a, b))
                        .as("max(%s, %s)", a, b)
                        .isEqualTo(expected);
            }
        }
    }

    /** 显式点名几条契约（读测试的人不必自己推 ordinal） */
    @Test
    void namedCasesFromThePolicy() {
        assertThat(Sensitivity.max(Sensitivity.NORMAL, Sensitivity.SENSITIVE))
                .isEqualTo(Sensitivity.SENSITIVE);
        assertThat(Sensitivity.max(Sensitivity.SENSITIVE, Sensitivity.RESTRICTED))
                .isEqualTo(Sensitivity.RESTRICTED);
        assertThat(Sensitivity.max(Sensitivity.NORMAL, Sensitivity.RESTRICTED))
                .isEqualTo(Sensitivity.RESTRICTED);
        assertThat(Sensitivity.max(Sensitivity.SENSITIVE, Sensitivity.NORMAL))
                .as("顺序无关：谁严取谁，与传参顺序无关")
                .isEqualTo(Sensitivity.SENSITIVE);
        assertThat(Sensitivity.max(Sensitivity.NORMAL, Sensitivity.NORMAL))
                .isEqualTo(Sensitivity.NORMAL);
    }

    /** null 参与：返回另一个；两个都 null → NORMAL（"没有更严的信息"就是默认档，而不是 NPE） */
    @Test
    void nullIsTreatedAsAbsentNotAsStrictest() {
        assertThat(Sensitivity.max(null, Sensitivity.NORMAL)).isEqualTo(Sensitivity.NORMAL);
        assertThat(Sensitivity.max(Sensitivity.NORMAL, null)).isEqualTo(Sensitivity.NORMAL);
        assertThat(Sensitivity.max(null, Sensitivity.SENSITIVE)).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(Sensitivity.max(Sensitivity.SENSITIVE, null)).isEqualTo(Sensitivity.SENSITIVE);
        assertThat(Sensitivity.max(null, Sensitivity.RESTRICTED)).isEqualTo(Sensitivity.RESTRICTED);
        assertThat(Sensitivity.max(Sensitivity.RESTRICTED, null)).isEqualTo(Sensitivity.RESTRICTED);
        assertThat(Sensitivity.max(null, null))
                .as("两个都缺失时必须是 NORMAL —— 越严兜底会让普通轮次被误判成敏感档")
                .isEqualTo(Sensitivity.NORMAL);
    }

    /** 可交换性：max 是"取更严"，不是"取后者" —— 这道断言专门防"实现被改成返回 b" */
    @Test
    void isCommutative() {
        for (Sensitivity a : Sensitivity.values()) {
            for (Sensitivity b : Sensitivity.values()) {
                assertThat(Sensitivity.max(a, b))
                        .as("max(%s, %s) 应等于 max(%s, %s)", a, b, b, a)
                        .isEqualTo(Sensitivity.max(b, a));
            }
        }
    }

    /**
     * 声明顺序契约：{@link Sensitivity#max} 用 {@code ordinal()} 比较，因此
     * <b>枚举的声明顺序本身就是策略</b>。有人为了"看起来更像字典序"把 RESTRICTED 挪到前面，
     * 会让 max 静默变成"取更松" —— 这条断言让那次改动在 CI 里立刻变红。
     */
    @Test
    void declarationOrderIsLoosestToStrictest() {
        assertThat(Sensitivity.values())
                .containsExactly(Sensitivity.NORMAL, Sensitivity.SENSITIVE, Sensitivity.RESTRICTED);
        assertThat(Sensitivity.NORMAL.ordinal()).isLessThan(Sensitivity.SENSITIVE.ordinal());
        assertThat(Sensitivity.SENSITIVE.ordinal()).isLessThan(Sensitivity.RESTRICTED.ordinal());
    }

    /** max 必须等于"可出网性"递减的那个方向：越严的档越不能出网（两者不能互相矛盾） */
    @Test
    void stricterAlsoMeansStricterEgress() {
        Sensitivity max = Sensitivity.max(Sensitivity.NORMAL, Sensitivity.RESTRICTED);
        assertThat(max.canEgress()).isFalse();
        assertThat(Sensitivity.max(Sensitivity.NORMAL, Sensitivity.SENSITIVE).canEgress())
                .as("sensitive 仍可出网（脱敏后），只有 restricted 不出网")
                .isTrue();
    }
}
