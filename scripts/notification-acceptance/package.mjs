import fs from 'node:fs';import path from 'node:path';import { fileURLToPath } from 'node:url';import { createHash } from 'node:crypto';import { spawnSync } from 'node:child_process';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const runtime=path.join(root,'.backups/notification-acceptance');const destination=path.join(runtime,'candidate-'+Date.now());fs.mkdirSync(destination,{recursive:true});
const hash=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const files=[];
function copy(source,relative){const target=path.join(destination,relative);fs.mkdirSync(path.dirname(target),{recursive:true});fs.copyFileSync(source,target);files.push({path:relative,sha256:hash(target)});}
copy(path.join(root,'pams-backend/target/pams-backend-1.0.0.jar'),'app.jar');
copy(path.join(root,'pams-web/nginx.conf'),'nginx.conf');
function walk(dir,prefix){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){if(entry.isSymbolicLink())throw Error('Release assets must not contain symlinks');const source=path.join(dir,entry.name),relative=prefix+'/'+entry.name;if(entry.isDirectory())walk(source,relative);else if(entry.isFile())copy(source,relative);}}
walk(path.join(root,'pams-web/dist'),'web');
const git=args=>{const r=spawnSync('git',args,{cwd:root,encoding:'utf8'});if(r.status!==0)throw Error('Git metadata unavailable');return r.stdout.trim();};
fs.writeFileSync(path.join(destination,'manifest.json'),JSON.stringify({sourceCommit:git(['rev-parse','HEAD']),workingTreeDirty:Boolean(git(['status','--porcelain'])),createdAt:new Date().toISOString(),target:'172.18.38.131:8081',approvalRequired:true,files},null,2));
const archive=path.join(runtime,'notification-candidate.tar.gz');const tar=process.platform==='win32'?'C:\\Windows\\System32\\tar.exe':'tar';
const packed=spawnSync(tar,['-czf',archive,'-C',destination,'.'],{cwd:root,encoding:'utf8'});if(packed.status!==0)throw Error('Candidate packaging failed');
const result={archive:'.backups/notification-acceptance/notification-candidate.tar.gz',sha256:hash(archive),backendSha256:hash(path.join(destination,'app.jar')),sourceCommit:git(['rev-parse','HEAD'])};
fs.writeFileSync(path.join(runtime,'candidate-summary.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
