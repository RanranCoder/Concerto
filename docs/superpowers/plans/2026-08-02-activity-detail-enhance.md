# 活动详情页增强实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增强活动详情页 5 个 Tab——基本信息直接预览甘特图与关联文件并支持独立编辑页、策划书支持 Word 文档预览/编辑+导入导出、议程表可导出、座位表改为电影选座风格+Excel 编辑+图例、签到支持扫码（二维码手动刷新长期有效）。

**Architecture:** 前后端分离。后端仅新增签到扫码两个接口（令牌生成/校验），其余全部前端增强。Word 编辑器用自绘 contenteditable + A4 纸张样式，导入用 mammoth（懒加载）、导出用 docx 库；座位表用 CSS Grid 自绘选座矩阵 + Handsontable(MIT) Excel 编辑 + localStorage 图例；二维码用 qrcode.react。

**Tech Stack:** React 18 · Vite 7 · TS 5.7 · AntD 5 · mammoth · docx(@nativedocuments) · docx-preview · qrcode.react · @handsontable/react(7.x MIT) · Spring Boot 4（仅签到后端）

---

## Global Constraints

- **技术栈（不可擅自更换）**：Word 编辑自绘 contenteditable（非 wangEditor/Quill）；导入 mammoth、导出 docx、预览 docx-preview（均懒加载）；座位表 CSS Grid 自绘矩阵 + Handsontable 7.x（MIT 版，若 npm 装到 8.x 商业版则降级自绘表格）；二维码 qrcode.react；扫码签到后端内存令牌（`ConcurrentHashMap`，24h 过期 + 手动刷新作废）。
- **数据模型不改**：策划书仍用现有 `activity_plan` 7 字段（background/purpose/content/flow/notice/emergency/budget），Word 编辑器只是这些字段的 Word 纸张化编辑/渲染视图；座位表沿用 `seat_map`（zone/rowNo/colNo/personName/seatType）；议程沿用 `activity_agenda`。
- **图例持久化**：seatType→颜色 映射存前端 localStorage（键 `pams_seat_legend_{activityId}`），不新增后端表。
- **扫码免登录**：`POST /api/signins/scan` 是公开接口（SecurityConfig permitAll），令牌门禁防滥用；`POST /api/signins/token` 需登录（部长及以上可生成，文秘部主责）。
- **UI 规范**：沿用 liquid-glass 设计系统（黑白+国旗红 #DE2910、明暗双主题、GlassCard/GlassModal/GlassTable、`--color-*` CSS 变量）；中文文案。
- **策划书模板**：以参考策划书"策划书新模板(终)1.docx"的 **12 章结构**为骨架：一、活动名称；二、活动主题；三、活动背景；四、活动目的；五、活动时间；六、活动地点；七、活动组织单位；八、活动对象；九、活动内容（前期/中期/流程/后期）；十、活动注意事项；十一、应急预案；十二、经费预算（4 列表格：物品/数量/单价/总价）+ 落款（右对齐"策划人：信息工程学院党建办公室"+ 中文日期）。三档字号：宋体 22pt 标题 / 14pt 章节 / 12pt 正文。
- **路由**：新增 `/activities/:id/edit`（独立编辑页，MainLayout 内）、`/signin/:token`（免登录落地页，MainLayout 外）。
- **质量门**：每个 Task 结束前跑对应测试并 `git commit`；前端任务必须 `npm run dev` + 浏览器点验后声明完成。
- **懒加载**：mammoth、docx、docx-preview 用动态 `import()` 按需加载，不进主包（控制 chunk 体积）。

---

## 文件结构总览

```
pams-backend/src/main/java/com/pams/
├─ module/activity/service/SigninService.java          # +generateToken/scanSignin
├─ module/activity/controller/SigninController.java    # +POST /token、/scan
├─ config/SecurityConfig.java                          # 放行 POST /api/signins/scan
└─ test/.../activity/SigninTokenTest.java              # 令牌测试

pams-web/src/
├─ package.json                                        # +mammoth/docx/docx-preview/qrcode.react/@handsontable
├─ api/signin.ts                                       # +generateToken/scanSignin
├─ api/activity.ts                                     # +getActivity 已有（编辑页用）
├─ router/index.tsx                                    # +/activities/:id/edit、/signin/:token
├─ components/
│  ├─ word/WordEditor.tsx                              # contenteditable 编辑器 + 工具条
│  ├─ word/WordPreview.tsx                             # A4 纸张渲染 7 字段
│  ├─ word/planTemplate.ts                             # 12 章模板 + 导出 docx 函数 + 导入 mammoth 函数
│  ├─ seat/SeatMapView.tsx                             # CSS Grid 选座矩阵 + 图例 + 区域标签
│  ├─ seat/SeatExcelEditor.tsx                         # Handsontable 编辑 + 图例配置
│  └─ signin/SigninQR.tsx                              # 二维码 + 手动刷新
├─ pages/activity/ActivityDetail.tsx                   # 5 Tab 增强（嵌入上述组件）
├─ pages/activity/ActivityEdit.tsx                     # 新建独立编辑页
└─ pages/signin/SigninScan.tsx                         # 新建扫码落地页（免登录）
```

---

## M1 · 依赖与后端签到接口

### Task 1: 前端依赖安装 + 后端签到令牌接口

**Files:**
- Modify: `pams-web/package.json`（+ `mammoth`、`docx`、`docx-preview`、`qrcode.react`、`@handsontable/react` 与 `handsontable@7`）
- Modify: `pams-backend/src/main/java/com/pams/module/activity/service/SigninService.java`
- Modify: `pams-backend/src/main/java/com/pams/module/activity/controller/SigninController.java`
- Modify: `pams-backend/src/main/java/com/pams/config/SecurityConfig.java`
- Create: `pams-backend/src/test/java/com/pams/module/activity/SigninTokenTest.java`

