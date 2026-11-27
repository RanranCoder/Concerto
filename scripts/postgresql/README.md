# PostgreSQL 迁移和候选验收

使用 PostgreSQL 18 和当前 Spring Boot 管理的 PostgreSQL JDBC 与 Flyway 模块。V74 建立业务基线，V75 添加中文检索索引，V76 增加存量导入和开写记录。业务表位于 `pams`，Flyway 历史和初始化模式位于 `pams_flyway`。 [存量导入与恢复](historical-cutover.md)

基线取自隔离 MySQL 空库完整执行 V74 迁移链后的结构。自增列使用可显式导入历史主键的标识列。MySQL `DATETIME` 保留本地时间，`TIMESTAMP` 转为带时区时间戳。MySQL JSON 转为 `jsonb`。文本唯一键使用 ICU 的大小写和重音不敏感比较。历史导入和序列校准在批次 1C 实施。

## 准备隔离数据库和角色

用数据库管理员创建独立 UTF-8 空库并运行 `provision.sql`。数据库的 `LC_CTYPE` 须识别汉字。Linux 可用 `C.UTF-8`，Windows 使用本机可用的中文 locale，例如 `Chinese (Simplified)_China.936`。

```bash
psql -X -d concerto_acceptance -f scripts/postgresql/provision.sql
```

按环境分别配置三个登录角色。默认角色名为 `pams_migrator`、`pams_runtime`、`pams_candidate`，可分别用 `-v migrator=...`、`-v runtime=...`、`-v candidate=...` 指定。执行脚本的管理员提供所需的数据库及角色管理权限。使用 `psql` 的 `\password` 为每个角色设置独立密码，再通过环境凭据传给对应进程。

- 迁移角色拥有应用和迁移历史 schema，负责 Flyway 与参考数据初始化。
- 运行角色拥有业务 DML 和序列使用权限，迁移历史权限为只读。
- 候选角色只能读取业务表和迁移历史，并默认使用只读事务。

脚本将 `pg_trgm` 安装到 `pams`。从 1A 的 V74 库升级时，由数据库管理员先执行 `CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA pams`。V75 通过 `show_trgm('组织排班')` 验证字符分类。索引覆盖可见标题及 T1 正文。一至两个汉字在组织、层级和可见性谓词内做子串匹配。 [PostgreSQL pg_trgm](https://www.postgresql.org/docs/18/pgtrgm.html)

在 PostgreSQL 18.3 的五组织、25,000 条合成材料中，清理 GIN 待合并条目后，四字查询「组织排班」使用标题和正文的 GIN 位图扫描，单次 SQL 执行约 0.15 ms。「排班」使用组织索引限定后做子串匹配，单次约 11.1 ms。测试也验证了单字命中、跨组织过滤、权限过滤及 T2 的仅标题匹配。批次 0 的固定查询集和性能基线仍需用于正式的 Recall@5 与延迟对比。

## 执行一次性迁移

在 Linux 部署节点设置 `PAMS_BACKEND_IMAGE` 为候选镜像引用，`PAMS_PG_NETWORK` 为数据库所在的容器网络。向迁移进程注入 `PAMS_PG_MIGRATION_URL`、`PAMS_PG_MIGRATION_USER` 和 `PAMS_PG_MIGRATION_PASSWORD`。迁移入口发送的凭据属于迁移角色。部署任务共用 `PAMS_DEPLOY_LOCK_FILE`，默认路径为 `/var/lock/concerto-deploy.lock`。

```bash
bash scripts/postgresql/migrate.sh empty-seed
```

验收环境也可以打包后直接调用同一个入口。此方式用于隔离验证，部署节点使用上面的锁定入口。

```bash
mvn -B -pl pams-backend -DskipTests package
java -jar pams-backend/target/pams-backend-1.0.0.jar migrate-postgresql empty-seed
```

`empty-seed` 写入 MySQL V74 空库迁移链已有的默认组织、权限和座位模板等参考数据。`historical-import` 创建结构并登记模式，业务数据由后续导入填充。首次完成后模式固定，同模式可重跑。迁移任务持有数据库会话锁，直到 Flyway 校验及模式登记完成。

## 验证运行角色和只读候选

在隔离验收库中以运行角色执行下列检查。验证写入在一个回滚事务中完成。

```bash
psql -X -U pams_runtime -d concerto_acceptance -f scripts/postgresql/verify-v74.sql
```

向候选进程注入 `PAMS_PG_URL`、`PAMS_PG_USER` 和 `PAMS_PG_PASSWORD`，其中用户为候选只读角色。用独立端口启动候选。

```bash
java -jar pams-backend/target/pams-backend-1.0.0.jar \
  --spring.profiles.active=postgresql-candidate --server.address=127.0.0.1 --server.port=18074
```

在另一个终端检查存活与就绪。

```bash
curl --fail http://127.0.0.1:18074/api/ping
curl --fail http://127.0.0.1:18074/api/ready
```

候选启动时验证只读权限、初始化模式和成功迁移版本。版本由 `pams.postgresql.min-schema-version` 和 `pams.postgresql.max-schema-version` 指定，批次 1C 均为 76。候选关闭 Flyway、业务种子、启动租约续期、后台恢复和定时任务。读写运行使用 `postgresql` profile 与运行角色。Windows 开发可设置 `PAMS_DB_ENGINE=postgresql` 以及本机 `PAMS_PG_URL`、`PAMS_PG_USER`、`PAMS_PG_PASSWORD` 后执行 `start.bat`。

## 独立 Compose 环境

配置 `.env.example` 中的 PostgreSQL 项和 JWT 密钥。使用独立项目名启动数据库。

```bash
docker compose -p concerto-pg -f docker-compose.postgresql.yml up -d --wait postgresql
docker compose -p concerto-pg -f docker-compose.postgresql.yml exec -T postgresql \
  psql -X -U postgres -d pams_db < scripts/postgresql/provision.sql
```

设置三个角色密码后，为迁移入口配置 `PAMS_PG_NETWORK=concerto-pg_default` 和已构建的后端镜像。运行一次性迁移，再用 `docker compose -p concerto-pg -f docker-compose.postgresql.yml up -d backend web` 启动候选。默认候选使用 `postgresql-candidate` profile。隔离环境的写入回归使用 `PAMS_PG_PROFILE=postgresql` 和运行账号。
