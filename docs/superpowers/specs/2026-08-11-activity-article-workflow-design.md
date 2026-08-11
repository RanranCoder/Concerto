# 活动内推文管理（对接秀米+公众号发布流程）— 设计文档

> **日期**: 2026-08-11
> **状态**: 已获用户批准（方案 A：活动内管推文 + 长图截图审核 + 发布后回填归档）

## 背景与目标

新媒体中心的真实宣传流程是：**用秀米排版 → 截图长图 → 微信审稿 → 公众号发布**。宣传阵地是微信公众号，排版工具是秀米，两者都是外部工具。

现状系统里的「推文管理」（`/content/articles`）与真实流程**脱节**：正文存纯文本、内容就是文本框打字，既看不到秀米排版效果，也管不了发布和归档，导致干事不用、走了形式。用户确认要解决 4 个痛点：**发布流程跟踪、归档进活动档案、审核能看排版效果、任务与提醒**（全选）。

**定位**：系统管**流程和归档**，不替代秀米和公众号。秀米负责排版，公众号负责发布，PAMS 负责「这个活动该有哪些推文、谁负责、什么时候要交、审了没有、发了没有、发完的数据」。

**目标**：

1. 推文作为**活动子模块**管理（活动详情页「推文」Tab），预热/报道/视频推文挂到活动下，带负责人+截止时间；
2. 审核和归档以**长图截图**为载体（秀米排版完成 → 截图上传进系统），审核人看真实排版效果；
3. 发布后**回填公众号链接与阅读/在看数据**，推文自动进入活动档案；
4. **任务与提醒**：创建指派、提交审核、审核通过/驳回、发布完成各节点通知 + 每天定时扫描临近/过期未完成的截止提醒。

## 关键决策（已与用户确认）

1. **方案 A（活动内管推文）**：推文挂在活动详情页「推文」Tab，现有独立「推文管理」页保留为全部门聚合视图。
2. **长图截图**：秀米排版完成截图成一张长图上传进系统（可多张），作为审核与归档载体；正文纯文本降级为可选文字底稿。
3. **加 `APPROVED` 状态**：现有状态流「审核通过即发布」不符合真实流程（审过了还得去公众号后台发再回填链接），拆出「审核通过·待发布」态，让「审过但没发」清晰可查。
4. **截止提醒用后端定时扫描**（`@Scheduled`，每天一次），不依赖微信推送。

## 现状盘点

- 后端 `com.pams.module.content`：`Article`（`article` 表）字段 `title/summary/content/coverUrl/activityId/articleType/status/authorId/reviewerId/reviewComment/publishTime`；`ArticleType` = `PREHEAT/REPORT/VIDEO`；`ArticleStatus` = `DRAFT/PENDING/PUBLISHED/REJECTED`。`ArticleController` + `ArticleService` + `ArticleRepository`。审核接口 `@PreAuthorize` 限 `MEDIA_LEADER` 或 `TEACHER/DIRECTOR`。
- 前端 `pams-web/src/pages/content/ArticleList.tsx`：列表 + 撰写/编辑（纯文本 textarea + 封面 URL 文本输入）+ 审核弹窗 + 预览弹窗；`api/article.ts` 已有 CRUD/提交/审核封装。
- 通知事件已挂：`NotificationEventListener` L204 已有「新闻稿/推文」上传 → 全部门通知链路（`NEWS` 与 `ARTICLE` 区分）。
- 文件上传：`POST /api/files/upload`（multipart，返回 `FileRecord` 含 `id`）；前端 `api/file.ts` 已有 `uploadFile`。
- 活动详情页 `ActivityDetail.tsx` 用 antd `<Tabs items={tabItems}>`，可加新 Tab。
- 无定时任务基础设施（本期新增第一个 `@Scheduled`）。

## 设计

### 1. 数据模型（改造 `article` 表，不新建表）