**Interfaces:**
- Produces:
  - `SigninService.generateToken(Long activityId)` → `SigninToken { token: String, activityId: Long, expiresAt: LocalDateTime }`（内存 `ConcurrentHashMap<String, TokenEntry>`，24h 过期）
  - `SigninService.scanSignin(String token, String name, String studentNo)` → `Signin`（校验令牌有效+活动存在+未过期，写 signType=SCAN）
  - `SigninController.POST /api/signins/token`（登录，body `{activityId}`）→ `{token, qrContent, expiresAt}`
  - `SigninController.POST /api/signins/scan`（公开，body `{token, name, studentNo}`）→ `Signin`
  - `SecurityConfig`：`/api/signins/token` 需认证；`POST /api/signins/scan` permitAll
- 供 M5 前端扫码对接。

- [ ] **Step 1: 安装前端依赖**

```bash
cd /d/MyApp/PAMS/pams-web
npm i mammoth docx docx-preview qrcode.react
# Handsontable：指定 7.x MIT 版（8.x 起商业授权）
npm i @handsontable/react@4 handsontable@7.4.6
npm run build
```

Expected: 依赖安装成功，`npm run build` 通过（新依赖未使用所以无 TS 影响）。若 `@handsontable/react@4` 与 `handsontable@7` 版本不匹配，调整到匹配版本并记录。

- [ ] **Step 2: 写 SigninTokenTest（先红）**

```java
package com.pams.module.activity;

import com.pams.common.BizException;
import com.pams.module.activity.entity.Signin;
import com.pams.module.activity.repository.SigninRepository;
import com.pams.module.activity.service.SigninService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SigninTokenTest {

    SigninRepository repo;
    SigninService service;

    @BeforeEach
    void setup() {
        repo = mock(SigninRepository.class);
        service = new SigninService(repo);
    }

    @Test
    void generateToken_returnsUniqueToken() {
        var a = service.generateToken(1L);
        var b = service.generateToken(1L);
        assertThat(a.getToken()).isNotEqualTo(b.getToken());
        assertThat(a.getExpiresAt()).isAfter(java.time.LocalDateTime.now());
    }

    @Test
    void scanSignin_validToken_createsScannedRecord() {
        var t = service.generateToken(1L);
        when(repo.save(any(Signin.class))).thenAnswer(inv -> inv.getArgument(0));
        Signin s = service.scanSignin(t.getToken(), "张三", "2025001");
        assertThat(s.getSignType()).isEqualTo(Signin.SignType.SCAN);
        assertThat(s.getActivityId()).isEqualTo(1L);
        assertThat(s.getName()).isEqualTo("张三");
    }

    @Test
    void scanSignin_invalidToken_throws() {
        assertThatThrownBy(() -> service.scanSignin("bad-token", "张三", "2025001"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void scanSignin_expiredToken_throws() {
        var t = service.generateToken(2L);
        // 手动把过期时间改为过去
        service.forceExpire(t.getToken());
        assertThatThrownBy(() -> service.scanSignin(t.getToken(), "李四", "2025002"))
                .isInstanceOf(BizException.class);
    }
}
```

> `forceExpire(token)` 是测试辅助方法（包可见或 public，实现时在 Service 里加，用于把 token 置过期）。

- [ ] **Step 3: 实现 SigninService 令牌逻辑**

```java
// 在 SigninService 内新增字段与方法
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 字段（构造器注入不变，追加）
private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

public static class TokenEntry {
    private final Long activityId;
    private final LocalDateTime expiresAt;
    public TokenEntry(Long activityId, LocalDateTime expiresAt) {
        this.activityId = activityId;
        this.expiresAt = expiresAt;
    }
    public Long getActivityId() { return activityId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}

/** 生成一次性签到令牌（24h 有效） */
public TokenEntry generateToken(Long activityId) {
    String token = UUID.randomUUID().toString().replace("-", "");
    TokenEntry entry = new TokenEntry(activityId, LocalDateTime.now().plusHours(24));
    tokenStore.put(token, entry);
    return entry; // token 需暴露给调用方
}
```

> 注意：`generateToken` 需要返回 token 字符串本身。**修正签名**：返回 `Map<String,Object>` 或一个含 token 的 DTO。**采用**：`generateToken(Long activityId)` 返回 `SigninTokenDTO{token, activityId, expiresAt}`（新建内部类或复用）。测试里 `a.getToken()` 和 `t.getToken()` 用同一个 getter。为测试可读性，`forceExpire(token)` 把 `tokenStore` 里的 entry 换成过去时间。

```java
public Signin scanSignin(String token, String name, String studentNo) {
    TokenEntry e = tokenStore.get(token);
    if (e == null) throw new BizException(2302, "签到码无效或已失效");
    if (e.getExpiresAt().isBefore(LocalDateTime.now())) {
        tokenStore.remove(token);
        throw new BizException(2303, "签到码已过期，请刷新");
    }
    // 活动必须存在
    if (!repository.existsById(e.getActivityId())) {
        tokenStore.remove(token);
        throw new BizException(2001, "活动不存在");
    }
    Signin s = new Signin();
    s.setActivityId(e.getActivityId());
    s.setName(name);
    s.setStudentNo(studentNo);
    s.setSignType(Signin.SignType.SCAN);
    s.setSignTime(LocalDateTime.now());
    s.setCreatedAt(LocalDateTime.now());
    return repository.save(s);
}
```

> 测试 `scanSignin_validToken_createsScannedRecord` 依赖 `repo.existsById(1L)` 返回 true——测试里 `when(repo.existsById(1L)).thenReturn(true)`。

- [ ] **Step 4: 实现 SigninController 两个端点 + SecurityConfig 放行**

