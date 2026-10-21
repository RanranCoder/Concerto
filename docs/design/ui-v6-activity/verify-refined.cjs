const {chromium}=require('../../../pams-web/node_modules/playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{pathToFileURL}=require('node:url');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:path.join(process.env.LOCALAPPDATA,'ms-playwright/chromium_headless_shell-1247/chrome-headless-shell-win64/chrome-headless-shell.exe')});
 const page=await browser.newPage({viewport:{width:1440,height:1100},deviceScaleFactor:1}),errors=[],requests=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});
 const url=pathToFileURL(path.resolve(__dirname,'../V6.html')).href;
 const go=async(theme='light',tab='plan')=>{await page.goto(url+'?view=detail&activity=1&tab='+tab+'#'+theme);if(tab==='plan')await page.waitForSelector('#ev-word-frame[data-ready="true"]');};
 const action=async(a)=>page.locator(`[data-ev="${a}"]:visible`).first().click();
 const shot=async(name)=>page.screenshot({path:path.join(__dirname,'refined-'+name+'.png'),fullPage:true,animations:'disabled'});
 const frame=()=>page.frameLocator('#ev-word-frame');
 const checkPaper=async()=>{
   const result=await page.locator('#ev-word-frame').evaluate(f=>[...f.contentDocument.querySelectorAll('.ev-a4-sheet,section.ev-docx')].map(p=>{const r=p.getBoundingClientRect();return {width:r.width,height:r.height,bg:getComputedStyle(p).backgroundColor};}));
   assert(result.length>=2);result.forEach(p=>{assert(Math.abs(p.width/p.height-210/297)<.001,JSON.stringify(result));assert.equal(p.bg,'rgb(255, 255, 255)');assert(Math.abs(p.width-210*96/25.4)<1);});
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 };
 for(const theme of ['light','dark']){
   await go(theme);await checkPaper();await shot('plan-desktop-'+theme);
   await page.locator('#ev-word-zoom').selectOption('100');await checkPaper();
   await action('edit-activity');await page.waitForTimeout(250);let box=await page.locator('#ev-dialog').boundingBox();assert(Math.abs(box.x+box.width-1440)<1);assert.equal(Math.round(box.height),1100);await shot('activity-drawer-'+theme);
   const before=await page.locator('.ev-drawer footer').boundingBox();await page.locator('.ev-drawer .ev-dialog-body').evaluate(e=>e.scrollTop=e.scrollHeight);const after=await page.locator('.ev-drawer footer').boundingBox();assert.equal(before.y,after.y);
   await page.locator('[name="title"]').fill('未保存修改');await page.keyboard.press('Escape');assert(await page.locator('.ev-unsaved').isVisible());await shot('drawer-unsaved-'+theme);await action('continue-edit');assert(!await page.locator('.ev-unsaved').isVisible());await action('close');await action('discard-edit');await page.waitForSelector('#ev-dialog',{state:'hidden'});
   await action('edit-plan');await page.waitForTimeout(250);assert.equal(await page.locator('.ev-drawer textarea').count(),7);await shot('plan-drawer-'+theme);await action('close');await page.waitForSelector('#ev-dialog',{state:'hidden'});
   await page.locator('[data-ev-tab="articles"]').click();await action('new-article');await page.waitForTimeout(250);assert(await page.locator('#ev-dialog.ev-drawer').isVisible());await shot('article-drawer-'+theme);await action('close');await page.waitForSelector('#ev-dialog',{state:'hidden'});
   await page.setViewportSize({width:390,height:844});await go(theme);await checkPaper();await shot('plan-mobile-'+theme);
   await page.locator('#ev-word-zoom').selectOption('100');await checkPaper();assert(await page.locator('#ev-word-canvas').evaluate(e=>e.scrollWidth>e.clientWidth));await shot('plan-mobile-100-'+theme);
   await action('edit-activity');await page.waitForTimeout(250);box=await page.locator('#ev-dialog').boundingBox();assert.equal(Math.round(box.width),390);assert.equal(Math.round(box.height),844);await shot('activity-drawer-mobile-'+theme);await action('close');await page.waitForSelector('#ev-dialog',{state:'hidden'});
   await page.setViewportSize({width:1440,height:1100});
 }
 await go('dark');await page.locator('#ev-word-upload').setInputFiles(path.join(__dirname,'fixtures/a4-plan.docx'));
 await page.waitForFunction(()=>document.querySelector('#ev-word-notice').textContent.startsWith('本地试阅：'));
 await checkPaper();assert((await frame().locator('body').innerText()).includes('文末校验：全部内容显示完成。'));
 assert(await frame().locator('table').count()>0);assert(await frame().locator('img').count()>0);assert(await frame().locator('section.ev-docx').count()>=3);
 const flowedText=(await frame().locator('section.ev-docx > article').allTextContents()).join('');assert(flowedText.includes('工作人员提前到场，检查音响与投影，核对签到名单。'.repeat(180)),'long paragraph must retain its full text across page breaks');
 const overflow=await page.locator('#ev-word-frame').evaluate(f=>[...f.contentDocument.querySelectorAll('section.ev-docx')].map(s=>{const a=s.querySelector('article'),footer=s.querySelector('footer');return a.getBoundingClientRect().bottom>(footer?footer.getBoundingClientRect().top:s.getBoundingClientRect().bottom)+1;}));assert(!overflow.some(Boolean),'imported content must fit page');
 await shot('word-import-dark');
 await action('word-reset');await page.waitForSelector('#ev-word-frame[data-ready="true"]');
 const downloadEvent=page.waitForEvent('download');await action('word-download');const downloaded=await downloadEvent;assert(downloaded.suggestedFilename().includes('十月主题党日活动'));
 const exported=await require('../../../pams-web/node_modules/jszip').loadAsync(fs.readFileSync(await downloaded.path()));const xml=await exported.file('word/document.xml').async('string');assert(xml.includes('十月主题党日活动'));assert(xml.includes('11906'));assert(xml.includes('16838'));
 await page.locator('#ev-word-upload').setInputFiles({name:'exported.docx',mimeType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',buffer:fs.readFileSync(await downloaded.path())});await page.waitForFunction(()=>document.querySelector('#ev-word-notice').textContent.startsWith('本地试阅：exported.docx'));await checkPaper();assert((await frame().locator('body').innerText()).includes('十月主题党日活动'));await shot('word-export-roundtrip');
 await page.locator('#ev-word-upload').setInputFiles({name:'broken.docx',mimeType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',buffer:Buffer.from('not a docx')});await page.waitForSelector('#ev-word-notice.error');assert((await page.locator('#ev-word-notice').innerText()).includes('无法读取'));await shot('word-import-error');
 await page.locator('#ev-word-upload').setInputFiles({name:'old.doc',mimeType:'application/msword',buffer:Buffer.from('old')});assert((await page.locator('#ev-word-notice').innerText()).includes('另存为 .docx'));
 await go();await action('edit-plan');const longContent='核心工作流需要完整保留每一段策划内容。'.repeat(130);await page.locator('[name="content"]').fill(longContent);await page.locator('[name="flow"]').fill('流程修改校验：按任务分工执行。');await page.locator('[name="notice"]').fill('注意事项修改校验：核对签到名单。');await page.locator('[name="emergency"]').fill('应急预案修改校验：切换备用设备。');await page.locator('#ev-dialog button[type="submit"]').click();await page.waitForSelector('#ev-word-frame[data-ready="true"]');
 const sampleText=(await frame().locator('.ev-sample-body').allTextContents()).join('');assert(sampleText.includes(longContent));assert(sampleText.includes('流程修改校验'));assert(sampleText.includes('注意事项修改校验'));assert(sampleText.includes('应急预案修改校验'));assert(await frame().locator('.ev-a4-sheet').count()>2);await checkPaper();await shot('long-plan-paginated');
 await action('plan-details');assert(await page.locator('#ev-dialog.ev-drawer').isVisible());await shot('review-drawer');
 assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
 fs.writeFileSync(path.join(__dirname,'refined-verification.json'),JSON.stringify({result:'pass',checks:['A4 210×297mm fixed dimensions, white pages in both themes','desktop/mobile fit and 100% zoom, no layout reflow','DOCX local rendering: headings, table, image, header/footer, explicit and overflow page breaks','long paragraphs continue onto additional fixed A4 sheets','unsupported DOC and invalid DOCX feedback','all data-entry forms use full-height right drawers','fixed header/footer, independent scrolling, unsaved-change guard','activity/plan/article drawers in both themes and mobile','no script errors or external requests'],screenshots:fs.readdirSync(__dirname).filter(n=>n.startsWith('refined-')&&n.endsWith('.png')).length},null,2));
 console.log('PASS: refined A4/Word/drawer checks');await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
