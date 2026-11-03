import { Tag, Tooltip } from 'antd'
import type { AgentToolCallEntry } from '@/api/agent'
import { Rules } from './LingsiIcons'

interface Props {
  provider: string
  degraded: boolean
  durationMs: number
  toolCalls: AgentToolCallEntry[]
}

/**
 * 结果区底部的元信息条（灵思 §3.5.1 + UI v2 §E）。
 *
 * <p>改版后只剩两件事：**每轮的耗时/工具数** + **降级标识**。
 * 原来还把 provider 名字也挂在这里，现在模型档位常驻在输入框（单侧控制点），
 * 消息底部重复一遍只是噪声。
 *
 * <p><b>硬约束保留</b>：`provider === 'stub'` 或 `degraded === true` 时必须显式标注「规则模式」。
 * 这条不能因为"composer 已经有模型档位"就删掉 —— 两者语义不同：
 * composer 说的是"下一个回答会用什么"，这里的标签说的是"**这条**回答是怎么来的"。
 * 历史消息里可能混着降级回答，不逐条标注就是把模板输出当模型输出给用户看。
 */
/**
 * 工具结果失败态。⚠️ 状态值来自后端 ToolResult：OK / FAILED / DENIED / SKIPPED / TIMEOUT / FORM
 * ——**没有 "SUCCESS"**（SUCCESS 是 agent_task 的任务终态，两套枚举不同域）。
 * 误写成 `status !== 'SUCCESS'` 会把 OK/FORM 全部判成失败（回归实测踩到：
 * 每条带工具调用的消息都红字提示"N 项失败"，与真实执行结果无关）。
 */
const FAILED_STATUSES = new Set(['FAILED', 'TIMEOUT', 'DENIED'])

export default function LingsiMetaBar({ provider, degraded, durationMs, toolCalls }: Props) {
  const isStub = provider === 'stub' || degraded
  const toolCount = toolCalls?.length ?? 0
  const failed = toolCalls?.filter((t) => t.status && FAILED_STATUSES.has(t.status)).length ?? 0

  if (!isStub && toolCount === 0 && durationMs <= 0) return null

  return (
    <div className="lingsi-meta">
      {isStub && (
        <Tooltip title="这条答复生成时未启用模型，或该次模型调用失败，因此使用本地规则。当前服务配置请查看输入框。">
          <Tag color="orange" className="lingsi-meta-tag">
            <span className="lingsi-meta-tag-in">
              <Rules size={11} />
              规则模式
            </span>
          </Tag>
        </Tooltip>
      )}
      {toolCount > 0 && (
        <span>
          调用 {toolCount} 项能力
          {failed > 0 && <span className="lingsi-meta-failed">（{failed} 项失败）</span>}
        </span>
      )}
      {durationMs > 0 && <span>耗时 {(durationMs / 1000).toFixed(2)}s</span>}
    </div>
  )
}
