# 协奏演示组织数据库

这套数据用于本地演示，人员、联系方式与业务内容均为虚构。当前独立库为 `pams_concerto_demo`，不替换项目默认的 `pams_db`。部门为主任、宣传部、组织部和秘书处。

演示账号：`admin`（主任及组织所有者）、`media`（宣传部负责人）、`org`（组织部负责人）、`secretary`（秘书处负责人）；其他成员账号为 `media01`–`media04`、`org01`–`org04`、`sec01`–`sec04`。密码均为 `123456`。这是弱口令，仅限隔离的演示环境，禁止用于生产或开放到公网。

## 启动现有演示库

在项目根目录的 PowerShell 中运行（根据自己的数据库环境修改用户名和密码）：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3306/pams_concerto_demo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
$env:SPRING_DATASOURCE_USERNAME = 'root'
$env:DB_PASSWORD = 'root'
$env:PAMS_AGENT_PROVIDER = 'stub'
Set-Location pams-backend
java -jar target/pams-backend-1.0.0.jar
```

使用现有前端的启动方式即可连接后端；执行前请确认后端监听端口未被其他实例占用。此数据库本身不会改变默认配置，不设置以上环境变量时项目仍连接默认库。AI 设置为本地 stub，避免演示时意外调用外部模型。后端进程停止后，这些环境变量只在当前 PowerShell 会话中生效。

## 从空库重建

以下操作会向指定库写入并清理初始化业务记录，只能用于新建的 `pams_concerto_demo`。不要将 SQL 导入现有业务库；这些脚本不是增量迁移，也不支持重复执行。若该库已存在，先自行备份并使用一个全新 MySQL 实例或明确处理现有库，再执行以下步骤。命令会在库名已存在时失败，不会覆盖现有库。

1. 创建空库：

   ```powershell
   $env:MYSQL_PWD = 'root'
   mysql -h 127.0.0.1 -u root -e 'CREATE DATABASE pams_concerto_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
   ```

2. 从仓库根目录执行 `mvn -f pams-backend/pom.xml -DskipTests package` 构建后端，再按上面的启动命令连接新库。等待启动完成，让 Flyway 执行全部迁移和后端初始化角色等系统配置，然后停止后端。

3. 回到仓库根目录，按顺序执行（保持 `MYSQL_PWD` 和数据库连接配置与上一步一致）：

   ```powershell
   mysql -h 127.0.0.1 -u root --default-character-set=utf8mb4 pams_concerto_demo -e 'source scripts/concerto-demo/cleanup-initial-business.sql'
   mysql -h 127.0.0.1 -u root --default-character-set=utf8mb4 pams_concerto_demo -e 'source scripts/concerto-demo/base.sql'
   mysql -h 127.0.0.1 -u root --default-character-set=utf8mb4 pams_concerto_demo -e 'source scripts/concerto-demo/business.sql'
   mysql -h 127.0.0.1 -u root --default-character-set=utf8mb4 pams_concerto_demo -e 'source scripts/concerto-demo/notifications.sql'
   Remove-Item Env:MYSQL_PWD
   ```

4. 再次按上面的命令启动后端并登录。生成的数据库只存演示用信息；材料和模板的示例记录不附带实际上传文件，文件预览或下载需要自行上传真实的演示附件。上传文件、外部服务配置和真实模型密钥不随种子创建。

活动、任务和排班的部分日期按导入当天生成，不会随时间自动滚动。可选字段按业务需要留空；系统运行日志、真实投递历史和外部 AI 服务等不会凭空生成，因此并非每张内部表和每个字段均有演示值。
