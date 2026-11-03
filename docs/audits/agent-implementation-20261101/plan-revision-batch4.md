# 第四批预算定向修订

日期：2026-11-01。交付为受限首版后端闭环，不代表通用文档编辑或生产验收完成。

## 实际支持范围

用户当前原始消息须明确匹配“只把预算总额改为3000元，其余保留”一类请求；金额可变化。服务端从当前 stream 对应的真实 USER 消息读取目标，不接受模型自填 instruction 来扩大范围。请求若包含其他要求、目标不明确或没有可追溯消息则拒绝准备。

仅支持当前作者有权修改的、非审核中的原生 DOCX 当前版本。小于等于1MB、正文最多500个读取块、完整连续读取后，才可进入修订。原文必须有唯一的“预算总额为2500元”或“预算总额：2500元”文本目标，首版金额使用阿拉伯数字，最多7位整数、2位小数，目标须大于0。只替换授权数字，其他字词不允许变化；存在其他金额或分项/费用内容时返回冲突，不擅自平衡分项。

格式白名单仅包含简单段落、文字样式及页面设置节点，实际目标须匹配一个完整文字节点。表格、图片、页眉页脚、内容控件、字段、修订痕迹、嵌入对象、签名/宏/非白名单包部件，以及跨多个文字节点的替换均拒绝。旧 HTML 不支持修订。历史版本只读，不会绕过当前版本作者规则。

## 处理链路

1. readPlanDocument 从授权原件读取，并保存用户、组织、会话绑定的不可变字节/原文块快照。覆盖清单只按连续页推进；直接跳到末页不能声称已读全文。超过快照预算仍可只读，但 documentRef=null、revisionEligible=false。
2. preparePlanRevision 校验 planId/documentRef/baseline、完整覆盖、原始用户目标、块ID、旧文及指纹。基于原始 OOXML 验证变更可应用后，保存服务端变更集并返回真实前后差异，尚不写 activity_plan 或生成附件。
3. 复用 TaskGate/ArtifactLedger 的确认流程，用户确认完整单处变更集。新工具不支持自由改写预览；修改范围需重新准备。
4. 确认时重新检查权限、任务归属/RUNNING状态、原版本访问和作者范围；锁定变更集与活动，重新读取实际原件并校验 revision+内容哈希。过期基线拒绝。
5. 仅替换原件 word/document.xml 中的目标文字节点，其他 ZIP 部件的解压内容逐字节保留。创建独立 PLAN 附件与一份新 DRAFT，保留原稿与冻结元数据；不更新活动、不提交审核。
6. 新版本ID与变更集回执在同一数据库事务提交。同一变更集重复或并发提交复用该回执；不同变更集若基线已失效则拒绝。异常提交结果无法确定时返回 RESULT_UNKNOWN，不能推断未执行并自动重试。

## 持久化与失效

V61 新增 agent_plan_snapshot 和 agent_plan_revision。快照保存2小时，进程重启后可从数据库恢复，不依赖本地缓存；用户/组织/会话不匹配不能读取。每10分钟清理过期快照及尚未提交的关联变更；已提交回执保留。过期后不能凭旧快照发起写入，已成功任务重复确认仍使用 TaskGate 已保存结果。

新建附件在数据库事务回滚时尝试删除新文件，绝不删除原件。进程在文件生成后、事务结束前崩溃仍可能留下未引用文件；文件系统与数据库不是同一事务，孤立文件回收仍需后续运维能力。RESULT_UNKNOWN 的自动回执核对/恢复属于后续连续执行批次，本批只停止重复写入并明确待核对。

planning 默认作用域增加 preparePlanRevision；仅精确匹配此前默认 scope 的启用配置自动迁移，自定义与禁用配置保留。工具总数26，见 [本批契约快照](tool-inventory-batch4.json)。

## 验证

运行：

```bash
mvn -q -f pams-backend/pom.xml -Dtest=PlanRevisionIntegrationTest,BudgetDocxRevisionTest,PreparePlanRevisionToolTest,ToolInventoryTest,AgentDefinitionUpgradeTest,PromptRendererTest,AgentPlanVersionTest,PlanDocumentReaderTest,AgentPlanReadServiceTest,AgentPlanReadAccessTest,SpecialistPipelineTest test
```

结果：31项不同测试通过，失败、错误、跳过均为0。完整回归命令先通过30项，补充确认时原始目标重检后再运行8项 PlanRevisionIntegrationTest，通过新增目标变化拒绝场景。覆盖真实 H2/Flyway V61、原生文件存储/读取、只预览不写业务表、真实确认令牌门禁、重复确认与并发提交只新增一版、旧稿原字节不变、新稿文字正确及加粗/颜色保留、未修改 ZIP 部件逐字节相同、权限不足/取消任务/跨用户与会话/过期快照/覆盖跳页/过期基线拒绝、错误授权金额与分项冲突、旧工具及作用域迁移回归。

尚未完成：真实 Word/浏览器渲染对比、真实用户文档、真实模型选工具与修订质量评测、MySQL 并发部署验收、故障注入后的自动回执核对。上述范围外不开放修订，不能将本批称为通用 DOCX 格式保真或完整 T1/A2 验收。
