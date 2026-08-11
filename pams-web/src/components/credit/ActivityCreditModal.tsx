import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Checkbox, Collapse, Empty, Form, Input, InputNumber, Select, Space, Spin, Steps } from 'antd'
import GlassModal from '@/components/glass/GlassModal'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listSignInGroups, type SignInGroupVO } from '@/api/signinGroup'
import { activityBatchCredit } from '@/api/credit'

interface PersonOption {
  userId?: number | null
  personName: string
  studentNo?: string | null
}

interface ActivityCreditModalProps {
  open: boolean
  onClose: () => void
  onSuccess?: () => void
}

/**
 * 素拓活动加分弹窗（PRD F05）
 * 步骤：①选择活动 → ②选择名单分组 → ③选择人员 → ④设置加分 → ⑤预览确认
 */
export default function ActivityCreditModal({ open, onClose, onSuccess }: ActivityCreditModalProps) {
  const { message } = App.useApp()
  const [step, setStep] = useState(0)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [groups, setGroups] = useState<SignInGroupVO[]>([])
  const [selectedActivity, setSelectedActivity] = useState<number>()
  const [checkedGroupIds, setCheckedGroupIds] = useState<number[]>([])
  const [selectedPeople, setSelectedPeople] = useState<PersonOption[]>([])
  const [peoplePool, setPeoplePool] = useState<PersonOption[]>([])
  const [checkedPeople, setCheckedPeople] = useState<Set<string>>(new Set())
  const [credit, setCredit] = useState<number>(0.5)
  const [reason, setReason] = useState('')
  const [loadingActivities, setLoadingActivities] = useState(false)
  const [loadingGroups, setLoadingGroups] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()

  const loadActivities = useCallback(() => {
    setLoadingActivities(true)
    listActivities({ size: 200 })
      .then((res) => {
        // 仅可选已完成/已归档活动（有签到数据的更佳）
        const list = (res.records ?? []).filter(
          (a) => a.status === 'FINISHED' || a.status === 'ARCHIVED',
        )
        setActivities(list)
      })
      .catch(() => {
        /* 拦截已提示 */
      })
      .finally(() => setLoadingActivities(false))
  }, [])

  useEffect(() => {
    if (open) {
      setStep(0)
      setSelectedActivity(undefined)
      setCheckedGroupIds([])
      setPeoplePool([])
      setCheckedPeople(new Set())
      setCredit(0.5)
      form.resetFields()
      loadActivities()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // 选择活动后加载其签到分组
  const loadGroups = useCallback((activityId: number) => {
    setLoadingGroups(true)
    listSignInGroups(activityId)
      .then((res) => {
        setGroups(res ?? [])
        // 默认全选所有分组
        setCheckedGroupIds((res ?? []).map((g) => g.id))
      })
      .catch(() => {
        /* 拦截已提示 */
      })
      .finally(() => setLoadingGroups(false))
  }, [])

  const handleActivityChange = (activityId: number) => {
    setSelectedActivity(activityId)
    setCheckedGroupIds([])
    setPeoplePool([])
    setCheckedPeople(new Set())
    loadGroups(activityId)
    const act = activities.find((a) => a.id === activityId)
    setReason(act?.name ?? '')
    form.setFieldsValue({ project: act?.name ? `${act.name}参与加分` : '', reason: act?.name ?? '' })
  }

  // 汇总选中分组的人员（去重，按姓名+学号）
  const poolFromGroups = useMemo(() => {
    const map = new Map<string, PersonOption>()
    for (const g of groups) {
      if (!checkedGroupIds.includes(g.id)) continue
      for (const p of g.people ?? []) {
        const key = `${p.fields['姓名'] ?? ''}|${p.fields['学号'] ?? ''}`
        map.set(key, {
          userId: undefined,
          personName: p.fields['姓名'] ?? '未知',
          studentNo: p.fields['学号'] || null,
        })
      }
    }
    return Array.from(map.values())
  }, [groups, checkedGroupIds])

  const totalSelected = selectedPeople.length
  const totalCredit = Math.round(totalSelected * (credit ?? 0) * 100) / 100

  const next = async () => {
    if (step === 0) {
      if (!selectedActivity) {
        message.warning('请选择活动')
        return
      }
      setStep(1)
    } else if (step === 1) {
      if (checkedGroupIds.length === 0) {
        message.warning('请至少选择一个分组')
        return
      }
      setPeoplePool(poolFromGroups)
      setStep(2)
    } else if (step === 2) {
      const checked = peoplePool.filter((p) => checkedPeople.has(`${p.personName}|${p.studentNo ?? ''}`))
      if (checked.length === 0) {
        message.warning('请至少选择一名人员')
        return
      }
      setSelectedPeople(checked)
      setStep(3)
    } else if (step === 3) {
      const values = await form.validateFields()
      setReason(values.reason ?? '')
      setStep(4)
    }
  }

  const prev = () => setStep((s) => Math.max(0, s - 1))

  const submit = async () => {
    setSubmitting(true)
    try {
      await activityBatchCredit({
        sourceActivityId: selectedActivity as number,
        project: form.getFieldValue('project') || reason,
        credit,
        remark: form.getFieldValue('remark') || null,
        people: selectedPeople,
      })
      message.success(`已为 ${totalSelected} 人各加 ${credit} 分`)
      onClose()
      onSuccess?.()
    } catch {
      /* 拦截已提示 */
    } finally {
      setSubmitting(false)
    }
  }

  const togglePerson = (p: PersonOption) => {
    const key = `${p.personName}|${p.studentNo ?? ''}`
    setCheckedPeople((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const groupItems = groups.map((g) => ({
    key: String(g.id),
    label: (
      <Space size={8}>
        <Checkbox
          checked={checkedGroupIds.includes(g.id)}
          onChange={(e) => {
            setCheckedGroupIds((prev) =>
              e.target.checked ? [...prev, g.id] : prev.filter((id) => id !== g.id),
            )
          }}
          onClick={(e) => e.stopPropagation()}
        />
        <span>{g.groupName}</span>
        <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
          {g.signedCount}/{g.count} 已签
        </span>
      </Space>
    ),
    children: (
      <div style={{ maxHeight: 260, overflow: 'auto' }}>
        {(g.people ?? []).map((p, i) => {
          const key = `${p.fields['姓名'] ?? ''}|${p.fields['学号'] ?? ''}`
          return (
            <div
              key={i}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 4px',
                cursor: 'pointer',
                borderRadius: 6,
                background: checkedPeople.has(key) ? 'var(--color-primary-weak, rgba(22,119,255,.08))' : 'transparent',
              }}
              onClick={() =>
                togglePerson({
                  userId: undefined,
                  personName: p.fields['姓名'] ?? '未知',
                  studentNo: p.fields['学号'] || null,
                })
              }
            >
              <Checkbox checked={checkedPeople.has(key)} />
              <span>{p.fields['姓名'] ?? '未知'}</span>
              <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
                {p.fields['学号'] ?? ''} · {p.signed ? '已签' : '未签'}
              </span>
            </div>
          )
        })}
      </div>
    ),
  }))

  return (
    <GlassModal
      title="活动加分"
      open={open}
      onCancel={onClose}
      width={720}
      footer={
        <Space>
          <Button onClick={onClose}>取消</Button>
          {step > 0 && <Button onClick={prev}>上一步</Button>}
          {step < 4 && (
            <Button type="primary" onClick={next}>
              下一步
            </Button>
          )}
          {step === 4 && (
            <Button type="primary" loading={submitting} onClick={submit}>
              确认提交
            </Button>
          )}
        </Space>
      }
    >
      <Steps
        size="small"
        current={step}
        items={[
          { title: '选择活动' },
          { title: '选择分组' },
          { title: '选择人员' },
          { title: '设置加分' },
          { title: '确认' },
        ]}
        style={{ marginBottom: 20 }}
      />

      {step === 0 && (
        <div>
          <Select
            showSearch
            optionFilterProp="label"
            style={{ width: '100%' }}
            placeholder={loadingActivities ? '加载中…' : '选择已完成/已归档的活动'}
            value={selectedActivity}
            loading={loadingActivities}
            onChange={handleActivityChange}
            options={activities.map((a) => ({ value: a.id, label: a.name }))}
          />
          <div style={{ marginTop: 12, color: 'var(--color-text-secondary)', fontSize: 12 }}>
            仅显示已完成 / 已归档的活动，将按活动的签到名单批量加分
          </div>
        </div>
      )}

      {step === 1 && (
        <div>
          <div style={{ marginBottom: 8, color: 'var(--color-text-secondary)', fontSize: 12 }}>
            勾选要参与加分的名单分组（共 {groups.length} 组）
          </div>
          <Spin spinning={loadingGroups}>
            {groups.length === 0 ? (
              <Empty description="该活动暂无签到分组，请先在签到管理中上传名单" />
            ) : (
              <Collapse
                items={groupItems}
                defaultActiveKey={groups.map((g) => String(g.id))}
                style={{ maxHeight: 380, overflow: 'auto' }}
              />
            )}
          </Spin>
        </div>
      )}

      {step === 2 && (
        <div>
          <div style={{ marginBottom: 8, display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
              人员池 {peoplePool.length} 人 · 已选 {selectedPeople.length} 人
            </span>
            <Space>
              <Button size="small" onClick={() => setCheckedPeople(new Set(peoplePool.map((p) => `${p.personName}|${p.studentNo ?? ''}`)))}>
                全选
              </Button>
              <Button size="small" onClick={() => setCheckedPeople(new Set())}>
                清空
              </Button>
            </Space>
          </div>
          <div style={{ maxHeight: 380, overflow: 'auto', border: '1px solid var(--color-border)', borderRadius: 8, padding: 8 }}>
            {peoplePool.map((p, i) => {
              const key = `${p.personName}|${p.studentNo ?? ''}`
              return (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '6px 4px',
                    cursor: 'pointer',
                    borderRadius: 6,
                    background: checkedPeople.has(key) ? 'var(--color-primary-weak, rgba(22,119,255,.08))' : 'transparent',
                  }}
                  onClick={() => togglePerson(p)}
                >
                  <Checkbox checked={checkedPeople.has(key)} />
                  <span>{p.personName}</span>
                  <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>{p.studentNo ?? ''}</span>
                </div>
              )
            })}
            {peoplePool.length === 0 && <Empty description="所选分组暂无人员" />}
          </div>
        </div>
      )}

      {step === 3 && (
        <Form form={form} layout="vertical" preserve={false} initialValues={{ credit: 0.5 }}>
          <Form.Item
            name="credit"
            label="加分值"
            rules={[{ required: true, message: '请输入加分值' }]}
            extra="范围 0.1 ~ 10，精度 0.1"
          >
            <InputNumber
              min={0.1}
              max={10}
              precision={1}
              step={0.1}
              style={{ width: '100%' }}
              onChange={(v) => setCredit(v ?? 0.5)}
            />
          </Form.Item>
          <Form.Item name="project" label="加分原因（自动填充活动名称）" rules={[{ required: true, message: '请输入加分原因' }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} placeholder="选填" />
          </Form.Item>
        </Form>
      )}

      {step === 4 && (
        <div style={{ textAlign: 'center', padding: '24px 0' }}>
          <div style={{ fontSize: 28, fontWeight: 600, color: 'var(--color-primary)' }}>
            将为 {totalSelected} 人各加 {credit ?? 0} 分
          </div>
          <div style={{ marginTop: 8, color: 'var(--color-text-secondary)' }}>
            共计 {totalCredit} 分 · 每人一条加分记录，可整体撤回
          </div>
          <div style={{ marginTop: 12, color: 'var(--color-text-secondary)', fontSize: 13 }}>
            来源活动：{activities.find((a) => a.id === selectedActivity)?.name}
          </div>
        </div>
      )}
    </GlassModal>
  )
}
