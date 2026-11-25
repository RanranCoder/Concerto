# 协奏文档索引

当前项目结构和运行入口见 [项目 README](../README.md)。下方保留 2026-11-12 的文档审计记录，基准提交为 `2bec9ee`。线上版本及数据库迁移状态以对应发布记录为准。

## 使用、开发与验收

| 要查什么 | 入口 |
| --- | --- |
| 启动、环境变量、部署、日常测试 | [项目 README](../README.md) |
| 页面入口、操作流程、业务授权 | [业务操作手册](协奏PAMS-当前业务功能与使用流程说明.md) |
| HTTP 接口、认证、STOMP、SSE | [API 接口清单](API-inventory.md) |
| 组织模型服务、协议和密钥保存 | [AI 服务设置](design/ai-services/README.md) |
| Concerto Agent 隔离环境和专项命令 | [S5 验收](../scripts/agent-s5/README.md) |
| 通知与 MySQL、Nginx 和 SSE 联调 | [通知验收](../scripts/notification-acceptance/README.md) |
| 独立演示库与本地演示种子 | [协奏演示库](../scripts/concerto-demo/README.md)、[本地演示数据](../scripts/demo-data/README.md) |
| 设计规范和模块实现背景 | [设计与原型索引](design/README.md) |

## 设计和历史记录

- [总体设计](PAMS设计方案.md)、[PRD v4](PRD-v4.md)、[PRD v5](PRD-v5-开发规划.md)和 [10 月 31 日开发计划](下一阶段开发计划-2026-10-31.md)记录各阶段的目标和取舍。
- [旧版 API](API.md)保留 2026-08-26 的接口明细，供排查旧版本时引用。
- [Concerto Agent 分批落地记录](design/PAMS-Agent新一期分批落地记录.md)、[Concerto Agent 协作界面](design/agent-collaboration-ui-20261110.md)和[六任务包收口](audits/agent-closure-20261103/README.md)记录当时的实现及验收条件。
- [11 月 11 日代码审计](audits/code-audit-2026-11-11.md)含 11 月 12 日的修复和验证记录。其他阶段证据保存在 [audits/](audits/)。

## 2026-11-12 文档审计

| 核对范围 | 发现与处理 |
| --- | --- |
| 启动与发布 | README 停在 V58，并沿用 DeepSeek 专用接入口径。按 Flyway、Compose、模型配置和发布工作流补齐 V71、模型 ID、AI 密钥卷及测试门禁。按锁文件中的前端依赖要求，将建议的 Node.js 22.x 下限调整到 22.20。 |
| 接口 | 逐项比较 Controller 静态映射与清单，56 个 Controller 的 351 组 HTTP 方法与路径一致。更新上传文件认证、STOMP 会话校验及通知 SSE，注明注解统计的边界。 |
| 使用说明 | 补齐组织 AI 服务、Concerto Agent 协作概览、常用修订和送审工具。使用角色相关的能力清单可从 Concerto Agent 页面或 `GET /api/agent/tools` 获取。 |
| 导航与验收 | 在历史 API 页标注基线并指向当前清单，补充 Windows 启动器的执行终端与真实模型验收配置，记录 S5 CI 的 live 分支尚未注入模型 ID。 |

本次使用静态源码和文档核对，验证范围为本地工作区。后端、浏览器、真实模型、MySQL 双 JVM 及生产发布的运行结果见各自验收记录。
