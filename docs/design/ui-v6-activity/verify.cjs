// Local UI prototype verification only; no application backend is contacted.
const {chromium}=require('../../../pams-web/node_modules/playwright');
const path=require('node:path'),fs=require('node:fs'),assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
(async()=>{
  const output=__dirname;
  const browser=await chromium.launch({headless:true,executablePath:path.join(process.env.LOCALAPPDATA,'ms-playwright/chromium_headless_shell-1247/chrome-headless-shell-win64/chrome-headless-shell.exe')});
  const page=await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:1});
  const errors=[],network=[];page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))network.push(r.url());});
  const url=pathToFileURL(path.resolve(__dirname,'../V6.html')).href;
  const go=async(view='activities',tab='basic',theme='light')=>{await page.goto(url+'?view='+view+'&activity=1&tab='+tab+'#'+theme);await page.waitForSelector('#ev-list-body',{state:'attached'});if(view==='detail'&&tab==='plan')await page.waitForSelector('#ev-word-frame[data-ready="true"]');};
  const click=async(selector)=>page.locator(selector).first().click();
  const action=async(a,v)=>click(`[data-ev="${a}"]${v!==undefined?`[data-value="${v}"]`:''}`);
  const tab=async(t)=>click(`[data-ev-tab="${t}"]`);
  const shot=async(name)=>page.screenshot({path:path.join(output,name+'.png'),fullPage:true,animations:'disabled'});
  const submit=async()=>page.locator('#ev-dialog-form button[type="submit"]').click();
  const visible=async(s)=>assert(await page.locator(s).isVisible(),s+' must be visible');
  const noOverflow=async()=>assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'body overflow '+page.url());
  await go();await visible('#activities');assert.equal(await page.locator('#ev-list-body tbody tr').count(),6);await shot('activities-desktop-light');
  await page.locator('#ev-query').fill('党日');assert.equal(await page.locator('#ev-list-body tbody tr').count(),1);await action('reset');
  await action('phase','策划审核');assert.equal(await page.locator('#ev-list-body tbody tr').count(),1);await action('reset');
  await page.locator('#ev-type').selectOption('会议');assert.equal(await page.locator('#ev-list-body tbody tr').count(),1);await action('reset');
  await page.locator('#ev-size').selectOption('3');assert.equal(await page.locator('#ev-list-body tbody tr').count(),3);await action('page','2');assert.equal(await page.locator('#ev-list-body tbody tr').count(),3);await action('reset');
  await page.locator('#ev-query').fill('不存在');await visible('#ev-list-body .ev-empty');await shot('activities-empty');await action('reset');
  await action('new-activity');await page.locator('[name="title"]').fill('原型验证活动');await page.locator('[name="place"]').fill('测试会议室');await page.locator('[name="owner"]').fill('测试同学');await submit();assert((await page.locator('#ev-list-body').innerText()).includes('原型验证活动'));
  await go('detail');assert.equal(await page.locator('.ev-detail-nav [role="tab"]').count(),8);
  const names=['basic','materials','plan','agenda','seat','score','signin','articles'];
  for(const theme of ['light','dark']){
    await page.setViewportSize({width:1440,height:1000});
    await go('activities','basic',theme);await noOverflow();if(theme==='dark')await shot('activities-desktop-dark');
    for(const t of names){await go('detail',t,theme);await noOverflow();await shot(t+'-desktop-'+theme);}
    await page.setViewportSize({width:390,height:844});await go('activities','basic',theme);await noOverflow();await shot('activities-mobile-'+theme);
    for(const t of names){await go('detail',t,theme);await noOverflow();
      assert(await page.evaluate(()=>[...document.querySelectorAll('#ev-detail-panel .ev-table tbody tr')].every(row=>[...row.children].every(cell=>cell.getBoundingClientRect().bottom<=row.getBoundingClientRect().bottom+1))),'mobile table content must fit each row: '+t);
      if(t==='plan')assert(await page.locator('#ev-word-frame').evaluate(f=>{const t=f.contentDocument.querySelector('table');return t.scrollWidth<=t.clientWidth+1;}),'document table must fit paper');
      if(t==='seat')assert(await page.evaluate(()=>[...document.querySelectorAll('.ev-seat')].every(b=>b.getBoundingClientRect().height===39)),'all seats must have consistent height');
      await shot(t+'-mobile-'+theme);}
  }
  await page.setViewportSize({width:1440,height:1000});await go('detail','plan');
  await action('approve-plan');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('已通过'));await action('advance');await submit();assert((await page.locator('.ev-identity').innerText()).includes('执行中'));
  await action('edit-plan');await page.locator('[name="purpose"]').fill('用于验证原型编辑后的版本和状态。');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('v4'));assert((await page.locator('#ev-detail-panel').innerText()).includes('草稿'));
  await action('submit-plan');await submit();await action('reject-plan');await page.locator('[name="reason"]').fill('请补充预算明细');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('已驳回'));
  await tab('agenda');await action('new-agenda');await page.locator('[name="title"]').fill('新增核对环节');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('新增核对环节'));
  await tab('seat');await action('seat','47');await action('assign-seat');await page.locator('[name="name"]').fill('测试来宾');await submit();assert.equal(await page.locator('.ev-seat.selected').innerText(),'测试来宾');await shot('seat-assigned');
  await tab('score');await action('new-score');await page.locator('[name="name"]').fill('测试小组');for(let i=0;i<3;i++)await page.locator(`[name="dim${i}"]`).fill('20');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('测试小组'));
  await tab('signin');await action('mark-signin','9');await page.locator('[name="reason"]').fill('现场设备故障');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('补签'));await action('import-roster');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('20231021'));
  await action('signin-tab','签到记录');await action('add-signin');await page.locator('[name="name"]').fill('新增同学');await page.locator('[name="no"]').fill('20231999');await page.locator('[name="class"]').fill('计科2301');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('新增同学'));
  await tab('articles');await action('new-article');await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('草稿'));
  await tab('materials');await action('upload');await page.locator('[name="file"]').setInputFiles({name:'验证材料.txt',mimeType:'text/plain',buffer:Buffer.from('prototype only')});await submit();assert((await page.locator('#ev-detail-panel').innerText()).includes('验证材料.txt'));
  await page.locator('#ev-material-query').fill('策划');assert.equal(await page.locator('#ev-detail-panel tbody tr').count(),1);
  for(const mode of ['空数据','加载中','加载失败']){await page.locator('#detail #ev-scenario').selectOption(mode);await shot('detail-state-'+({'空数据':'empty','加载中':'loading','加载失败':'error'})[mode]);}
  await action('retry-detail');await visible('#ev-material-query');
  await tab('basic');await page.locator('#ev-tab-basic').focus();await page.keyboard.press('ArrowRight');assert.equal(await page.locator('.ev-detail-nav [aria-selected="true"]').getAttribute('id'),'ev-tab-materials');await page.keyboard.press('End');assert.equal(await page.locator('.ev-detail-nav [aria-selected="true"]').getAttribute('id'),'ev-tab-articles');
  await page.setViewportSize({width:768,height:1024});await go('detail','plan');await noOverflow();await shot('plan-tablet-light');
  await page.goto(pathToFileURL(path.resolve(__dirname,'../PAMS-UI-bright-v6.html')).href+'?view=detail&activity=1&tab=agenda#light');await visible('.ev-timeline');
  assert.deepEqual(errors,[]);assert.deepEqual(network,[]);
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({result:'pass',screenshots:fs.readdirSync(output).filter(x=>x.endsWith('.png')).length,pageErrors:errors,externalRequests:network,checks:['活动列表检索、类型/状态筛选、分页、新建','8个详情页，桌面/手机，明/暗主题无整页横向溢出','策划审核、阶段推进、版本更新、提交与驳回','议程新增、座位分配、评分录入','名单导入、补签、手动签到','推文任务新增、材料上传示例与检索','加载/空/失败状态、错误重试、键盘标签导航','平板布局与两个HTML入口']},null,2));
  console.log('PASS: '+fs.readdirSync(output).filter(x=>x.endsWith('.png')).length+' screenshots; no page errors or external requests');await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
