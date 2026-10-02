package com.pams.module.agent.core;

import java.util.Set;

/**
 * 工具统一约定（业务设计方案 §3.5 "工具实现的统一约定"，对应 D11）。
 *
 * <p>由 {@link ToolContext} 从 {@link com.pams.security.LoginUser} 统一推导，**禁止工具自行判断角色**。
 *
 * <p>为什么必须统一：{@code PartyMemberService.page(..., boolean staff)} 的 {@code staff} 标志
 * 是由 **Controller 传入**的（脱敏不是自动注入）。工具若自己判断角色而漏传或传错，
 * 脱敏会**静默失效且不报错**——这是 D11 第三类变更里最危险的一种。
 */
public final class ToolConventions {

    private ToolConventions() {}

    /** 干事角色码 */
    public static final String STAFF = "STAFF";

    /** 领导角色码（与 ActivityController.LEADER 同源，用于 content 模块的 LEADER 等价口径） */
    public static final Set<String> LEADER_ROLES =
            Set.of("TEACHER", "DIRECTOR", "ORG_LEADER", "SECRETARY_LEADER", "MEDIA_LEADER", "TECH_LEADER");

    /**
     * 单字符 JSON 摘要用的占位。args_digest 只落 hash，不落明文（D9）。
     */
    public static final int ARGS_DIGEST_LEN = 32;
}
