# PAMS 策划书内嵌 Word 编辑器选型调研

后续决定：用户已选定 SuperDoc，正式代码的实现和验收见 [SuperDoc 接入与验收](PAMS-SuperDoc-接入与验收.md)。以下为选型阶段的调研记录。

调研日期：2026-10-21。依据项目现有实现及厂商公开文档；尚未进行各编辑器的样本文档实测，也未取得商务报价。以下推荐是初步技术判断，价格和授权范围以最终合同为准。

## 1. 建议

**优先验证自部署 ONLYOFFICE Docs；以 WPS WebOffice 作为国内商业方案对照。** 如果更重视工具栏与现有页面的融合，且希望减少独立文档服务，再验证 SuperDoc 或 Syncfusion。

PAMS 当前使用 React 18、Vite、Spring Boot、MySQL，并已有 Docker Compose 部署。ONLYOFFICE 有官方 React 组件及独立文档服务，适合在现有策划书正文区域内嵌。正式部署所需的授权版本、并发额度、移动端和 API 权利需按实际用途确定。[官方 React 集成](https://api.onlyoffice.com/docs/docs-api/get-started/frontend-frameworks/react)、[版本对比](https://www.onlyoffice.com/compare-editions)

## 2. 现有实现为什么需要更换文档内核

目前 `PlanRichEditor.tsx` 使用 wangEditor，导入依赖 mammoth，导出通过 docx 库重建，预览使用 docx-preview。主体仍是结构化字段和 HTML，Word 是导入、导出的文件格式。

这个路径适合常规图文编辑。对于分节、页眉页脚、目录、复杂编号、浮动图片、跨页表格和修订信息，现有文档模型与转换代码不足以保证完整往返保留。给工具栏增加按钮也不能解决文档模型缺失的问题。

建议改成：**DOCX 文件作为正文的权威数据；章节摘要、搜索文本、业务字段作为辅助数据。** 此处指业务存储，不要求所有厂商内部都使用同一种编辑模型。导入的原始文件应保留，不再经过 HTML 重建后覆盖。

## 3. 可选方案

| 方案 | 编辑与接入方式 | 部署和授权 | 对 PAMS 的判断 |
| --- | --- | --- | --- |
| ONLYOFFICE Docs | 完整 Office 风格界面；提供页面布局、目录、页眉页脚、批注、修订、协同等能力；官方 React 组件 | 自部署或云服务；社区版 AGPLv3，商业集成有 Enterprise/Developer 等版本 | 首轮验证首选；需要新增文档服务、保存回调及运维 |
| WPS WebOffice | 页面内嵌在线 Office，业务系统提供文件信息、用户权限、下载和上传回调 | 本次核实了云服务回调模式；私有化产品范围及费用需商务确认 | 国内商业方案重点对照；中文复杂排版仍须样本验收 |
| SuperDoc | DOCX 浏览器组件；当前 v2 文档描述为 OOXML 文档模型；可自定义界面，支持批注、修订 | 单人浏览器编辑不需要独立 SuperDoc 文档服务器；AGPLv3 / 商业双授权；协同另接基础设施 | 页面融合度值得验证；重点检查中文排版、长文档及复杂对象兼容 |
| Syncfusion DOCX Editor | React 组件，编辑模型为 SFDT；DOCX 导入通过转换服务，DOCX 导出可在客户端进行 | 可自托管转换接口，官方支持 Java；商业许可及有资格限制的 Community License | 适合定制工具栏；必须验证 DOCX ↔ SFDT 往返效果及完整授权范围 |
| Collabora Online | 可嵌入的在线 Office；通过 WOPI 对接文件与权限 | 自部署；CODE 用于开发测试，受支持的生产版本按订阅购买 | 有内网部署需求时可对照，接入 WOPI 的工作量需计入 |
| Microsoft 365 Word 网页版 | 微软官方浏览器编辑体验；通过 CSPP/WOPI 集成 | 需要申请、验证及正式上线流程，另有许可和地域约束 | 当前大陆项目不作为默认方案，原因见下文 |

能力及部署依据：[ONLYOFFICE 文档编辑指南](https://helpcenter.onlyoffice.com/docs/userguides/document_editor)、[WPS 回调说明](https://open.wps.cn/documents/app-integration-dev/docs-center/online-preview-edit/callback/question)、[SuperDoc 编辑器架构](https://docs.superdoc.dev/editor/)、[Syncfusion 服务端依赖](https://help.syncfusion.com/document-processing/word/word-processor/react/web-services-overview)、[Syncfusion 导出](https://help.syncfusion.com/document-processing/word/word-processor/react/export)、[Collabora 集成 FAQ](https://www.collaboraonline.com/faqs/)、[Microsoft CSPP 申请流程](https://learn.microsoft.com/en-us/microsoft-365/cloud-storage-partner-program/online/apply-for-cspp-program)。

上述功能由厂商公开资料确认，不等于所有文档都能与桌面 Word 像素一致，也不等于选定套餐默认包含全部 API、移动端和协同权利。

## 4. 授权和费用：需要区分的几件事

| 方案 | 本次查到的公开信息 | 决策注意事项 |
| --- | --- | --- |
| ONLYOFFICE | 社区版 AGPLv3、无保证的 SLA；当前定价页列出 Docs Connect 为 8 美元/用户/月、Developer pack 为 12 美元/用户/月的附加项 | 这些附加项不能直接当成 PAMS 自部署生产版总价。旧 Developer 定价链接已重定向，搜索缓存中的 3,500 美元不能直接采用；需获取自部署生产用途的正式报价 |
| WPS | 本次未核实到可直接用于 PAMS 的官方统一集成总价 | 按云/私有化、并发、功能和服务范围询价；不要套用个人 WPS 会员价 |
| SuperDoc | 官网 Business 为 6,000 美元/年，范围为一个生产文档功能场景；Enterprise 询价 | 确认策划书、模板编辑和其他文档模块是否属于同一授权场景；免费社区许可为 AGPLv3 |
| Syncfusion | 官网 DOCX Editor SDK 标价 1,199 美元/开发者/年，最低 5 名开发者，即基础 5,995 美元/年 | 页面另要求选择文档/终端用户用量，实际费用可能更高；符合条件者可申请 Community License |
| Collabora | 官网 Business 展示 3.40 美元/用户/月，面向不超过 99 用户；更大规模询价 | PAMS 嵌入和教育机构适用的具体合同需确认；CODE 不应等同于受支持的生产套餐 |

来源：[ONLYOFFICE 社区许可 FAQ](https://helpcenter.onlyoffice.com/docs/faq/docs-community.aspx)、[ONLYOFFICE 当前定价](https://www.onlyoffice.com/pricing)、[SuperDoc 定价](https://www.superdoc.dev/#pricing)、[Syncfusion 定价](https://www.syncfusion.com/sales/pricing?category=document-solutions)、[Collabora 订阅](https://www.collaboraonline.com/subscriptions/)。

ONLYOFFICE 的官方 FAQ 建议不希望公开专有代码的产品采用商业许可。这里记录厂商的许可口径，不把所有 API 对接一概认定为同一种法律结论。正式选择时应把 PAMS 的内部使用、是否分发、是否提供 SaaS、是否修改编辑器等实际用途交给厂商确认。[官方许可说明](https://helpcenter.onlyoffice.com/docs/faq/docs-community.aspx)

Syncfusion 社区许可当前要求年总收入低于 100 万美元、开发者不超过 5 人、总员工不超过 10 人，并对外部融资有条件。学校或其他最终使用单位是否符合条件不能仅按开发团队人数推断。[Community License 条件](https://www.syncfusion.com/products/communitylicense)

Microsoft CSPP 的当前公开条款第 4(g) 对中国大陆用户或总部位于中国大陆的用户有明确限制。因此，这条第三方内嵌路线不适合直接用于当前大陆场景；这不代表所有微软中国产品都不可用。另行采购的本地产品需要单独确认许可及生命周期。[CSPP 条款](https://learn.microsoft.com/en-us/microsoft-365/cloud-storage-partner-program/legal/cspp-terms)

## 5. 页面如何延续现有设计

保留顶部活动与策划书操作区、中央文档区、右侧“审核意见 / 版本记录”。文档编辑器直接嵌入中央区域。

- 阅读时加载已保存文档的只读视图，隐藏编辑工具栏。
- 点击“编辑”后，在同一文档区切换到编辑器；工具栏固定在编辑器视口顶部，正文在内部滚动。
- “保存”必须等后端确认 DOCX 已持久化，再退出编辑并原地显示最新内容。
- “取消”放弃本次临时工作副本，重新加载上次保存内容并隐藏工具栏。
- 文档内批注与 PAMS 的“通过 / 驳回”业务审核分别处理；接受修订不自动代表审核通过。

工具栏、缩略图、导航窗格和厂商品牌能否按上述要求配置，需要在选定版本中验证，可能受到商业授权约束。不要直接修改跨域 iframe 内部 DOM。

## 6. 最关键的接入设计

### 文件版本与审核版本分开

业务版本仍为 v1、v2、v3；一次业务草稿可保存多次文件修订。每次保存文件不应自动新增一条审核版本，否则右侧时间线会充满自动保存记录。

建议为业务版本关联 `documentFileId`、内容哈希及文件修订号；编辑工作副本另记录所属用户、业务版本、初始文件修订号和状态。历史业务版本关联的已提交 DOCX 应不可变。

现有章节对比可从 DOCX 中派生文本后继续提供，但只能称为文本/章节差异，不能声称覆盖所有排版变化。完整格式差异需要验证编辑器原生比较能力和授权。

### 自动保存先进入工作副本

ONLYOFFICE 的保存包含异步回调，可通过 forcesave 获取编辑中的当前文件；WPS 也存在手动、关闭和自动触发的保存。前端看到“保存事件”不代表文件已经写入 PAMS。[ONLYOFFICE 保存流程](https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/saving-file)、[forcesave](https://api.onlyoffice.com/docs/docs-api/additional-api/command-service/forcesave)、[WPS 保存时机](https://open.wps.cn/documents/app-integration-dev/docs-center/online-preview-edit/callback/question)

要满足真正的“取消编辑”，编辑器自动保存应写入临时工作副本。用户点击 PAMS“保存”并收到持久化确认后才提交工作副本；取消时使工作副本失效，迟到回调不得覆盖正式文件。首期建议沿用作者单人编辑，后续多人协同需重新定义取消行为，不能丢弃其他人的修改。

### 提交审核需要建立保存屏障

建议流程：停止接受新的编辑 → 请求并等待最终保存 → 后端确认文件修订和哈希 → 原子地冻结该文件并创建审核记录 → 结束旧编辑会话。保存失败或超时则不提交审核，并保留可恢复的草稿。

提交后的迟到回调不得修改审核快照；驳回后修订生成新的业务版本及文档标识。不要只在前端把编辑器切成只读。

### 版本权限从后端贯穿到文档服务

审核人勾选规则继续由现有 `PlanAccessService` 等业务权限判断控制。未被选中的用户只能获得其可见历史版本的文件和编辑器配置；当前版本的文档地址、预览、下载、搜索索引和缓存入口均不得旁路泄露。

ONLYOFFICE 的 `document.key` 关联文档版本及缓存，同一 key 的用户共享编辑会话。不同业务版本必须隔离；同一有效协同会话则应共享正确的 key，不能每次请求随意随机化。权限配置由后端生成并签名，签名密钥留在后端；回调应验证签名、工作副本状态和预期修订。[React 集成中的 key 说明](https://api.onlyoffice.com/docs/docs-api/get-started/frontend-frameworks/react)、[JWT 安全机制](https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/security)

### 部署影响

ONLYOFFICE 路线需要独立文档服务，可作为 Compose 新服务接入。浏览器、PAMS 后端和文档服务之间需要正确配置下载地址、回调、反向代理与 WebSocket。官方社区 Docker 基础要求包括至少 4GB 内存和 40GB 空闲磁盘；这不是与 MySQL、后端共用服务器的整体容量承诺，生产容量应按实际文档和并发压测。[官方资源要求](https://helpcenter.onlyoffice.com/docs/installation/docs-community-sys-reqs-docker.aspx)

WPS 云模式需要厂商服务能够访问回调与下载地址。文件由 PAMS 管理不等于文档内容不会送往云端处理；内网和数据驻留要求应在选型时确认。[WPS 回调网络要求](https://open.wps.cn/documents/app-integration-dev/docs-center/online-preview-edit/callback/question)

中文字体应统一部署并核实使用权，特别检查宋体、仿宋、黑体的替换、行距和分页。不同字体度量可能改变目录页码与跨页表格布局。

## 7. 建议验证顺序与验收标准

先用 ONLYOFFICE 商业试用建立最小验证，再用同一组文件对照 WPS。尚未确认授权前不安装付费正式版、不替换现有生产编辑器。[ONLYOFFICE 自部署试用入口](https://www.onlyoffice.com/for-developers)

使用 5—10 份真实策划书副本，覆盖封面、自动目录、多级标题、页眉页脚、合并单元格预算表、跨页表格、图片环绕、批注和修订。执行“导入 → 编辑 → 保存 → 刷新 → 下载 → 桌面 Word 打开”的完整往返。

| 验收项 | 通过条件 |
| --- | --- |
| 文档兼容 | 关键内容和样式保留，导出无需 Word 修复；分页变化逐项记录并评估 |
| 保存/取消 | 保存后刷新可见；取消不影响上次保存；断网与失败可恢复 |
| 审核一致性 | 提交冻结准确文件；迟到回调无法覆盖审核版及历史版 |
| 可见范围 | 未选审核人无法通过页面、文件下载、编辑器配置和索引获取当前版本 |
| 页面交互 | 正文区原地编辑，工具栏固定；右侧审核意见和版本记录可用 |
| 并发与性能 | 多标签、重复回调、长文档及预期并发下无丢失、串版和错误覆盖 |
| 授权与费用 | 明确生产用途、用户/连接口径、移动端、品牌、API 和后续扩容费用 |

## 8. 迁移范围

前端替换策划书的富文本编辑内核和导入导出主链路，保留外围工作台；后端增加文件关联、工作副本、编辑器配置、签名下载、保存回调及提交保存屏障；部署新增所选编辑器依赖。

既有策划书可先由现有导出器生成初始 DOCX，并保留原字段用于回溯。新模式启用后，DOCX 正文只从文件修订读取；章节摘要异步提取，不能再反向覆盖正文。业务元信息与文档内活动信息的同步规则须单独定义。

最终建议：以真实模板往返效果、可靠保存、审核版本权限和正式授权报价共同决定选型。当前阶段 ONLYOFFICE 最值得先验证；WPS 是重点商业对照；SuperDoc / Syncfusion 留作组件化集成的备选。
