import { useCallback, useEffect, useState } from 'react'
import { Button, Descriptions, Empty, Spin, Tabs, Tag, Timeline } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import { useAuthStore } from '@/stores/auth'
import {
  getPartyMember,
  listStages,
  STAGE_LABEL_MAP,
  type PartyMemberVO,
  type PartyStageVO,
} from '@/api/party'
import { InvestigationPanel, RegisterPanel, TransferPanel } from './PartyRecordPanels'

const dash = (v: unknown) => (v == null || (typeof v === 'string' && !v.trim()) ? '-' : String(v))

export default function PartyMemberDetail() {
  const { id } = useParams()
  const memberId = Number(id)
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3

  const [member, setMember] = useState<PartyMemberVO | null>(null)
  const [stages, setStages] = useState<PartyStageVO[]>([])
  const [loading, setLoading] = useState(false)

  const fetchDetail = useCallback(async () => {
    if (!memberId) return
    setLoading(true)
    try {
      const [m, s] = await Promise.all([getPartyMember(memberId), listStages(memberId)])
      setMember(m)
      setStages(s ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [memberId])

  useEffect(() => {
    fetchDetail()
  }, [fetchDetail])

  const basicItems = member
    ? [
        { key: 'name', label: '姓名', children: member.name || '-' },
        { key: 'gender', label: '性别', children: dash(member.gender) },
        { key: 'nation', label: '民族', children: dash(member.nation) },
        { key: 'className', label: '班级', children: dash(member.className) },
        { key: 'branchName', label: '所在党支部', children: dash(member.branchName) },
        { key: 'college', label: '学院', children: dash(member.college) },
        {
          key: 'politicalStatus',
          label: '政治面貌',
          children: member.politicalStatus ? <StatusTag status={member.politicalStatus} /> : '-',
        },
        { key: 'studentNo', label: '学号', children: dash(member.studentNo) },
        { key: 'birthDate', label: '出生日期', children: dash(member.birthDate) },
        { key: 'nativePlace', label: '籍贯', children: dash(member.nativePlace) },
        { key: 'education', label: '学历', children: dash(member.education) },
        // 敏感字段：仅部长及以上可见
        ...(isMinisterOrAbove
          ? [
              { key: 'idCard', label: '身份证号', children: dash(member.idCard) },
              { key: 'phone', label: '联系电话', children: dash(member.phone) },
              { key: 'homeAddress', label: '家庭地址', children: dash(member.homeAddress) },
            ]
          : []),
      ]
    : []

  const timelineItems = stages.length
    ? stages.map((s) => ({
        key: s.id,
        color: 'red',
        children: (
          <div>
            <div style={{ fontWeight: 600, color: 'var(--color-text)' }}>
              {STAGE_LABEL_MAP[s.stage] ?? s.stage}
              {s.issueNo ? <Tag style={{ marginLeft: 8 }}>第 {s.issueNo} 期</Tag> : null}
              {s.status === 'CURRENT' ? <Tag color="red" style={{ marginLeft: 4 }}>当前</Tag> : null}
            </div>
            <div style={{ color: 'var(--color-text-secondary)', fontSize: 12, marginTop: 2 }}>
              {s.startDate ? `${s.startDate}${s.endDate ? ` ~ ${s.endDate}` : ''}` : (s.createdAt ? dayjs(s.createdAt).format('YYYY-MM-DD') : '')}
            </div>
            {s.remark ? (
              <div style={{ color: 'var(--color-text-secondary)', marginTop: 4 }}>{s.remark}</div>
            ) : null}
          </div>
        ),
      }))
    : []

  const recordTabs = [
    { key: 'investigation', label: '函调', children: <InvestigationPanel memberId={memberId} /> },
    { key: 'register', label: '登记', children: <RegisterPanel memberId={memberId} /> },
    { key: 'transfer', label: '转移', children: <TransferPanel memberId={memberId} /> },
  ]

  return (
    <div>
      <PageHeader
        title={member ? member.name : '成员详情'}
        description={member ? `${dash(member.className)} · ${dash(member.politicalStatus)}` : '加载中…'}
        extra={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/party/members')}>
            返回列表
          </Button>
        }
      />
      <Spin spinning={loading}>
        <GlassCard style={{ padding: 20, marginBottom: 16 }}>
          <Descriptions
            column={{ xs: 1, sm: 2, md: 3 }}
            size="small"
            items={basicItems}
          />
          {member?.remark ? (
            <div style={{ marginTop: 12 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>备注</div>
              <div style={{ whiteSpace: 'pre-wrap', color: 'var(--color-text)', lineHeight: 1.8 }}>{member.remark}</div>
            </div>
          ) : null}
        </GlassCard>

        <GlassCard style={{ padding: 20, marginBottom: 16 }}>
          <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 12 }}>阶段流转历史</div>
          {timelineItems.length ? (
            <Timeline items={timelineItems} />
          ) : (
            <Empty description="暂无流转记录" />
          )}
        </GlassCard>

        {isMinisterOrAbove ? (
          <GlassCard style={{ padding: 20 }}>
            <Tabs items={recordTabs} />
          </GlassCard>
        ) : (
          <GlassCard style={{ padding: 20 }}>
            <Empty description="函调 / 登记 / 转移记录仅部长及以上可见" />
          </GlassCard>
        )}
      </Spin>
    </div>
  )
}
