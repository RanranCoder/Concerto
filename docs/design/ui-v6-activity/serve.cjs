// Local static preview server, limited to docs/design. Start from any directory.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..');
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.webp':'image/webp','.svg':'image/svg+xml','.json':'application/json; charset=utf-8'};
http.createServer((req,res)=>{
  let target;
  try{const requested=decodeURIComponent(new URL(req.url,'http://localhost').pathname);target=path.resolve(root,'.'+(requested==='/'?'/V6.html':requested));}catch{res.writeHead(400).end();return;}
  if(!target.startsWith(root+path.sep)){res.writeHead(403).end();return;}
  fs.stat(target,(error,stat)=>{
    if(error||!stat.isFile()){res.writeHead(404).end();return;}
    res.writeHead(200,{'Content-Type':mime[path.extname(target).toLowerCase()]||'application/octet-stream','Cache-Control':'no-store'});fs.createReadStream(target).pipe(res);
  });
}).listen(4176,'127.0.0.1',()=>console.log('V6 activity preview: http://127.0.0.1:4176/V6.html?view=activities#light'));
