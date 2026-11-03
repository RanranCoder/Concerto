# 第三批文档读取技术验证

日期：2026-11-01。范围：后端只读工具和自动化格式探测，不是文档修订写入验收。

## 已开放能力

| 工具 | 定位与权限 | 输出边界 |
| --- | --- | --- |
| getActivityDetail | activityId；activity:view；当前组织校验 | 基本事实，不带成员和敏感子模块。活动无可靠 revision，明确 revisionAvailable=false |
| listPlanVersions | activityId；activity:view + plan:view；PlanAccessService 对象策略 | 先过滤不可见版本，再按版本/ID 倒序分页，每页5条；含作者、状态、revision；最新可见不冒充最新已通过 |
| readPlanDocument | planId；同上；每页重新鉴权 | DOCX 正文段落及表格单元格，或原样分段的旧 HTML 字段；每页4块，每块最多600字符 |

三个工具不支持 FORM，不写业务表。活动、版本标识需先明确，不猜 ID。原生文件读取经 PlanAttachmentService 执行文件组织校验，禁止模型提供路径。

## 基线与分页

baseline 由版本 ID、revision 和实际文件字节哈希生成；旧文档基于全部正文及覆盖字段序列化哈希。同一草稿保存以及内容发生变化时旧基线失效。续读必须带 expectedBaseline 与 nextCursor；基线变化返回 STALE_BASELINE，从第一页重读。

块地址由正文顺序/行列/分片序号组成，仅在该基线内稳定，不是跨版本段落 ID 或可执行编辑句柄。documentRef 为用户/组织/基线的哈希引用，不是授权凭据。当前每页重新读取和核验来源，没有持久化不可变快照或全文覆盖台账；不能据连续分页声称获得修订提交资格。

completeness.complete 仅在本次从0覆盖全部正文块且未检测到不支持元素时为 true；末页不自动升级为全文已读。原生正文与旧 HTML 都保留来源类型；旧 HTML 明确不是原生 DOCX。所有读取结果 revisionEligible=false。

## 格式探测范围

自动化测试生成内存 DOCX，验证普通段落、加粗文本、简单表格单元格的原文读取与重复地址稳定，读取前后原字节完全相同，加粗标记仍在。长段落拆分后拼接与原文一致。这仅证明读取未改写原件，不证明未来修改后格式保真，也不是 Word/浏览器的视觉渲染验收。

检测到图片、drawing/pict/object、内容控件、插入/删除修订、字段、altChunk、合并单元格、文本框、嵌套表格、页眉页脚、脚注尾注等时返回 unsupportedElements。检测不是完整 OOXML 特性白名单；复杂文档应回原生编辑器核对。无论检测结果如何，本批都不开放变更提交。

限制：文件最大20MB、最多10000个读取块；无效/加密 DOCX 返回 UNSUPPORTED_FORMAT，不以空正文报成功。列表复用现有仓储读取并在鉴权后内存分页，版本很多时仍需后续数据库授权分页优化。没有修改原活动综合查询的前200条截取问题。

## 验证记录

首轮发现并修正：空修订标签识别遗漏；旧测试要求迁移已禁用角色。现在禁用定义不迁移，仅精确匹配此前内置作用域的启用定义添加新读能力，自定义 scope 和角色文本保留。

运行测试：PlanDocumentReaderTest（3）、AgentPlanReadServiceTest（4）、AgentPlanReadAccessTest（1）、ToolInventoryTest（1）、AgentDefinitionUpgradeTest（3）、AgentScopeTest（2）、AgentPlanVersionTest（1）、SpecialistPipelineTest（4），合计19项不同测试通过。新增访问策略测试使用真实 PlanAccessService，验证当前版本非作者拒绝、跨组织拒绝、权限撤销后再读拒绝。H2 专家集成回归通过，但不代表真实模型选工具验证通过。

更新后的 [25个注册工具快照](tool-inventory-batch3.json) 保留旧22工具快照供对照。尚未进行真实用户文档/真实模型评测、生产权限配置验收或部署。
