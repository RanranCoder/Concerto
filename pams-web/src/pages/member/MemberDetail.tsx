import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { App, Button, Card, Descriptions, Select, Space, Statistic, Table, Tag } from 'antd'
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
  const [detail, setDetail] = useState<MemberDetail | null>(null)
  const [status, setStatus] = useState<string>('ACTIVE')

  const fetchDetail = useCallback(async () => {
    const d = await getMember(Number(id))
    setDetail(d)
    setStatus(d.member.status)
  }, [id])

  useEffect(() => { fetchDetail() }, [fetchDetail])

  const changeStatus = async (next: string) => {
    if (!detail) return
    await updateMember(detail.member.id, {
      sessionId: detail.member.sessionId, deptId: detail.member.deptId, position: detail.member.position,
      name: detail.member.name, gender: detail.member.gender, studentNo: detail.member.studentNo,
      className: detail.member.className, phone: detail.member.phone,
      politicalStatus: detail.member.politicalStatus, status: next, remark: detail.member.remark,
    })
    setStatus(next)
    message.success('状态已更新')
    fetchDetail()
  }

  if (!detail) return <div>加载中...</div>
  const m: MemberVO = detail.member

  return (
    <div>
      <PageHeader
        title={`${m.name} · 成员详情`}
        description={`${detail.member.sessionName ?? ''} / ${m.deptName ?? '主任室'} / ${POSITION_LABELS[m.position] ?? m.position}`}
        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/members')}>返回列表</Button>}
      />

      <GlassCard style={{ padding: 20, marginBottom: 16 }}>
        <Space size="large" wrap>
          <Tag color={STATUS_COLOR[m.status]} style={{ fontSize: 14, padding: '4px 12px' }}>{STATUS_LABELS[m.status]}</Tag>
          <Select value={status} options={STATUS_OPTIONS} style={{ width: 120 }}
            onChange={(v) => changeStatus(v)} placeholder="快捷改状态" />
        </Space>
        <Descriptions column={3} style={{ marginTop: 16 }}>
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

      <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
        <Card style={{ flex: 1 }}><Statistic title="排班次数" value={detail.scheduleCount} /></Card>
        <Card style={{ flex: 1 }}><Statistic title="考勤记录" value={detail.attendanceCount} /></Card>
        <Card style={{ flex: 1 }}><Statistic title="素拓累计分" value={detail.totalCredit} precision={2} /></Card>
      </div>

      <GlassCard style={{ padding: 16 }}>
        <PageHeader title="素拓记录" description="按学号精确聚合 credit_record" />
        <Table
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
