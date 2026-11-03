# 第五批功能补齐记录

日期：2026-11-02。结论：第五批约定的后端能力已实现；本地确定性验收与外部发布验收分开记录，不据此认定真实模型、MySQL 或生产部署已验收。

## 交付矩阵

| 能力 | 实现与验证落点 |
| --- | --- |
| 成员定位 findMembers | 按姓名/部门/届别过滤、每页5条、同口径总数；只返回最小身份。memberId、userId、accountDeptId 分开，学号仅服务端严格关联，不输出学号/电话。重名不默选，多账号返回歧义 |
| 组织定位 listOrgUnits | 当前组织部门、实际成员岗位代码、通知受众角色标签分别输出，各5条；岗位代码不冒充账号角色ID，不导出角色权限或配置 |
| 任务查询 queryActivityTasks | 行中有revision、assigneeUserId；分页5条、totalMatches/hasMore、整体planBaseline；页内数量不冒充总数，末页之外不假称没有任务 |
| 任务进度 prepareTaskProgressUpdate | 沿用上个增量：本人/管理范围、确认、revision校验、幂等及真实回执 |
| 任务计划 prepareTaskPlanChange | 单项新增/显式字段修改，缺省字段保留，修改校验目标revision及整图基线；复用TaskService.preview/savePlan；不支持删除、撤销或隐式级联 |
| 任务建议 generateActivityTasks | 支持1–10项明确建议及标准模板，整批预览、整批确认、同事务原子保存；同名默认跳过，显式要求重复也须区分名称或改为修改；批中后项失败回滚前项 |
| 受众 resolveNotificationAudience | userIds/deptIds/roleCodes有界筛选，必须明确UNION/INTERSECTION，同组内为并集；不默认全员。最多200人、15分钟快照，仅返回数量和句柄，不因有发送权限就暴露名单 |
| 通知 sendNotification | 新请求保存受众/内容绑定的预览，确认时重查集合；成员新增、停用或范围变化需重新预览。内容不静默截断；提交Outbox才报“已提交投递”，不报已阅读 |
| 投递核对 getNotificationDeliveryStatus | 仅同组织发送者可查；提交、Outbox处理、入箱、信号、阅读分别统计；materializationComplete与allInboxDelivered分开，失效对象被跳过不等于全部送达 |
| 回执与跨确认接续 | 原有核对接口新增任务计划、任务建议和通知适配。仅依据同事务持久化的真实回执结算；缺少回执保持待核对，不重做写操作。复用原有DAG、权限上限和事务后Outbox接续 |

## 并发与副作用控制

V63 增加 agent_business_operation 与 agent_notification_audience。服务端生成 operationId，用户、组织、工具种类、内容和基线均绑定持久化记录；确认只读取记录，不接受客户端覆盖内部提交参数。

任务计划先锁操作记录及活动，再刷新实体校验整体基线。单项变更返回真实前后字段；若需影响其他任务日期则拒绝并指向原生计划界面。批量建议只允许对已有任务的依赖，不提供新任务之间的临时ID映射。确认后内部逐项调用原生preview/savePlan，整批处于同一事务，回执同事务保存。

通知确认先锁操作记录和快照，重算同一选择规则并比较完整接收集合。NotificationPipeline.recordFrozen 使用 `agent:operationId` 稳定事件键和固定用户集合，复用既有 Outbox/Inbox/Delivery；不同操作即使标题正文相同也不误合并。投递时继续复核账号和业务可见性，提交后新增成员不扩大发送。过期受众快照每10分钟清理，成功操作回执保留。

TaskGate仍负责一次性令牌、确认归属、取消及抢占。对象权限在业务适配层重查；写工具在只读/知识模式不可见。意外提交异常保留 RESULT_UNKNOWN，不能从异常推断没有副作用。

## 兼容与明确限制

- 旧 name/schema 参数保留。sendNotification 的旧单一选择条件可自动产生快照；多个旧选择条件必须显式指定组合方式。优先使用 resolveNotificationAudience 的句柄。
- 新通知首版不接受未经校验的 entityType/entityId 绑定，避免猜测链接或绕过对象可见性；需要业务链接时走已有原生业务通知界面。
- 已持久化的旧通知/模板任务清单保持原提交语义，不静默改写旧plan_json；旧清单没有新操作回执时只能人工核对，不把它们纳入新幂等保证。
- 排班/事务专家增加完成此闭环的最小查询能力，但只对实际持有对应权限的用户显示。仅迁移精确匹配已知旧默认scope的启用记录，不恢复禁用项、不覆盖自定义scope或角色文字。
- 本批没有增加组织审批决策、账号创建/授予权限、删除任务、自动撤销、通用MCP或无限自主规划。任务关联日期级联交回原生界面，不擅自改变其他任务。
- 业务通知已受理不等于下游业务全部完成；需要确认投递或阅读时，必须依据状态查询的真实字段，不能把DAG节点SUCCESS当作收件人已读证明。

## 验证与复跑

默认离线全量：

```bash
mvn -q -f pams-backend/pom.xml test
```

结果：共864项，854项通过、10项按环境条件跳过，失败/错误均为0。末次目录语义调整后另跑2项目录集成测试。机器可读结果见 [后端测试汇总](backend-test-summary.json)，当前32个工具契约见 [工具快照](tool-inventory-batch5-complete.json)。跳过项不计作验收通过。

专项类：AgentTaskPlanIntegrationTest、AgentNotificationIntegrationTest、AgentDirectoryReadTest，以及此前的任务进度、策划书、DAG、通知服务/策略/提醒/权限回归。覆盖预览不写业务表、真实确认门禁、低权限/跨组织/取消/过期拒绝、整图基线冲突、并发同操作一次写入、批中失败整体回滚、受众扩张/停用重新预览、Outbox实际入箱、独立阅读状态、无回执不重发及遗留工具兼容。

全量首跑发现3条LongTermMemoryTest失败：它们把beginTask/endTask记账误判为模型推理调用。本次只修正测试口径，分别断言callLlm/callIsolated/callSummary从未执行，保留“记忆命令及无证据问答不能调用模型”的真实约束，没有修改记忆业务行为。

旧S5 21工具基线矩阵改为显式命名清单，并额外输出未覆盖的新工具集合，避免将注册工具增长误判为旧矩阵已覆盖所有工具。32个当前注册工具的契约由ToolInventoryTest核对。

### 隔离MySQL复跑

在已配置的独立 `pams_s5_*` 库和回环地址上设置 PAMS_S5_DB_URL、PAMS_S5_DB_USER、PAMS_S5_DB_PASSWORD；不要提供生产库。然后显式启用：

```bash
PAMS_S5_BATCH5=true mvn -q -f pams-backend/pom.xml -Dtest=AgentTaskPlanIntegrationTest,AgentNotificationIntegrationTest,AgentDirectoryReadTest test
```

同一组测试在Spring建立连接前校验库名/回环地址。本次验收进程未提供上述隔离数据库参数，因此没有执行MySQL专项、真实杀进程或真实模型选工具验收；也没有通过默认stub通过替代这些结果。生产发布前仍须补齐这些环境型验收和浏览器检查。未部署、未提交。
