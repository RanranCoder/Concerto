import { Input, Popover, Select, Spin, Tooltip } from 'antd'
import { useState, useRef, useEffect } from 'react'
import type { AgentChatRef, AgentMode } from '@/api/agent'
import { listActivities } from '@/api/activity'
import type { ActivityVO } from '@/api/activity'
import { Book, Check, Close, Lock, Plus, Rules, Send, Stop, Toolbox } from './LingsiIcons'

interface Props {
  busy: boolean
  disabled?: boolean
  /** 运行模式（UI v2 §E）：只读 / 标准，由 store 持有 */
  mode: AgentMode
  onModeChange: (m: AgentMode) => void
  /**
   * 自动协作开关（仅标准档位有效）。
   * 标准模式下后端会自动判断「这次要不要派专家团队」；这个开关是用户的否决权，
   * 误判时能立刻关掉，不用忍着变慢。
   */
  autoCollab: boolean
  onAutoCollabChange: (v: boolean) => void
  /** 当前模型标识（来自最近一轮的返回）；空串 = 尚未产生过回答 */
  provider?: string
  /** 规则模式（Stub 兜底） */
  degraded?: boolean
  /** 待附加的上下文引用（UI v2 `+`） */
  pendingRefs: AgentChatRef[]
  onAddRef: (r: AgentChatRef) => void
  onRemoveRef: (r: AgentChatRef) => void
  /**
   * 外部塞入的草稿（用户消息的「编辑并重新发送」）。
   * 用 nonce 而不是比对文本：同一句话可能被编辑两次，只比文本第二次不会生效。
   * **只填入不发送** —— 用户常有微调需求，自动发送会制造大量"重发一次"。
   */
  seed?: { text: string; nonce: number } | null
  onSend: (text: string) => void
  onStop: () => void
  onOpenTools: () => void
}

interface ActivityOption {
  value: string
  label: string
  activity: ActivityVO
}

/**
 * 输入区（灵思 §6.3 + UI v2 §E「单侧控制点」）。
 *
 * <p>改版要点：运行参数从"散在消息底部"收进输入框 ——
 * 因为它们是**每次任务都可能变**的东西，放在设置页等于让用户来回跳。
 *
 * <p>`+` 附加上下文（B4 收尾项）的定位要分清楚：
 * 它<b>不是</b>替代 ActivityResolver 的名称解析，而是把"消歧"提前到零成本的一步 ——
 * 重名、简写、记不清全称时，与其让系统回一份候选清单再来一轮，
 * 不如直接从列表里点。选中的 ID 由系统产生而非模型猜测，后端可以据此做确定性解析。
 *
 * <p>交互约定（保留原设计）：Enter 发送 / Shift+Enter 换行 / 进行中变停止 / 自动增高 6 行。
 */
