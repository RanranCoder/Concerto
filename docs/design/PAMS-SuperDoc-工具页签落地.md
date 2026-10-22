# 策划书 SuperDoc 工具页签

日期：2026-10-22。基于项目已固定的 SuperDoc 2.24.0 实现，不引入协作服务器、模型或校对服务。

开始本次修改前，已将原生 DOCX 接入和旧编辑器清理成果提交到远程 `Concerto` 分支：`75d4fb7`。

## 界面与功能

| 页签 | 已接入的操作 |
| --- | --- |
| 开始 | 撤销、重做、格式刷、清除格式；中文常用字体、字号、粗体、斜体、下划线、删除线、颜色与高亮；对齐、缩进、行距、项目符号、编号；文档自身的标题样式；中文查找替换 |
| 插入 | PNG/JPG 图片、链接、表格；增删行列、合并拆分单元格等按选区启用的操作；目录、页眉或页脚中的页码 |
| 布局 | 按节设置纸张（A4/A3/Letter 或保留原始尺寸）、方向、四边页边距；显示标尺、厘米或英寸 |
| 审阅 | 对选中文字添加批注；切换直接编辑和修订；待处理数量；上一处/下一处修订；接受或拒绝修改 |
| 视图 | 百分比缩放、适应宽度、标尺、段落标记、全屏编辑 |

工具栏固定在正文显示区域上方，正文独立滚动。切换页签只更新控制区，不重新创建编辑器，不丢失未保存文字。窄屏时工具区内部横向滚动，页面不横向溢出。页签支持方向键、Home/End 和键盘焦点。

保留原地保存、取消和失败重试。只读或取消编辑后不显示工具栏。全屏包含保存、取消操作；全屏中取消会先退出全屏，再显示放弃修改确认。

## 接口与状态

- `SuperDocRibbon.tsx` 使用公开的 `superdoc.ui` 控制器及 `activeEditor.doc.sections`，不操作编辑器私有状态，不通过 HTML 重建 DOCX。
- 控件订阅命令是否可用、当前格式、样式目录和修订状态；无选区或不支持的操作禁用。字段弹窗使用冻结选区，避免焦点切换导致插入位置变化。
- 字体列表增加宋体、仿宋、黑体、楷体、微软雅黑、等线；实际渲染仍取决于客户端可用字体。
- 字号、批注、修订、表格、目录、页码、页面设置随 DOCX 保存；缩放、标尺和段落标记属于显示偏好。
- 页面设置接口使用英寸，界面显示厘米。未编辑的页边距保留原始精度；自定义纸张调整方向时保留原尺寸。
- 文档操作尚未结束时暂停保存入口，失败保留当前编辑内容。查找替换调用本地文档搜索，不连接第三方服务。
- 文档内修订决定与右侧业务审核独立；既有审核人选择、版本读取权限、业务版本记录不改变。

## 实际边界

页面设置针对弹窗中明确选择的节；复杂分栏、分节符和其他未列出的 Word 设置不作为本次新增入口。

当前引擎会拒绝在含批注或修订的部分段落中插入链接；收到这一明确回执时保留输入，并提示换到其他段落重试。引擎在重排页面时偶尔可能无法保留原来的精确视口锚点，此时提示重新选择正文，不覆盖文档内容。

多人实时协作、AI 润色、拼写语法服务与模板业务字段填充均未接入。

## 验收方式

浏览器测试使用真实 SuperDoc 引擎和隔离的业务接口响应，不向现有业务数据库写测试文档。保存后解包 DOCX 检查原生 XML、批注、修订、图片资源、表格、链接关系、目录及页码，而不只检查按钮是否可点击。

最终验证：生产构建通过；前端针对性测试 22 项通过；生产静态构建下 4 项 SuperDoc 浏览器测试全部通过，另有既有 Word 导入与显式保存流程通过。Ant Design 检查无问题。保存失败测试中的 HTTP 500 为主动注入，用于确认输入保留与重试，不是运行故障。

浏览器验证同时覆盖了全屏取消后确认放弃、查找替换写回、格式刷准备完成后拖选目标、撤销重做、标题与列表格式，以及窄屏无页面横向溢出。

复验命令：

```bash
cd pams-web
npm run build
NODE_OPTIONS=--no-experimental-webstorage npm test -- src/pages/activity/ActivityPlanTab.test.tsx src/pages/activity/planComparison.test.ts src/components/word
NODE_OPTIONS=--no-experimental-webstorage npm run test:e2e -- e2e/superdoc-plan.spec.ts e2e/activity-v6.spec.ts --grep 'native SuperDoc|ribbon|Word import stays'
```

`PAMS_SUPERDOC_URL` 可指定原生文档测试地址。浏览器测试进程顺序执行，避免同时清理输出目录。

实际页面截图：

- [开始工具栏](superdoc-ribbon/superdoc-ribbon-home.png)
- [插入工具栏](superdoc-ribbon/superdoc-ribbon-insert.png)
- [布局工具栏](superdoc-ribbon/superdoc-ribbon-layout.png)
- [修订审阅](superdoc-ribbon/superdoc-ribbon-review.png)
- [全屏编辑](superdoc-ribbon/superdoc-ribbon-fullscreen.png)
- [窄屏编辑](superdoc-ribbon/superdoc-ribbon-mobile.png)

## 官方依据

- [公开界面控制器与命令状态](https://docs.superdoc.dev/editor/custom-ui/commands-and-state/)
- [自定义工具栏](https://docs.superdoc.dev/editor/custom-ui/formatting-controls/)
- [修订审阅](https://docs.superdoc.dev/editor/track-changes/)
- [标尺](https://docs.superdoc.dev/editor/built-in-ui/ruler/)
- [按节设置纸张](https://docs.superdoc.dev/document-api/reference/sections/set-page-setup/)
