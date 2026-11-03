# 第五批第二个增量 任务进度闭环

日期：2026-11-02。范围：任务进度读取、预览、确认、并发基线与回执核对。不代表整个第五批或任务计划变更能力完成。

## 实现

- queryActivityTasks 的现有行结构增加 revision 和 assigneeUserId，供目标定位与并发基线使用；不把负责人姓名当作身份凭据。
- 新增 prepareTaskProgressUpdate：taskId、expectedRevision、0–100整数 progress 必填，无 FORM。预览展示原进度、拟改进度、任务/活动ID和基线，只持久化内部操作记录，不更新业务任务。
- 沿用 task:progress / task:manage 的 ANY 权限。Agent 适配层额外核验对象：仅 task:progress 时须 assigneeUserId 等于当前用户；管理权限也必须在当前组织、关联活动有效且未归档。不扩充原生 REST 权限或放宽任何模型作用域。
- 确认经过现有 TaskGate，核验任务归属、工具名及 RUNNING 状态；重新检查权限/负责人/活动状态。锁定操作记录与活动，刷新实体后检查实际 revision，再调用既有 TaskService.updateProgress。状态派生及通知业务事件继续由业务服务负责。
- V62 新增 agent_task_progress_operation，业务更新与 result_revision 回执同事务保存。相同操作并发/重复提交复用回执，其他操作沿用旧基线会被拒绝。业务任务的负责人、日期、依赖等不修改。
- 接入第五批的 `/api/agent/tasks/{id}/reconcile`：有本次操作的已提交回执才结算；不能根据“当前进度碰巧相同”推断操作已成功。缺失回执仍保持 RESULT_UNKNOWN，不自动更新任务。历史回执明确标记操作时的进度/revision，当前值须重新查询。
- 排班专家默认 scope 增加 queryActivityTasks 和 prepareTaskProgressUpdate；精确匹配已知旧默认的启用配置迁移，自定义/禁用配置和角色文本保留。注册工具总数27，见 [工具契约快照](tool-inventory-batch5-2.json)。

## 关键边界

确认请求不接受客户端重新指定任务ID、进度或基线，仅采用服务端持久化操作记录。缺参、分数、小数、越界进度不补造参数；进度不变不发起提交。业务服务拒绝或过期基线作为明确失败；意外提交异常返回 RESULT_UNKNOWN，避免盲重试。

本工具不支持任务新增、删除、整套计划修改、负责人/日期/依赖调整，也不保证相关通知已经投递或阅读。现有 queryActivityTasks 的授权列表分页与统计口径仍需独立升级，本次只增加身份和 revision 字段。

## 验证

```bash
mvn -q -f pams-backend/pom.xml -Dtest=TaskProgressActionIntegrationTest,PlanRevisionIntegrationTest,DagSchedulerTest,ToolInventoryTest,AgentDefinitionUpgradeTest test
```

结果：51项测试通过，失败、错误、跳过均为0。覆盖真实 H2/Flyway V62、预览不写业务任务、真实确认门禁、进度/状态派生、重复确认、并发同操作仅增一次revision、相同基线不同操作冲突、本人/跨组织/负责人变更/归档/取消拒绝、缺失回执不重做及历史回执核对。并回归策划书修订、DAG接续、实际工具注册和旧scope差异迁移。

未做真实模型、MySQL并发/故障注入、前端交互和通知投递验收。未部署、未提交。第五批后续继续成员/组织与受众解析、通知回执，以及任务计划变更；不直接标为进入第六批。
