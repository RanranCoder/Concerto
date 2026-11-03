# 第五批回执核对与接续

日期：2026-11-02。状态：第五批首个增量；T2 的成员受众、任务变更、通知投递回执仍未全部实现。

## 已落地

- 修正 RESULT_UNKNOWN 在持久化协作节点中的映射：统一显示为 NEEDS_RECONCILE，阻止依赖该节点的后续步骤；恢复、取消均不能将未知提交冒充失败或已取消。
- 新增 `POST /api/agent/tasks/{id}/reconcile`，登录用户仅可核对自己的同组织任务。可传 `{"mode":"standard"}`；只读/知识模式及当前工具权限不满足时拒绝。核对不是模型工具，也不是再次确认或重复提交接口。
- 允许核对 RESULT_UNKNOWN，以及 confirmedAt 早于本进程启动、executionOwner 非本进程的 RUNNING 动作。当前进程正常执行中的动作不被强行结算。
- 首个业务回执适配器为 preparePlanRevision：从持久化变更集读取 result_plan_id，并验证原版本与结果版本的当前访问范围和活动一致性。源快照过期/已清理也可核对已提交回执，不再依赖两小时源文件快照。
- 找到真实回执后，以任务状态及 rowVersion 条件更新 SUCCESS，写入实际版本ID和引用。已结算状态不被过时请求覆盖；重复核对不再次提交业务写入。
- 有 WorkItem 的任务通过既有 settle/事务后事件和 Outbox 接续：只有核对成功后，依赖节点才就绪，后续步骤读到真实业务回执；取消运行不会因此重新启动下游。权限上限仍由现有调度器刷新并限制。
- 未查到回执、业务尚无适配器时，明确返回 `reconciled=false`、`reconciliationState=RESULT_UNKNOWN`；不将“未查到”推断为未执行或失败，不重做写操作。已保存成功任务再次核对也检查原生版本的当前可见性。

## API 结果

保留 TaskGate 任务详情字段，附加：

| 字段 | 含义 |
| --- | --- |
| reconciled | 是否已核对为实际成功 |
| reconciliationState | VERIFIED_SUCCESS 或 RESULT_UNKNOWN |
| reconciliationMessage | 事实、待处理事项与接续条件说明 |

成功核对只证明已有业务操作产生真实回执；不表示后续任务已经完成，也不把旧回执描述为文档的当前正文。核对未知结果时不尝试重新创建草稿、重新发送通知或重新生成任务。

## 验证

```bash
mvn -q -f pams-backend/pom.xml -Dtest=PlanRevisionIntegrationTest,DagSchedulerTest,SpecialistPipelineTest,AgentOrchestratorTurnSensitivityTest test
```

47项测试通过：PlanRevisionIntegrationTest 12、DagSchedulerTest 26、SpecialistPipelineTest 4、AgentOrchestratorTurnSensitivityTest 5。覆盖 H2/Flyway 下回执核对、快照清理后核对、旧进程 RUNNING 核对、缺失回执维持未知、跨用户/组织和模式拒绝、依赖阻塞与实际回执接续、取消/恢复保留未知、重复核对不新增版本，以及既有确认链路和敏感级留存。最后补充成功任务再次核对时的业务可见性检查及跨组织断言后，另跑12项回执集成测试。

## 剩余范围

当前没有前端核对按钮或自动批量核对调度，也未验证真实杀进程、MySQL 故障注入及真实模型连续业务。原有 S5ProcessRecoveryTest 依赖显式外部环境，本次没有运行，不据 H2 测试声称完成进程故障验收。

只有策划书修订提供可靠回执适配。createActivity 等旧写工具即使已执行，也不能仅凭对象名称猜测是否成功；保持待人工核对。成员/组织定位、受众快照、通知提交/投递/阅读分层回执、任务计划变更与进度更新仍属于第五批剩余工作，不标为全部 T2/A3 完成。未部署、未提交。
