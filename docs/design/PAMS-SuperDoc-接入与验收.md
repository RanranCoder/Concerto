# 策划书 SuperDoc 接入与验收

日期：2026-10-21。当前正式项目采用 **SuperDoc 2.24.0**，替代原 wangEditor 编辑与自制分页方案。此前选型报告中的 ONLYOFFICE 建议已被本次用户选型取代。

## 实际接入

- `pams-web/src/components/word/SuperDocDocument.tsx`：同一个 SuperDoc 内核用于只读和编辑；按需加载、自带分页、固定工具栏、适应容器宽度。编辑器未准备好时不能保存，加载失败不回退旧编辑器。
- `ActivityPlanTab.tsx`：原地进入编辑、保存、取消，Word 导入直接读取原始 DOCX；下载已保存的 DOCX。右侧审核意见和版本记录继续保留。
- 保存时先由 SuperDoc 导出 DOCX 字节，上传为独立 `PLAN` 文件，再由后端把文件绑定到当前草稿。保存期间禁止继续输入；失败保留编辑器，允许重试。取消丢弃浏览器中的未保存副本，不写入正式文档。
- 数据库 V41 增加 `document_file_id`、`document_text` 及文件关联索引。文件存储复用现有上传服务，无需额外 Office 服务器。
- 文档读取使用 `GET /api/plans/{id}/document`，依据策划书版本权限授权，不要求审核人额外拥有材料下载权限。作者也可保存自己的文档上传；通用文件接口的既有权限不会放宽。
- 后端校验 DOCX 文件、大小和读取权限，再从真实文件提取正文用于检索。原生版本拒绝通过旧 HTML 字段覆盖正文；旧窗口保存继续受修订号和更新时间检查保护。
- 提交后该业务版本不可编辑；修订生成新的业务版本，保存修改时创建新的文件。未选审核人不能读取当前版本的文件，历史版本按既有权限开放。
- 知识库使用从 DOCX 提取的完整正文，并继续执行版本权限检查。兼容字段只保存有限长度的索引，不用于重建原生正文。

## 旧方案清理

已移除 `@wangeditor/editor`、`@wangeditor/editor-for-react`、`mammoth` 依赖；删除 `WordEditor`、`PlanRichEditor`、`WordPreview`、`A4DocumentPreview`、`a4Pagination`，以及旧工具栏、纸张分页和预览样式。相关旧测试已由原生 DOCX 测试取代。

保留以下有实际用途的部分：

- `legacyPlanMigration.ts` / `legacyHtmlToDocx.ts`：仅用于已有章节数据首次转换为 DOCX，及新建策划书的初始模板；首次保存后优先读取持久化文件。旧数据不会因切换编辑器而被删除。
- `docx`：用于上述一次转换和独立的议程导出。
- `docx-preview`：供其他模块的通用附件预览使用，策划书不再调用它。
- `docs/design` 内历史原型、截图和设计记录保留为历史资料，不作为正式页面的编辑器入口。

## 交互边界

文档内活动名称等文字可自由编辑；活动管理的基本信息独立维护，不从任意 DOCX 段落自动反推覆盖。

版本侧栏对原生文档展示提取后的正文文字差异。文件改变但正文文字相同时提示查看原文，不把它冒称为完整的排版差异分析。批注和修订随 DOCX 保留，业务审核仍使用右侧审核意见与通过/退回操作。

首期为作者编辑、审核人阅读与业务审核。没有启用多人实时协同，也没有接入第三方文档存储。编辑器遥测已关闭。

## 验证

- 前端针对性测试：22 项，覆盖原生文件直读、保存回显、取消、失败重试、审核人选择、权限入口及 DOCX 容器验证。
- 后端请求/权限测试：43 项策划书和附件相关测试通过，覆盖 DOCX 持久化、原文下载、当前版/历史版可见范围、私密文件 ID 猜测、无效 DOCX、旧字段覆盖拦截、过期窗口保存、完整正文检索及独立于材料权限的文档访问。
- 浏览器使用真实 SuperDoc 引擎，接口使用测试响应：键盘输入、导出 DOCX 解包校验、保存失败重试、刷新重载、取消不写入和勾选审核人。另有真实多页 Word 样例的导入与保存验证。
- 生产构建完成；同时验证开发服务与生产静态构建，防止只在一种启动方式下可用。
- 本地 MySQL 已成功应用 V41；本地后端和前端已重启加载新实现。浏览器测试不向现有业务数据库写入测试策划书。

验收截图：

- [只读预览](superdoc-implementation/superdoc-preview.png)
- [原地编辑](superdoc-implementation/superdoc-editor.png)
- [保存回显](superdoc-implementation/superdoc-saved.png)
- [深色界面](superdoc-implementation/superdoc-dark.png)
- [窄屏阅读](superdoc-implementation/superdoc-mobile.png)

复验：

```bash
cd pams-web
npm run build
NODE_OPTIONS=--no-experimental-webstorage npm test -- src/pages/activity/ActivityPlanTab.test.tsx src/pages/activity/planComparison.test.ts src/components/word
NODE_OPTIONS=--no-experimental-webstorage npm run test:e2e -- e2e/superdoc-plan.spec.ts
NODE_OPTIONS=--no-experimental-webstorage npm run test:e2e -- e2e/activity-v6.spec.ts --grep 'Word import stays'
```

浏览器测试应顺序执行，以免不同测试进程同时清理 Playwright 输出目录。`PAMS_SUPERDOC_URL` 可以指定原生文档测试的服务地址。

## 部署与授权

SuperDoc 已固定到 2.24.0，脚本、Worker 和样式随项目构建发布；首次打开会加载较大的文档引擎资源，界面有加载状态。当前构建包含约 11.6MB 的引擎分包，生产端使用现有 Nginx gzip 与静态资源缓存。

当前安装为 AGPLv3 开源包；商业授权按厂商条款处理。若已有商业许可，可设置 `VITE_SUPERDOC_LICENSE_KEY` 后重新构建，Docker Compose 也已传递此构建参数。该参数是客户端许可标识，不应填入服务端密钥。

尚未覆盖所有中文 Office 特性和移动端排版组合；复杂浮动对象、特殊字体和更多真实模板需继续按实际文档验收。
