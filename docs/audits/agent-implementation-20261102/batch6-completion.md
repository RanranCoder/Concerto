# 第六批后端交付与验收边界

日期：2026-11-02。范围：A4 受控规划执行基础、T3 宣传与送审、T4 可开放扩展。本文承接首个预览增量，不把后端实现、合成模型评测和生产验收混为一项。

后续验收更新：已完成 [第六批隔离MySQL与进程恢复验收](../agent-acceptance-20261102/README.md)，43项全部通过，并修复评分汇总的MySQL驱动兼容及普通中断任务状态；完整后端回归926项中909项通过、17项专项默认跳过、0失败/错误。以下原交付时的验证记录保留历史语义。

## 交付矩阵

| 范围 | 已实现 | 边界 |
| --- | --- | --- |
| 受控规划 | 独立规划调用、服务端校验、一次修复、规则回退、服务端规划句柄 | 普通 `/team/run` 继续采用原有规则规划 |
| 持久化执行约束 | 保存节点能力、实际 Schema、输入绑定、完成条件、固定完成策略、Prompt/目录指纹；每次尝试保存契约及解析参数 | 客户端不能提交自定义图、工具授权或参数覆盖 |
| 参数与事实接续 | USER 连续原文按实际类型解析；STEP 仅从直接成功上游的 `payload` / 确认业务回执 `detail` 取值 | 不从专家说明、摘要、失败结果、敏感原文或未确认预览中猜测参数；字段缺失停止该分支 |
| 执行门禁 | 节点精确工具与解析参数限制、当前权限/专家 scope/Schema 再校验、原 TaskGate 确认和业务幂等回执 | 读成功与业务确认成功分开；模型的自然语言完成条件不作为自动审批或语义正确性证明 |
| 局部重规划 | 同目标、会话、模式、专家及依赖结构下，凭新服务端句柄替换一个节点；该节点和下游增加 revision、清除旧产物并取消旧确认 | 保留上游与独立分支、保留旧尝试证据；已有业务提交或结果未知的分支禁止重做。改变图结构须另建任务 |
| 稿件读取/修订 | `readArticleDocument`、`prepareArticleRevision`，Article 原生编辑与 Agent 共享 `@Version`；真实单处文本差异、确认再鉴权/CAS | 完整保存正文合计最多16000字，不含附件；仅 title/summary/content 唯一、不含 HTML 标记/实体的 before→after 替换，不是通用富文档重写 |
| 组织送审 | `prepareArticleSubmission`、`preparePlanSubmission` | 精确对象/版本；策划审核人来自现有候选服务，不默选。提交只进入 PENDING，不等于批准或发布 |
| 审批状态 | `getArticleReviewStatus`、`getPlanReviewStatus` | 返回当前业务状态、可见意见及版本；不把 AI 内容审校当作组织审批 |
| 知识证据 | `searchKnowledgeEvidence`、`readKnowledgeEvidence` | 复用 KnowledgeQuery、回源权限复核及出网策略；证据句柄绑定用户/组织/会话，15分钟有效，续读重新核验内容哈希及权限；仅片段/单块，不冒充整篇来源 |
| 活动执行只读 | `queryActivityAgenda`、`queryActivityScores`、`queryActivitySchedules` | 议程/排班分页；评分仅数据库已录入汇总及规则，不返回人员评分明细、不推断缺失评分或参评人数；排班提供 revision/完整班次人员基线，草稿与已发布分开 |
| 班次发布 | `prepareSchedulePublication` | 明确1–20个班次和各自基线，不默认 publish-all；按 ID 排序锁定，重新核验班次与人员后原子调用原生发布/考勤/通知流程；通知是否送达须另外核对 |
| 材料/公告/座位 | `readMaterialDocument`、`queryAnnouncements`、`querySeatLayout` | 材料先过 FileService 原生授权，只读白名单类型的 UTF-8 文本/DOCX、2MB上限、分页及文件哈希，不解析 OCR/PDF/压缩包；公告不自动已读/确认；座位返回登记单元格，不凭网格尺寸推断可用容量 |

