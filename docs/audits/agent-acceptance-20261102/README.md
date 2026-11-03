# 第六批验收：隔离 MySQL 与进程恢复

日期：2026-11-02。状态：本轮隔离 MySQL 与进程恢复验收通过，最终43项全部通过，失败/错误/跳过均为0。此轮先验收第六批后端数据库与恢复边界，不表示浏览器或 A4 真实业务收益验收完成。

## 隔离方式

使用本机 MySQL 9.5.0，为每次运行创建全新的 `pams_s5_batch6_<随机后缀>` 数据库和仅授权该库的临时账号，分别验证 localhost/127.0.0.1 连接。生成密码只在启动器与子进程环境中传递，不输出、不保存为凭证明文文件。普通业务库不作为测试数据源；生产规划开关不改变。

启动器只清理本次成功创建的数据库与账号，并回查 `information_schema.schemata` 和 `mysql.user` 验证剩余数量为0。每次执行保留独立目录，不覆盖失败记录。

## 验收矩阵

| 项目 | 断言 |
| --- | --- |
| V64 真实迁移 | 空库执行 V1–V64；Article/Schedule 非空 revision、节点 JSON 契约/结果字段及新表在 MySQL 中存在 |
| 数据源/账号边界 | 数据库名与账号前缀在 Spring 创建数据源前验证；检查 SHOW GRANTS，仅允许本轮测试库 |
| 同操作并发提交 | 两线程同时提交同一 operation，仅保存一个业务 revision，返回同一持久化回执 |
| 同确认并发提交 | 两线程使用同一确认清单，业务只变更一次，最终 AgentTask 为 SUCCESS |
| 原生编辑竞态 | 原生事务先读旧 revision，Agent 提交后原生事务保存旧版本必须因乐观锁失败，不覆盖 Agent 结果 |
| 外层事务回滚 | 业务修改与成功回执一起回滚，结果未知时仅核对，不重复写入 |
| 待确认跨进程 | 真正杀死准备进程后，在新 JVM 使用原持久化清单确认；重复确认不产生第二次修改 |
| 提交后、Agent结算前死亡 | 业务及 operation 回执已提交，AgentTask仍为RUNNING时杀进程；新 JVM 核对回执转SUCCESS，不重放业务写入 |
| 写入中死亡 | 业务SQL已flush、外层事务仍开放时写出检查点并阻塞；父进程强制终止JVM；新 JVM 验证数据库撤销未提交业务/回执，任务显式 RESULT_UNKNOWN，不重放 |
| 受控节点跨进程 | 已落库的 QUEUED 节点在新 JVM 中保留契约、按原参数绑定执行并保存真实只读回执，不调用规划模型 |
| 连续业务回归 | 稿件修订/送审、指定班次发布、局部重规划及旧确认失效、任务计划和通知投递相关现有用例在同一隔离库复跑 |

## 验收中修复的问题

1. 评分汇总 SQL 的 `minTotal` 别名被项目实际使用的 Connector/J 9.5.0 错误识别为 SELECT INTO，导致 `executeQuery` 拒绝执行。已将 SQL 列别名改为中性名称，再显式映射回既有 API 字段，不改变对外返回契约；补充真实分数样本验证数量、最小值、最大值和均值。通过本地9.5/9.7驱动解析器对照复现，官方问题记录见 [MySQL Bug #119718](https://bugs.mysql.com/bug.php?id=119718)。
2. 普通确认任务在前一进程中断后可能仍显示 RUNNING。现在在已鉴权的回执核对路径使用 rowVersion CAS 将该旧执行转换为 RESULT_UNKNOWN；有成功业务回执时继续核实为SUCCESS，无回执时保留待核对，不猜测未执行、不重新提交。

首次 MySQL 执行失败和修复后的阶段记录分别保存在：

- `batch6-mysql-b1cc2ea71002`：发现评分汇总兼容问题；迁移、并发与第一版进程恢复断言已通过，整体未通过。
- [第二次运行](batch6-mysql-9acce82559ef/test-summary.json)：42项通过，修复 SQL 兼容问题。
- [第三次运行](batch6-mysql-aad141ffa65b/test-summary.json)：43项通过，包含中断任务显式未知状态及数据库/账号清理回查。
- [最终运行](batch6-mysql-49915159f3f7/test-summary.json)：43项全部通过，将进程恢复场景强化为持有真实未提交事务时强杀 JVM，见 [四类跨进程结果](batch6-mysql-49915159f3f7/process-recovery-evidence.json)和 [真实并发与迁移](batch6-mysql-49915159f3f7/mysql-business-evidence.json)。

最终 [隔离清理回查](batch6-mysql-49915159f3f7/batch6-isolation.json)确认本轮数据库与账号均已移除，remainingSchemas=0、remainingAccounts=0。`git diff --check`通过。

完整后端 H2 回归926项中909项通过、17项环境/网络专项默认跳过，失败和错误均为0，见 [后端回归](backend-regression-summary.json)。隔离 MySQL 专项由显式启动器另外运行，不把默认跳过当成通过。

## 复跑

在仓库根目录执行（本机已缓存的 Connector/J 9.7 仅用于启动隔离库管理脚本；项目业务测试仍使用其原有9.5依赖）：

```bash
java --class-path C:/Users/zhara/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar scripts/agent-s5/BatchSixAcceptance.java
```

脚本读取本机 `.env` 的数据库管理凭证，仅在内存中使用；创建临时库和权限受限账号，设置子进程环境后运行指定6个测试类，并在结束时清理。模型固定为stub；不导入真实业务数据，不连接真实模型。

运行输出给出独立证据目录。随后立即汇总（在其他测试覆盖 Surefire 输出之前）：

```bash
node scripts/agent-s5/summarize-batch6.cjs docs/audits/agent-acceptance-20261102/batch6-mysql-<本次后缀>
```

汇总同时检查Maven退出码、6个测试类报告、失败/错误/跳过数及隔离清理记录。只执行临时库清理，不执行已有业务库迁移或发布。

## 后续出口

下一项为浏览器真实 HTTP/确认卡端到端验收；其后开展 A4 与规则基线的真实业务执行收益对照。前五批遗留项及设计标为储备的写操作仍分别保留。未部署、未提交。
