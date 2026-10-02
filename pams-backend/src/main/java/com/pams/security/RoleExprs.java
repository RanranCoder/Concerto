package com.pams.security;

/**
 * 角色谓词常量（SpEL 片段）—— 供 {@code @PreAuthorize} 复用。
 *
 * <p><b>为什么要抽出来</b>：本系统的权限判定存在**两套并行口径**：
 * <ul>
 *   <li><b>权限码</b>（{@code hasAuthority('x:y')}）：细粒度、可在权限管理页运行时配置</li>
 *   <li><b>角色谓词</b>（{@code hasAnyRole(...)}）：粗粒度、硬编码，用于"部长及以上"这类**角色级别**语义</li>
 * </ul>
 * 两套口径并存本身可以接受，但**同一个资源只能用一套**。
 * 2026-09-14 权限审计发现的实际事故（A9）正是"同一资源混用两套"：
 * 前端 `/party/*` 路由用 {@code RequireRole(LEADER_ROLES)} 守卫（角色口径），
 * 而灵思工具 {@code queryPartyMemberProgress} 用 {@code party:view}（权限码口径），
 * 且该权限码被授予了 STAFF —— 结果**干事看不到党务台账页面，却能通过灵思问出党员姓名与阶段**。
 *
 * <p>所以本类的定位是：<b>凡是用角色口径的资源，都引用这里的常量，不要各写一份字面量</b>。
 * 字面量散落会导致"改了一处漏了另一处"，而这类漏不会编译报错、也不会运行报错。
 *
 * <p>与前端 {@code src/components/glass/RequireRole.tsx} 的 {@code LEADER_ROLES} / {@code ADMIN_ROLES}
 * <b>必须保持一致</b>——它们是同一口径的两侧。改动任一侧都要同步。
 */
public final class RoleExprs {

    private RoleExprs() {}

    /**
     * 「部长及以上」：6 个角色，不含 STAFF。
     *
     * <p>对应前端 {@code LEADER_ROLES}，也对应 {@link com.pams.module.agent.core.ToolConventions#LEADER_ROLES}
     * （后者用于 {@code AgentTool.requiresLeader()}，因为工具层拿到的是 roleCode 字符串而非 Security 表达式）。
     */
    public static final String LEADER =
            "hasAnyRole('TEACHER','DIRECTOR','ORG_LEADER','SECRETARY_LEADER','MEDIA_LEADER','TECH_LEADER')";

    /** 「主任 / 指导老师」：系统设置等管理入口 */
    public static final String ADMIN = "hasAnyRole('TEACHER','DIRECTOR')";
}
