import { useEffect, useState } from 'react'
import { Button, Descriptions, Result as AntResult, Space, Spin, Typography } from 'antd'
import { CheckCircleOutlined, ReloadOutlined, SettingOutlined, SyncOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import { getSystemInfo, type SystemInfoVO } from '@/api/system'
import { useAuthStore } from '@/stores/auth'

/** 系统设置页：版本 / 上传目录 / 系统健康（GET /api/ping）。主任 / 指导老师可见。 */
export default function Settings() {
  const user = useAuthStore((s) => s.user)
  // undefined=初始加载中；null=加载失败；否则为成功数据
  const [info, setInfo] = useState<SystemInfoVO | null | undefined>(undefined)
  const [loading, setLoading] = useState(false)

  const fetchInfo = () => {
    setLoading(true)
    getSystemInfo()
      .then((res) => setInfo(res))
      .catch(() => {
        setInfo(null)
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    fetchInfo()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div>
      <PageHeader
        title="系统设置"
        description="查看系统版本、文件存储位置与运行状态"
        extra={
          <Button icon={<ReloadOutlined />} loading={loading} onClick={fetchInfo}>
            刷新
          </Button>
        }
      />

      <Spin spinning={loading}>
        {info === undefined ? (
          <GlassCard style={{ padding: 32, textAlign: 'center' }}>
            <Spin tip="系统信息加载中…" />
          </GlassCard>
        ) : info === null ? (
          <GlassCard style={{ padding: 32, textAlign: 'center' }}>
            <AntResult
              status="warning"
              title="系统信息加载失败"
              subTitle="请确认后端服务已启动，或点击右上角「刷新」重试"
            />
          </GlassCard>
        ) : (
          <Space direction="vertical" size={16} style={{ display: 'flex' }}>
            <GlassCard style={{ padding: 24 }}>
              <Typography.Title level={5} style={{ margin: '0 0 16px' }}>
                <SettingOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
                系统信息
              </Typography.Title>
              <Descriptions column={1} size="middle" bordered>
                <Descriptions.Item label="系统名称">党务管理系统（信息与智能工程学院党建办公室）</Descriptions.Item>
                <Descriptions.Item label="后端版本">{info.version}</Descriptions.Item>
                <Descriptions.Item label="当前登录">{user?.realName}（{user?.deptName ?? user?.roleCode}）</Descriptions.Item>
              </Descriptions>
            </GlassCard>

            <GlassCard style={{ padding: 24 }}>
              <Typography.Title level={5} style={{ margin: '0 0 16px' }}>
                <SyncOutlined style={{ color: 'var(--color-red)', marginRight: 8 }} />
                存储与健康
              </Typography.Title>
              <Descriptions column={1} size="middle" bordered>
                <Descriptions.Item label="上传目录">
                  <Typography.Text copyable={{ text: info.uploadDir }} style={{ wordBreak: 'break-all' }}>
                    {info.uploadDir}
                  </Typography.Text>
                </Descriptions.Item>
                <Descriptions.Item label="系统健康">
                  <Space size={4}>
                    <CheckCircleOutlined style={{ color: '#52A052' }} />
                    <span style={{ color: '#52A052' }}>正常（pong）</span>
                  </Space>
                </Descriptions.Item>
                <Descriptions.Item label="服务时间">{dayjs().format('YYYY-MM-DD HH:mm:ss')}</Descriptions.Item>
              </Descriptions>
            </GlassCard>
          </Space>
        )}
      </Spin>
    </div>
  )
}
