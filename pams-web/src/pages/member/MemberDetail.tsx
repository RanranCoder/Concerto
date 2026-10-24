import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { App, Button, Descriptions, Empty, Select, Space, Spin, Table, Tag } from 'antd'
import { useHasPerm } from '@/components/glass/RequireRole'
import { RecordsSummary } from '@/components/records/RecordsLayout'
import { ArrowLeftOutlined } from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import {
  getMember, updateMember, STATUS_COLOR, STATUS_LABELS, STATUS_OPTIONS, POSITION_LABELS,
  type MemberDetail, type MemberVO,
} from '@/api/member'

export default function MemberDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const hasPerm = useHasPerm()
  const [detail, setDetail] = useState<MemberDetail | null>(null)
  const [status, setStatus] = useState<string>('ACTIVE')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  const fetchDetail = useCallback(async () => {
    setLoading(true)
    try {
      const d = await getMember(Number(id))
      setDetail(d)
      setStatus(d.member.status)
    } catch { setDetail(null) } finally { setLoading(false) }
  }, [id])

  useEffect(() => { fetchDetail() }, [fetchDetail])

  const changeStatus = async (next: string) => {
    if (!detail || !hasPerm('member:manage')) return
    setSaving(true)
    try { await updateMember(detail.member.id, {
      sessionId: detail.member.sessionId, deptId: detail.member.deptId, position: detail.member.position,
      name: detail.member.name, gender: detail.member.gender, studentNo: detail.member.studentNo,
      className: detail.member.className, phone: detail.member.phone,
      politicalStatus: detail.member.politicalStatus, status: next, remark: detail.member.remark,
    })
    setStatus(next)
    message.success('状态已更新')
    fetchDetail()
    } catch { /* HTTP layer reports errors */ } finally { setSaving(false) }
  }

  if (!detail) return loading ? <Spin /> : <Empty description="未能加载成员资料"><Button onClick={fetchDetail}>重试</Button></Empty>
  const m: MemberVO = detail.member

  return (
    <div className="records-page">
      <PageHeader
        title={`${m.name} · 成员详情`}
        description={`${detail.member.sessionName ?? ''} / ${m.deptName ?? '主任室'} / ${POSITION_LABELS[m.position] ?? m.position}`}
        extra={<Space wrap><Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/members')}>返回列表</Button>{hasPerm('member:manage') && <Button onClick={() => navigate(`/members?edit=${m.id}`)}>编辑资料</Button>}</Space>}
      />

      <RecordsSummary items={[{ label: '排班次数', value: detail.scheduleCount, note: '次' }, { label: '考勤记录', value: detail.attendanceCount, note: '条' }, { label: '素拓累计', value: detail.totalCredit.toFixed(2), note: '分' }]} />
      <div className="records-two">
      <GlassCard className="records-panel">
        <h3>基本资料</h3>
        <Space size="large" wrap>
          <Tag color={STATUS_COLOR[m.status]} style={{ fontSize: 14, padding: '4px 12px' }}>{STATUS_LABELS[m.status]}</Tag>
          {hasPerm('member:manage') && <Select aria-label="修改成员状态" disabled={saving} loading={saving} value={status} options={STATUS_OPTIONS} style={{ width: 120 }}
            onChange={(v) => changeStatus(v)} placeholder="快捷改状态" />}
        </Space>
        <Descriptions column={{ xs: 1, sm: 2 }} layout="vertical" style={{ marginTop: 16 }}>
          <Descriptions.Item label="姓名">{m.name}</Descriptions.Item>
          <Descriptions.Item label="性别">{m.gender || '-'}</Descriptions.Item>
          <Descriptions.Item label="学号">{m.studentNo || '-'}</Descriptions.Item>
          <Descriptions.Item label="班级">{m.className || '-'}</Descriptions.Item>
          <Descriptions.Item label="联系方式">{m.phone || '-'}</Descriptions.Item>
          <Descriptions.Item label="政治面貌">{m.politicalStatus || '-'}</Descriptions.Item>
          <Descriptions.Item label="部门">{m.deptName ?? '主任室'}</Descriptions.Item>
          <Descriptions.Item label="职位">{POSITION_LABELS[m.position] ?? m.position}</Descriptions.Item>
          <Descriptions.Item label="届别">{m.sessionName || '-'}</Descriptions.Item>
          <Descriptions.Item label="备注" span={3}>{m.remark || '-'}</Descriptions.Item>
        </Descriptions>
      </GlassCard>

      <GlassCard className="records-panel"><h3>任职信息</h3><Descriptions column={1} layout="vertical" items={[
        { key: 'session', label: '届别', children: m.sessionName || '未设置' },
        { key: 'dept', label: '部门', children: m.deptName ?? '主任室' },
        { key: 'position', label: '职务', children: POSITION_LABELS[m.position] ?? m.position },
        { key: 'status', label: '成员状态', children: STATUS_LABELS[m.status] ?? m.status },
      ]} /><p className="records-note">展示本届任职资料，工作摘要来自现有排班、考勤与素拓记录。</p></GlassCard>
      </div>

      <GlassCard className="records-panel" style={{ marginTop: 20 }}>
        {/* 卡片内的小节标题用 14/600，不再复用 PageHeader：h2/20px 的页级标题缩进在卡内，
            会和页头标题形成两条不在同一竖线、层级又相同的标题。 */}
        <div style={{ marginBottom: 12 }}>
          <div style={{ fontSize: 14, fontWeight: 'calc(600 - var(--font-weight-compensation))', color: 'var(--brand-text)' }}>素拓记录</div>
          <div style={{ marginTop: 2, fontSize: 'var(--fs-caption)', color: 'var(--text-secondary)' }}>查看参与工作获得的素拓加分与依据</div>
        </div>
        <Table
          scroll={{ x: 700 }}
          size="small" rowKey="id" pagination={{ pageSize: 10 }} dataSource={detail.credits}
          columns={[
            { title: '项目', dataIndex: 'project' },
            { title: '分值', dataIndex: 'credit', width: 100, render: (v: number) => <Tag color="gold">{v}</Tag> },
            { title: '依据', dataIndex: 'basis', width: 140, render: (v: string | null) => v || '-' },
            { title: '备注', dataIndex: 'remark', render: (v: string | null) => v || '-' },
            { title: '时间', dataIndex: 'createdAt', width: 180, render: (v: string) => v?.slice(0, 16).replace('T', ' ') || '-' },
          ]} />
      </GlassCard>
    </div>
  )
}
