import { test, expect, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

test.use({channel:process.platform==='win32'?'msedge':undefined,viewport:{width:1600,height:1000},timezoneId:'Asia/Shanghai'})
const output=resolve('..','docs','ui-v6-lingsi-implementation')
const user={id:1,username:'teacher',realName:'林老师',roleCode:'TEACHER',roleLevel:5,deptId:1,deptName:'文秘部',orgId:1,permissions:['activity:view','activity:edit','schedule:view','schedule:generate','material:view','member:view','kb:read','quality:activity_add']}
const skipped=[{label:'10月16日 16:00–17:40',sub:'未分配',reason:'空闲人员不足，尚缺 2 人'}]
const plan={planId:10,summary:'培训班 · 保存策划草稿',total:2,items:[{id:null,label:'活动目的',sub:'专题学习与交流'},{id:null,label:'准备事项',sub:'场地、名单与会务'}],skipped,detailLines:['关联活动：第四十期培训班','__args__:{"internal":"hidden"}'],reversible:true,confirmToken:'test-one-time-token',needsConfirm:true,preview:'## 第四十期培训班策划初稿\n\n### 活动目的\n专题学习与交流。\n\n### 准备事项\n核对场地、名单与会务。'}
const form={taskId:30,formKey:'generateSchedule',toolName:'generateActivitySchedule',title:'补充排班条件',description:'请选择日期、时段和参与部门',fields:[{name:'startDate',label:'开始日期',type:'date',required:true},{name:'endDate',label:'结束日期',type:'date',required:true},{name:'department',label:'参与部门',type:'text',required:true}],values:{startDate:'2026-10-15',endDate:'2026-10-16',department:'文秘部'}}
async function setup(page:Page) {
  const requests:{path:string,body:Record<string,unknown>}[]=[]
  let status='PENDING_CONFIRM', formPending=true
  const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message))
  await page.clock.setFixedTime(new Date('2026-10-14T10:00:00+08:00'))
  await page.addInitScript(user=>{
    localStorage.setItem('pams_token',JSON.stringify({state:{token:'test-token',user},version:1}))
    localStorage.setItem('pams_theme',JSON.stringify({state:{mode:'light'},version:0}))
  },user)
  const baseMessage={id:100,conversationId:1,role:'ASSISTANT',sensitivity:'normal',streamId:'stream-1',provider:'deepseek',durationMs:800,createdAt:'2026-10-14T09:31:00',toolCalls:[{tool:'queryActivityOverview',label:'查询活动概览',status:'OK',durationMs:120,write:false,sensitivity:'normal',argsJson:'{"activityName":"第四十期培训班"}'}],thinking:[{round:0,text:'先核对已有资料，再整理本期需要准备的内容。',ts:1}],thinkingNotes:[],references:[],artifacts:[],citations:[],form:null,plan:null}
  const artifact={entityType:'plan',entityId:5,name:'培训班策划书',path:'/activities/1?tab=plan'}
  const result={status,taskId:10,message:status==='CANCELLED'?'已取消该操作':'策划草稿已保存',skipped,artifacts:[artifact]}
  await page.route(url => url.pathname.startsWith('/api/'),async route=>{
    const req=route.request(),url=new URL(req.url()),path=url.pathname
    if(req.method()==='POST')requests.push({path,body:req.postDataJSON()||{}})
    let data:unknown=[]
    if(path==='/api/auth/me')data=user
    else if(path==='/api/orgs/current')data={id:1,name:'党建办公室',themeJson:null,enabledPacks:null,terminologyJson:null}
    else if(path==='/api/notifications/unread-count')data=0
    else if(path==='/api/agent/conversations')data={records:[
      {id:1,title:'准备培训班策划初稿',status,pendingTaskCount:status==='PENDING_CONFIRM'?1:0,createdAt:'2026-10-14T09:30:00',updatedAt:'2026-10-14T09:31:00'},
      {id:2,title:'查询归档要求',status:'COMPLETED',pendingTaskCount:0,createdAt:'2026-10-13T16:30:00',updatedAt:'2026-10-13T16:40:00'},
      {id:3,title:'补充排班生成条件',status:formPending?'WAITING_INPUT':'PENDING_CONFIRM',pendingTaskCount:formPending?0:1,createdAt:'2026-10-12T09:30:00',updatedAt:'2026-10-12T09:31:00'},
    ],total:3,current:1,size:50,pendingTaskCount:(status==='PENDING_CONFIRM'?1:0)+(formPending?0:1)}
    else if(path==='/api/agent/conversations/1/messages')data={records:[{...baseMessage,id:99,role:'USER',content:'参考历史策划准备培训班初稿。'}, {...baseMessage,content:'已整理初稿，请先预览，再核对变更。',plan:status==='PENDING_CONFIRM'?plan:null,taskResult:status==='PENDING_CONFIRM'?null:{...result,status},artifacts:status==='SUCCESS'?[artifact]:[]}],total:2}
    else if(path==='/api/agent/conversations/2/messages')data={records:[{...baseMessage,role:'USER',content:'活动需要归档哪些材料？'},{...baseMessage,content:'需要归档策划、签到与总结材料。[1]',citations:[{index:1,sourceType:'manual',sourceId:5,sourceTitle:'活动材料归档指引',path:'材料清单',snippet:'活动结束后整理策划、签到与总结材料。',link:'/knowledge'}]}],total:2}
    else if(path==='/api/agent/conversations/3/messages')data={records:[{...baseMessage,role:'USER',content:'帮我安排培训班值班。'},{...baseMessage,content:'请先补齐排班条件。',form:formPending?form:null,plan:formPending?null:{...plan,planId:30}}],total:2}
    else if(path==='/api/agent/tasks/10/confirm'){status='SUCCESS';data={id:10,status,summary:plan.summary,result:{...result,status,affectedCount:2},artifacts:[artifact]}}
    else if(path==='/api/agent/tasks/10/cancel'){status='CANCELLED';data=null}
    else if(path==='/api/agent/tasks/30/input'){formPending=false;data={status:'PLAN',plan:{...plan,planId:30}}}
    else if(path==='/api/agent/chat')data={...baseMessage,messageId:200,conversationId:1,reply:'已整理本次要求，请核对清单。',plan,form:null,pendingTaskCount:1,degraded:false,mode:req.postDataJSON().mode}
    else if(path==='/api/agent/tools')data=[{name:'queryActivityOverview',label:'活动概况',description:'查询活动进展',write:false,sensitivity:'NORMAL'}]
    else if(path==='/api/activities')data={records:[{id:1,name:'第四十期培训班',startDate:'2026-10-13',status:'EXECUTING'}],total:1}
    await route.fulfill({json:{code:200,data}})
  })
  mkdirSync(output,{recursive:true})
  await page.goto('/agent')
  await expect(page.locator('.lingsi-main-title')).toBeVisible()
  return {requests,errors}
}
const task=(page:Page,title:string)=>page.locator('.lingsi-conv-select').filter({hasText:title})
/** 运行模式是自建下拉（非原生 select）：点触发器展开，再点选项 */
const pickMode=async(page:Page,label:string)=>{await page.getByRole('combobox',{name:'运行模式'}).click();await page.getByRole('option',{name:label,exact:true}).click()}

