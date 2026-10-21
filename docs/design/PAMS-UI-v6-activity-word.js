/* Local DOCX review surface. Fixed A4 pages live in an isolated, always-white iframe. */
(() => {
  'use strict';
  const E=esc,$=(s,r=document)=>r.querySelector(s);
  const sources=new Map();let current=null,resize=null,loadId=0;
  const sampleToc=()=>['活动背景','活动目的','活动安排','责任分工','经费预算','注意事项','应急预案'].map((t,i)=>`<button type="button" data-ev="word-section" data-value="ev-plan-section-${i}">${t}</button>`).join('');
  const css=`
    :root{color-scheme:light!important;background:#e7e9ed;color:#222;font:14px/1.8 'SimSun','Songti SC',serif}
    *{box-sizing:border-box}html,body{margin:0;padding:0;width:210mm;background:transparent;color:#222}
    .ev-a4-sheet,section.ev-docx{width:210mm!important;height:297mm!important;min-height:297mm!important;max-height:297mm!important;position:relative;display:block!important;margin:0 0 24px!important;background:#fff!important;color:#222;box-shadow:0 2px 8px #202b3a20;overflow:hidden;flex-shrink:0;box-sizing:border-box!important}
    .ev-a4-sheet:last-child,section.ev-docx:last-child{margin-bottom:0!important}
    .ev-a4-sheet{padding:25.4mm 25.4mm 23mm}
    .ev-doc-title{text-align:center;font-size:24px;font-weight:bold;line-height:1.8;margin:8px 0 18px;font-family:'SimSun','Songti SC',serif}
    .ev-doc-meta{text-align:center;font-size:12px;color:#555;line-height:1.8;margin:0 0 28px}
    .ev-a4-sheet h2{font-size:16px;line-height:1.8;margin:22px 0 9px;break-after:avoid}
    .ev-a4-sheet p{font-size:14px;line-height:1.9;margin:0 0 10px;text-align:justify;white-space:pre-wrap;overflow-wrap:anywhere}
    .ev-doc-running{position:absolute;left:25.4mm;right:25.4mm;top:12mm;font-size:10px;color:#777;border-bottom:1px solid #d9d9d9;padding-bottom:6px}
    .ev-doc-page-number{position:absolute;left:0;right:0;bottom:12mm;text-align:center;font:11px/1.5 'SimSun',serif;color:#555}
    .ev-a4-sheet table{width:100%;border-collapse:collapse;font-size:13px;color:#222;table-layout:fixed;margin:14px 0}
    .ev-a4-sheet td,.ev-a4-sheet th{border:1px solid #999;padding:9px 10px;vertical-align:top;line-height:1.8;overflow-wrap:anywhere;text-align:left;font-size:13px;font-weight:normal}
    .ev-a4-sheet th{background:#f5f5f5;font-weight:bold}.ev-a4-sheet th:first-child{width:19%}.ev-a4-sheet th:last-child{width:20%}
    section.ev-docx{color:#222!important}section.ev-docx>article{min-height:0!important;flex:initial!important;margin:0;position:relative;overflow:visible}
    section.ev-docx>header,section.ev-docx>footer{position:relative;display:block;width:100%;font-size:11px}
    section.ev-docx>footer{position:absolute!important;bottom:12.7mm;left:25.4mm;right:25.4mm;width:auto!important;margin:0!important}
    section.ev-docx img{max-width:100%;object-fit:contain}section.ev-docx table{max-width:100%}a{color:#135fb1}
    @media print{.ev-a4-sheet,section.ev-docx{box-shadow:none;margin:0!important;break-after:page}html,body{background:#fff}}
  `;
  function sample(a,p,old){
    const title=`<div class="ev-doc-title">${E(a.title)}<br>活动策划书</div><div class="ev-doc-meta">${E(a.department)}<br>${E(a.date)}　版本 ${old?'v2':'v'+p.version}</div>`;
    const number=n=>`<div class="ev-doc-page-number">— ${n} —</div>`;
    const running=`<div class="ev-doc-running">${E(a.title)} / 活动策划书</div>`;
    const execution=(p.content?'<h2>活动内容</h2><p>'+E(p.content)+'</p>':'')+(p.flow?'<h2>执行流程</h2><p>'+E(p.flow)+'</p>':'');
    return `<article class="ev-a4-sheet" aria-label="第 1 页">${running}${title}<h2 id="ev-plan-section-0">一、活动背景</h2><p>${E(p.background)}</p><h2 id="ev-plan-section-1">二、活动目的</h2><p>${E(old?'围绕活动主题组织交流学习，明确参加范围与责任分工。':p.purpose)}</p><h2 id="ev-plan-section-2">三、活动安排</h2><p>活动时间：${E(a.date)} ${E(a.time)}<br>活动地点：${E(a.place)}<br>面向对象：${E(a.audience||'待确认')}</p><table><thead><tr><th>时间</th><th>活动环节</th><th>负责人</th></tr></thead><tbody>${a.data.agenda.map(x=>`<tr><td>${E(x.time)}</td><td>${E(x.title)}</td><td>${E(x.owner)}</td></tr>`).join('')}</tbody></table>${number(1)}</article><article class="ev-a4-sheet" aria-label="第 2 页">${running}<h2 id="ev-plan-section-3">四、责任分工</h2><p>${E(a.department)}负责统筹活动安排；${E(a.owner)}协调策划、会务与宣传。会务组完成设备调试、签到引导与现场秩序维护，宣传组负责图文记录和活动报道。</p>${execution}<h2 id="ev-plan-section-4">五、经费预算</h2><p>${E(p.budget)}</p><table><thead><tr><th>项目</th><th>用途</th><th>预算</th></tr></thead><tbody><tr><td>资料印制</td><td>活动学习材料、签到名单</td><td>120 元</td></tr><tr><td>场地布置</td><td>现场指引、会场布置</td><td>80 元</td></tr><tr><td>合计</td><td>按实际支出核销</td><td>200 元</td></tr></tbody></table><h2 id="ev-plan-section-5">六、注意事项</h2><p>${E(p.notice||'提前核对场地和参会名单。工作人员于活动开始前 30 分钟到场，检查设备、材料与签到安排。活动结束后及时整理现场，核对签到记录与相关材料。')}</p><h2 id="ev-plan-section-6">七、应急预案</h2><p>${E(p.emergency||'设备故障时由会务组切换备用设备；现场突发情况及时报告活动负责人，并引导人员有序离场。负责人统筹后续安排，指定专人记录处置情况。')}</p>${number(2)}</article>`;
  }
  function markup(a,p,old){
    current={a,p,old};const source=sources.get(a.id),zoom=source?.zoom||'fit';
    return `<div class="ev-word-layout"><aside class="ev-word-outline"><div class="ev-word-outline-title">文档目录</div><div id="ev-word-toc">${['活动背景','活动目的','活动安排','责任分工','经费预算','注意事项','应急预案'].map((t,i)=>`<button type="button" data-ev="word-section" data-value="ev-plan-section-${i}">${t}</button>`).join('')}</div><div class="ev-word-outline-footer">A4 纵向<br>210 × 297 mm</div></aside><div class="ev-word-main"><div class="ev-word-toolbar"><span class="ev-word-filename" id="ev-word-filename">${E(source?.name||'活动策划书.docx')}<small id="ev-word-source">${source?.buffer?'本地试阅':'排版示例'}</small></span><div class="ev-actions"><button class="link" id="ev-word-reset" data-ev="word-reset" type="button" ${source?.buffer?'':'hidden'}>返回活动策划书</button><label class="ev-word-zoom">缩放 <select id="ev-word-zoom" aria-label="文档缩放">${['fit','75','100','125','150'].map(v=>`<option value="${v}"${zoom===v?' selected':''}>${v==='fit'?'适合宽度':v+'%'}</option>`).join('')}</select></label><button class="icon-btn" type="button" data-ev="word-fullscreen" aria-label="展开文档阅读区"><svg viewBox="0 0 24 24"><path d="M9 3H3v6m12-6h6v6M3 15v6h6m12-6v6h-6"/></svg></button></div></div><div class="ev-word-notice" id="ev-word-notice" role="status" hidden></div><div class="ev-word-canvas" id="ev-word-canvas" tabindex="0" aria-label="A4 文档预览，可滚动浏览"><div class="ev-word-scaled" id="ev-word-scaled"><iframe id="ev-word-frame" title="白色 A4 策划书文档" sandbox="allow-same-origin"></iframe></div></div><div class="ev-word-status"><span id="ev-word-page-count">共 2 页</span><span>A4 纵向 · 白色纸张</span><span id="ev-word-scale-label">100%</span></div></div></div><input id="ev-word-upload" type="file" accept=".docx,.doc" hidden>`;
  }
  function stylesheet(doc){const style=doc.createElement('style');style.textContent=css;doc.head.append(style);}
  function sheets(doc){return [...doc.querySelectorAll('.ev-a4-sheet,section.ev-docx')];}
  function fit(){
    const frame=$('#ev-word-frame'),canvas=$('#ev-word-canvas'),scaled=$('#ev-word-scaled');if(!frame?.contentDocument?.body||!canvas)return;
    const doc=frame.contentDocument,width=210*96/25.4,pages=sheets(doc),height=pages.length?pages[pages.length-1].offsetTop+pages[pages.length-1].offsetHeight:297*96/25.4;
    const mode=$('#ev-word-zoom')?.value||'fit',scale=mode==='fit'?Math.min(1,Math.max(.1,(canvas.clientWidth-48)/width)):Number(mode)/100;
    frame.style.width=width+'px';frame.style.height=height+'px';frame.style.transform=`scale(${scale})`;scaled.style.width=width*scale+'px';scaled.style.height=height*scale+'px';
    $('#ev-word-page-count').textContent='共 '+Math.max(1,pages.length)+' 页';$('#ev-word-scale-label').textContent=Math.round(scale*100)+'%';
    if(current){const state=sources.get(current.a.id)||{};state.zoom=mode;sources.set(current.a.id,state);}
  }
  function sanitizeDocument(doc){
    doc.querySelectorAll('script,iframe,object,embed,link,base').forEach(n=>n.remove());
    doc.body.querySelectorAll('*').forEach(n=>{[...n.attributes].forEach(a=>{if(/^on/i.test(a.name))n.removeAttribute(a.name);});if(n.tagName==='A'){n.removeAttribute('href');n.removeAttribute('target');}if(n.tagName==='IMG'&&!/^(data:|blob:)/i.test(n.getAttribute('src')||''))n.remove();});
  }
  // DOCX supplies explicit page breaks; overflow between them is flowed onto additional A4 sheets.
  function paginate(doc){
    const splitText=(node,index)=>{
      const walker=doc.createTreeWalker(node,4);let text,remaining=index;
      while((text=walker.nextNode())){if(remaining<=text.length)break;remaining-=text.length;}
      if(!text)return null;
      const before=doc.createRange();before.selectNodeContents(node);before.setEnd(text,remaining);
      const after=doc.createRange();after.selectNodeContents(node);after.setStart(text,remaining);
      const first=node.cloneNode(false),rest=node.cloneNode(false);first.append(before.cloneContents());rest.append(after.cloneContents());return [first,rest];
    };
    for(const section of [...doc.querySelectorAll('section.ev-docx,.ev-a4-sheet')]){
      const article=section.querySelector(':scope > article,:scope > .ev-sample-body');if(!article)continue;
      const original=section.cloneNode(true),nodes=[...article.childNodes];article.replaceChildren();
      let page=section,content=article;
      const newPage=()=>{const next=original.cloneNode(true);next.querySelector(':scope > article,:scope > .ev-sample-body').replaceChildren();page.after(next);page=next;content=next.querySelector(':scope > article,:scope > .ev-sample-body');};
      const available=()=>{const style=doc.defaultView.getComputedStyle(page),header=page.querySelector(':scope > header'),footer=page.querySelector(':scope > footer');return page.clientHeight-parseFloat(style.paddingTop)-parseFloat(style.paddingBottom)-(header?.getBoundingClientRect().height||0)-(footer?.getBoundingClientRect().height||0)-12;};
      for(const node of nodes){
        if(node.nodeType===1)node.querySelectorAll('img').forEach(img=>{img.style.maxHeight=Math.max(100,available()-40)+'px';});
        content.append(node);
        if(content.scrollHeight<=available()+1)continue;
        if(content.childNodes.length>1){node.remove();const heading=content.lastElementChild?.matches('h1,h2,h3')?content.lastElementChild:null;heading?.remove();newPage();if(heading)content.append(heading);content.append(node);}
        if(node.nodeType===1&&node.tagName==='TABLE'&&content.scrollHeight>available()){
          const table=node,rows=[...table.querySelectorAll('tr')],header=table.querySelector('thead')?.cloneNode(true);let chunk=table;
          for(const row of rows)row.remove();
          for(const row of rows){chunk.append(row);if(content.scrollHeight>available()&&chunk.querySelectorAll('tr').length>1){row.remove();newPage();chunk=table.cloneNode(false);if(header)chunk.append(header.cloneNode(true));content.append(chunk);chunk.append(row);}}
        }
        if(node.nodeType===1&&node.tagName!=='TABLE'&&node.textContent.length>1){
          let remaining=node;
          while(content.scrollHeight>available()+1&&remaining.textContent.length>1){
            let low=1,high=remaining.textContent.length-1,best=0;
            while(low<=high){const mid=Math.floor((low+high)/2),pair=splitText(remaining,mid);if(!pair)break;remaining.replaceWith(pair[0]);const fits=content.scrollHeight<=available();pair[0].replaceWith(remaining);if(fits){best=mid;low=mid+1;}else high=mid-1;}
            if(!best)break;
            const pair=splitText(remaining,best);remaining.replaceWith(pair[0]);newPage();remaining=pair[1];content.append(remaining);
          }
        }
        content.querySelectorAll('img').forEach(img=>{img.style.maxHeight=Math.max(100,available()-40)+'px';});
      }
    }
    sheets(doc).forEach((sheet,i)=>{const label=sheet.querySelector('.ev-doc-page-number');if(label)label.textContent='— '+(i+1)+' —';sheet.setAttribute('aria-label','第 '+(i+1)+' 页');});
  }
  function renderSample(doc,data){
    doc.body.innerHTML=sample(data.a,data.p,data.old);
    doc.querySelectorAll('.ev-a4-sheet').forEach(sheet=>{const body=doc.createElement('div');body.className='ev-sample-body';[...sheet.children].filter(n=>!n.matches('.ev-doc-running,.ev-doc-page-number')).forEach(n=>body.append(n));sheet.insertBefore(body,sheet.querySelector('.ev-doc-page-number'));});
    paginate(doc);
  }
  function notice(text,error=false){const el=$('#ev-word-notice');if(el){el.hidden=!text;el.classList.toggle('error',error);el.textContent=text;}}
  async function exportPlan(){
    const {a,p,old}=current,X=text=>String(text).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
    const run=(text,bold=false,size=24)=>`<w:r><w:rPr><w:rFonts w:ascii="SimSun" w:eastAsia="宋体"/><w:sz w:val="${size}"/>${bold?'<w:b/>':''}</w:rPr><w:t xml:space="preserve">${X(text)}</w:t></w:r>`;
    const para=(text,{bold=false,center=false,size=24}={})=>`<w:p><w:pPr><w:spacing w:after="140" w:line="360" w:lineRule="auto"/>${center?'<w:jc w:val="center"/>':''}</w:pPr>${String(text).split('\n').map((line,i)=>(i?'<w:r><w:br/></w:r>':'')+run(line,bold,size)).join('')}</w:p>`;
    const table=rows=>`<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders>${['top','left','bottom','right','insideH','insideV'].map(s=>`<w:${s} w:val="single" w:sz="4" w:color="888888"/>`).join('')}</w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w="1650"/><w:gridCol w:w="5800"/><w:gridCol w:w="1576"/></w:tblGrid>${rows.map(row=>`<w:tr>${row.map(text=>`<w:tc><w:tcPr/><w:p>${run(text)}</w:p></w:tc>`).join('')}</w:tr>`).join('')}</w:tbl>`;
    const ns='http://schemas.openxmlformats.org/wordprocessingml/2006/main';
    const body=para(a.title+'\n活动策划书',{bold:true,center:true,size:36})+para(a.department+'\n'+a.date+'　版本 '+(old?'v2':'v'+p.version),{center:true,size:22})+para('一、活动背景',{bold:true,size:28})+para(p.background)+para('二、活动目的',{bold:true,size:28})+para(p.purpose)+para('三、活动安排',{bold:true,size:28})+para('活动时间：'+a.date+' '+a.time+'\n活动地点：'+a.place+'\n面向对象：'+a.audience)+table([['时间','活动环节','负责人'],...a.data.agenda.map(x=>[x.time,x.title,x.owner])])+'<w:p><w:r><w:br w:type="page"/></w:r></w:p>'+para('四、责任分工',{bold:true,size:28})+para(a.department+'统筹活动，'+a.owner+'协调策划、会务与宣传。')+(p.content?para('活动内容',{bold:true})+para(p.content):'')+(p.flow?para('活动流程',{bold:true})+para(p.flow):'')+para('五、经费预算',{bold:true,size:28})+para(p.budget)+para('六、注意事项',{bold:true,size:28})+para(p.notice||'提前核对场地和参会名单，检查设备与签到安排。')+para('七、应急预案',{bold:true,size:28})+para(p.emergency||'设备故障时切换备用设备，突发情况及时报告活动负责人。');
    const zip=new JSZip();
    zip.file('[Content_Types].xml',`<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>${[['/word/document.xml','document.main'],['/word/header1.xml','header'],['/word/footer1.xml','footer']].map(([part,type])=>`<Override PartName="${part}" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.${type}+xml"/>`).join('')}</Types>`);
    zip.file('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
    zip.file('word/_rels/document.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/><Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>');
    zip.file('word/document.xml',`<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="${ns}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>${body}<w:sectPr><w:headerReference w:type="default" r:id="rIdHeader"/><w:footerReference w:type="default" r:id="rIdFooter"/><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720"/></w:sectPr></w:body></w:document>`);
    zip.file('word/header1.xml',`<w:hdr xmlns:w="${ns}">${para(a.title+' / 活动策划书',{size:20})}</w:hdr>`);
    zip.file('word/footer1.xml',`<w:ftr xmlns:w="${ns}"><w:p><w:pPr><w:jc w:val="center"/></w:pPr>${run('— ',false,20)}<w:fldSimple w:instr="PAGE">${run('1',false,20)}</w:fldSimple>${run(' —',false,20)}</w:p></w:ftr>`);
    const blob=await zip.generateAsync({type:'blob',mimeType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}),url=URL.createObjectURL(blob),anchor=document.createElement('a');anchor.href=url;anchor.download=a.title+'-策划书.docx';anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  }
  async function mount(){
    const frame=$('#ev-word-frame');if(!frame||!current)return;
    const token=++loadId,data=current;resize?.disconnect();
    frame.srcdoc='<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src data: blob:; font-src data: blob:"></head><body></body></html>';
    await new Promise(resolve=>frame.addEventListener('load',resolve,{once:true}));if(token!==loadId||!frame.isConnected)return;
    const doc=frame.contentDocument,state=sources.get(data.a.id);stylesheet(doc);
    if(state?.buffer){
      notice('正在读取本地 Word 文档…');
      try{
        if(!window.docx?.renderAsync)throw Error('Word 预览组件未加载');
        const documentStyles=doc.createElement('div');doc.head.append(documentStyles);
        await docx.renderAsync(state.buffer,doc.body,documentStyles,{className:'ev-docx',inWrapper:false,ignoreWidth:true,ignoreHeight:true,breakPages:true,ignoreLastRenderedPageBreak:false,renderHeaders:true,renderFooters:true,renderFootnotes:true,renderEndnotes:true,renderAltChunks:false,useBase64URL:true});
        if(token!==loadId||!frame.isConnected)return;stylesheet(doc);sanitizeDocument(doc);await doc.fonts.ready;await Promise.all([...doc.images].map(img=>img.decode().catch(()=>{})));paginate(doc);
        const titles=[...doc.body.querySelectorAll('h1,h2,h3,p')].filter(n=>/^([一二三四五六七八九十]+、|[1-9][.、])/.test(n.textContent.trim())).slice(0,16);
        $('#ev-word-toc').innerHTML=titles.length?titles.map((n,i)=>{n.id='ev-import-section-'+i;return `<button type="button" data-ev="word-section" data-value="${n.id}">${E(n.textContent.slice(0,36))}</button>`;}).join(''):'<p class="ev-muted">该文档没有可识别的章节标题，可直接滚动阅读。</p>';
        notice(state.generated?'当前策划书的 A4 分页预览，与导出 Word 使用同一份正文。':'本地试阅：'+state.name+'。只在浏览器中读取，未上传或替换活动策划书。');
      }catch(error){
        renderSample(doc,data);$('#ev-word-toc').innerHTML=sampleToc();sources.set(data.a.id,{zoom:state.zoom||'fit'});$('#ev-word-filename').innerHTML='活动策划书.docx<small id="ev-word-source">排版示例</small>';$('#ev-word-reset').hidden=true;notice('无法读取此文件，请确认文件是有效的 .docx 格式后重试。当前显示活动排版示例。',true);
      }
    }else{renderSample(doc,data);$('#ev-word-toc').innerHTML=sampleToc();}
    fit();resize=new ResizeObserver(fit);resize.observe($('#ev-word-canvas'));
    $('#ev-word-frame').dataset.ready='true';
  }
  function handle(action,value){
    if(action==='word-import'){$('#ev-word-upload')?.click();return true;}
    if(action==='word-reset'){sources.delete(current.a.id);const input=$('#ev-word-upload');if(input)input.value='';$('#ev-word-filename').innerHTML='活动策划书.docx<small id="ev-word-source">排版示例</small>';$('#ev-word-reset').hidden=true;mount();notice('');return true;}
    if(action==='word-section'){
      const frame=$('#ev-word-frame'),section=frame?.contentDocument?.getElementById(value);if(section){let offset=section.offsetTop,ancestor=section.offsetParent;while(ancestor&&ancestor!==frame.contentDocument.body){offset+=ancestor.offsetTop;ancestor=ancestor.offsetParent;}const scale=parseFloat(frame.style.transform.match(/scale\(([^)]+)/)?.[1]||1);$('#ev-word-canvas').scrollTo({top:offset*scale-24,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});}return true;
    }
    if(action==='word-fullscreen'){const root=$('.ev-plan-workspace'),expanded=root.classList.toggle('ev-word-expanded'),button=$('[data-ev="word-fullscreen"]');button.setAttribute('aria-label',expanded?'收起文档阅读区':'展开文档阅读区');button.setAttribute('aria-pressed',String(expanded));fit();return true;}
    if(action==='word-download'){const state=sources.get(current.a.id);if(!state?.buffer){exportPlan();return true;}const anchor=document.createElement('a'),url=URL.createObjectURL(new Blob([state.buffer],{type:'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}));anchor.href=url;anchor.download=state.name;anchor.click();setTimeout(()=>URL.revokeObjectURL(url),1000);return true;}
    return false;
  }
  document.addEventListener('change',async e=>{
    if(e.target.id==='ev-word-zoom'){fit();return;}
    if(e.target.id!=='ev-word-upload')return;
    const file=e.target.files[0];if(!file||!current)return;
    if(!/\.docx$/i.test(file.name)){notice('旧版 .doc 请先在 Word 中另存为 .docx 后再打开。',true);e.target.value='';return;}
    const id=current.a.id,buffer=await file.arrayBuffer();sources.set(id,{name:file.name,buffer,zoom:$('#ev-word-zoom')?.value||'fit'});if(current.a.id!==id)return;
    $('#ev-word-filename').innerHTML=E(file.name)+'<small id="ev-word-source">本地试阅</small>';$('#ev-word-reset').hidden=false;mount();
  });
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!document.querySelector('dialog[open]')&&$('.ev-word-expanded')){$('.ev-word-expanded').classList.remove('ev-word-expanded');fit();}});
  async function showBuffer(buffer,name){
    if(!current)return;
    sources.set(current.a.id,{buffer,name,zoom:'fit',generated:true});
    await mount();
    const filename=$('#ev-word-filename');if(filename)filename.textContent=name;
    const reset=$('#ev-word-reset');if(reset)reset.hidden=true;
  }
  function clearSource(id){sources.delete(id);loadId++;}
  window.ActivityWord={markup,mount,handle,showBuffer,clearSource};
})();