export default function LingsiComposer({
  busy,
  disabled,
  mode,
  onModeChange,
  autoCollab,
  onAutoCollabChange,
  provider,
  degraded,
  pendingRefs,
  onAddRef,
  onRemoveRef,
  seed,
  onSend,
  onStop,
  onOpenTools,
}: Props) {
  const [value, setValue] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)

  const [pickerOpen, setPickerOpen] = useState(false)
  const [options, setOptions] = useState<ActivityOption[]>([])
  const [searching, setSearching] = useState(false)
  const [picked, setPicked] = useState<string | undefined>(undefined)
  const timer = useRef<number | null>(null)

  // 请求结束后把焦点还给输入框（连续提问体验）
  useEffect(() => {
    if (!busy) ref.current?.focus()
  }, [busy])

  // 「编辑并重新发送」：填入草稿并聚焦（不发送）
  useEffect(() => {
    if (!seed) return
    setValue(seed.text)
    ref.current?.focus()
  }, [seed])

  // 搜索去抖：组件卸载时清掉，避免 setState 到已卸载组件上
  useEffect(
    () => () => {
      if (timer.current) window.clearTimeout(timer.current)
    },
    [],
  )

  // ⚠️ kb 必须单独派生，不能复用 readonly：
  // 两者都会让写工具不可见，但 readonly 是"这次不许写"，kb 是"这次只查资料"——
  // 后端走的是完全不同的两条路径（工具循环 vs KbPipeline）。
  // 用 readonly 表达 kb 会让「切到对话档位」看起来只是权限收紧。
  const readonly = mode === 'readonly'
  const kb = mode === 'kb'

  const submit = () => {
    const text = value.trim()
    if (!text || busy || disabled) return
    setValue('')
    onSend(text)
  }

  const searchActivity = (kw: string) => {
    if (timer.current) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(async () => {
      setSearching(true)
      try {
        const res = await listActivities({ keyword: kw || undefined, size: 10 })
        setOptions(
          (res?.records ?? []).map((a) => ({
            value: String(a.id),
            label: `${a.name}（ID ${a.id}）`,
            activity: a,
          })),
        )
      } catch {
        /* http 拦截器已提示 */
      } finally {
        setSearching(false)
      }
    }, 300)
  }

  /** 模型档位：只做展示，切换入口在后端配置（pams.agent.provider），不做假下拉 */
  const modelNode = degraded ? (
    <Tooltip title="后端未接入模型或调用失败，回答由本地关键词规则产生，能力有限">
      <span className="lingsi-model-pill lingsi-model-pill-warn">
        <Rules size={11.5} />
        规则模式
      </span>
    </Tooltip>
  ) : (
    <Tooltip title="模型由后端配置决定（pams.agent.provider / pams.agent.model）">
      <span className="lingsi-model-pill">{provider || '待命'}</span>
    </Tooltip>
  )

  const pickerContent = (
    <div className="lingsi-ref-picker">
      <Select<ActivityOption>
        showSearch
        allowClear
        style={{ width: 320 }}
        placeholder="输入活动名称搜索，选中即附加"
        filterOption={false}
        value={picked as unknown as ActivityOption}
        options={options}
        onSearch={searchActivity}
        onDropdownVisibleChange={(open) => {
          // 首次打开就给一份最近活动，避免"空列表让人以为不能搜"
          if (open && options.length === 0) searchActivity('')
        }}
        notFoundContent={searching ? <Spin size="small" /> : '暂无匹配活动'}
        onChange={(_v, opt) => {
          const o = opt as unknown as ActivityOption | undefined
          if (o?.activity) {
            onAddRef({ type: 'activity', id: o.activity.id, label: o.activity.name })
            setPicked(undefined) // 选完即清空，方便继续附加下一个
          }
        }}
      />
      <div className="lingsi-ref-picker-hint">
        选中的活动会作为可信上下文注入本次提问，避免名称歧义；最多 5 个。
      </div>
    </div>
  )

  return (
    <div className="lingsi-composer">
      <div className="lingsi-composer-inner">
        <div className="lingsi-composer-box">
          {pendingRefs.length > 0 && (
            <div className="lingsi-ref-chips">
              {pendingRefs.map((r) => (
                <span className="lingsi-ref-chip" key={`${r.type}-${r.id}`}>
                  <span className="lingsi-ref-chip-label">{r.label || `${r.type} ${r.id}`}</span>
                  <button
                    type="button"
                    className="lingsi-ref-chip-x"
                    aria-label="移除"
                    onClick={() => onRemoveRef(r)}
                  >
                    <Close size={10} />
                  </button>
                </span>
              ))}
            </div>
          )}

          <Input.TextArea
            ref={ref}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={
              disabled
                ? '正在加载会话…'
                : kb
                  ? '问制度、问流程、问以前怎么做的，例如「发展党员要走哪些流程」'
                  : '问我任何党务相关的事，例如「南湖革命纪念馆活动谁还没签到」'
            }
            autoSize={{ minRows: 1, maxRows: 6 }}
            disabled={disabled}
            variant="borderless"
            className="lingsi-composer-ta"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault()
                submit()
              }
            }}
          />

          <div className="lingsi-composer-bar">
            <Popover
              open={pickerOpen}
              onOpenChange={setPickerOpen}
              trigger="click"
              placement="topLeft"
              title="附加活动作为上下文"
              content={pickerContent}
            >
              <Tooltip title="附加活动作为上下文">
                <button type="button" className="lingsi-icon-btn" aria-label="附加上下文">
                  <Plus size={15} />
                </button>
              </Tooltip>
            </Popover>

            <Tooltip title="我当前会做什么">
              <button type="button" className="lingsi-icon-btn" onClick={onOpenTools}>
                <Toolbox size={15} />
              </button>
            </Tooltip>

            {/* ---- 档位：只读 / 标准 / 对话 ---- */}
            <span className="lingsi-seg" role="group" aria-label="运行模式">
              <Tooltip title="本次会话不写入任何数据。你的账号权限没有变化，只是主动放弃写能力。">
                <button
                  type="button"
                  className={`lingsi-seg-btn${readonly ? ' lingsi-seg-btn-on' : ''}`}
                  aria-pressed={readonly}
                  onClick={() => onModeChange('readonly')}
                >
                  <Lock size={11.5} />
                  只读
                </button>
              </Tooltip>
              <Tooltip title="可执行写操作，但每次写入前都会先给你一份清单，确认后才落库。">
                <button
                  type="button"
                  className={`lingsi-seg-btn${mode === 'standard' ? ' lingsi-seg-btn-on' : ''}`}
                  aria-pressed={mode === 'standard'}
                  onClick={() => onModeChange('standard')}
                >
                  <Check size={11.5} />
                  标准
                </button>
              </Tooltip>
              <Tooltip
                title={
                  '只在制度、公告、稿件、策划书等资料里找答案，并给出出处。'
                  + '不执行任何操作，也不会改动数据。'
                }
              >
                <button
                  type="button"
                  className={`lingsi-seg-btn${kb ? ' lingsi-seg-btn-on' : ''}`}
                  aria-pressed={kb}
                  onClick={() => onModeChange('kb')}
                >
                  <Book size={11.5} />
                  对话
                </button>
              </Tooltip>
            </span>

            {/*
              自动协作开关：只在标准档位出现 —— 只读不会升级协作，对话档位走的是
              完全不同的问答流水线（KbPipeline），两者都不涉及「派不派专家」。
              关闭时给一个显式状态，而不是让开关消失，否则用户会以为功能坏了。
            */}
            {mode === 'standard' && (
              <Tooltip
                title={
                  autoCollab
                    ? '遇到跨领域的复合任务时，灵思会自动派专家团队协作（检索/策划/写作/审查）。'
                      + '简单问题仍然单 Agent 处理。'
                    : '已关闭：所有任务都由单个 Agent 处理。'
                }
              >
                <button
                  type="button"
                  className={`lingsi-collab-toggle${autoCollab ? ' lingsi-collab-toggle-on' : ''}`}
                  aria-pressed={autoCollab}
                  onClick={() => onAutoCollabChange(!autoCollab)}
                >
                  <Rules size={11.5} />
                  自动协作
                </button>
              </Tooltip>
            )}

            {modelNode}

            {busy ? (
              <Tooltip title="停止本次回答">
                <button type="button" className="lingsi-send lingsi-send-stop" onClick={onStop}>
                  <Stop size={13} />
                </button>
              </Tooltip>
            ) : (
              <Tooltip title="发送（Enter）">
                <button
                  type="button"
                  className="lingsi-send"
                  onClick={submit}
                  disabled={disabled || !value.trim()}
                >
                  <Send size={14} />
                </button>
              </Tooltip>
            )}
          </div>
        </div>

        <div className="lingsi-composer-hint">
          <span>Enter 发送 · Shift+Enter 换行</span>
          <span className="lingsi-composer-hint-r">
            {kb
              ? '对话模式 · 只查资料，不会改动任何数据；回答都带出处'
              : readonly
                ? '只读模式 · 本次会话不会改动任何数据'
                : '涉及变更的操作会先生成清单，你确认后才执行'}
          </span>
        </div>
      </div>
    </div>
  )
}