工具总数由32增至47，新增15项（11 READ、4 PREPARE）；当前30 READ、17 PREPARE，见 [实际工具快照](tool-inventory-batch6-complete.json)。searchArticles 增加类型、活动与分页，明确 Article 与 News 不同，元数据检索不代表已读正文。默认 scope 仅升级已识别的旧默认配置；自定义/禁用配置不覆盖。额外修正“全部角色禁用后重新启用内置回退”的缺口：只有定义表确实为空才使用内置目录。

稿件修订、两类送审和班次发布使用 AgentPreparedOperations 保存快照及同事务成功回执，均接入 ActionReconciliationService。结果未知时只核对已保存回执，不重放写入。业务门禁不受 Prompt 或模型计划覆盖。

## 接口与开关

- `POST /api/agent/team/plan/controlled`：预览，不保存执行句柄。
- `POST /api/agent/team/plan/controlled/prepare`：输入现有 TeamRequest，只有 MODEL_PLAN_VALIDATED 才返回用户/组织/会话/模式绑定的 `planRef`；15分钟内可启动。澄清或回退不返回可执行句柄。
- `POST /api/agent/team/run/controlled`：`{planRef, conversationId, toolMode}`，只接受服务端句柄。重复启动同句柄返回同 runId；权限变化仍由调度器阻止续跑。
- `POST /api/agent/team/runs/{id}/nodes/{nodeId}/replan`：`{revision, planRef, conversationId, toolMode}`，原图内局部替换并失效受影响的旧确认。旧的自然语言 revise 不得改写受控节点授权。
- `POST /api/agent/bench/planning/compare`：`{goals:[...]}`，需要 `org:audit:view`、benchmark 开关及隔离组织名单；最多10条合成目标。只对照计划，不运行业务工具、不创建业务 Run。

两个规划开关默认均为 false。隔离环境可显式配置：

```yaml
pams:
  agent:
    controlled-planning-enabled: true
    controlled-execution-enabled: true
    controlled-planning-org-ids: [1]
```

这只是隔离环境运行方式，不是生产启用建议。受控执行每个节点及确认时继续检查开关；关闭后不能借历史确认继续提交。发布、通知发送、成员/账号创建等不因存在工具就自动进入首版模型规划准备白名单。

规划最多2次调用（一次可选修复）、每次最多2048输出 token及15秒。输入按现有保守 UTF-8 字节预算检查，不裁断 JSON/原生协议状态；完整能力 Schema 按工具去重，专家/工具组合另列，避免重复目录挤满默认上下文。预算不足、服务不可用、降级、截断或非法计划均返回显式规则基线，不暗中重试。

受控节点首版不允许表单编辑扩大原始绑定参数：若原生工具执行后仍要求 FORM 补参，该节点明确失败，不创建无法提交的表单/确认任务；用户须补齐明确目标后重新规划或走普通办事流程。普通链路既有 FORM 能力保持兼容。

`completionCriteria` 保存模型提出的目标；服务端实际完成策略固定为 READ 的 `TOOL_RESULT_OK` 或写入的 `CONFIRMED_BUSINESS_RECEIPT`。这些策略证明工具执行/提交状态，不宣称自然语言目标已得到语义验证。评测接口固定返回 `qualityVerdict=NOT_ASSESSED`、`releaseEligible=false`，成本未知时不伪造费用。

## 数据迁移与兼容

V64 增加 Article/Schedule 并发 revision、节点 contract/result JSON、受控规划句柄和短期知识证据引用。历史规则节点的 contract 为空，沿用旧调度逻辑；现有单 Agent、规则 Run 与确认入口继续兼容。该迁移只在测试环境验证，本轮未对现有开发/生产 MySQL 执行迁移。

材料正文和座位登记标签使用 SENSITIVE 策略：仅有界、经出网脱敏的数据进入当轮模型，持久化保持既有敏感摘要策略。受控节点不把敏感原文另存为可绑定的结构化成功回执。

