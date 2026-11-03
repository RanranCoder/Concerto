import { Drawer, Empty, Spin, Tag, Alert } from 'antd'
import { useEffect, useState } from 'react'
import { listAgentTools } from '@/api/agent'
import type { AgentToolVO } from '@/api/agent'
import { kbSources } from '@/api/knowledge'
import type { KnowledgeSourceVO } from '@/api/knowledge'
import { useAgentStore } from '@/stores/agent'

interface Props {
  open: boolean
  onClose: () => void
}

const SENSITIVITY_LABEL: Record<string, { text: string; color: string }> = {
  NORMAL: { text: '普通', color: 'default' },
  SENSITIVE: { text: '敏感', color: 'orange' },
  RESTRICTED: { text: '受限', color: 'red' },
}

/**
 * 能力清单抽屉（灵思 §5.4）。
 *
 * 后端 GET /api/agent/tools 只会返回**当前用户有权调用**的工具（D3 第三层：可见性过滤）。
 * 所以这个抽屉同时是一个权限自查工具 —— 干事打开只能看到子集，与后端实际可调用集一致。
 */
export default function LingsiToolDrawer({ open, onClose }: Props) {
  const [list, setList] = useState<AgentToolVO[]>([])
  const [sources, setSources] = useState<KnowledgeSourceVO[]>([])
  const [loading, setLoading] = useState(false)
  // 只读档位下后端会把写类工具整体摘掉，面板必须跟着拉 ——
  // 否则会出现"面板里写着能做，实际调不动"的错位
  const mode = useAgentStore((s) => s.mode)

  const kb = mode === 'kb'

  useEffect(() => {
    if (!open) return
    setLoading(true)
    // 对话档位**不用工具**（走独立流水线），所以这里拉的是知识源清单而不是工具清单。
    // 继续显示 11 个读工具会造成"对话模式能查业务数据"的误解 —— 它不能。
    const loader = kb
      ? kbSources()
      : (listAgentTools(mode) as Promise<unknown>)
    Promise.resolve(loader)
      .then((res) => {
        if (kb) setSources((res as KnowledgeSourceVO[]) ?? [])
        else setList((res as AgentToolVO[]) ?? [])
      })
      .catch(() => {
        /* http 拦截器已提示 */
      })
      .finally(() => setLoading(false))
  }, [open, mode, kb])

  const reads = list.filter((t) => !t.write)
  const writes = list.filter((t) => t.write)

  const renderGroup = (title: string, items: AgentToolVO[]) =>
    items.length > 0 && (
      <div style={{ marginBottom: 20 }}>
        <div style={{ fontSize: 14, fontWeight: 'calc(600 - var(--font-weight-compensation))', marginBottom: 6, color: 'var(--text-primary)' }}>
          {title}（{items.length}）
        </div>
        {items.map((t) => {
          const s = SENSITIVITY_LABEL[t.sensitivity] ?? SENSITIVITY_LABEL.NORMAL
          return (
            <div className="lingsi-tool-row" key={t.name}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="lingsi-tool-name">
                  {t.label}
                  <span style={{ fontWeight: 'calc(400 - var(--font-weight-compensation))', opacity: 0.5, marginLeft: 6, fontSize: 13 }}>{t.name}</span>
                </div>
                <div className="lingsi-tool-desc">{t.description}</div>
              </div>
              <Tag color={s.color} style={{ margin: 0, flexShrink: 0, fontSize: 13 }}>
                {s.text}
              </Tag>
            </div>
          )
        })}
      </div>
    )

  return (
    <Drawer title="concerto当前会做什么" open={open} onClose={onClose} width={460}>
      {kb ? (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="当前是对话模式（知识库问答）"
            description="这个档位只查资料、不执行任何操作 —— 它不使用工具，所以这里列出的是它能检索的资料范围。回答都会带出处。"
          />
          {loading ? (
            <div style={{ textAlign: 'center', padding: 40 }}>
              <Spin />
            </div>
          ) : sources.length === 0 ? (
            <Empty description="知识库暂时没有可检索的内容" />
          ) : (
            <div style={{ marginBottom: 20 }}>
              <div style={{ fontSize: 14, fontWeight: 'calc(600 - var(--font-weight-compensation))', marginBottom: 6, color: 'var(--text-primary)' }}>
                可检索的资料（{sources.length} 类）
              </div>
              {sources.map((s) => (
                <div className="lingsi-tool-row" key={s.sourceType}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div className="lingsi-tool-name">
                      {s.label}
                      <span style={{ fontWeight: 'calc(400 - var(--font-weight-compensation))', opacity: 0.5, marginLeft: 6, fontSize: 13 }}>
                        {s.chunkCount} 条
                      </span>
                    </div>
                    <div className="lingsi-tool-desc">{s.description}</div>
                  </div>
                  <Tag style={{ margin: 0, flexShrink: 0, fontSize: 13 }}>
                    {s.tier === 'T2' ? '仅标题' : '全文'}
                  </Tag>
                </div>
              ))}
            </div>
          )}
        </>
      ) : (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="这里只列出你有权使用的能力"
            description="能力清单按你的权限实时过滤。看不到某能力，说明你当前无权调用它。"
          />
          {mode === 'readonly' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="当前是只读模式"
              description="写类能力已整体从清单中移除（模型也看不到）。切到「标准模式」后它们会重新出现。你的账号权限没有变化。"
            />
          )}
          {loading ? (
            <div style={{ textAlign: 'center', padding: 40 }}>
              <Spin />
            </div>
          ) : list.length === 0 ? (
            <Empty description="暂无可用的能力" />
          ) : (
            <>
              {renderGroup('查询类（只读，不改变数据）', reads)}
              {renderGroup('操作类（会改动数据，先生成清单待你确认）', writes)}
            </>
          )}
        </>
      )}
    </Drawer>
  )
}
