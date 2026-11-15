# Agent 六个任务包收口

日期：2026-11-03。用户授权推进六个任务包；此文件记录实际证据，不把实施清单当作验收结果。

后续更新：用户选择处理账户状态后继续deepseek-flash；本次恢复探针仍返回HTTP402，未继续其余模型请求。本地边界修正及37项回归通过，详见[恢复检查记录](resume-20261103.md)。

| 任务包 | 出口 | 状态 |
| --- | --- | --- |
| 1 浏览器 E2E | 真实登录、确认卡、刷新/断线恢复、重复提交、过期基线、送审/指定发布及权限隔离 | 本轮10项全部通过，含原生DOCX单处预算修订、原稿哈希保留、新草稿唯一创建和页面正文显示；其他跨确认业务场景仍按各自范围补验 |
| 2 A0 真实模型质量 | 40–60 条版本化场景，记录模型、调用和失败类型，覆盖两条执行路径及文档/任务/通知 | 建立28场景×2路径的56样本基线，56/56自动检查通过；内容复核发现问题，修正后模型复测待账户恢复 |
| 3 A4 规划收益 | 实际执行对照、事实一致性、完成率、时间/调用开销、受控重规划边界 | 6组已运行记录，前3组成对有效，后3组受HTTP402中断；无发布收益结论，动态改图未开放 |
| 4 T0 工具契约 | 每工具对象策略、完整性、错误、预算、幂等性和可见范围诊断 | 47工具均有逐工具策略说明，诊断API/UI已接入；9项持久回执写操作和8项旧门禁写操作区分。旧错误细分/恢复能力及完整主体×对象矩阵仍待补 |
| 5 A1 运行上下文 | 时区来源、动态预算、任务约束、版本基线和可恢复的 Prompt/角色快照 | 新Run持久化资源/角色全文快照与时区，跨JVM恢复通过；专家内置角色资源化，循环动态剩余轮数已接入。结构化活动约束/选定实体/通用基线、总次数/时间/费用硬预算仍未全部接线 |
| 6 A2 文档保真 | 实际DOCX渲染对比、目标金额准确、其他内容保留、原件保留与格式边界 | 支持范围内样本WPS原生渲染与全部页面检查通过；预算数字外像素相同。6份实际文档及1份常见模板文件明确拒绝。Microsoft Word和复杂格式未验收 |

已有 MySQL 并发、真实JVM强杀恢复及926项回归证据见[上一轮验收](../agent-acceptance-20261102/README.md)。新增任务不重新开放储备写能力，不改变生产受控执行默认开关。

## 本轮证据

[最终交付证据汇总](delivery-evidence.json)同时记录本地检查、当前资源指纹和未完成状态；当前指纹不反向冒充早期模型样本的输入快照。

- [56个真实样本](quality-hardened/report.json)与[28个场景定义](quality-hardened/cases.json)：实际普通循环和专家循环，真实工具访问隔离内存H2，仅合成业务数据。观测到111次模型调用，全部返回用量，无失败/降级；费用未推测为账单。较早的48样本及上下文预算失败也分别保留。
- [内容复核](quality-semantic-review.md)：时间误标、稿件/策划书审核人规则混淆、原文来源误解等明确保留。自动通过不代表语义全通过，不据此开启生产。
- [实际执行对照](execution-comparison/report.json)与[汇总](real-model-summary.json)：前3组均完成，受控调用数分别为1/1/1，规则为2/4/2；用时并非全部更快。仅这些小样本不足以证明普遍收益，后3组HTTP402不可用于质量比较。写操作收益尚未评测。
- [隔离MySQL最终45项](../agent-acceptance-20261102/batch6-mysql-e415f8a2ceca/test-summary.json)：7个专项类，全部通过、无跳过；包括V65迁移、会话长文本回查、无效Prompt包阻止工具调度，以及[跨进程恢复](../agent-acceptance-20261102/batch6-mysql-e415f8a2ceca/process-recovery-evidence.json)保留提示词包/时区。[清理回查](../agent-acceptance-20261102/batch6-mysql-e415f8a2ceca/batch6-isolation.json)剩余库/账号均为0。
- [最终浏览器10项](../agent-acceptance-20261102/batch6-mysql-6385cc707c02/browser-results.json)：在独立MySQL和正式HTTP/页面中通过，包含成果栏真实回执状态断言。[原生DOCX页面](../agent-acceptance-20261102/batch6-mysql-6385cc707c02/DOCX_REVISION-native-document.png)显示3000元及保留段落；重复确认不另建版本，旧原件哈希及其他ZIP部件均保持。[本轮库/账号清理](../agent-acceptance-20261102/batch6-mysql-6385cc707c02/batch6-isolation.json)剩余均为0。合成原稿和新稿留在本地独立验收目录。
- [后端全量回归](backend-regression-summary.json)：933项，913通过、20项显式专项跳过，失败/错误0。其后新增的策略撤销守卫由45项MySQL及10项Prompt/渲染专项补验。前端35文件227项通过、生产构建通过。
- [工具契约快照](tool-inventory.json)：47个工具，30 READ/17 PREPARE；对象策略仅描述现有代码，不授予权限、不替代业务校验。默认未知错误不自动重试。明确声明的来源完整性和渲染裁剪分开，实际裁剪撤销本轮全文完整性承诺。
- [文档像素/包验证](document-visual/verification.json)及[逐页复核](document-visual/visual-review.md)：仅`word/document.xml`变化，原有XML语义除预算文字完全一致，其余ZIP部件字节一致。PDF元数据为WPS文字，不能写成Microsoft Word通过。