## 自动化与真实模型证据

默认完整后端回归命令：

```bash
mvn -q -f pams-backend/pom.xml test
```

最终全量919项中908项通过，0失败、0错误、11项跳过（既有10项环境专项，加1项需要显式网络授权的真实模型测试）；见 [后端测试汇总](batch6-backend-test-summary.json)。真实模型专项另行显式运行并通过，结果见下一段，不用默认跳过代替实际验证。末次执行护栏 [33项专项](batch6-final-guards-summary.json)也全部通过。

测试覆盖原生编辑并发失效、角色/模式/组织隔离、精确工具与参数约束、缺失结果字段失败、固定图局部重规划、取消旧确认、确认后依真实业务回执接续、未知结果不重放、班次指定发布、文件授权/路径/格式/哈希及DOCX展开预算、证据回源与敏感出网、全目录预算和原规则/DAG/工具门禁兼容。`git diff --check`通过。

用户指定 `deepseek-flash` 后，真实模型评测只读当前本地部署的指定服务商配置，将凭证在内存中解密并重新加密到强制使用内存 H2 的隔离环境；不输出凭证，不修改部署配置，不调用业务工具。服务商为现有配置的 `https://api.deepseek.com`、`openai_responses`。

六条合成目标及首次结果见 [真实模型首轮](batch6-real-planning-run1.json)：5条匹配预设能力/澄清要求，稿件送审场景未成功返回后明确回退规则基线，没有自动重试或业务写入。[单独复跑](batch6-real-planning-run2.json)仍未成功；[诊断复跑](batch6-real-planning-run3-diagnostic.json)将已知 Responses 未完成响应单独标记为 MODEL_OUTPUT_INCOMPLETE，和超时、连接/协议错误区分，未知错误仍不回显上游消息。

随后收紧 task/完成条件/约束的冗余输出文字，保持2048 token、15秒及最多一次修复的同一预算重新运行完整六条。结果见 [最终真实模型对照](batch6-real-planning-final.json)与 [专项测试结果](batch6-real-model-test-summary.json)：6/6匹配预设能力或澄清要求，全部仅1次模型调用，均有完整用量报告，无降级/失败；单次耗时约2.37–10.03秒、输出348–1699 token。失败历史未被最终成功覆盖，不据这一次小样本推断常态性能或业务收益。

显式复跑命令：

```bash
PAMS_TEST_AGENT_PROVIDER=openai PAMS_RUN_CONTROLLED_PLANNING_REAL=true PAMS_REAL_MODEL=deepseek-flash \
  mvn -q -f pams-backend/pom.xml -Dtest=ControlledPlanningRealModelTest test
```

`PAMS_REAL_CASES=A1_SUBMISSION` 可仅复查稿件送审场景；复跑仍采用相同预算。该专项测试默认跳过，需要本地指定模型配置、原始加密主密钥及只读部署配置连接；写入目标被固定为内存 H2，并在读取部署配置前再次核验。它不证明真实业务执行收益，也不替代生产权限/故障恢复验收。

## 仍未认定完成的验收与储备

1. A4 要求的“比规则基线在真实复合业务执行上有收益”尚未证明。当前对照只验证合成计划的能力/澄清选择与调用预算，不以节点更多或 JSON 合法推断业务收益；生产受控执行保持关闭。
2. 隔离 MySQL 的本轮迁移、并发、幂等、事务与JVM恢复样本已通过；使用本轮新建并清理的专用库/账号，不复用现有业务库。浏览器业务验收仍待执行；数据库专项通过不替代浏览器或生产验收。
3. 设计目录标为储备的议程变更、换人/班次调整、稿件发布，以及考核改写、排班取消、组织审批决策等，不在本次开放工具内。它们仍须各自完成服务提取、对象授权、并发、幂等和业务规则验证。
4. 跨分支语义一致性、动态改变依赖图的重规划、任意富文档变更和前五批遗留验收继续保留，不因第六批后端交付而被标记为已完成。

未部署、未提交。默认执行入口与生产开关保持原状。
