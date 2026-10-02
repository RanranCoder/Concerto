package com.pams.module.knowledge.snapshot;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 事实快照模板（D12 的 **B 形态聚合快照**）。
 *
 * <h3>为什么是"预注册"而不是"让模型决定取什么数"</h3>
 * 快照会把业务数据写进知识库、进而进入 LLM 上下文。如果取数范围由模型临时决定，
 * 就等于"能不能出网"这件事由模型自己说了算 —— 这与整个方案的权限设计相抵触。
 * 所以每个快照都必须在此**预先声明**：取什么、给谁看、多久刷一次。
 *
 * <p>声明清单本身就是**隐私与权限的评审入口** ——
 * 想确认"系统自动把哪些数据写进了知识库"，读 {@code SnapshotSpecRegistry} 一份就够。
 *
 * <h3>只接计数与组织名（结构性约束）</h3>
 * {@link #narrator} 的契约是"输入作用域 id、输出一段叙述文本"，
 * 而所有实现都只使用**已聚合成 {@code (label, count)} 的查询结果** ——
 * 类型上就拿不到个人字段。这不是靠"写的时候注意点"，而是收窄取值口：
 * 想塞进一个人名，就得先写一条返回明细的查询，那一步在评审时看得见。
 *
 * <p>但要说清楚：<b>这条约束目前靠"取值口 + 评审 + 端到端断言"三重保障，不是类型强制的</b>。
 * 端到端那一道在 {@code kb3.mjs}：用真实数据生成快照后，
 * 断言落库内容里不含手机号/身份证/真实人名。
 *
 * <h3>asOf 的语义</h3>
 * 快照的"数据截止时间"取**生成时刻**：聚合查询读到的是当前状态，
 * 所以"截至现在"就是准确的表述。这也让用户不会被误导成"这是实时值"——
 * 它明确是一个时间点上的数字。
 */
public record SnapshotSpec(

        /** 模板标识，落到 {@code kb_snapshot.spec_code}。改它等于换了一个模板 */
        String code,

        /** 评审用说明：这个快照收什么、为什么是这个可见范围 */
        String description,

        /** 作用域类型：{@code GLOBAL} / {@code MEMBER_SESSION} 等 */
        String scopeType,

        /**
         * 标题模板，{@code %s} 会被作用域名替换。
         * 例：{@code "%s 成员构成"} → "第 40 届 成员构成"
         */
        String titleTemplate,

        /**
         * 可见性：{@code ALL} / {@code LEADER}。
         *
         * <p><b>必须来自来源页面的守卫口径，且必须显式声明</b>（没有默认值）——
         * 默认值会让"这个快照该给谁看"这个决策被跳过。
         * 口径的判断方法见 {@code KnowledgeSource} 的约定 ①：
         * 先问"这份数据的页面入口被谁挡住了"。
         */
        String visibility,

        /** 附加权限码要求；与 {@code visibility} 取"与" */
        String requiredPermission,

        /** 出处跳转（引用卡的"打开原文"） */
        String link,

        /** 刷新提示（人读的，供知识库页面展示；实际频率由定时任务决定） */
        String refreshHint,

        /** 要生成哪些作用域的快照 */
        Supplier<List<ScopeRef>> scopes,

        /**
         * 生成叙述文本。
         *
         * <p>实现里**只允许使用聚合成 {@code (label, count)} 的结果**，
         * 不得读取任何含个人标识的字段。见类注释的"只接计数与组织名"。
         */
        Function<Long, String> narrator
) {

    /**
     * 一个作用域。
     *
     * @param id    作用域主键；{@code 0} = 全局（不能用 null，见 {@code KbSnapshot#scopeId}）
     * @param label 人读的作用域名，用于标题
     */
    public record ScopeRef(long id, String label) {
        public static ScopeRef global() { return new ScopeRef(0L, "全校"); }
    }

    /** 生成该作用域的标题 */
    public String titleOf(String scopeLabel) {
        return String.format(titleTemplate, scopeLabel);
    }

    /** 该快照是否仅部长及以上可见 */
    public boolean leaderOnly() {
        return "LEADER".equals(visibility);
    }

    /** 展示用：可见范围描述 */
    public String visibilityLabel() {
        return leaderOnly() ? "部长及以上" : "全员";
    }
}
