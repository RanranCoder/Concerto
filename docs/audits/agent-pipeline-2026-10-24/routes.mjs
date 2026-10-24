import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const out=path.dirname(fileURLToPath(import.meta.url));
const root=path.resolve(out,'../../..');
const require=createRequire(path.join(root,'pams-web/package.json'));
const {matchRoutes}=require('react-router-dom');
const source=fs.readFileSync(path.join(root,'pams-web/src/router/index.tsx'),'utf8');
const routes=[...source.matchAll(/path:\s*['"](\/[^'"]*)['"]/g)].map(m=>({path:m[1]}));
// Nested routes, expanded from the current router configuration.
routes.push(...['/members/:id','/party/members','/party/members/:id','/party/rosters'].map(p=>({path:p})));
const inventory=JSON.parse(fs.readFileSync(path.join(out,'inventory.json'),'utf8'));
const rows=inventory.tools.flatMap(t=>t.links.map(prefix=>{
  const url=prefix.endsWith('/')?prefix+'1':prefix.endsWith('=')?prefix+'1':prefix;
  return {tool:t.name,prefix,probe:url,registered:!!matchRoutes(routes,url)};
}));
fs.writeFileSync(path.join(out,'routes.json'),JSON.stringify(rows,null,2));
console.log(JSON.stringify({checked:rows.length,matched:rows.filter(r=>r.registered).length,unmatched:rows.filter(r=>!r.registered)},null,2));
