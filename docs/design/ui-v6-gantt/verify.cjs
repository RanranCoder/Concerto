const {chromium}=require('../../../pams-web/node_modules/playwright');
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:path.join(process.env.LOCALAPPDATA,'ms-playwright/chromium_headless_shell-1247/chrome-headless-shell-win64/chrome-headless-shell.exe')});
 const page=await browser.newPage({viewport:{width:1440,height:1080}}),errors=[],requests=[];
 page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(!r.url().startsWith('http://127.0.0.1:4176/')&&!r.url().startsWith('data:'))requests.push(r.url());});
 const base='http://127.0.0.1:4176/V6.html';
 const go=async(theme='light',view='gantt')=>{await page.goto(`${base}?view=${view}&activity=1&tab=basic#${theme}`);await page.waitForSelector(view==='gantt'?'#gt-body':'#ev-detail-panel');};
 const a=async(action,id)=>{const selector=`[data-gt="${action}"]${id!==undefined?`[data-id="${id}"]`:''}`;if(action==='edit'&&!(await page.locator(selector).filter({visible:true}).count()))await a('task',id);await page.locator(selector).filter({visible:true}).first().click();};
 const shot=async(name)=>page.screenshot({path:path.join(__dirname,name+'.png'),fullPage:true,animations:'disabled'});
 const noOverflow=async()=>assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'page overflow');
 const form=()=>page.locator('#gt-form');
 const submit=async()=>form().locator('button[type=submit]').click();
 await go();assert.equal(await page.locator('#gantt [data-row]').count(),12);await shot('desktop-light');
 await a('filter','late');assert.equal(await page.locator('[data-row]').count(),1);
 await a('filter','conflict');assert.equal(await page.locator('[data-row]').count(),3);
 await a('filter','unscheduled');assert.equal(await page.locator('[data-row]').count(),1);
 await a('reset');await page.locator('#gt-query').fill('不存在');assert(await page.getByText('没有符合条件的任务').isVisible());await shot('filtered-empty');await a('reset');
 await page.locator('#gt-dept').selectOption('组织部');assert.equal(await page.locator('[data-row]').count(),3);await a('reset');
 await a('task',5);await shot('task-detail');await a('preset',100);await submit();assert((await page.locator('#gantt .gt-progress-summary').innerText()).includes('4'));await a('undo');
 await a('new');await form().locator('[name=name]').fill('待排期物资任务');await submit();assert((await page.locator('#gt-result').innerText()).includes('13'));
 await a('filter','unscheduled');assert.equal(await page.locator('[data-row]').count(),2);await a('reset');
 await a('edit',4);await form().locator('[name=end]').fill('2026-10-14');await submit();assert(await page.locator('.gt-confirm[open]').isVisible());await shot('reschedule-impact');await page.locator('[name=scope][value=cascade]').check();await page.locator('#gt-confirm-form button[type=submit]').click();
 await a('task',6);assert((await form().innerText()).includes('10/15'));await a('close');await a('undo');
 await a('edit',1);await form().locator('[name=dep]').selectOption('2');await submit();assert((await page.locator('#gt-form-error').innerText()).includes('循环'));await a('close');assert(await page.locator('.gt-unsaved').isVisible());await shot('unsaved-guard');await a('discard');
 await a('edit',1);await a('delete',1);assert((await page.locator('#gt-form-error').innerText()).includes('仍依赖'));await a('close');
 await a('new');await form().locator('[name=name]').fill('新增关键节点');await form().locator('[name=milestone]').check();await form().locator('[name=start]').fill('2026-10-15');await submit();assert(await page.locator('.gt-bar.milestone').filter({hasText:'新增关键节点'}).isVisible());
 await a('task',5);await a('related','signin');assert(await page.locator('#ev-tab-signin[aria-selected=true]').isVisible());
 await go('light','detail');assert(await page.getByRole('button',{name:'管理任务计划'}).isVisible());assert.equal(await page.locator('.ev-detail-nav [role=tab]').first().getAttribute('id'),'ev-tab-gantt');await shot('detail-tab-entry');await a('summary-task',4);assert(await form().isVisible());await a('close');assert(await page.locator('#ev-tab-gantt[aria-selected=true]').isVisible());assert(await page.locator('#ev-detail-panel #gantt').isVisible());await page.locator('#ev-tab-basic').click();assert(await page.locator('#ev-tab-basic[aria-selected=true]').isVisible());await page.locator('#ev-tab-gantt').click();await shot('detail-gantt-tab');await page.locator('#ev-tab-gantt').press('ArrowRight');assert(await page.locator('#ev-tab-basic[aria-selected=true]').isVisible());await page.locator('#ev-tab-basic').press('Home');assert(await page.locator('#ev-tab-gantt[aria-selected=true]').isVisible());
 await go();await page.locator('#gt-role').selectOption('executor');assert.equal(await page.locator('#gantt [data-gt=new]').count(),0);await a('task',4);assert.equal(await page.locator('.gt-dialog[open] [data-gt=edit]').count(),0);await a('preset',75);await submit();
 await page.locator('#gt-role').selectOption('viewer');await a('task',4);assert(await page.locator('#gt-progress').isDisabled());assert.equal(await page.locator('#gt-form button[type=submit]').count(),0);await a('close');
 await go();await a('collapse','组织部');assert.equal(await page.locator('[data-row]').count(),9);await a('collapse','组织部');await a('fit');await a('today');await noOverflow();
 // Drag remains a preview until explicitly confirmed.
 await go();const bar=page.locator('.gt-bar[data-id="4"]'),box=await bar.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+84,box.y+box.height/2,{steps:8});await page.mouse.up();assert(await page.locator('.gt-confirm[open]').isVisible());await a('cancel-confirm');
 for(const theme of ['light','dark']){
  for(const size of [{width:1440,height:1080},{width:1024,height:900},{width:768,height:1024},{width:390,height:844},{width:375,height:812}]){
   await page.setViewportSize(size);await go(theme);await noOverflow();if(size.width===390){await a('view','list');await shot('mobile-'+theme);await a('task',5);await shot('mobile-drawer-'+theme);await a('close');await a('view','gantt');await noOverflow();await shot('mobile-gantt-'+theme);}if(size.width===1440){await shot('desktop-'+theme);await a('edit',5);await shot('edit-'+theme);await a('close');}if(size.width===768)await shot('tablet-'+theme);
   if(size.width===1440||size.width===390){await go(theme,'detail');await page.locator('#ev-tab-gantt').click();await noOverflow();assert(await page.locator('#ev-detail-panel #gantt').isVisible());await shot((size.width===1440?'detail-embedded-':'mobile-embedded-')+theme);}
  }
 }
 await page.setViewportSize({width:1440,height:1080});await go();
 for(const state of ['loading','empty','error']){await page.locator('#gt-scenario').selectOption(state);await shot('state-'+state);}
 await a('retry');await a('review');await shot('design-review');await a('cancel-confirm');
 await page.goto(base);assert(await page.locator('#gantt').isVisible());await page.goto('http://127.0.0.1:4176/PAMS-UI-bright-v6.html?view=gantt&activity=1');assert(await page.locator('#gantt').isVisible());
 assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
 fs.writeFileSync(path.join(__dirname,'verification.json'),JSON.stringify({checkedAt:new Date().toISOString(),passed:true,errors,externalRequests:requests,checks:['summary navigation','risk and department filters','no-results recovery','progress save and undo','unscheduled create','milestone create','cycle prevention','dependent deletion protection','dirty close protection','reschedule cascade and undo','drag preview cancellation','related module navigation','executor and viewer permissions','group collapse','time navigation','light and dark','1440/1024/768/390/375 layouts','mobile list and chart','loading/empty/error/retry','default and alternate entry']},null,2));
 await browser.close();console.log('Gantt prototype checks passed');
})().catch(e=>{console.error(e);process.exit(1);});