```java
// SigninController 新增
@PostMapping("/token")
public Result<Map<String, Object>> generateToken(@RequestBody Map<String, Long> body) {
    Long activityId = body.get("activityId");
    if (activityId == null) throw new BizException(400, "活动ID不能为空");
    var t = service.generateToken(activityId);
    Map<String, Object> resp = new HashMap<>();
    resp.put("token", t.getToken());
    resp.put("activityId", activityId);
    resp.put("expiresAt", t.getExpiresAt());
    // qrContent 由前端拼链接，后端只给 token + 活动 id + 过期时间
    return Result.ok(resp);
}

@PostMapping("/scan")
public Result<Signin> scan(@RequestBody Map<String, String> body) {
    String token = body.get("token");
    String name = body.get("name");
    String studentNo = body.get("studentNo");
    if (token == null || name == null || name.isBlank()) {
        throw new BizException(400, "签到码或姓名不能为空");
    }
    return Result.ok(service.scanSignin(token, name.trim(), studentNo));
}
```

`SecurityConfig` 放行：
```java
.requestMatchers(HttpMethod.POST, "/api/signins/scan").permitAll()
```

- [ ] **Step 5: 跑测试 + 提交**

```bash
cd /d/MyApp/PAMS/pams-backend
mvn -q test
git add pams-backend/src pams-web/package.json pams-web/package-lock.json
git commit -m "feat: 签到扫码令牌接口与前端依赖"
```

Expected: SigninTokenTest 4 用例 + 既有 90 全绿。

---

## M2 · 基本信息 Tab 增强 + 独立编辑页

### Task 2: 基本信息 Tab 嵌甘特图预览 + 关联文件 + 编辑入口

**Files:**
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（基本信息 Tab）
- Modify: `pams-web/src/api/activity.ts`（无改动，已有 getActivityDetail 返回 tasks）
- Create: `pams-web/src/pages/activity/ActivityEdit.tsx`（独立编辑页）
- Modify: `pams-web/src/router/index.tsx`（+ `/activities/:id/edit`）

**Interfaces:**
- Consumes: `GanttChart`（`GanttChart.tsx`，props `{tasks, onUpdate, pxPerDay, onEdit?}`，不传 onEdit 时内部精简编辑；只读场景可传 `onEdit={() => {}}` 禁止编辑或直接不传）；`listTasks(activityId)`（`@/api/task`）；`getActivityDetail`；`getActivity`；`updateActivity`；`listMaterials`、`downloadFile`（`@/api/material`、`@/api/file`）。
- Produces: 基本信息 Tab 内嵌只读甘特图 + 关联文件列表；`/activities/:id/edit` 编辑页（表单复用 ActivitySave）。

- [ ] **Step 1: 基本信息 Tab 嵌入甘特图预览**

在 `ActivityDetail.tsx` 的 `tabItems` 中 `basic` 的 children 里，状态操作区下方加：

```tsx
<div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--glass-border)' }}>
  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>
    任务甘特图预览
    <Button type="link" size="small" onClick={() => navigate(`/activities/${activityId}/gantt`)}>
      查看完整甘特图 →
    </Button>
  </div>
  {/* 内嵌只读甘特图：tasks 来自 detail.tasks（聚合接口已返回），不传 onEdit 只读 */}
  <GanttChart tasks={(detail?.tasks ?? []) as unknown as GanttTask[]} onUpdate={() => {}} />
</div>
```

> 需要 `import GanttChart from '@/components/gantt/GanttChart'` 和 `import type { GanttTask } from '@/components/gantt/gantt.utils'`。确认 `ActivityDetail` 类型含 `tasks` 字段（聚合接口已返回，若 TS 类型缺则补到 `@/api/activity.ts` 的 `ActivityDetail`）。

- [ ] **Step 2: 关联文件列表**

基本信息 Tab 甘特图下方加"关联文件"区块——用 `listMaterials(activityId)` 查该活动材料，分组展示 + 下载：

```tsx
// ActivityDetail 顶部加状态
const [materials, setMaterials] = useState<MaterialVO[]>([])
const fetchMaterials = useCallback(async () => {
  try {
    const page = await listMaterials({ activityId, page: 1, size: 50 })
    setMaterials(page.records)
  } catch { /* http 拦截已提示 */ }
}, [activityId])
useEffect(() => { fetchMaterials() }, [fetchMaterials])
```

渲染（甘特图下方）：
```tsx
<div style={{ marginTop: 20 }}>
  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-red)', marginBottom: 6 }}>
    关联文件（{materials.length}）
  </div>
  {materials.length === 0 ? (
    <div style={{ color: 'var(--color-text-secondary)', fontSize: 13 }}>暂无关联文件，可在材料库上传</div>
  ) : (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {materials.map((m) => (
        <Tag key={m.id} color="red" style={{ cursor: 'pointer' }} onClick={() => m.fileId && downloadFile(m.fileId)}>
          {MATERIAL_BIZ_TYPE_MAP[m.bizType] ?? m.bizType} · {m.name}
        </Tag>
      ))}
    </div>
  )}
</div>
```

> `MATERIAL_BIZ_TYPE_MAP` 从 `@/api/material` 导入；`downloadFile` 从 `@/api/file` 导入（已有）。

- [ ] **Step 3: 独立编辑页 ActivityEdit.tsx**