test('业务入口填入草稿、真实任务筛选、预览与确认门禁',async({page})=>{
  const {requests,errors}=await setup(page)
  await expect(page.locator('.lingsi-starter')).toHaveCount(6)
  await page.screenshot({path:resolve(output,'welcome-light.png'),animations:'disabled'})
  await page.locator('.lingsi-starter').filter({hasText:'活动筹备'}).click()
  await expect(page.locator('.lingsi-composer-ta')).toHaveValue(/策划初稿/)
  expect(requests.filter(r=>r.path==='/api/agent/chat')).toHaveLength(0)
  await page.locator('.lingsi-task-filters').getByRole('button',{name:'已完成'}).click()
  await expect(page.locator('.lingsi-conv-select')).toHaveCount(1)
  await page.locator('.lingsi-task-filters').getByRole('button',{name:'全部',exact:true}).click()
  await page.getByRole('textbox',{name:'搜索任务'}).fill('培训班')
  await expect(page.locator('.lingsi-conv-select')).toHaveCount(1)
  await page.getByRole('textbox',{name:'搜索任务'}).fill('')
  await task(page,'准备培训班策划初稿').click()
  await expect(page.locator('.lingsi-main-title h1')).toHaveText('准备培训班策划初稿')
  await page.getByRole('button',{name:'预览工作成果',exact:true}).click()
  await expect(page.locator('.lingsi-v6-panel')).toBeVisible()
  await expect(page.locator('.lingsi-preview-document')).toContainText('活动目的')
  await page.getByRole('tab',{name:'本次变更',exact:true}).click()
  await expect(page.locator('.lingsi-preview-scroll')).not.toContainText('__args__')
  await expect(page.locator('.lingsi-preview-scroll').getByRole('button',{name:'确认执行'})).toHaveCount(0)
  await page.getByRole('tab',{name:'内容预览',exact:true}).click()
  await page.screenshot({path:resolve(output,'plan-preview-light.png'),animations:'disabled'})
  await pickMode(page,'只读模式')
  await expect(page.getByRole('button',{name:'确认执行',exact:true})).toBeDisabled()
  await expect(page.getByRole('checkbox',{name:'自动协作'})).toBeDisabled()
  await pickMode(page,'资料问答')
  await expect(page.getByRole('button',{name:'确认执行',exact:true})).toBeDisabled()
  await pickMode(page,'标准模式')
  await page.getByRole('button',{name:'确认执行',exact:true}).click()
  await expect(page.locator('.lingsi-notice')).toContainText('策划草稿已保存')
  await expect(page.locator('.lingsi-preserved-skips').first()).toContainText('尚缺 2 人')
  expect(requests.find(r=>r.path.endsWith('/confirm'))?.body).toEqual({confirmToken:'test-one-time-token',mode:'standard'})
  await page.reload();await task(page,'准备培训班策划初稿').click()
  await expect(page.locator('.lingsi-notice')).toContainText('策划草稿已保存')
  await expect(page.locator('.lingsi-preserved-skips')).toContainText('尚缺 2 人')
  await page.getByRole('button',{name:'切换深色'}).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme','dark')
  await page.screenshot({path:resolve(output,'completed-dark.png'),animations:'disabled'})
  expect(errors).toEqual([])
})

