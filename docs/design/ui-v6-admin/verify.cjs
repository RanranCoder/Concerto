// Local prototype QA; does not navigate to or write to the application.
const { chromium } = require('../../../pams-web/node_modules/playwright');
const path = require('node:path');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const assert = require('node:assert/strict');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:path.join(process.env.LOCALAPPDATA,'ms-playwright/chromium_headless_shell-1247/chrome-headless-shell-win64/chrome-headless-shell.exe')});
  const page=await browser.newPage({viewport:{width:1440,height:1080},deviceScaleFactor:1});
  const errors=[], requests=[];
  page.on('pageerror',e=>errors.push(e.message));
  page.on('request',r=>{if(/^https?:/.test(r.url()))requests.push(r.url());});
  const url=pathToFileURL(path.resolve(__dirname,'../V6.html')).href;
  const output=__dirname;
  const go=async(view,theme='light')=>{await page.goto(url+'?view='+view+'#'+theme);await page.waitForFunction(()=>!!document.querySelector('#'+new URLSearchParams(location.search).get('view')+' .a-review-mark'));await page.waitForTimeout(100);};
  const click=async(selector)=>page.locator(selector).first().click();
  const screenshot=async(name)=>page.screenshot({path:path.join(output,name+'.png'),fullPage:true,animations:'disabled'});
  const visible=async(selector)=>assert(await page.locator(selector).isVisible(),selector+' must be visible');
  const noOverflow=async()=>assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'page horizontal overflow');
  await go('knowledge');await visible('#knowledge');await screenshot('knowledge-desktop-light');
  await click('[data-a="doc-detail"][data-value="1"]');await visible('dialog[open] .a-article');await screenshot('knowledge-document-light');await click('dialog [data-a="close"]');
  await page.locator('#a-kb-query').fill('不存在的内容');assert(await page.locator('#a-kb-pane').innerText().then(t=>t.includes('没有找到匹配资料')));await click('#knowledge [data-a="kb-reset"]');
  await click('[data-a="kb-source"][data-value="制度与指引"]');assert.equal(await page.locator('#a-kb-pane tbody tr').count(),2);await click('[data-a="kb-source"][data-value="全部资料"]');
  await click('[data-a="kb-tab"][data-value="检索记录"]');await screenshot('knowledge-retrievals-light');await click('[data-a="query-detail"][data-value="2"]');await visible('dialog[open] .a-log-flow');await click('dialog [data-a="close"]');
  await click('[data-a="kb-tab"][data-value="事实快照"]');await screenshot('knowledge-snapshots-light');await click('[data-a="doc-detail"][data-value="7"]');await visible('dialog[open] .a-snapshot-date');await click('dialog [data-a="close"]');
  await click('#knowledge [data-a="new-doc"]');await page.locator('[name="title"]').fill('原型验证文档');await page.locator('[name="body"]').fill('第一条 核对资料的来源、可见范围与引用位置。');await page.locator('#a-editor button[type="submit"]').click();assert(await page.locator('#a-kb-pane').innerText().then(t=>t.includes('原型验证文档')));
  await go('users');await visible('#users');await screenshot('users-desktop-light');
  await page.locator('#a-user-query').fill('周宁');assert.equal(await page.locator('#a-user-pane tbody tr').count(),1);await click('[data-a="user-reset"]');
  await click('[data-a="edit-user"][data-value="3"]');await page.locator('[name="role"]').selectOption({label:'总负责人'});await page.locator('#a-editor button[type="submit"]').click();await visible('.a-role-diff');await screenshot('users-role-preview-light');await click('[data-a="confirm-role"]');assert(await page.locator('#a-user-pane').innerText().then(t=>t.includes('总负责人')));
  await page.locator('[data-user-select="4"]').check();await visible('.a-batch');await click('[data-a="batch-status"][data-value="停用"]');await click('[data-a="apply-status"]');
  await click('[data-a="user-detail"][data-value="4"]');assert(await page.locator('#a-review-dialog').innerText().then(t=>t.includes('停用')));await click('dialog [data-a="close"]');
  await click('[data-a="user-detail"][data-value="1"]');await screenshot('users-account-detail-light');assert.equal(await page.locator('dialog [data-a="user-status"]').count(),0);await click('dialog [data-a="close"]');
  await click('#users [data-a="new-user"]');await page.locator('[name="username"]').fill('demo_review');await page.locator('[name="name"]').fill('演示账号');await page.locator('[name="password"]').fill('prototype-only');await page.locator('#a-editor button[type="submit"]').click();assert(await page.locator('#a-user-pane').innerText().then(t=>t.includes('演示账号')));
  await click('[data-a="user-detail"][data-value="10"]');await click('[data-a="delete-user"]');await page.locator('[name="confirmation"]').fill('demo_review');await page.locator('#a-editor button[type="submit"]').click();assert(!await page.locator('#a-user-pane').innerText().then(t=>t.includes('演示账号')));
  await go('settings');await visible('#settings');await screenshot('settings-desktop-light');await click('[data-a="settings-tab"][data-value="使用偏好"]');await click('[data-a="density"][data-value="紧凑"]');await click('.nav [data-view="users"]');await visible('dialog[open]');assert(await page.locator('#a-dialog-title').innerText().then(t=>t.includes('离开前保存偏好')));await click('[data-a="leave-save"]');await visible('#users .a-user-table.compact');
  await click('.nav [data-view="settings"]');await click('[data-a="settings-tab"][data-value="使用偏好"]');await screenshot('settings-preferences-light');await click('[data-a="settings-tab"][data-value="存储与诊断"]');await screenshot('settings-diagnostics-light');
  for(const theme of ['light','dark'])for(const view of ['knowledge','users','settings']){
    await page.setViewportSize({width:1440,height:1080});await go(view,theme);await noOverflow();if(theme==='dark')await screenshot(view+'-desktop-dark');
    await page.setViewportSize({width:390,height:844});await noOverflow();await screenshot(view+'-mobile-'+theme);
    if(view==='knowledge'){await click('[data-a="doc-detail"][data-value="1"]');await screenshot('knowledge-document-mobile-'+theme);await click('dialog [data-a="close"]');}
    await click('#menu-button');await visible('#sidebar.open');await click('#overlay');
  }
  await page.setViewportSize({width:1440,height:1080});await go('knowledge');
  await click('#knowledge [data-a="review"]');await click('[data-a="kb-state"][data-value="加载失败"]');await screenshot('knowledge-error-light');await click('[data-a="kb-state"][data-value="正常"]');
  await click('#knowledge [data-a="review"]');await click('[data-a="kb-state"][data-value="空状态"]');await screenshot('knowledge-empty-light');
  await page.goto(pathToFileURL(path.resolve(__dirname,'../PAMS-UI-bright-v6.html')).href+'?view=users');await visible('#users');
  assert.deepEqual(errors,[]);assert.deepEqual(requests,[]);
  const result={passed:true,browserErrors:errors,networkRequests:requests,viewports:['1440×1080','390×844'],checks:['three routes and shared navigation','source/query/result filters','document create and excerpt drawer','snapshot data cutoff','retrieval explanation','account search and protected identity','role replacement preview and apply','bulk account status confirmation','new account form','unsaved preferences guard','density persistence','light/dark and mobile overflow','empty and error states','both HTML entry points'],screenshots:fs.readdirSync(output).filter(x=>x.endsWith('.png')).length};
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
