// 对应 scripts/concerto-demo/base.sql 中的协奏演示组织账号。

export type DemoAccount = {
  username: string
  password: string
  name: string
  dept: string
  position: string
  role: string
}

export const DEMO_PASSWORD = "123456"

export const DEMO_DEPARTMENTS = [
  "主任",
  "宣传部",
  "组织部",
  "秘书处",
] as const

export const DEMO_ACCOUNTS: DemoAccount[] = [
  {
    username: "admin",
    password: DEMO_PASSWORD,
    name: "陈知远",
    dept: "主任",
    position: "主任",
    role: "主任",
  },
  {
    username: "media",
    password: DEMO_PASSWORD,
    name: "林若宁",
    dept: "宣传部",
    position: "宣传部长",
    role: "宣传部长",
  },
  {
    username: "media01",
    password: DEMO_PASSWORD,
    name: "苏言",
    dept: "宣传部",
    position: "干事",
    role: "干事",
  },
  {
    username: "media02",
    password: DEMO_PASSWORD,
    name: "宋雨桐",
    dept: "宣传部",
    position: "干事",
    role: "干事",
  },
  {
    username: "media03",
    password: DEMO_PASSWORD,
    name: "叶星辰",
    dept: "宣传部",
    position: "干事",
    role: "干事",
  },
  {
    username: "media04",
    password: DEMO_PASSWORD,
    name: "唐悦",
    dept: "宣传部",
    position: "干事",
    role: "干事",
  },
  {
    username: "org",
    password: DEMO_PASSWORD,
    name: "周明轩",
    dept: "组织部",
    position: "组织部长",
    role: "组织部长",
  },
  {
    username: "org01",
    password: DEMO_PASSWORD,
    name: "顾嘉宁",
    dept: "组织部",
    position: "干事",
    role: "干事",
  },
  {
    username: "org02",
    password: DEMO_PASSWORD,
    name: "沈亦航",
    dept: "组织部",
    position: "干事",
    role: "干事",
  },
  {
    username: "org03",
    password: DEMO_PASSWORD,
    name: "江小满",
    dept: "组织部",
    position: "干事",
    role: "干事",
  },
  {
    username: "org04",
    password: DEMO_PASSWORD,
    name: "陆言川",
    dept: "组织部",
    position: "干事",
    role: "干事",
  },
  {
    username: "secretary",
    password: DEMO_PASSWORD,
    name: "许清禾",
    dept: "秘书处",
    position: "秘书长",
    role: "秘书长",
  },
  {
    username: "sec01",
    password: DEMO_PASSWORD,
    name: "方可欣",
    dept: "秘书处",
    position: "干事",
    role: "干事",
  },
  {
    username: "sec02",
    password: DEMO_PASSWORD,
    name: "夏沐阳",
    dept: "秘书处",
    position: "干事",
    role: "干事",
  },
  {
    username: "sec03",
    password: DEMO_PASSWORD,
    name: "白静宜",
    dept: "秘书处",
    position: "干事",
    role: "干事",
  },
  {
    username: "sec04",
    password: DEMO_PASSWORD,
    name: "韩景行",
    dept: "秘书处",
    position: "干事",
    role: "干事",
  },
]
