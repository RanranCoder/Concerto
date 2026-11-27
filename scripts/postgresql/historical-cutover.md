# MySQL 存量导入与 PostgreSQL 恢复

这套流程要求源库在取快照时停止业务写入和后台处理。预发使用脱敏快照，生产使用已备份、可追踪到停写时间点的快照。切换前确认源库 Flyway V74、备份及异机恢复环境、应用和附件规模、维护窗口、预发发布清单，以及原 MySQL 的回退期。 [技术框架 PRD](../../docs/Concerto-项目技术框架PRD.md#42-迁移演练与生产切换)

## 导入和对账

1. 按 PostgreSQL 指引准备独立 V76 目标库，用 `migrate-postgresql historical-import` 建结构。保持目标应用停机。
2. 将 `PAMS_MYSQL_IMPORT_URL`、`PAMS_MYSQL_IMPORT_USER`、`PAMS_MYSQL_IMPORT_PASSWORD` 注入迁移进程，使用快照库的只读账号。通过 `PAMS_PG_MIGRATION_URL`、`PAMS_PG_MIGRATION_USER`、`PAMS_PG_MIGRATION_PASSWORD` 提供 PostgreSQL 迁移账号。
3. 在部署节点持有部署锁执行导入。用备份编号或停写检查点编号作为稳定的快照 ID。为每次执行使用新的对账报告路径。

```bash
flock --exclusive --nonblock "$PAMS_DEPLOY_LOCK_FILE" \
  java -jar pams-backend/target/pams-backend-1.0.0.jar \
  import-mysql-history snapshot-20261127 artifacts/import.json
```

导入器按外键依赖和稳定主键顺序读取源库。在同一 PostgreSQL 事务中写入并逐表对账。检查表、列、主键及源库成功的 Flyway 版本。附属序列重启值取源库 `AUTO_INCREMENT` 与目标最大主键加一的较大值。失败时回滚，成功后用同一 ID 重跑会复核全部数据。快照内容、来源或目标变化时报告差异。新快照使用重新准备的空目标库。数据库会话锁与结构迁移、开写命令共用。

报告记录逐表行数、组织内行数、主键起止、摘要、关键状态汇总、序列值和耗时。文本或混合主键记录指纹。时间字段保留本地时间或 UTC 瞬时语义，JSON 的对象键顺序和数字格式归一化后计算摘要。拒绝报告记录表、列、主键指纹及错误代码。报告保存在受控发布工件中。

导入使用单次原子提交。若在代表性数据规模下超出维护窗口，先验证增量同步及最终对账，再安排生产计划。

## 基础备份和 WAL

备用主机使用 PostgreSQL 18 客户端，连接使用环境级凭据和 TLS。将基础备份、WAL 和恢复记录放在主机故障后仍可访问的加密存储上。WAL 归档目录按集群分开并提供 POSIX 原子硬链接和持久化语义。

数据库启用 `wal_level=replica`、`archive_mode=on` 和符合 RPO 的 `archive_timeout`。将下例的目录替换为实际归档位置。检查 `pg_stat_archiver`，监控最近成功归档时间、失败数和未归档积压。 [PostgreSQL 连续归档](https://www.postgresql.org/docs/18/continuous-archiving.html)

```conf
archive_command = 'bash /opt/concerto/scripts/postgresql/archive-wal.sh "%p" "%f" "/mnt/concerto-backup/cluster-a/wal"'
```

从备用主机运行基础备份脚本。脚本用 SHA-256 生成 PostgreSQL 备份清单，验证通过后发布目录。 [pg_basebackup](https://www.postgresql.org/docs/18/app-pgbasebackup.html) · [pg_verifybackup](https://www.postgresql.org/docs/18/app-pgverifybackup.html)

```bash
PGSERVICE=concerto-backup bash scripts/postgresql/base-backup.sh base-20261127 /mnt/concerto-backup/cluster-a/base
```

应用暂停写入后，将附件快照及其文件摘要、AI 密钥的恢复版本和本次发布清单登记为工件。基础备份完成后以具备权限的连接建立数据库恢复点，并等待输出的 WAL 段在异机归档中可用。

```bash
PGSERVICE=concerto-maintenance bash scripts/postgresql/create-recovery-point.sh concerto-cutover-20261127 > artifacts/point.json
python scripts/postgresql/record-recovery.py \
  --point artifacts/point.json \
  --backup-manifest /mnt/concerto-backup/cluster-a/base/base-20261127/backup_manifest \
  --archived-segment "$ARCHIVED_RESTORE_POINT_SEGMENT" \
  --attachments-snapshot-id "$ATTACHMENTS_SNAPSHOT_ID" \
  --attachments-manifest artifacts/attachments.json \
  --ai-key-version "$AI_KEY_VERSION" \
  --release-manifest artifacts/release.json \
  --reconciliation artifacts/import.json \
  --output artifacts/recovery.json
```

只记录密钥版本，密钥内容通过原有密钥系统独立保留。恢复时按清单核验工件 SHA-256，并使用同一个版本恢复附件及密钥。

## 备用环境演练

1. 从备用存储验证基础备份，再复制到备用实例的空数据目录。
2. 保持备用实例应用流量隔离。配置独立监听地址、端口、`restore_command`、恢复清单中的 `recovery_target_name` 以及对应时间线，创建 `recovery.signal` 后启动 PostgreSQL。首轮使用 `recovery_target_action=pause`，核对归档已回放到指定恢复点。 [恢复目标配置](https://www.postgresql.org/docs/18/runtime-config-wal.html)
3. 核对数据、顺序值、最近已提交的业务写入、附件与密钥。用恢复后的应用镜像和配置验证内网 HTTPS、登录、只读业务、附件访问和密钥解密，再验证后台任务恢复。
4. 用故障时间减最近可完整恢复的业务恢复点计算 RPO，用故障时间到核心业务恢复读写计算 RTO。预发目标为 RPO ≤ 15 分钟及 RTO ≤ 2 小时。记录实际数据量、归档等待、恢复耗时和验收结果。

## 生产开写和回退

1. 停止 MySQL 业务写入及后台处理，排空或登记仍在途的任务和未决回执。确认事务结束，记录停写时间并保全源库、附件和密钥。
2. 从最终快照完成导入、对账和恢复点登记。用候选角色和 `postgresql-candidate` profile 在内网 HTTPS 验证登录、组织数据、附件、通知和 Agent 回执。对账完成后只读候选才会启动。
3. 汇合预发验收、只读冒烟、对账和恢复证据后，用报告的 `reconciliationSha256` 登记开写。再启用 `postgresql` profile 的运行实例和后台任务，复验首批业务写入。应用读写启动会检查开写记录。

```bash
flock --exclusive --nonblock "$PAMS_DEPLOY_LOCK_FILE" \
  java -jar pams-backend/target/pams-backend-1.0.0.jar \
  open-postgresql-writes snapshot-20261127 "$RECONCILIATION_SHA256"
```

开写前回退时，停止 PostgreSQL 候选，恢复原 MySQL 和原发布清单并验证 HTTPS。开写登记后以 PostgreSQL 为权威。应用故障使用已验收的 PostgreSQL 兼容发布清单恢复。只有当前候选兼容时，先停写并保全数据库、附件和密钥，再前滚修复。数据故障在备用实例演练恢复与对账。需要回切 MySQL 时，先完成已验收的增量回灌及数据库、附件和密钥对账，再切流量。
