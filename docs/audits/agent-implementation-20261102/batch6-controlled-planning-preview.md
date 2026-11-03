# 第六批首个增量 受控规划预览

日期：2026-11-02。状态：实验预览入口与结构校验已实现，不是模型规划执行上线，也未开始宣称A4收益达标。

后续更新：本文件保留首个增量的历史范围，节点持久化、受控执行、送审及扩展的当前实现见 [第六批补齐记录](batch6-completion.md)；以下“剩余工作”不应覆盖后续交付状态。

## 本批交付

- 新增 planner/v1 独立资源，由 PromptResolver 固定版本并提供SHA-256。规划器不继承执行工具循环，不调用业务工具。
- 新增 `POST /api/agent/team/plan/controlled`，需要登录身份、有效组织上下文；指定会话时验证会话归属。知识问答模式不能进入业务规划。
- 默认关闭模型规划预览；必须同时打开 controlled-planning-enabled 并将当前组织加入非空 controlled-planning-org-ids。关闭/未在名单时只返回明确标记的规则基线，不调用模型。
- 模型只看到实际权限、模式和专家scope交集中的能力目录。只读模式不提供写预览能力；发送、发布、成员创建等不在首版模型规划准备能力白名单中。已有规则执行链仍可按原权限与确认机制处理这些业务，实验预览不会代替它。
- 服务端校验目标不变、最多8步、唯一正整数ID、现有专家/能力组合、无环依赖、输入参数名、USER原文引用以及STEP输入来源的直接依赖。未知字段、伪造能力、未声明依赖、虚构原文或尾随JSON均拒绝。
- READ/PREPARE和requiresConfirmation从服务端实际工具计算，不接受模型自行关闭门禁。STEP结果字段路径只表示待验证的绑定建议，明确标记 resultPathVerified=false；没有执行结果时不能称其已绑定成功。
- 单次模型请求不提供工具、最多2048输出token、至多15秒；最多一次JSON修复，共最多2次单次传输尝试。输入超预算时整体拒绝，不裁断JSON或原生协议状态。修复时保留原生响应状态，不复制公开摘要为内部推理。
- 模型不可用、降级、截断或校验仍失败时返回RULE_BASELINE及reasonCode；澄清问题存在时steps必须为空，返回NEEDS_CLARIFICATION。规划期间能力撤销则停止接受候选，新授权不扩大本次目录。

## 响应与启用

请求示例：

```json
{"goal":"查询活动42，再根据活动事实准备任务","toolMode":"standard"}
```

响应包含 status、previewOnly=true、reasonCode、attempts、planner版本/指纹、能力目录摘要指纹、实际模型标识，以及建议plan、constraints、clarifications、steps。MODEL_PLAN_VALIDATED只代表结构/来源约束通过，不表示参数值已求解、事实已核实、计划已经执行或业务已经完成。

隔离实验环境配置示例：

```yaml
pams:
  agent:
    controlled-planning-enabled: true
    controlled-planning-org-ids: [1]
```

以上仅启用预览端点中的模型尝试，不会改变 `/team/run`、CoordinatorAgent.executeRun 或任何现有DAG入口。客户端不能把返回JSON作为已批准执行计划提交；当前执行入口仍根据原目标生成规则计划。

## 验证

```bash
mvn -q -f pams-backend/pom.xml -Dtest=ControlledPlanValidatorTest,ControlledPlanningServiceTest,ControlledPlanningHttpTest,PlanningCallTest,PromptRendererTest,MultiAgentBaseTest,AgentScopeTest,DagSchedulerTest,LongTermMemoryTest test
```

覆盖结构与输入来源攻击、循环/重复/不存在依赖、目标变更、门禁覆盖、一次修复及规则回退、权限撤销、只读/知识模式隔离、取消清理、原生状态保留与预算、无工具/无隐式重试的模型请求、真实JWT HTTP默认关闭行为、预览不创建Run/活动，以及原规则/DAG/记忆命令兼容。

本轮9个测试类共78项全部通过，0失败、0错误、0跳过。汇总见 [测试结果](batch6-preview-test-summary.json)。`git diff --check`通过。

这些确定性测试不代替真实模型选能力、依赖质量及规则基线收益对照。没有把候选计划接入调度，也没有创建业务写入工具或审批决策工具。

## 第六批剩余工作

1. 规划节点能力范围、输入绑定、完成条件和版本的不可变持久化；执行时限制每节点真实工具集合，不能只把能力名写在自然语言里。
2. 执行前求解绑定、结果路径/参数类型验证、局部重规划及修订失效，继续复用业务状态与确认门禁。
3. 同等预算下的规则/模型规划真实样本对照，验证收益后再决定是否接入实际运行。
4. 宣传稿件读取/修订、策划书/稿件提交准备、审批状态查询及后续扩展。组织审批仍由有权人员办理，模型不得自行审批。
5. 前五批尚未完成的跨环境和真实模型/浏览器验收继续保留，不因启动第六批而视为已通过。

未部署、未提交；默认规则执行行为保持不变。