```tsx
import { useEffect, useState } from 'react'
import { Button, DatePicker, Form, Input, Select, Space, Spin, message } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import PageHeader from '@/components/glass/PageHeader'
import GlassCard from '@/components/glass/GlassCard'
import { getActivity, updateActivity, type ActivityVO } from '@/api/activity'
import { ACTIVITY_TYPES } from '@/api/activityStatus' // 或从 ActivityList 的类型映射抽

export default function ActivityEdit() {
  const { id } = useParams()
  const activityId = Number(id)
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  useEffect(() => {
    if (!activityId) return
    setLoading(true)
    getActivity(activityId)
      .then((a) => {
        form.setFieldsValue({
          name: a.name,
          theme: a.theme ?? undefined,
          type: a.type ?? 'OTHER',
          range: a.startDate ? [dayjs(a.startDate), a.endDate ? dayjs(a.endDate) : undefined] : undefined,
          location: a.location ?? undefined,
          organizer: a.organizer ?? undefined,
          targetAudience: a.targetAudience ?? undefined,
          host: a.host ?? undefined,
          leader: a.leader ?? undefined,
          description: a.description ?? undefined,
        })
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [activityId, form])

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        name: values.name,
        theme: values.theme || null,
        type: values.type ?? 'OTHER',
        startDate: values.range?.[0]?.format('YYYY-MM-DD') ?? null,
        endDate: values.range?.[1]?.format('YYYY-MM-DD') ?? null,
        location: values.location || null,
        organizer: values.organizer || null,
        targetAudience: values.targetAudience || null,
        host: values.host || null,
        leader: values.leader || null,
        description: values.description || null,
      }
      await updateActivity(activityId, payload)
      message.success('活动已保存')
      navigate(`/activities/${activityId}`, { replace: true })
    } catch { /* 校验失败或 http 拦截已提示 */ } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <PageHeader
        title="编辑活动"
        description="修改活动基本信息"
        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate(`/activities/${activityId}`)}>返回详情</Button>}
      />
      <Spin spinning={loading}>
        <GlassCard style={{ padding: 24, maxWidth: 720 }}>
          <Form form={form} layout="vertical">
            <Form.Item name="name" label="活动名称" rules={[{ required: true, message: '请输入活动名称' }]}>
              <Input maxLength={100} />
            </Form.Item>
            <Form.Item name="theme" label="活动主题"><Input maxLength={200} /></Form.Item>
            <Form.Item name="type" label="类型">
              <Select options={ACTIVITY_TYPES.map((t) => ({ value: t, label: t }))} />
            </Form.Item>
            <Form.Item name="range" label="时间范围"><DatePicker.RangePicker style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="location" label="地点"><Input maxLength={100} /></Form.Item>
            <Form.Item name="organizer" label="组织单位"><Input maxLength={100} /></Form.Item>
            <Form.Item name="targetAudience" label="面向对象"><Input maxLength={200} /></Form.Item>
            <Form.Item name="host" label="主持人"><Input maxLength={50} /></Form.Item>
            <Form.Item name="leader" label="负责人"><Input maxLength={50} /></Form.Item>
            <Form.Item name="description" label="活动描述"><Input.TextArea rows={4} /></Form.Item>
            <Space>
              <Button onClick={() => navigate(`/activities/${activityId}`)}>取消</Button>
              <Button type="primary" loading={saving} onClick={handleSave}>保存</Button>
            </Space>
          </Form>
        </GlassCard>
      </Spin>
    </div>
  )
}
```

> `ACTIVITY_TYPES`：从 `@/api/activityStatus` 或 `ActivityList.tsx` 的类型映射导出。若没有现成常量，在 `@/api/activityStatus.ts` 加 `export const ACTIVITY_TYPES = ['PARTY_LESSON','DATE','PARTY_DAY','COMPETITION','VOLUNTEER','LECTURE','MEETING','OTHER']`。

- [ ] **Step 4: 路由 + 基本信息 Tab 加编辑按钮**

`router/index.tsx` 的 `/activities/:id` 后加：
```tsx
{ path: '/activities/:id/edit', element: <ActivityEdit /> },
```

`ActivityDetail.tsx` 的 PageHeader extra 里加：
```tsx
<Button type="primary" icon={<EditOutlined />} onClick={() => navigate(`/activities/${activityId}/edit`)}>
  编辑
</Button>
```
（`EditOutlined` 已 import。）

- [ ] **Step 5: 构建 + 浏览器验证 + 提交**

```bash
cd /d/MyApp/PAMS/pams-web && npm run build
npm run dev
```

浏览器点验：活动详情→基本信息 Tab 显示甘特图预览（只读）+ 关联文件列表；点"编辑"→ 独立编辑页表单回填 → 修改保存 → 返回详情已更新。curl --noproxy 确认 `/activities/:id/edit` 路由可达。

```bash
git add pams-web/src
git commit -m "feat: 基本信息内嵌甘特图预览与关联文件，新增独立编辑页"
```

---

## M3 · 策划书 Word 编辑器

### Task 3: planTemplate 模板 + Word 编辑器 + Word 预览 + 导入导出

**Files:**
- Create: `pams-web/src/components/word/planTemplate.ts`（12 章模板骨架 + buildDocx 导出 + importDocx 导入）
- Create: `pams-web/src/components/word/WordEditor.tsx`（contenteditable 编辑器 + 工具条）
- Create: `pams-web/src/components/word/WordPreview.tsx`（A4 纸张渲染 7 字段 + docx-preview 可选）
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（PlanTab 加"预览/编辑"切换 + 导入导出按钮）

**Interfaces:**
- Consumes: `PlanTab` 现有 7 字段数据（`ActivityPlanVO`）；`updatePlan/createPlan`。
- Produces:
  - `planTemplate.ts`:
    - `PLAN_TEMPLATE_SECTIONS`（12 章节数组，含每章 label + 字段映射）
    - `planToDocx(plan: PlanFields): Blob`（docx 库生成 .docx）
    - `docxToPlan(file: File): Promise<{background, purpose, ...}>`（mammoth 解析填充字段）
  - `WordEditor.tsx`: props `{ value: PlanFields, onChange: (v: PlanFields) => void }`
  - `WordPreview.tsx`: props `{ plan: PlanFields }`
  - 供 M3 联调。

- [ ] **Step 1: 写 planTemplate.ts（模板结构 + 导入导出）**