## 修复和新增边界

工具数量增到47后，原32K保守UTF8字节预算不能容纳完整工具菜单及后续真实回执，评测发现后将默认预算调整为64K。保留工具说明/Schema及原生协议，不截断或删除参数约束；输出上限不因此扩大。用户指定模型的公开能力参考[DeepSeek模型说明](https://api-docs.deepseek.com/quick_start/pricing/)，应用仍按自身预算限制，不能据本轮配置假定任意服务商容量相同。

会话长文本此前被JPA映射成CLOB，Hibernate7拒绝对其做忽略大小写检索；保留数据库MEDIUMTEXT并去掉错误的locator映射，H2和MySQL均验证长文本、大小写、特殊字符及归属校验。策划审核人候选改为白名单Map字段，避免在模型通道丢成“未展开对象”。新增服务端UTC/用户时区双时间值，并明确稿件送审不需要策划书的reviewerIds；这些修正尚待真实模型复测。

V65只向Run增加提示词包和用户时区。包中保存资源原文/指纹及当时领域角色文本；新Run固定包，派生上下文/权限刷新不丢失。资源缺失、指纹不匹配或公共策略撤销时，节点执行与确认都在副作用前停止。旧Run没有包时明确标为未固定资源，不能承诺精确重现。总时间、工具调用总次数和费用没有硬上限配置时返回未知，不能把已配置最大轮数冒充其他剩余预算。

浏览器发现成果详情刷新期间仍展示旧“尚未写入”标签：现在当前版本采用不早于详情的服务端目录回执，并关闭已执行版本编辑入口；历史选中版本保留自己的已取消状态。组件用例和浏览器新增了真实回执后的状态断言。另将AI服务配置页的3处字号接回既有设计刻度，修复全量前端样式检查。

## 复跑与后续

在仓库根目录运行隔离库启动器；凭证仅在进程内存/子进程环境中使用，不输出或保存明文：

```bash
java --class-path C:/Users/zhara/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar scripts/agent-s5/BatchSixAcceptance.java
node scripts/agent-s5/summarize-batch6.cjs docs/audits/agent-acceptance-20261102/batch6-mysql-<本次后缀>
# 浏览器专项：启动器另建临时库，使用正式登录/接口，不调用外部模型
java --class-path C:/Users/zhara/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar scripts/agent-s5/BatchSixAcceptance.java --browser
```

模型账户恢复后再显式运行`AgentQualityRealModelTest`与`ControlledExecutionRealModelTest`；本轮已停止追加真实模型请求。先重测内容问题和E04–E06，再补跨确认任务/通知、真实DOCX及跨产物事实一致性。其余任务以表中未完成出口为准，六个任务包没有被整体标成完成。未部署、未提交，也未修改原业务库或服务商配置。
