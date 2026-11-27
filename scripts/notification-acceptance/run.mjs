import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const mode=process.argv[2]||'start';
if(mode==='postgresql-test') {
  const url=process.env.PAMS_PG_ACCEPTANCE_URL;
  const user=process.env.PAMS_PG_ACCEPTANCE_USER;
  const password=process.env.PAMS_PG_ACCEPTANCE_PASSWORD;
  if(!url || !/^jdbc:postgresql:\/\/127\.0\.0\.1:[0-9]+\/pams_acceptance_[a-zA-Z0-9_]+$/.test(url) || !user || !password) {
    throw Error('A migrated local pams_acceptance_* database and runtime credentials are required');
  }
  const tests='NotificationPolicyTest,NotificationPolicyHttpTest,NotificationServiceTest,NotificationStageThreeTest,MaintenanceNoticeTest,NotificationReminderTest,NotificationStreamHttpTest,PendingWorkTest,PostgresqlBusinessTest,RoutineMySqlConcurrencyTest';
  const args=['-B','-pl','pams-backend',`-Dtest=${tests}`,'test'];
  const windows=process.platform==='win32';
  const result=spawnSync(windows?'C:\\dev\\Git\\bin\\bash.exe':'mvn',
    windows?['-c',`mvn ${args.join(' ')}`]:args,
    {cwd:root,stdio:'inherit',env:{...process.env,
      SPRING_CONFIG_IMPORT:'optional:file:./acceptance-empty.properties',
      SPRING_DATASOURCE_URL:`${url}?currentSchema=pams&stringtype=unspecified`,
      SPRING_DATASOURCE_USERNAME:user,
      SPRING_DATASOURCE_PASSWORD:password,
      SPRING_DATASOURCE_DRIVER_CLASS_NAME:'org.postgresql.Driver',
      SPRING_FLYWAY_ENABLED:'false',
      PAMS_POSTGRESQL_ACCEPTANCE:'1',
      PAMS_TEST_AGENT_PROVIDER:'stub'
    }});
  if(result.error) throw result.error;
  process.exit(result.status??1);
}
const runtime=path.join(root,'.backups/notification-acceptance');fs.mkdirSync(runtime,{recursive:true});
const envFile=path.join(runtime,'runtime.env');
if(!fs.existsSync(envFile))fs.writeFileSync(envFile,`ACCEPTANCE_DB_PASSWORD=${randomBytes(24).toString('hex')}\nACCEPTANCE_JWT_SECRET=${randomBytes(40).toString('hex')}\n`,{mode:0o600});
const secrets=Object.fromEntries(fs.readFileSync(envFile,'utf8').trim().split(/\r?\n/).map(s=>s.split('=')));
const compose=['compose','--env-file',envFile,'-f',path.join(root,'scripts/notification-acceptance/compose.yml')];
function run(cmd,args,options={}) {
  const r=spawnSync(cmd,args,{cwd:root,encoding:'utf8',maxBuffer:30*1024*1024,...options});
  if(r.error)throw r.error;
  if(r.status!==0)throw Error((r.stderr||r.stdout||'Command failed').replaceAll(secrets.ACCEPTANCE_DB_PASSWORD,'[REDACTED]'));
  return r.stdout;
}
function sql(statement){return run('docker',[...compose,'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names'],{input:statement});}
if(mode==='start') console.log(run('docker',[...compose,'up','-d','--wait','mysql']));
else if(mode==='test') {
  const db=JSON.parse(run('docker',[...compose,'ps','--format','json','mysql']).trim());
  if(db.Project!=='pams-notify-acceptance' || db.Service!=='mysql')throw Error('Isolated MySQL project required');
  if(run('docker',[...compose,'ps','--status','running','-q','backend']).trim())throw Error('Stop the isolated backend before running database fixtures');
  if(process.argv.includes('--reset')) {
    if(run('docker',[...compose,'ps','--status','running','-q','backend']).trim())throw Error('Stop isolated backend before resetting fixtures');
    sql('DROP DATABASE IF EXISTS notification_acceptance; DROP DATABASE IF EXISTS notification_upgrade_acceptance; CREATE DATABASE notification_acceptance CHARACTER SET utf8mb4;');
  }
  sql('CREATE DATABASE IF NOT EXISTS notification_upgrade_acceptance CHARACTER SET utf8mb4;');
  const url='jdbc:mysql://127.0.0.1:13316/notification_acceptance?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false';
  const args=['-Dpams.acceptance.mysql=true','-Dtest=NotificationAcceptanceGuardTest,NotificationMySqlMigrationTest,NotificationPolicyTest,NotificationPolicyHttpTest,NotificationServiceTest,NotificationStageThreeTest,MaintenanceNoticeTest,NotificationReminderTest,NotificationStreamHttpTest,PendingWorkTest,MemberAccountImportServiceTest','test'];
  const env={...process.env,SPRING_CONFIG_IMPORT:'optional:file:./acceptance-empty.properties',SPRING_DATASOURCE_URL:url,SPRING_DATASOURCE_USERNAME:'root',SPRING_DATASOURCE_PASSWORD:secrets.ACCEPTANCE_DB_PASSWORD,SPRING_DATASOURCE_DRIVER_CLASS_NAME:'com.mysql.cj.jdbc.Driver',SPRING_FLYWAY_LOCATIONS:'classpath:db/migration,classpath:db/migration-mysql',PAMS_TEST_AGENT_PROVIDER:'stub',PAMS_ACCEPTANCE_JDBC_URL:url};
  const maven=process.platform==='win32'?'C:\\dev\\apache-maven-3.9.12\\bin\\mvn.cmd':'mvn';
  const log=fs.openSync(path.join(runtime,'mysql-tests.log'),'w');
  const cmd=process.platform==='win32'?'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe':maven;
  const actual=process.platform==='win32'?['-NoProfile','-Command',`& '${maven}' @(${args.map(a=>`'${a.replaceAll("'","''")}'`).join(',')}); exit $LASTEXITCODE`]:args;
  const result=spawnSync(cmd,actual,{cwd:path.join(root,'pams-backend'),env,stdio:['ignore',log,log]});fs.closeSync(log);
  console.log(JSON.stringify({suite:'mysql8-notification',exitCode:result.status,log:'.backups/notification-acceptance/mysql-tests.log'}));process.exitCode=result.status??1;
} else if(mode==='serve') console.log(run('docker',[...compose,'up','-d','backend','web']));
else if(mode==='stop') console.log(run('docker',[...compose,'down']));
else throw Error('Use start, test, postgresql-test, serve or stop');