test('取消保留跳过事项，资料来源在同一成果面板回看',async({page})=>{
  await setup(page);await task(page,'准备培训班策划初稿').click()
  await page.locator('.lingsi-plan').getByRole('button',{name:/^取\s*消$/}).click()
  await expect(page.locator('.lingsi-notice')).toContainText('未执行写入')
  await expect(page.locator('.lingsi-preserved-skips')).toContainText('尚缺 2 人')
  await task(page,'查询归档要求').click()
  await page.getByRole('button',{name:'查看片段'}).click()
  await expect(page.locator('.lingsi-preview-document')).toContainText('活动结束后整理')
  await page.getByRole('tab',{name:'参考资料',exact:true}).click()
  await expect(page.locator('.lingsi-preview-scroll')).toContainText('活动材料归档指引')
  await page.screenshot({path:resolve(output,'source-light.png'),animations:'disabled'})
})

test('待补信息在对话内校验并提交，补齐后转换确认清单',async({page})=>{
  const {requests,errors}=await setup(page)
  await task(page,'补充排班生成条件').click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.locator('input[id="startDate"]')).toHaveValue('2026-10-15')
  await page.locator('input[id="department"]').fill('')
  await page.getByRole('button',{name:'补充并继续'}).click()
  await expect(page.getByText('参与部门为必填', {exact:true})).toBeVisible()
  expect(requests.filter(r=>r.path.endsWith('/input'))).toHaveLength(0)
  await page.locator('input[id="department"]').fill('文秘部')
  await page.screenshot({path:resolve(output,'input-needed-light.png'),animations:'disabled'})
  await page.getByRole('button',{name:'补充并继续'}).click()
  await expect(page.locator('.lingsi-plan')).toBeVisible()
  expect(requests.find(r=>r.path.endsWith('/input'))?.body).toEqual({values:{startDate:'2026-10-15',endDate:'2026-10-16',department:'文秘部'},mode:'standard'})
  expect(errors).toEqual([])
})

test('手机完整显示工作区，通过抽屉打开任务与成果',async({page})=>{
  await page.setViewportSize({width:390,height:844});await setup(page)
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  await page.screenshot({path:resolve(output,'welcome-mobile.png'),animations:'disabled'})
  await page.getByRole('button',{name:'打开任务列表',exact:true}).click()
  await task(page,'准备培训班策划初稿').click()
  await expect(page.locator('.lingsi-v6-task-drawer')).not.toBeVisible()
  await page.getByRole('button',{name:'预览工作成果',exact:true}).click()
  await expect(page.locator('.lingsi-v6-preview-drawer')).toBeVisible()
  await page.screenshot({path:resolve(output,'preview-mobile.png'),animations:'disabled'})
  await page.getByRole('button',{name:'关闭工作成果预览'}).click()
  await page.getByRole('button',{name:'切换深色'}).click()
  await page.screenshot({path:resolve(output,'conversation-mobile-dark.png'),animations:'disabled'})
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
})