```ts
// 策划书 12 章模板（以参考策划书"策划书新模板(终)1.docx"为骨架）
export interface PlanFields {
  background: string
  purpose: string
  content: string
  flow: string
  notice: string
  emergency: string
  budget: string
}

// 章节模板：label 是章节标题，field 映射到 PlanFields 字段
export const PLAN_TEMPLATE_SECTIONS: Array<{ label: string; field: keyof PlanFields | null; hint?: string }> = [
  { label: '一、活动名称', field: null, hint: '信息工程学院党建办公室"XXX"活动' },
  { label: '二、活动主题', field: null },
  { label: '三、活动背景', field: 'background' },
  { label: '四、活动目的', field: 'purpose' },
  { label: '五、活动时间', field: null },
  { label: '六、活动地点', field: null },
  { label: '七、活动组织单位', field: null, hint: '信息工程学院党建办公室' },
  { label: '八、活动对象', field: null },
  { label: '九、活动内容', field: 'content' },
  { label: '十、活动注意事项', field: 'notice' },
  { label: '十一、应急预案', field: 'emergency' },
  { label: '十二、经费预算', field: 'budget', hint: '表格：物品/数量/单价(元)/总价(元)' },
]
```

导出 docx（用 `docx` 库，动态 import）：
```ts
export async function planToDocx(plan: PlanFields, meta: { name?: string; theme?: string }): Promise<Blob> {
  const { Document, Packer, Paragraph, Table, TableRow, TableCell, TextRun, AlignmentType } = await import('docx')
  // 组装：抬头（22pt 宋体居中）→ 各章节（标题 14pt 加粗，正文 12pt）→ 预算表（4 列）→ 落款
  // 具体实现：按 PLAN_TEMPLATE_SECTIONS 遍历，field 有值则输出标题+正文段落
  // budget 若为 JSON 数组则解析成 Table（物品/数量/单价/总价），否则纯文本
  const doc = new Document({
    sections: [{ children: [...sections] }],
  })
  return Packer.toBlob(doc)
}
```

导入 docx（mammoth 动态 import，提取文本填充字段）：
```ts
export async function docxToPlan(file: File): Promise<Partial<PlanFields>> {
  const mammoth = await import('mammoth')
  const arrayBuffer = await file.arrayBuffer()
  const result = await mammoth.extractRawText({ arrayBuffer })
  const text = result.value
  // 按章节标题切分，粗粒度提取到字段（简单实现：按 "三、活动背景" 等标题 split）
  // 返回 Partial<PlanFields>
}
```

> 说明：docx 导出是核心（要生成标准策划书），mammoth 导入做**粗粒度提取**（按章节标题 split 填充对应字段），不追求完美还原表格。

- [ ] **Step 2: 写 WordEditor.tsx（contenteditable + 工具条）**

```tsx
import { useRef } from 'react'
import { Button, Space } from 'antd'
import { BoldOutlined, OrderedListOutlined, TableOutlined } from '@ant-design/icons'

interface WordEditorProps {
  value: PlanFields
  onChange: (v: PlanFields) => void
}

export default function WordEditor({ value, onChange }: WordEditorProps) {
  // 结构：左侧章节导航（12 章，点击聚焦对应 contenteditable 块）
  // 右侧：A4 白纸 contenteditable 渲染，每章一个可编辑区（div[contenteditable]）
  // 工具条：加粗 / 编号列表 / 插入表格 / 字号三档
  // onChange 时同步收集所有区块的 innerHTML 回填到 PlanFields
  // 简化实现：每章独立 contenteditable，onInput 时更新对应 field
  return (
    <div style={{ display: 'flex', gap: 16 }}>
      <div style={{ width: 160, flexShrink: 0 }}>
        {PLAN_TEMPLATE_SECTIONS.map((s, i) => (
          <div key={i} style={{ padding: '4px 0', color: 'var(--color-text-secondary)', cursor: 'pointer', fontSize: 12 }}>
            {s.label}
          </div>
        ))}
      </div>
      <div className="word-paper" style={{ flex: 1 }}>
        {/* A4 纸张：contenteditable 区块 */}
      </div>
    </div>
  )
}
```

> 关键 CSS（global.css 加 `.word-paper`）：白底、A4 比例（约 595px 宽）、内边距、宋体 12pt、`box-shadow` 纸张感、明暗主题下保持白纸（编辑区内容物始终白底黑字，像真实 Word）。

- [ ] **Step 3: 写 WordPreview.tsx（A4 渲染 7 字段）**

```tsx
import type { PlanFields } from './planTemplate'
import { PLAN_TEMPLATE_SECTIONS } from './planTemplate'

export default function WordPreview({ plan }: { plan: PlanFields }) {
  // 按 12 章顺序渲染：标题 14pt 加粗 + 正文 12pt（whiteSpace: pre-wrap）
  // budget 若是 JSON 数组 → 渲染 4 列表格；否则纯文本
  return (
    <div className="word-paper" style={{ padding: 40 }}>
      {/* 机构名抬头 + 活动大标题（22pt 居中） */}
      {PLAN_TEMPLATE_SECTIONS.map((s, i) => {
        const val = s.field ? plan[s.field] : ''
        if (!val) return null
        return (
          <div key={i}>
            <div style={{ fontSize: 14, fontWeight: 600, margin: '12px 0 4px' }}>{s.label}</div>
            <div style={{ fontSize: 12, whiteSpace: 'pre-wrap', lineHeight: 1.8 }}>{val}</div>
          </div>
        )
      })}
    </div>
  )
}
```

- [ ] **Step 4: PlanTab 加"预览/编辑"切换 + 导入导出**

