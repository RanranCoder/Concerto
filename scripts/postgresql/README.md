# PostgreSQL V74 空库和只读候选验收

批次 1A 使用 PostgreSQL 18 和当前 Spring Boot 管理的 PostgreSQL JDBC 与 Flyway 模块。基线是独立迁移目录中的 `V74__mysql_v74_equivalent_schema.sql`，后续结构变更从 V75 起。业务表位于 `pams`，Flyway 历史和初始化模式位于 `pams_flyway`。

基线取自隔离 MySQL 空库完整执行 V74 迁移链后的结构。自增列使用可显式导入历史主键的标识列。MySQL `DATETIME` 保留本地时间，`TIMESTAMP` 转为带时区时间戳。MySQL JSON 转为 `jsonb`。文本唯一键使用 ICU 的大小写和重音不敏感比较。MySQL 特有排序、尾随空格、中文检索和业务 SQL 在批次 1B 验证。历史导入和导入后的序列校准在批次 1C 实施。

## 准备隔离数据库和角色

用数据库管理员在独立的空库上运行 `provision.sql`。

```bash
psql -X -d concerto_acceptance -f scripts/postgresql/provision.sql
```

按环境分别配置三个登录角色。默认角色名为 `pams_migrator`、`pams_runtime`、`pams_candidate`，可分别用 `-v migrator=...`、`-v runtime=...`、`-v candidate=...` 指定。执行脚本的管理员提供所需的数据库及角色管理权限。使用 `psql` 的 `\password` 为每个角色设置独立密码，再通过环境凭据传给对应进程。

- 迁移角色拥有应用和迁移历史 schema，负责 Flyway 与参考数据初始化。
- 运行角色拥有业务 DML 和序列使用权限，迁移历史权限为只读。
- 候选角色只能读取业务表和迁移历史，并默认使用只读事务。

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

候选启动时验证只读权限、初始化模式和成功迁移版本。版本由 `pams.postgresql.min-schema-version` 和 `pams.postgresql.max-schema-version` 指定，批次 1A 均为 74。候选自动关闭 Flyway、业务种子、启动租约续期、后台恢复和定时任务。批次 1A 验证候选启动和探针，业务冒烟在 1B 的方言适配后执行。
