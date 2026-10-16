import { AutoComplete, Button, Form, Select, type FormInstance } from 'antd'
import { CloseOutlined, PlusOutlined } from '@ant-design/icons'
import type { UserOptionVO } from '@/api/user'
import type { SchedulePersonItem } from '@/api/schedule'

export default function PeopleFields({
  form,
  users,
}: {
  form: FormInstance
  users: UserOptionVO[]
}) {
  const nameCounts = users.reduce((counts, u) => counts.set(u.realName, (counts.get(u.realName) ?? 0) + 1), new Map<string, number>())
  return (
    <Form.Item label="值班人员" required>
      <Form.List
        name="persons"
        rules={[
          {
            validator: async (_, rows?: SchedulePersonItem[]) => {
              if (!rows?.length) throw new Error('至少安排一名值班人员')
              if (rows.some((p) => !p?.personName?.trim()))
                throw new Error('请填写每位人员的姓名')
              const keys = rows.map((p) =>
                p.userId ? `id:${p.userId}` : `name:${p.personName.trim()}`,
              )
              if (new Set(keys).size !== keys.length)
                throw new Error('同一班次不能重复安排同一成员')
              if (rows.filter((p) => p.isPrimary === 1).length !== 1)
                throw new Error('请指定且仅指定一位主班')
            },
          },
        ]}
      >
        {(fields, { add, remove }, { errors }) => (
          <>
            {fields.map((f) => (
              <div className="routine-person-edit" key={f.key}>
                <Form.Item name={[f.name, 'userId']} hidden>
                  <input />
                </Form.Item>
                <Form.Item
                  name={[f.name, 'personName']}
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      message: '请输入或选择姓名',
                    },
                  ]}
                >
                  <AutoComplete
                    aria-label="值班人员姓名"
                    placeholder="选择成员或输入姓名"
                    options={users.map((u) => ({
                      value: `${u.realName} [${u.id}]`,
                      label: `${u.realName}（${u.deptName ?? u.roleName ?? ''}${(nameCounts.get(u.realName) ?? 0) > 1 ? `，编号 ${u.id}` : ''}）`,
                      userId: u.id,
                      realName: u.realName,
                    }))}
                    filterOption={(input, o) =>
                      String(o?.label).includes(input)
                    }
                    onChange={() =>
                      form.setFieldValue(['persons', f.name, 'userId'], null)
                    }
                    onSelect={(_, o) => {
                      form.setFieldValue(
                        ['persons', f.name, 'personName'],
                        o.realName,
                      )
                      form.setFieldValue(
                        ['persons', f.name, 'userId'],
                        o.userId,
                      )
                    }}
                  />
                </Form.Item>
                <Form.Item name={[f.name, 'isPrimary']}>
                  <Select
                    aria-label="主副班"
                    options={[
                      { value: 1, label: '主班' },
                      { value: 0, label: '副班' },
                    ]}
                  />
                </Form.Item>
                <Button
                  type="text"
                  danger
                  icon={<CloseOutlined />}
                  aria-label="移除值班人员"
                  onClick={() => remove(f.name)}
                />
              </div>
            ))}
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              onClick={() => add({ isPrimary: fields.length ? 0 : 1 })}
            >
              添加人员
            </Button>
            <Form.ErrorList errors={errors} />
            <p className="routine-muted">
              选择系统成员可关联个人排班；手工输入姓名时请核对成员身份。
            </p>
          </>
        )}
      </Form.List>
    </Form.Item>
  )
}