`ActivityDetail.tsx` 的 PlanTab 顶部加模式切换：
```tsx
const [mode, setMode] = useState<'preview' | 'edit'>('preview')
const [planFields, setPlanFields] = useState<PlanFields | null>(null)

// 进入编辑时从 latest 初始化 planFields
useEffect(() => {
  if (latest) {
    setPlanFields({
      background: latest.background ?? '',
      purpose: latest.purpose ?? '',
      content: latest.content ?? '',
      flow: latest.flow ?? '',
      notice: latest.notice ?? '',
      emergency: latest.emergency ?? '',
      budget: latest.budget ?? '',
    })
  }
}, [latest])

// 工具条：预览/编辑切换 + 导入(docx) + 导出(docx) + 保存
<Space>
  <Radio.Group value={mode} onChange={(e) => setMode(e.target.value)}>
    <Radio.Button value="preview">Word 预览</Radio.Button>
    <Radio.Button value="edit">编辑</Radio.Button>
  </Radio.Group>
  {mode === 'edit' && (
    <>
      <Button icon={<UploadOutlined />} onClick={handleImport}>导入 docx</Button>
      <Button icon={<DownloadOutlined />} onClick={handleExport}>导出 docx</Button>
      <Button type="primary" onClick={handleSaveFields}>保存</Button>
    </>
  )}
</Space>
```

处理函数：
```tsx
const handleImport = () => { /* Upload file → docxToPlan → setPlanFields */ }
const handleExport = async () => {
  const blob = await planToDocx(planFields!, { name: activity?.name, theme: activity?.theme })
  // Blob → a.download 下载 planToDocx_活动名.docx
}
const handleSaveFields = async () => {
  // 把 planFields 写入后端（updatePlan/createPlan），成功后 onChanged()
}
```

- [ ] **Step 5: 构建 + 浏览器验证 + 提交**

浏览器点验：策划书 Tab 默认 Word 预览（A4 纸张样式渲染 7 字段）；切编辑模式 → 章节可编辑 + 工具条；导出 docx → 下载文件打开是标准策划书（含预算表）；导入一个真实策划书 docx（用参考文件）→ 字段填充；保存 → 刷新后数据保持。

```bash
git add pams-web/src/components/word pams-web/src/pages/activity/ActivityDetail.tsx pams-web/src/styles/global.css
git commit -m "feat: 策划书 Word 预览/编辑与导入导出"
```

---

## M4 · 议程导出 + 座位表图表化

### Task 4: 议程导出 Word + 座位表矩阵视图 + Excel 编辑

**Files:**
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（AgendaTab 加导出、SeatTab 重构）
- Create: `pams-web/src/components/seat/SeatMapView.tsx`（CSS Grid 选座矩阵 + 图例 + 区域标签）
- Create: `pams-web/src/components/seat/SeatExcelEditor.tsx`（Handsontable 编辑 + 图例配置）
- Create: `pams-web/src/components/word/planTemplate.ts`（追加 `agendaToDocx` 导出函数）

**Interfaces:**
- Produces:
  - `agendaToDocx(agendas: ActivityAgendaVO[]): Promise<Blob>`（docx 编号列表）
  - `SeatMapView.tsx`: props `{ seats: SeatMapVO[], legend: Record<string, string>, onSelect?: (s) => void }`
  - `SeatExcelEditor.tsx`: props `{ seats: SeatMapVO[], onChange: (seats) => void }`
  - 图例键：`pams_seat_legend_{activityId}`（localStorage）

- [ ] **Step 1: agendaToDocx 导出函数（planTemplate.ts 追加）**

```ts
export async function agendaToDocx(agendas: Array<{ stepNo: number; title: string; remark?: string | null }>): Promise<Blob> {
  const { Document, Packer, Paragraph, TextRun, AlignmentType } = await import('docx')
  const children = [
    new Paragraph({ children: [new TextRun({ text: '活动议程表', bold: true, size: 28 })], alignment: AlignmentType.CENTER }),
    ...agendas.map((a) =>
      new Paragraph({
        children: [new TextRun({ text: `${a.stepNo}. ${a.title}${a.remark ? '　' + a.remark : ''}`, size: 24 })],
      }),
    ),
  ]
  const doc = new Document({ sections: [{ children }] })
  return Packer.toBlob(doc)
}
```

- [ ] **Step 2: AgendaTab 加导出按钮**

`AgendaTab` 顶部按钮组加：
```tsx
<Button icon={<DownloadOutlined />} onClick={handleExport}>导出议程表</Button>
```
```tsx
const handleExport = async () => {
  const blob = await agendaToDocx(list)
  // Blob → a.download 下载 活动议程表.docx
}
```

- [ ] **Step 3: 写 SeatMapView.tsx（选座矩阵 + 图例）**

```tsx
import { useMemo } from 'react'
import { Empty } from 'antd'
import type { SeatMapVO } from '@/api/activity'

interface SeatMapViewProps {
  seats: SeatMapVO[]
  legend: Record<string, string> // seatType -> 颜色
  onSelect?: (s: SeatMapVO) => void
}

export default function SeatMapView({ seats, legend, onSelect }: SeatMapViewProps) {
  // 按 zone 分组，每组一个区域：顶部区域名标签 + CSS Grid 座位矩阵
  // 行首列显示排号；座位为圆角方块，颜色 = legend[seatType] ?? 默认灰；已占（有 personName）加粗/标记
  // 底部图例条：legend 的每个色块 + seatType 标签
  // 点击座位 → onSelect(s)
  const groups = useMemo(() => {
    const m = new Map<string, SeatMapVO[]>()
    for (const s of seats) {
      const zone = s.zone ?? '未分区'
      m.set(zone, [...(m.get(zone) ?? []), s])
    }
    return [...m.entries()]
  }, [seats])

  return (
    <div>
      {groups.map(([zone, zoneSeats]) => (
        <div key={zone} style={{ marginBottom: 20 }}>
          <div style={{ fontWeight: 600, color: 'var(--color-text)', marginBottom: 8 }}>{zone}</div>
          {/* CSS Grid：按最大 colNo 定列数，每个座位一个格 */}
          <div style={{ display: 'grid', gridTemplateColumns: `repeat(${maxCol}, 40px)`, gap: 6 }}>
            {zoneSeats.map((s) => (
              <button key={s.id} onClick={() => onSelect?.(s)} style={{ /* 色块 */ }}>
                {s.rowNo}-{s.colNo}
              </button>
            ))}
          </div>
        </div>
      ))}
      {/* 图例条 */}
      <div style={{ display: 'flex', gap: 12, marginTop: 12 }}>
        {Object.entries(legend).map(([type, color]) => (
          <span key={type} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <span style={{ width: 12, height: 12, borderRadius: 3, background: color }} />
            {type}
          </span>
        ))}
      </div>
    </div>
  )
}
```

