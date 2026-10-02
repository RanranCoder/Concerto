package com.pams.common;

/**
 * 当前请求的组织（租户）上下文。ThreadLocal 承载，由 {@code JwtAuthenticationFilter}
 * 在认证成功后设置、请求结束于 finally 清理（Web 线程池复用，必须清理，否则串租户）。
 *
 * <p>阶段 1「只写不过滤」：本上下文只用于写入时归属组织，不参与任何查询过滤。
 * 阶段 2 才会把它接入 Hibernate 的租户过滤器。
 *
 * <p>为什么不从 JWT 读取 org：与现有安全哲学一致——{@code JwtAuthenticationFilter}
 * 每请求都回源查库重算权限，租户同样取 {@code sys_user.org_id}，
 * 避免 token 被跨组织重放/伪造（见策划 §9.5）。
 */
public final class OrgContext {

    /** 默认组织 id：承接改造前的全部存量数据（V30 种下）。 */
    public static final Long DEFAULT_ORG_ID = 1L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private OrgContext() {
    }

    public static void set(Long orgId) {
        CURRENT.set(orgId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    /** 兜底默认组织：匿名端点（如扫码签到）无登录用户时使用。 */
    public static Long getOrDefault() {
        Long v = CURRENT.get();
        return v == null ? DEFAULT_ORG_ID : v;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