| 字段 | 改动 | 说明 |
|---|---|---|
| `activity_id` | 新建必填 | 推文都挂在活动下（现有字段；后端创建时校验必填，**存量 NULL 数据不动，不强制 DB 级 NOT NULL**） |
| `image_urls` | **新增** `TEXT`（JSON 数组） | 长图截图 URL 列表（1-N 张），经 `/api/files/upload` 上传后回填 |
| `deadline` | **新增** `DATETIME` | 任务截止时间（预热 = 活动开始前 N 天，可改） |
| `wx_url` | **新增** `VARCHAR(500)` | 公众号发布链接 |
| `read_count` | **新增** `INT` 默认 0 | 阅读量，发布后回填可改 |
| `like_count` | **新增** `INT` 默认 0 | 在看数，发布后回填可改 |
| `content` | 保留，降级 | 可选文字底稿，不再作为主内容 |
| `cover_url` | 保留，可选 | 封面图（现有字段） |
| `deadline_reminded_at` | **新增** `DATETIME` | 截止提醒去重：记录最近一次提醒时间，跨天且未发布才再提醒 |

Flyway 新增迁移 `V12__article_workflow.sql`：`ALTER TABLE article ADD COLUMN image_urls TEXT ...`、`deadline DATETIME`、`wx_url VARCHAR(500)`、`read_count INT NOT NULL DEFAULT 0`、`like_count INT NOT NULL DEFAULT 0`、`deadline_reminded_at DATETIME`；`activity_id` 加索引。

### 2. 状态流（加 `APPROVED`）

```
DRAFT(创建任务) → PENDING(已提交审核) → APPROVED(审核通过·待发布) → PUBLISHED(已发布·回填链接)
                        └──→ REJECTED(驳回，改后重提)
```

- `PUBLISHED` 判定：审核通过后回填了 `wx_url` + `publish_time` 才置为 `PUBLISHED`；仅审核通过为 `APPROVED`。
- 状态迁移与权限沿用现有 `ArticleService` 校验，新增 `APPROVED → PUBLISHED` 的「标记发布」接口（回填链接/发布时间）。
- 现有「审核通过即发布」逻辑改为「审核通过置 `APPROVED`」。

### 3. 后端接口

在现有 `ArticleController` 增补/调整：

| 接口 | 说明 |
|---|---|
| `GET /api/articles?activityId=&...` | 列表加 `activityId` 筛选（活动 Tab 用） |
| `POST /api/articles` | 创建推文任务：必填 `activityId`、`title`、`articleType`、`deadline`、`authorId`（负责人） |
| `PUT /api/articles/{id}` | 编辑（含 deadline、imageUrls、content 底稿） |
| `POST /api/articles/{id}/submit` | 提交审核 → `PENDING`，通知新媒体部长 |
| `POST /api/articles/{id}/review` | 审核通过 → `APPROVED` / 驳回 → `REJECTED` + 意见，通知负责人 |
| `POST /api/articles/{id}/publish` | 回填 `wxUrl` + `publishTime` → `PUBLISHED`，通知全部门（复用现有事件） |
| `PUT /api/articles/{id}/stats` | 更新 `readCount` / `likeCount`（发布后可随时改） |
| `GET /api/articles/overdue?days=` | 截止扫描用：未完成（非 `PUBLISHED`）且 deadline 在 n 天内/已过期的任务列表 |

新增后端定时任务 `ArticleDeadlineTask`（`@Scheduled(cron = "0 30 8 * * ?")` 每天 8:30）：扫 overdue 推文 → 通知负责人「推文《X》截止 Y 即将/已到期」，同一任务一天只提醒一次（去重：`overdue_reminded` 加字段或按「已发过的通知」判重）。为避免重复通知，`article` 表加 `deadline_reminded_at DATETIME`（记最近一次提醒时间，仅当跨天且状态未 PUBLISHED 时再提醒）。

### 4. 前端

**活动详情页 `ActivityDetail.tsx` 新增「推文」Tab**：

- 顶部「新建推文」按钮（新媒体部长+老师+主任可见）→ 弹窗：类型（预热/报道/视频）+ 标题 + 负责人（新媒体干事下拉）+ 截止时间（默认预热=活动开始前 3 天 / 报道=结束后 2 天，可改）+ 摘要 + 可选底稿 + 长图上传（`Upload`，多张，走 `/api/files/upload`）。提供「快捷创建预热」「快捷创建报道」两个预填按钮。
- 推文卡片列表：标题、类型 Tag、状态 Tag、负责人、截止时间（临近/过期标红）、长图缩略（点开看大图）、公众号链接（可点）、阅读/在看数。
- 卡片操作（按状态与权限显隐）：
  - 编辑（DRAFT/REJECTED）
  - 提交审核（DRAFT）
  - 审核（PENDING，canReview）：弹窗查看长图 + 通过/驳回带意见
  - 标记发布（APPROVED）：回填公众号链接 + 发布时间 → PUBLISHED
  - 更新数据（PUBLISHED）：改阅读量/在看数
  - 删除（DRAFT/REJECTED）