> 座位格用 `aspect-ratio: 1`、圆角、背景 = legend 色；选中态红描边；已占座位显示 `personName` 首字。空区域显示 Empty。

- [ ] **Step 4: 写 SeatExcelEditor.tsx（Handsontable + 图例配置）**

```tsx
import { useEffect, useMemo, useState } from 'react'
import { Button, ColorPicker, Input, Space, Tag } from 'antd'
import { HotTable } from '@handsontable/react'
import { registerAllModules } from 'handsontable/registry'
import 'handsontable/dist/handsontable.full.min.css'
import type { SeatMapVO } from '@/api/activity'
import type { CellChange } from 'handsontable/common'

registerAllModules()

interface SeatExcelEditorProps {
  seats: SeatMapVO[]
  legend: Record<string, string>
  onChangeLegend: (legend: Record<string, string>) => void
  onChangeSeats: (seats: SeatMapVO[]) => void
}

export default function SeatExcelEditor({ seats, legend, onChangeLegend, onChangeSeats }: SeatExcelEditorProps) {
  // Handsontable 网格：列 = [区域, 排, 列, 座位类型, 就座人]
  // 行 = 每个座位；编辑后 onChangeSeats 回传
  // 图例配置面板：列出当前所有 seatType + 颜色选择器（ColorPicker），可新增 seatType
  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        {/* 图例配置：legend 的 seatType -> 颜色，ColorPicker 改色，可加新类型 */}
      </div>
      <HotTable
        data={rows}
        colHeaders={['区域', '排', '列', '座位类型', '就座人']}
        columns={columns}
        afterChange={(changes) => handleChange(changes)}
        licenseKey="non-commercial-and-evaluation"
        rowHeaders
        stretchH="all"
      />
    </div>
  )
}
```

> Handsontable 7 的 `licenseKey="non-commercial-and-evaluation"` 即可（MIT 版不校验）。若安装的是 8.x 商业版会弹 license 水印——**必须用 7.x MIT 版**。图例配置：遍历 legend 渲染 ColorPicker，改色调 `onChangeLegend`；新增 seatType 用 Input + Button。

- [ ] **Step 5: SeatTab 重构（展示/编辑双模式 + 图例）**

`SeatTab` 改为：
```tsx
const [view, setView] = useState<'matrix' | 'excel'>('matrix')
const [legend, setLegend] = useState<Record<string, string>>(() => {
  // 从 localStorage 读 pams_seat_legend_{activityId}，无则按现有 seatType 自动分配默认色
  const cached = localStorage.getItem(`pams_seat_legend_${activityId}`)
  return cached ? JSON.parse(cached) : defaultLegend(zones)
})

const handleLegendChange = (next: Record<string, string>) => {
  setLegend(next)
  localStorage.setItem(`pams_seat_legend_${activityId}`, JSON.stringify(next))
}

// 视图切换
<Space>
  <Radio.Group value={view} onChange={(e) => setView(e.target.value)}>
    <Radio.Button value="matrix">图表视图</Radio.Button>
    <Radio.Button value="excel">Excel 编辑</Radio.Button>
  </Radio.Group>
</Space>
{view === 'matrix' ? <SeatMapView seats={allSeats} legend={legend} /> : <SeatExcelEditor ... />}
```

> `defaultLegend`：按现有 seatType 从设计系统色板（红系 + 灰阶 + 强调色）自动分配。矩阵视图点击已占座位显示 Tooltip（就座人/排/列）。

- [ ] **Step 6: 构建 + 浏览器验证 + 提交**

浏览器点验：座位表默认图表视图（电影选座矩阵，区域标签 + 图例条）；切 Excel 编辑 → Handsontable 网格编辑座位 → 保存回传；图例配置改色 → 矩阵视图立即变色；导出议程表 → 下载 docx 打开是编号列表。

```bash
git add pams-web/src
git commit -m "feat: 议程导出与座位表图表化+Excel编辑+图例"
```

---

## M5 · 扫码签到

### Task 5: 签到 Tab 扫码 + 落地页

**Files:**
- Modify: `pams-web/src/api/signin.ts`（+ generateToken/scanSignin）
- Create: `pams-web/src/components/signin/SigninQR.tsx`（二维码 + 手动刷新）
- Create: `pams-web/src/pages/signin/SigninScan.tsx`（免登录落地页）
- Modify: `pams-web/src/router/index.tsx`（+ `/signin/:token`，MainLayout 外）
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（SigninTab 加扫码卡片）

**Interfaces:**
- Consumes: Task 1 后端 `/api/signins/token`、`/api/signins/scan`；`qrcode.react`。
- Produces:
  - `SigninQR.tsx`: props `{ activityId: number, onSigned: () => void }`
  - `SigninScan.tsx`: 路由 `/signin/:token`，免登录页面

- [ ] **Step 1: api/signin.ts 加两个函数**

```ts
export const generateSigninToken = (activityId: number) =>
  post<{ token: string; activityId: number; expiresAt: string }>('/signins/token', { activityId })
export const scanSignin = (data: { token: string; name: string; studentNo?: string }) =>
  post<SigninVO>('/signins/scan', data)
```

- [ ] **Step 2: 写 SigninQR.tsx**

