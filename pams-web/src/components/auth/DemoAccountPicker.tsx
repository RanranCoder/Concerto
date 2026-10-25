import { useMemo, useState, type CSSProperties } from 'react'
import { Drawer, Input } from 'antd'
import {
  CheckOutlined,
  CloseOutlined,
  DownOutlined,
  SearchOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { DEMO_ACCOUNTS, DEMO_DEPARTMENTS, DEMO_PASSWORD, type DemoAccount } from './demoAccounts'
import '@/styles/demo-accounts.css'

type DemoAccountPickerProps = {
  brand: string
  disabled?: boolean
  onPick: (account: DemoAccount) => void
}

type DemoAccountListProps = DemoAccountPickerProps & {
  showClose?: boolean
  onClose?: () => void
}

function DemoAccountList({ brand, disabled, onPick, showClose, onClose }: DemoAccountListProps) {
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState<string[]>([])
  const [picked, setPicked] = useState<DemoAccount | null>(null)

  const keyword = query.trim().toLowerCase()
  const groups = useMemo(() => DEMO_DEPARTMENTS.map((dept) => ({
    dept,
    accounts: DEMO_ACCOUNTS.filter((account) => account.dept === dept
      && (!keyword || [account.name, account.username, account.dept, account.position, account.role]
        .some((value) => value.toLowerCase().includes(keyword)))),
  })).filter((group) => group.accounts.length > 0), [keyword])

  const total = groups.reduce((sum, group) => sum + group.accounts.length, 0)
  const isCollapsed = (dept: string) => !keyword && collapsed.includes(dept)

  const toggle = (dept: string) => {
    if (keyword) return
    setCollapsed((current) => (current.includes(dept)
      ? current.filter((item) => item !== dept)
      : [...current, dept]))
  }

  const pick = (account: DemoAccount) => {
    setPicked(account)
    onPick(account)
  }

  return (
    <div className="demo-accounts-surface" style={{ '--demo-brand': brand } as CSSProperties}>
      <div className="demo-accounts-head">
        <span className="demo-accounts-icon" aria-hidden="true"><ThunderboltOutlined /></span>
        <div className="demo-accounts-heading">
          <strong>演示账号</strong>
          <small>点击账号自动填充用户名和密码</small>
        </div>
        <span className="demo-accounts-count">{DEMO_ACCOUNTS.length}</span>
        {showClose && (
          <button type="button" className="demo-accounts-close" onClick={onClose} aria-label="关闭演示账号列表">
            <CloseOutlined />
          </button>
        )}
      </div>

      <div className="demo-accounts-meta">
        统一密码 <code>{DEMO_PASSWORD}</code>
        <span>仅演示环境</span>
      </div>

      <div className="demo-accounts-search">
        <Input
          allowClear
          size="small"
          prefix={<SearchOutlined />}
          placeholder="搜索姓名 / 账号 / 部门"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="搜索演示账号"
        />
      </div>

      <div className="demo-accounts-scroll">
        {groups.map((group) => (
          <section className="demo-accounts-group" key={group.dept}>
            <button
              type="button"
              className="demo-accounts-group-head"
              onClick={() => toggle(group.dept)}
              aria-expanded={!isCollapsed(group.dept)}
            >
              <span>{group.dept}</span>
              <small>{group.accounts.length}</small>
              <DownOutlined className={isCollapsed(group.dept) ? 'collapsed' : ''} />
            </button>
            {!isCollapsed(group.dept) && (
              <div className="demo-accounts-rows">
                {group.accounts.map((account) => {
                  const selected = picked?.username === account.username
                  return (
                    <button
                      type="button"
                      key={account.username}
                      className={`demo-account-row${selected ? ' selected' : ''}`}
                      onClick={() => pick(account)}
                      disabled={disabled}
                      aria-label={`填充 ${account.name}（${account.username}）`}
                    >
                      <span className="demo-account-avatar" aria-hidden="true">{account.position.slice(0, 1)}</span>
                      <span className="demo-account-copy">
                        <span className="demo-account-name">
                          {account.name}
                          <span className="demo-account-position">{account.position}</span>
                        </span>
                        <span className="demo-account-username">{account.username}</span>
                      </span>
                      {selected
                        ? <CheckOutlined className="demo-account-check" aria-hidden="true" />
                        : <span className="demo-account-fill" aria-hidden="true">填充</span>}
                    </button>
                  )
                })}
              </div>
            )}
          </section>
        ))}
        {!total && <p className="demo-accounts-empty">没有匹配的账号</p>}
      </div>

      <div className="demo-accounts-foot" aria-live="polite">
        {picked ? `已填充：${picked.name}（${picked.username}）` : '填充后仍可修改，再点击登录'}
      </div>
    </div>
  )
}

export default function DemoAccountPicker({ brand, disabled, onPick }: DemoAccountPickerProps) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <aside className="demo-accounts-panel" aria-label="演示账号列表">
        <DemoAccountList brand={brand} disabled={disabled} onPick={onPick} />
      </aside>

      <button
        type="button"
        className="demo-accounts-launcher"
        style={{ '--demo-brand': brand } as CSSProperties}
        onClick={() => setOpen(true)}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <ThunderboltOutlined aria-hidden="true" />
        <span>演示账号</span>
        <b>{DEMO_ACCOUNTS.length}</b>
      </button>

      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        placement="right"
        width={360}
        rootClassName="demo-accounts-drawer"
        closable={false}
        styles={{ body: { padding: 0 } }}
      >
        <DemoAccountList
          brand={brand}
          disabled={disabled}
          onPick={(account) => { onPick(account); setOpen(false) }}
          showClose
          onClose={() => setOpen(false)}
        />
      </Drawer>
    </>
  )
}
