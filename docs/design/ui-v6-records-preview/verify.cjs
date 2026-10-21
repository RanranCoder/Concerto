const {chromium}=require('../../../pams-web/node_modules/playwright');
const assert=require('node:assert/strict');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const page=await browser.newPage({viewport:{width:1440,height:1080}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const base=pathToFileURL(path.resolve(__dirname,'../PAMS-UI-bright-v6.html')).href;
 let navigation=0;
 const go=async id=>{await page.goto(base+'?view='+id+'&check='+(navigation++)+'#light');await page.locator('#'+id).waitFor({state:'visible'})};
 const capture=async name=>page.screenshot({path:path.join(__dirname,name+'.png'),fullPage:true,animations:'disabled',style:'.rec-toast{visibility:hidden!important}'});
 const click=async(a,v)=>page.locator('.records-view:visible [data-rec-action="'+a+'"]'+(v!==undefined?'[data-value="'+v+'"]':'')).first().click();
 const form=()=>page.locator('#rec-dialog');
 for(const id of ['members','publicity','archives','party']){
  await go(id);await capture(id);
  await page.locator('#'+id+' [data-rec-field="query"]').fill('没有匹配的记录123');
  assert.equal(await page.locator('#'+id+' .rec-empty').count(),1);
  await click('reset');
  await click('detail');await capture(id+'-detail');
  await click('back');
  await page.locator('#theme-toggle').click();await capture(id+'-dark');
  for(const width of [1024,390]){await page.setViewportSize({width,height:900});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'page overflow: '+id+' '+width);if(width===390)await capture(id+'-mobile')}
  await page.setViewportSize({width:1440,height:1080});
 }
 await go('members');await click('new');await form().locator('[name="name"]').fill('原型测试成员');await form().locator('[name="studentNo"]').fill('2026999901');await form().locator('[type="submit"]').click();assert.ok(await page.locator('#members').innerText().then(t=>t.includes('原型测试成员')));
 await click('tab','账号关联');await click('account','4');await form().locator('[type="submit"]').click();await capture('members-account');
 await go('publicity');await click('detail','1');await page.locator('#rec-review-comment').fill('信息齐备');await click('approve','1');await click('publish','1');await form().locator('[name="wxUrl"]').fill('https://mp.weixin.qq.com/s/prototype');await form().locator('[type="submit"]').click();assert.ok((await page.locator('#publicity').innerText()).includes('已发布'));await capture('publicity-published');
 await go('publicity');await click('detail','1');await click('reject','1');assert.ok(await page.locator('.rec-toast').innerText().then(t=>t.includes('驳回原因')));await page.locator('#rec-review-comment').fill('请核对现场信息');await click('reject','1');assert.ok((await page.locator('#publicity').innerText()).includes('被驳回'));
 await go('publicity');await click('tab','新闻稿');assert.equal(await page.locator('#publicity .rec-table tbody tr').count(),2);await capture('publicity-news');
 await go('archives');for(const tab of ['模板库','素拓加分','通知公告','群聊管理']){await click('tab',tab);await capture('archives-'+({模板库:'templates',素拓加分:'credits',通知公告:'notices',群聊管理:'groups'}[tab]))}
 await click('new');await form().locator('[name="v0"]').fill('原型测试群');await form().locator('[type="submit"]').click();assert.ok((await page.locator('#archives').innerText()).includes('原型测试群'));
 await go('party');await click('detail','1');await click('stage','1');await form().locator('[name="note"]').fill('原型阶段流转验证');await form().locator('[type="submit"]').click();assert.ok((await page.locator('#party').innerText()).includes('原型阶段流转验证'));for(const t of ['函调','登记','转移']){await click('record',t);await click('party-record','1');await form().locator('[name="note"]').fill(t+'原型验证');await form().locator('[type="submit"]').click();assert.ok((await page.locator('#party').innerText()).includes(t+'原型验证'));await capture('party-'+({函调:'investigation',登记:'register',转移:'transfer'}[t]))}
 await go('party');await click('tab','名册管理');await capture('party-rosters');await click('new');await form().locator('[name="name"]').fill('名册测试');await form().locator('[name="studentNo"]').fill('2026999902');await form().locator('[type="submit"]').click();assert.ok((await page.locator('#party').innerText()).includes('名册测试'));
 await go('members');await click('import');await form().locator('input[type="file"]').setInputFiles({name:'sample.xlsx',mimeType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',buffer:Buffer.from('prototype')});await form().locator('[type="submit"]').click();assert.ok((await form().innerText()).includes('导入检查示例'));await capture('members-import');await form().locator('[type="submit"]').click();
 await page.locator('.nav [data-view="activities"]').click();assert.ok(await page.locator('#activities').isVisible());await page.locator('.nav [data-view="routine"]').click();assert.ok(await page.locator('#routine').isVisible());await page.locator('.nav [data-view="lingsi"]').click();assert.ok(await page.locator('#lingsi').isVisible());
 await go('archives');await click('detail','5');await click('go-activity','2');assert.ok((await page.locator('#detail-heading').innerText()).includes('校园志愿服务活动'));
 await go('members');await click('sessions');await form().locator('[type="submit"]').click();assert.equal(await page.locator('#members [data-rec-field="session"]').inputValue(),'2027—2028届');assert.ok((await page.locator('#members').innerText()).includes('没有匹配的记录'));
 assert.deepEqual(errors,[]);console.log('PASS: four modules; light/dark; desktop/mobile; filtering; member/account; article review/publish; archive tabs; party stage/records/rosters; import; existing navigation.');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