```tsx
import { useCallback, useEffect, useState } from 'react'
import { Button, message, Space, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import QRCode from 'qrcode.react'
import { generateSigninToken } from '@/api/signin'

interface SigninQRProps {
  activityId: number
  onSigned: () => void
}

export default function SigninQR({ activityId, onSigned }: SigninQRProps) {
  const [qrContent, setQrContent] = useState('')
  const [expiresAt, setExpiresAt] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const t = await generateSigninToken(activityId)
      setQrContent(`${window.location.origin}/signin/${t.token}`)
      setExpiresAt(t.expiresAt)
    } catch { /* http 拦截已提示 */ } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => { refresh() }, [refresh])

  return (
    <div style={{ textAlign: 'center', padding: 16 }}>
      {qrContent ? (
        <QRCode value={qrContent} size={180} level="M" />
      ) : (
        <div style={{ height: 180 }} />
      )}
      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        扫码签到 · 有效期至 {expiresAt ? new Date(expiresAt).toLocaleTimeString() : '-'}
        <Tag style={{ marginLeft: 8 }} color="green">未刷新长期有效</Tag>
      </div>
      <Button icon={<ReloadOutlined />} loading={loading} onClick={refresh} style={{ marginTop: 8 }}>
        刷新二维码
      </Button>
    </div>
  )
}
```

- [ ] **Step 3: 写 SigninScan.tsx（免登录落地页）**

```tsx
import { useEffect, useState } from 'react'
import { Button, Form, Input, message, Spin } from 'antd'
import { useParams } from 'react-router-dom'
import { scanSignin } from '@/api/signin'

export default function SigninScan() {
  const { token } = useParams()
  const [done, setDone] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  const handleSubmit = async (values: { name: string; studentNo?: string }) => {
    if (!token) return
    setSaving(true)
    try {
      await scanSignin({ token, name: values.name, studentNo: values.studentNo || undefined })
      setDone(true)
      message.success('签到成功')
    } catch { /* http 拦截已提示（无效/过期码） */ } finally {
      setSaving(false)
    }
  }

  return (
    <div className="login-page">
      <div className="glass-card login-card">
        {/* 标题：活动签到 */}
        {done ? (
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 22, color: 'var(--color-red)', marginBottom: 8 }}>✓ 签到成功</div>
            <Button onClick={() => window.location.reload()}>继续</Button>
          </div>
        ) : (
          <Form form={form} layout="vertical" onFinish={handleSubmit}>
            <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
              <Input placeholder="请输入姓名" />
            </Form.Item>
            <Form.Item name="studentNo" label="学号"><Input placeholder="学号（选填）" /></Form.Item>
            <Button type="primary" htmlType="submit" loading={saving} block>签到</Button>
          </Form>
        )}
      </div>
    </div>
  )
}
```

> 复用 `login-page`/`login-card` 的毛玻璃样式。

- [ ] **Step 4: 路由 + SigninTab 嵌扫码**

`router/index.tsx` 加（在 `/login` 同级，MainLayout 外）：
```tsx
{ path: '/signin/:token', element: <SigninScan /> },
```

`ActivityDetail.tsx` 的 SigninTab 顶部加扫码卡片：
```tsx
<GlassCard style={{ padding: 16, marginBottom: 12 }}>
  <SigninQR activityId={activityId} onSigned={fetchList} />
</GlassCard>
```
（SigninTab 即 `SigninPanel.tsx`，在它的顶部插入 `SigninQR`。）

- [ ] **Step 5: 构建 + 浏览器验证 + 提交**

浏览器点验：签到 Tab 显示二维码（内容为 `/signin/{token}` 链接）；点"刷新二维码"→ 新 token 旧码失效；手机/新窗口打开链接 → 填姓名学号 → 签到成功 → 签到列表自动刷新出现 SCAN 记录；用旧 token 再次访问 → 提示无效。

```bash
git add pams-web/src
git commit -m "feat: 扫码签到（二维码手动刷新长期有效）"
```

---

## M6 · 联调打磨

### Task 6: 全流程联调 + 主题适配 + 回归

**Files:**
- Modify: 各组件按联调结果微调
- Test: `pams-backend` 全量 `mvn -q test`；`pams-web` `npm run build`

- [ ] **Step 1: 全流程联调**

走一遍：新建活动 → 基本信息看甘特图/关联文件 → 编辑页改信息 → 策划书 Word 编辑/导入/导出 → 议程导出 → 座位表矩阵/Excel/图例 → 扫码签到 → 保存数据全链路。

- [ ] **Step 2: 明暗主题适配**

检查 Word 编辑器纸张（编辑区白纸，周围玻璃卡片随主题）、座位矩阵色块、扫码页毛玻璃在 dark/light 下都正常。

- [ ] **Step 3: 回归**

```bash
cd /d/MyApp/PAMS/pams-backend && mvn -q test
cd /d/MyApp/PAMS/pams-web && npm run test && npm run build
```

- [ ] **Step 4: 提交收尾**

```bash
git add pams-web/src pams-backend/src
git commit -m "fix: 活动详情页增强联调打磨"
```

---

## 运行与测试命令速查

| 用途 | 命令 |
|---|---|
| 后端测试 | `cd pams-backend && mvn -q test` |
| 前端构建 | `cd pams-web && npm run build` |
| 前端开发 | `cd pams-web && npm run dev` |
| 后端启动 | `cd pams-backend && mvn spring-boot:run` |
| 一键启动 | `cmd //c start.bat` |

## 自审说明

- **策划书数据兼容**：现有 7 字段可能存 JSON 或纯文本，Word 预览用 `parseJsonField` 思路兼容（flow/budget 数组渲染成列表/表格，纯文本直接展示）。
- **Handsontable 授权**：计划锁定 7.x MIT 版；若 npm 装到 8.x 商业版，Task 1 需降级为自绘 Excel 表格并记录，矩阵/图例不受影响。
- **扫码令牌安全性**：24h 过期 + 手动刷新作废 + 随机 UUID，公开接口仅能通过有效 token 写入签到。
- **懒加载**：mammoth/docx/docx-preview 均动态 import，避免进主包。
