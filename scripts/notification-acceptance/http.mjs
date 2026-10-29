import fs from 'node:fs';import path from 'node:path';import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';import { randomUUID } from 'node:crypto';import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const runtime=path.join(root,'.backups/notification-acceptance');
const compose=['compose','--env-file',path.join(runtime,'runtime.env'),'-f',path.join(root,'scripts/notification-acceptance/compose.yml')];
function docker(args,input){const r=spawnSync('docker',[...compose,...args],{cwd:root,encoding:'utf8',input,maxBuffer:4*1024*1024});if(r.status!==0)throw Error('Isolated Docker operation failed');return r.stdout.trim();}
const web=JSON.parse(docker(['ps','--format','json','web']));assert.equal(web.Project,'pams-notify-acceptance');assert.ok(web.Publishers.some(p=>p.PublishedPort===18086 && p.URL==='127.0.0.1'));
const base='http://127.0.0.1:18086';const checks=[];const startedAt=new Date().toISOString();
const pause=ms=>new Promise(r=>setTimeout(r,ms));
function pass(name,detail={}){checks.push({name,result:'PASS',...detail});console.log(`PASS ${name}`);}
function sql(statement){return docker(['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names notification_acceptance'],statement);}
async function api(url,token,method='GET',body){const response=await fetch(base+url,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(10000)});let json;try{json=await response.json();}catch{}return {status:response.status,data:json?.data};}
async function until(fn,timeout=15000){const end=Date.now()+timeout;while(Date.now()<end){const result=await fn();if(result)return result;await pause(200);}throw Error('Acceptance wait timed out');}
async function open(token,extra={}){
  const controller=new AbortController(),began=Date.now();
  const response=await fetch(base+'/api/notifications/stream',{headers:{Authorization:`Bearer ${token}`,...extra},signal:controller.signal});assert.equal(response.status,200);assert.match(response.headers.get('content-type'),/text\/event-stream/);assert.equal(response.headers.get('content-encoding'),null);
  const stream={events:[],closed:false,close:()=>controller.abort(),began};const reader=response.body.getReader();
  void(async()=>{let buffer='';const decoder=new TextDecoder();try{while(true){const {done,value}=await reader.read();if(done)break;buffer+=decoder.decode(value,{stream:true}).replaceAll('\r\n','\n');let cut;while((cut=buffer.indexOf('\n\n'))>=0){const frame=buffer.slice(0,cut);buffer=buffer.slice(cut+2);const event=frame.match(/^event:\s*(.+)$/m)?.[1];const data=frame.match(/^data:\s*(.+)$/m)?.[1];if(event){assert.deepEqual(JSON.parse(data),{protocolVersion:1});stream.events.push(event);}}}}finally{stream.closed=true;}})().catch(()=>{stream.closed=true;});
  stream.wait=async(event,timeout=25000)=>until(()=>stream.events.includes(event),timeout);
  await stream.wait('ready',5000);return stream;
}
let stream,staffId,roleId,grant,disabled=false;
try {
  await until(async()=>{try{return (await api('/api/ping')).status===200;}catch{return false;}},90000);
  assert.equal((await api('/api/notifications/stream')).status,401);
  assert.equal((await api('/api/notifications/stream?token=acceptance-invalid-query-token')).status,401);pass('bearer-required-query-token-rejected');
  const teacher=await api('/api/auth/login',null,'POST',{username:'teacher',password:'123456'});
  const staff=await api('/api/auth/login',null,'POST',{username:'staff',password:'123456'});assert.equal(teacher.status,200);assert.equal(staff.status,200);
  staffId=staff.data.user.id;assert.ok(Number.isSafeInteger(staffId));const teacherToken=teacher.data.token,staffToken=staff.data.token;
  stream=await open(staffToken);pass('nginx-unbuffered-ready',{firstFrameMs:Date.now()-stream.began});
  const title='notification-acceptance-'+randomUUID();
  const published=await api('/api/announcements',teacherToken,'POST',{title,content:'Synthetic acceptance data',important:true,requireConfirmation:true,audienceAll:false,audienceUserIds:[staffId]});assert.equal(published.status,200);const id=published.data.id;
  await stream.wait('inbox.changed');
  const message=await until(async()=>{const list=await api('/api/notifications',staffToken);return list.data?.find(n=>n.entityType==='ANNOUNCEMENT' && n.entityId===id);});
  assert.equal((await api(`/api/notifications/${message.id}/read`,teacherToken,'PUT')).status>=400,true);pass('personal-inbox-isolation');
  assert.equal((await api(`/api/notifications/${message.id}/read`,staffToken,'PUT')).status,200);
  assert.ok((await api('/api/notifications/pending',staffToken)).data.items.some(n=>n.entityType==='ANNOUNCEMENT' && n.entityId===id));
  assert.equal((await api(`/api/announcements/${id}/confirm`,staffToken,'POST',{version:published.data.confirmationVersion})).status,200);
  assert.ok(!(await api('/api/notifications/pending',staffToken)).data.items.some(n=>n.entityType==='ANNOUNCEMENT' && n.entityId===id));pass('business-commit-outbox-sse-rest-confirmation');
  await stream.wait('heartbeat');pass('heartbeat-through-real-nginx');stream.close();
  const offline=await api('/api/announcements',teacherToken,'POST',{title:title+'-offline',content:'Synthetic offline recovery',important:true,audienceAll:false,audienceUserIds:[staffId]});assert.equal(offline.status,200);
  await until(async()=> (await api('/api/notifications',staffToken)).data?.find(n=>n.entityId===offline.data.id && n.entityType==='ANNOUNCEMENT'));
  stream=await open(staffToken,{'Last-Event-ID':'acceptance-disconnected'});pass('disconnected-rest-reconciliation');
  roleId=Number(sql(`SELECT role_id FROM sys_user WHERE id=${staffId};`));assert.ok(Number.isSafeInteger(roleId));
  grant=sql(`SELECT g.permission_id,g.scope_type,COALESCE(g.pack_version,'NULL') FROM role_grant g JOIN permission p ON p.id=g.permission_id WHERE g.org_id=1 AND g.role_id=${roleId} AND p.code='notice:view';`).split('\t');assert.ok(/^\d+$/.test(grant[0]));assert.ok(/^[A-Z_]+$/.test(grant[1]));assert.ok(/^(NULL|\d+)$/.test(grant[2]));
  sql(`DELETE FROM role_grant WHERE org_id=1 AND role_id=${roleId} AND permission_id=${grant[0]};`);
  const revoked=(await api('/api/notifications',staffToken)).data.find(n=>n.id===message.id);assert.equal(revoked.title,'业务通知');assert.equal(revoked.entityType,null);pass('live-business-permission-revocation');
  sql(`INSERT IGNORE INTO role_grant(org_id,role_id,permission_id,scope_type,pack_version) VALUES(1,${roleId},${grant[0]},'${grant[1]}',${grant[2]});`);grant=null;
  sql(`UPDATE sys_user SET status=0 WHERE id=${staffId};`);disabled=true;
  await until(()=>stream.closed,25000);assert.equal((await api('/api/notifications',staffToken)).status,401);pass('disabled-account-closes-sse-and-rest');
  sql(`UPDATE sys_user SET status=1 WHERE id=${staffId};`);disabled=false;
  docker(['stop','backend']);const key='acceptance-restart-'+randomUUID();
  sql(`START TRANSACTION; INSERT INTO notification(org_id,type,title,content,event_key,created_at,is_read,deleted) VALUES(1,'SYSTEM','restart fixture','Synthetic durable intent','${key}',CURRENT_TIMESTAMP,0,0); SET @message=LAST_INSERT_ID(); INSERT INTO notification_outbox(org_id,message_id,status,next_attempt_at,created_at) VALUES(1,@message,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP); INSERT INTO notification_outbox_recipient(outbox_id,user_id) VALUES(LAST_INSERT_ID(),${staffId}); COMMIT;`);
  docker(['start','backend']);await until(async()=>{try{return (await api('/api/ping')).status===200;}catch{return false;}},90000);
  const recovered=await until(async()=> (await api('/api/notifications',staffToken)).data?.find(n=>n.title==='restart fixture'),30000);
  assert.equal(sql(`SELECT COUNT(*) FROM notification_inbox WHERE org_id=1 AND user_id=${staffId} AND message_id=${recovered.id};`),'1');pass('backend-restart-recovers-durable-outbox');
  sql(`UPDATE notification_outbox SET status='PROCESSING',attempts=1,lease_token='expired-acceptance',lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND),next_attempt_at=NOW() WHERE message_id=${recovered.id};`);
  await until(()=>sql(`SELECT status FROM notification_outbox WHERE message_id=${recovered.id};`)==='COMPLETED');
  assert.equal(sql(`SELECT COUNT(*) FROM notification_inbox WHERE org_id=1 AND user_id=${staffId} AND message_id=${recovered.id};`),'1');pass('expired-lease-recovery-is-idempotent');
  fs.writeFileSync(path.join(runtime,'http-report.json'),JSON.stringify({result:'PASS',base,startedAt,finishedAt:new Date().toISOString(),checks},null,2));
} finally {
  stream?.close();
  if(disabled)sql(`UPDATE sys_user SET status=1 WHERE id=${staffId};`);
  if(grant)sql(`INSERT IGNORE INTO role_grant(org_id,role_id,permission_id,scope_type,pack_version) VALUES(1,${roleId},${grant[0]},'${grant[1]}',${grant[2]});`);
}