- 列表按状态/类型筛选；无推文时显示空态引导「活动预热推文建议活动前 3 天发布」。

**「推文管理」页改造为聚合视图**：

- 列表加列：所属活动（名称）、截止时间、公众号链接、阅读/在看数；筛选加「按活动」。
- 保留现有搜索/状态/类型筛选与审核能力，与活动 Tab 共用接口与组件。

**`api/article.ts`**：补 `publishArticle`、`updateArticleStats`、`overdueArticles`；`ArticleSave`/`ArticleVO` 加新字段。

### 5. 通知（复用现有通知系统，事件驱动）

| 触发点 | 通知对象 |
|---|---|
| 创建/指派推文任务 | 负责人 |
| 提交审核 | 新媒体部长（`MEDIA_LEADER`）+ 老师/主任 |
| 审核通过 / 驳回 | 负责人 |
| 标记发布 | 全部门（已有链路，调整文案） |
| 截止提醒（定时扫描） | 负责人 |

新增事件类型复用现有 `NotificationType`/事件监听机制（v4 批次 C 已建 `ARTICLE_SUBMIT`/`ARTICLE_REVIEW` 等链路，扩 `ARTICLE_ASSIGNED` 与截止提醒）。

### 6. 权限

| 操作 | 允许角色 |
|---|---|
| 创建/编辑推文任务、快捷创建 | `MEDIA_LEADER` + `TEACHER` + `DIRECTOR` |
| 上传长图、提交、标记发布、更新数据 | `MEDIA_LEADER` + 被指派负责人（`authorId == 当前用户`）|
| 审核 | `MEDIA_LEADER` / `TEACHER` / `DIRECTOR`（沿用现有 `canReview`）|
| 查看 | 全部门 |

### 7. 测试

**后端单元测试**（`ArticleServiceTest` 扩展 + 新增）：

| 用例 | 断言 |
|---|---|
| 创建推文任务（必填校验：activityId/title/deadline） | 创建成功 / 缺字段抛 `BizException` |
| 状态流：DRAFT→PENDING→APPROVED→PUBLISHED、驳回→REJECTED→重提→PENDING | 各迁移状态正确、非法迁移被拒 |
| 标记发布：回填 wxUrl+publishTime → PUBLISHED；仅 APPROVED 可发布 | 正确 |
| 更新阅读/在看数 | 数值更新 |
| 截止扫描：未完成且临近/过期 → 触发通知；同一天不重复提醒 | overdue 列表正确、去重生效 |
| 权限：非负责人不能上传/提交/回填他人推文 | `@PreAuthorize`/业务校验拒绝 |

**前端**：`tsc` + `vite build` 通过；浏览器手动验证「活动详情→推文 Tab→创建→上传长图→提交→审核→标记发布→更新数据」全链路 + 聚合视图筛选。

## 验收标准

1. 活动详情页有「推文」Tab，可创建预热/报道推文任务（含负责人+截止时间，快捷创建默认截止正确）。
2. 长图上传后可预览，审核弹窗以长图为主要审核载体。
3. 审核通过后状态为「待发布」，回填公众号链接+发布时间后变「已发布」。
4. 发布后可更新阅读量/在看数，列表与活动 Tab 展示。
5. 各节点通知正确触发；临近/过期未完成的推文每天定时提醒负责人（不重复）。
6. 推文进入活动档案维度（活动详情可见该活动全部推文及状态）。
7. `mvn -pl pams-backend test` 全绿；前端构建通过。

## 非目标 / 后续

- **不对接公众号 API**：不做自动上传草稿箱/自动发布/自动拉取阅读数据（学生公众号多为未认证订阅号，API 受限且需 AppSecret/IP 白名单，风险高）。
- **不替代秀米**：不在系统内做图文排版；排版仍在秀米，系统以长图截图承载审核与归档。
- 不做推文数据可视化（阅读量趋势图）——本期只记录数值，统计报表后续再说。
- 微信推送（企业微信/服务号）不在本期，仅站内通知。
