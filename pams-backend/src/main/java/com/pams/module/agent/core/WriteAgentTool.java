package com.pams.module.agent.core;

import java.util.List;
import java.util.Map;

/**
 * 写类工具契约（业务设计方案 D2）。
 *
 * <p>写类工具被拆成 <b>读阶段</b>（继承自 {@link AgentTool#execute}，即 dry-run：只查询匹配、绝不写库）
 * 与 <b>写阶段</b>（本接口的 {@link #commit}）两个方法。
 *
 * <p><b>为什么不给每个 Service 加 preview 方法</b>：dry-run 能力来自"把查询匹配与写库分离"，
 * 而不是给业务 Service 增加新接口——后者会污染业务侧，违反铁律①。
 */
public interface WriteAgentTool extends AgentTool {

    @Override
    default boolean writeOperation() { return true; }

    /**
     * 真正的写阶段。仅在用户通过确认门禁（携带一次性 confirmToken、乐观锁抢占成功）后被调用。
     *
     * @param ctx   执行上下文
     * @param plan  已确认的拟执行清单（含 dry-run 阶段算出的匹配结果）
     * @return 执行结果（SUCCESS / FAILED / PARTIAL）
     */
    CommitResult commit(ToolContext ctx, AgentActionPlan plan);

    /** 执行结果 */
    record CommitResult(String status, int affectedCount, int failedCount,
                        String message, Map<String, Object> detail, List<Map<String, Object>> references) {

        public static CommitResult success(int affected, String message) {
            return new CommitResult("SUCCESS", affected, 0, message, Map.of(), List.of());
        }

        public static CommitResult partial(int affected, int failed, String message) {
            return new CommitResult("PARTIAL", affected, failed, message, Map.of(), List.of());
        }

        public static CommitResult failed(String message) {
            return new CommitResult("FAILED", 0, 0, message, Map.of(), List.of());
        }

        public String resultStatus() { return status; }
    }
}
