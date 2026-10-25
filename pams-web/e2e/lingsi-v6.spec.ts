import { test, expect, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

test.use({channel:process.platform==='win32'?'msedge':undefined,viewport:{width:1600,height:1000},timezoneId:'Asia/Shanghai'})
const output=resolve('..','docs','ui-v6-lingsi-implementation',process.env.PAMS_E2E_ARTIFACT_SUBDIR ?? '')
const user={id:1,username:'teacher',realName:'林老师',roleCode:'TEACHER',roleLevel:5,deptId:1,deptName:'文秘部',orgId:1,permissions:['activity:view','activity:edit','schedule:view','schedule:generate','material:view','member:view','kb:read','quality:activity_add']}
const skipped=[{label:'10月16日 16:00–17:40',sub:'未分配',reason:'空闲人员不足，尚缺 2 人'}]
const plan={planId:10,summary:'培训班 · 保存策划草稿',total:2,items:[{id:null,label:'活动目的',sub:'专题学习与交流'},{id:null,label:'准备事项',sub:'场地、名单与会务'}],skipped,detailLines:['关联活动：第四十期培训班','__args__:{"internal":"hidden"}'],reversible:true,confirmToken:'test-one-time-token',needsConfirm:true,preview:'## 第四十期培训班策划初稿\n\n### 活动目的\n专题学习与交流。\n\n### 准备事项\n核对场地、名单与会务。'}
const form={taskId:30,formKey:'generateSchedule',toolName:'generateActivitySchedule',title:'补充排班条件',description:'请选择日期、时段和参与部门',fields:[{name:'startDate',label:'开始日期',type:'date',required:true},{name:'endDate',label:'结束日期',type:'date',required:true},{name:'department',label:'参与部门',type:'text',required:true}],values:{startDate:'2026-10-15',endDate:'2026-10-16',department:'文秘部'}}
async function setup(page:Page) {
  const requests:{path:string,body:Record<string,unknown>}[]=[]
  // Import the same store instance as the page, including Vite's HMR version.
  let agentModuleURL = ''
  page.on('request', request=>{
    if(new URL(request.url()).pathname==='/src/stores/agent.ts')agentModuleURL=request.url()
  })
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
  return {requests,errors,agentModule:()=>{
    if(!agentModuleURL)throw new Error('Agent store module was not loaded')
    return agentModuleURL
  }}
}
const task=(page:Page,title:string)=>page.locator('.lingsi-conv-select').filter({hasText:title})
/** 运行模式是自建下拉（非原生 select）：点触发器展开，再点选项 */
const pickMode=async(page:Page,label:string)=>{await page.getByRole('combobox',{name:'运行模式'}).click();await page.getByRole('option',{name:label,exact:true}).click()}

for(const compact of [false,true])test(`协作清单独立确认、续跑及刷新恢复（${compact?'手机深色':'桌面浅色'}）`,async({page})=>{
  const {errors}=await setup(page)
  if(compact) {
    await page.getByRole('button',{name:'切换深色'}).click()
    await page.setViewportSize({width:390,height:844})
    await page.getByRole('button',{name:'打开任务列表'}).click()
  }
  const actions=[10,11].map(id=>({id,runId:80,workItemId:id,revision:1,toolName:'draftPlanSections',status:'PENDING_CONFIRM',summary:`独立任务${id}`,plan:{...plan,planId:id,summary:`独立任务${id}`},reversible:true,artifacts:[]}))
  const run=()=>({id:80,streamId:'durable-run',conversationId:1,status:actions.every(a=>a.status==='SUCCESS')?'SUCCESS':'WAITING_CONFIRM',
    nodes:actions.map((a,index)=>({id:index+1,workItemId:a.id,agent:index?'writing':'planning',task:`准备任务${index+1}`,status:a.status==='SUCCESS'?'SUCCESS':'WAITING_CONFIRM',dependsOn:[],revision:1,attempt:1})),actions})
  const confirmed:number[]=[]
  await page.route('**/api/agent/**',async route=>{
    const path=new URL(route.request().url()).pathname
    let data:unknown
    if(path==='/api/agent/conversations/1/messages')data={records:[{id:90,role:'USER',content:'分别准备两项任务'},{id:91,role:'ASSISTANT',content:'请分别核对两项预览。',run:run(),streamId:'durable-run',executionState:run().status,references:[],toolCalls:[],artifacts:[]}],total:2}
    else if(path==='/api/agent/team/runs/80')data=run()
    else if(/\/api\/agent\/tasks\/(10|11)\/confirm/.test(path)) {
      const id=Number(path.split('/')[4]);confirmed.push(id)
      const action=actions.find(a=>a.id===id)!; action.status='SUCCESS'
      data={...action,result:{message:'已保存'},executionState:run().status}
    } else return route.fallback()
    await route.fulfill({json:{code:200,data}})
  })
  await task(page,'准备培训班策划初稿').click()
  await expect(page.locator('.lingsi-plan')).toHaveCount(2)
  await page.locator('.lingsi-plan').first().getByRole('button',{name:/保存业务草稿/}).click()
  await expect(page.locator('.lingsi-plan')).toHaveCount(1)
  await expect(page.locator('.lingsi-plan')).toContainText('独立任务11')
  await page.reload()
  if(compact) {
    await page.getByRole('button',{name:'切换深色'}).click()
    await page.getByRole('button',{name:'打开任务列表'}).click()
  }
  await task(page,'准备培训班策划初稿').click()
  await expect(page.locator('.lingsi-plan')).toHaveCount(1)
  await page.locator('.lingsi-plan').getByRole('button',{name:/保存业务草稿/}).click()
  await expect(page.locator('.lingsi-plan')).toHaveCount(0)
  await expect(page.locator('.lingsi-work-items>details>summary')).toContainText('2/2 步完成')
  if(compact)await expect(page.locator('html')).toHaveAttribute('data-theme','dark')
  await page.locator('.lingsi-work-items>details>summary').click()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
  await page.screenshot({path:resolve(output,`s2-durable-tasks-${compact?'dark-mobile':'light'}.png`),fullPage:true})
  expect(confirmed).toEqual([10,11]);expect(errors).toEqual([])
})

test('成果深链接定位指定稿件、材料与公告', async ({page}) => {
  const {errors}=await setup(page)
  await page.route('**/api/articles/91',route=>route.fulfill({json:{code:200,data:{
    id:91,title:'指定成果稿件',summary:'核对指定记录',content:'<p>指定稿件正文</p>',articleType:'REPORT',status:'DRAFT',
    activityId:1,activityName:'培训班',authorId:1,imageUrls:[],coverUrl:'',createdAt:null,updatedAt:null,
  }}}))
  await page.goto('/content/articles?id=91')
  await expect(page.getByRole('dialog')).toContainText('指定成果稿件')
  await page.route('**/api/materials/92',route=>route.fulfill({json:{code:200,data:{
    id:92,name:'指定成果材料',bizType:'PLAN',activityId:1,deptId:1,uploaderId:1,tag:'',description:'待核对材料',fileId:null,originFilename:null,createdAt:'2026-10-24',
  }}}))
  await page.goto('/archive/materials?id=92')
  await expect(page.getByRole('dialog')).toContainText('指定成果材料')
  await page.route('**/api/announcements',route=>route.fulfill({json:{code:200,data:[{
    id:93,title:'指定成果公告',content:'指定公告正文',publisherId:1,publishTime:null,createdAt:'2026-10-24',
  }]}}))
  await page.goto('/archive/announcements?id=93')
  await expect(page.getByRole('dialog')).toContainText('指定成果公告')
  expect(errors).toEqual([])
})

for(const phone of [false,true])test(`生成中离开底部显示动态返回按钮，点击跟随输出并尊重手动上翻（${phone?'手机深色':'桌面浅色'}）`,async({page})=>{
  if(phone)await page.setViewportSize({width:390,height:844})
  await page.emulateMedia({reducedMotion:'no-preference'})
  const {agentModule,errors}=await setup(page)
  if(phone){
    await page.getByRole('button',{name:'切换深色'}).click()
    await page.getByRole('button',{name:'打开任务列表'}).click()
  }
  await task(page,'查询归档要求').click()
  await expect(page.locator('.lingsi-reply')).toContainText('需要归档策划')
  const button=page.getByRole('button',{name:'回到底部',exact:true})
  await expect(button).toHaveCount(0)
  await page.evaluate(async source=>{
    const {useAgentStore}=await import(source)
    useAgentStore.setState((s:{outcomes:object[]})=>({thinking:true,activeStreamId:'scroll-indicator',outcomes:s.outcomes.map((o,index)=>index===s.outcomes.length-1?{
      ...o,streamId:'scroll-indicator',streaming:true,reply:'正在整理活动资料与审批要求。\n\n'.repeat(100)+'正在生成末段。',
    }:o)}))
  },agentModule())
  const scroll=page.locator('.lingsi-scroll')
  await expect(page.locator('.lingsi-reply')).toContainText('正在生成末段。')
  await scroll.evaluate(el=>el.scrollTo({top:0,behavior:'instant'}))
  await expect(button).toBeVisible()
  const box=await button.boundingBox(),composer=await page.locator('.lingsi-composer-box').boundingBox()
  expect(Math.abs(box!.x+box!.width/2-composer!.x-composer!.width/2)).toBeLessThan(1)
  expect(composer!.y-(box!.y+box!.height)).toBeGreaterThanOrEqual(8)
  expect(box!.width).toBe(48)
  const ring=button.locator('.lingsi-scroll-bottom-progress')
  expect(await ring.evaluate(el=>getComputedStyle(el).animationName)).toBe('lingsi-bottom-progress')
  await expect.poll(()=>ring.evaluate(el=>el.getAnimations().some(animation=>Number(animation.currentTime)>0))).toBe(true)
  await page.screenshot({path:resolve(output,phone?'scroll-indicator-dark-phone.png':'scroll-indicator-light-desktop.png')})
  if(phone){
    await page.emulateMedia({reducedMotion:'reduce'})
    await expect.poll(()=>ring.evaluate(el=>getComputedStyle(el).animationName)).toBe('none')
    await button.focus();await page.keyboard.press('Enter')
  }else await button.click()
  const bottomGap=()=>scroll.evaluate(el=>el.scrollHeight-el.clientHeight-el.scrollTop)
  await expect.poll(bottomGap).toBeLessThan(2)
  await expect(button).toHaveCount(0)
  const append=()=>page.evaluate(async source=>{
    const {useAgentStore}=await import(source)
    useAgentStore.setState((s:{outcomes:{reply:string}[]})=>({outcomes:s.outcomes.map((o,index)=>index===s.outcomes.length-1?{...o,reply:o.reply+'\n\n继续追加审批材料。'.repeat(30)}:o)}))
  },agentModule())
  await append()
  await expect.poll(bottomGap).toBeLessThan(2)
  await scroll.hover();await page.mouse.wheel(0,-500)
  await expect(button).toBeVisible()
  const reading=await scroll.evaluate(el=>el.scrollTop)
  await append()
  await expect.poll(()=>scroll.evaluate((el,position)=>Math.abs(el.scrollTop-position),reading)).toBeLessThan(2)
  await expect(button).toBeVisible()
  if(phone)await page.evaluate(async source=>{
    const {useAgentStore}=await import(source)
    useAgentStore.getState().applyStreamEvent({streamId:'scroll-indicator',seq:99,type:'MESSAGE_DONE',ts:99,
      payload:{reply:useAgentStore.getState().outcomes.at(-1).reply,citations:[]}})
  },agentModule())
  else await page.getByRole('button',{name:'停止任务',exact:true}).click()
  await expect(button).toHaveCount(0)
  expect(errors).toEqual([])
})

for (const phone of [false,true]) test(`发送后新消息自动移到对话区上方，手动阅读不被打断（${phone?'手机减少动态效果':'桌面平滑滚动'}）`, async ({page})=>{
  if(phone)await page.setViewportSize({width:390,height:844})
  await page.emulateMedia({reducedMotion:phone?'reduce':'no-preference'})
  const {agentModule}=await setup(page)
  await page.route(url=>url.pathname==='/api/agent/conversations/2/messages',route=>route.fulfill({json:{code:200,data:{
    records:Array.from({length:8},(_,index)=>[
      {id:500+index*2,role:'USER',content:`历史提问${index}`},
      {id:501+index*2,role:'ASSISTANT',content:'核对活动资料并整理筹备事项。'.repeat(18),toolCalls:[],thinking:[],citations:[]},
    ]).flat(),total:16,
  }}}))
  if(phone)await page.getByRole('button',{name:'打开任务列表'}).click()
  await task(page,'查询归档要求').click()
  await expect(page.locator('.lingsi-user-quote')).toHaveCount(8)
  const scroll=page.locator('.lingsi-scroll')
  const historyBottomGap=()=>scroll.evaluate(el=>el.scrollHeight-el.clientHeight-el.scrollTop)
  await expect.poll(historyBottomGap).toBeLessThan(2)
  await scroll.evaluate(el=>el.scrollTo({top:0,behavior:'instant'}))
  const question='请继续核对最新的活动安排。\n'.repeat(6).trim()
  await page.locator('.lingsi-composer-ta').fill(question)
  await page.getByRole('button',{name:'发送任务',exact:true}).click()
  const anchored=page.locator('[data-scroll-anchor="true"]')
  await expect(anchored.locator('.lingsi-user-quote-text')).toHaveText(question)
  const distance=()=>anchored.evaluate(el=>{
    const scroller=el.closest('.lingsi-scroll')!
    return Math.abs(el.getBoundingClientRect().top-scroller.getBoundingClientRect().top-parseFloat(getComputedStyle(scroller).paddingTop))
  })
  await expect.poll(distance).toBeLessThan(2)
  await expect(anchored.locator('.lingsi-reply')).toContainText('已整理本次要求')
  await expect.poll(distance).toBeLessThan(2)
  expect(await scroll.evaluate(el=>el.scrollTop)).toBeGreaterThan(200)
  await scroll.evaluate(el=>el.scrollTo({top:0,behavior:'instant'}))
  await page.evaluate(async source=>{
    const {useAgentStore}=await import(source)
    useAgentStore.setState((s:{outcomes:{reply:string}[]})=>({outcomes:s.outcomes.map((o,index)=>index===s.outcomes.length-1?{...o,reply:o.reply+'追加验证文本。'.repeat(100)}:o)}))
  },agentModule())
  await expect(anchored.locator('.lingsi-reply')).toContainText('追加验证文本。')
  expect(await scroll.evaluate(el=>el.scrollTop)).toBe(0)
  await page.locator('.lingsi-composer-ta').fill('再补充一条要求。')
  await page.locator('.lingsi-composer-ta').press('Enter')
  await expect(anchored).toHaveCount(1)
  await expect(anchored.locator('.lingsi-user-quote-text')).toHaveText('再补充一条要求。')
  await expect.poll(distance).toBeLessThan(2)
  if(phone)await page.getByRole('button',{name:'打开任务列表'}).click()
  await task(page,'查询归档要求').click()
  await expect(page.locator('.lingsi-user-quote')).toHaveCount(8)
  await expect(anchored).toHaveCount(0)
  await expect.poll(historyBottomGap).toBeLessThan(2)
})

test('长回复流式输出只维护显现窗口，保留已显示节点', async ({ page }) => {
  const {agentModule}=await setup(page)
  await page.emulateMedia({reducedMotion:'no-preference'})
  await task(page,'查询归档要求').click()
  await expect(page.locator('.lingsi-reply')).toContainText('需要归档策划、签到与总结材料。')
  const metrics = await page.evaluate(async source => {
    const {useAgentStore} = await import(source)
    const state = useAgentStore.getState()
    const original = state.outcomes.at(-1).reply
    const root = document.querySelector('.lingsi-reply > span')!
    const first = root.firstChild
    let maxAnimations = 0, maxNodes = 0
    const frames:number[] = []
    let last = performance.now(), recording = true
    const frame = (now:number) => {
      frames.push(now-last); last=now
      maxAnimations=Math.max(maxAnimations,root.getAnimations({subtree:true}).length)
      maxNodes=Math.max(maxNodes,root.querySelectorAll('*').length)
      if(recording)requestAnimationFrame(frame)
    }
    requestAnimationFrame(frame)
    let reply = original+'\n\n'+('核对活动资料并整理筹备事项。'.repeat(120))
    for(let i=0;i<20;i++){
      reply+='追加内容。'
      useAgentStore.setState({outcomes:state.outcomes.map((o:object,index:number)=>index===state.outcomes.length-1?{...o,reply,streaming:true}:o)})
      await new Promise(resolve=>setTimeout(resolve,60))
    }
    recording=false
    frames.sort((a,b)=>a-b)
    return {maxAnimations,maxNodes,p95FrameMs:frames[Math.floor(frames.length*.95)],maxFrameMs:frames.at(-1),preserved:first===root.firstChild}
  },agentModule())
  console.log('stream-reveal-performance',JSON.stringify(metrics))
  expect(metrics.maxAnimations).toBeLessThanOrEqual(12)
  expect(metrics.maxNodes).toBeLessThan(40)
  expect(metrics.preserved).toBe(true)
})

test('实时推理共用连续队列，展开收起不会重播已显示的文字', async ({ page }) => {
  const {agentModule}=await setup(page)
  await page.emulateMedia({reducedMotion:'no-preference'})
  await task(page,'查询归档要求').click()
  await expect(page.locator('.lingsi-reply')).toContainText('需要归档策划、签到与总结材料。')
  const reasoning = '先核对活动资料，再整理筹备事项，最后确认需要补充的条件。'.repeat(8)
  await page.evaluate(async ({text,source}) => {
    const {useAgentStore} = await import(source)
    const state = useAgentStore.getState()
    useAgentStore.setState({thinking:true,activeStreamId:'reveal-queue',timeline:[{
      key:'queue-reasoning',seq:1,kind:'thinking',round:0,label:text.slice(0,42)+'…',detail:text,completed:false,ts:1,
    }],outcomes:state.outcomes.map((o:object,i:number)=>i===state.outcomes.length-1 ? {...o,reply:'',streamId:'reveal-queue',streaming:true}:o)})
  },{text:reasoning,source:agentModule()})
  const headline = page.locator('.lingsi-tl-think-head .lingsi-tl-label').first()
  await expect(page.getByRole('button',{name:'展开推理',exact:true})).toBeVisible()
  await expect.poll(()=>headline.evaluate(el=>el.firstChild?.nodeType)).toBe(3)
  await page.getByRole('button',{name:'展开推理',exact:true}).click()
  await page.evaluate(async source => {
    const {useAgentStore} = await import(source)
    useAgentStore.getState().applyStreamEvent({streamId:'reveal-queue',seq:2,type:'THINKING_DELTA',ts:2,payload:{round:0,text:'追加片段。'.repeat(60)}})
  },agentModule())
  const body = page.locator('.lingsi-tl-think-body')
  // The appended chunk remains behind the first chunk, with no offscreen
  // per-character animations created for that distant part of the queue.
  await expect(body.locator('.pams-stream-pending')).toContainText('追加片段。')
  expect(await body.locator('.pams-stream-char').count()).toBeLessThanOrEqual(12)
  await page.getByRole('button',{name:'收起推理',exact:true}).click()
  await expect(headline).toBeVisible()
  expect(await headline.evaluate(el=>getComputedStyle(el).opacity)).toBe('1')
  expect(await headline.evaluate(el=>el.firstChild?.nodeType)).toBe(3)
  await page.getByRole('button',{name:'展开推理',exact:true}).click()
  expect(await body.evaluate(el=>el.firstElementChild?.firstChild?.nodeType)).toBe(3)

  // A real tool transition must finish the preceding reveal before its status
  // appears, rather than queueing the tool or answer behind an animation.
  await page.evaluate(async source => {
    const {useAgentStore}=await import(source)
    const apply=useAgentStore.getState().applyStreamEvent
    apply({streamId:'reveal-queue',seq:3,type:'THINKING_DELTA',ts:3,payload:{round:0,text:'即将核对。'.repeat(80)}})
    apply({streamId:'reveal-queue',seq:4,type:'TOOL_START',ts:4,payload:{tool:'queryActivityOverview',label:'核对活动资料'}})
  },agentModule())
  await expect(page.locator('.lingsi-tl-item').filter({hasText:'核对活动资料'})).toBeVisible()
  await expect(body.locator('.pams-stream-char, .pams-stream-pending')).toHaveCount(0)
  await page.evaluate(async source => {
    const {useAgentStore}=await import(source)
    const apply=useAgentStore.getState().applyStreamEvent
    apply({streamId:'reveal-queue',seq:5,type:'TOOL_END',ts:5,payload:{tool:'queryActivityOverview',status:'OK'}})
    apply({streamId:'reveal-queue',seq:6,type:'THINKING_DELTA',ts:6,payload:{round:1,text:'整理下一轮的资料与结论。'.repeat(60)}})
  },agentModule())
  await expect(page.locator('.lingsi-tl-row-think')).toHaveCount(2)
  const result = await page.evaluate(async source => {
    const {useAgentStore,flushStreamDeltas}=await import(source)
    useAgentStore.getState().applyStreamEvent({streamId:'reveal-queue',seq:7,type:'MESSAGE_DELTA',ts:7,payload:{round:1,text:'资料已核对，下面是本次结论。'}})
    flushStreamDeltas()
    return useAgentStore.getState().timeline.filter((item:{kind:string})=>item.kind==='thinking').every((item:{completed:boolean})=>item.completed)
  },agentModule())
  expect(result).toBe(true)
  await expect(page.locator('.lingsi-reply')).toContainText('资料已核对，下面是本次结论。')
  await expect(page.locator('.lingsi-process-head')).toContainText('正在生成结果')
  await expect(page.locator('.lingsi-tl-row-think .pams-stream-char, .lingsi-tl-row-think .pams-stream-pending')).toHaveCount(0)
})

test('推理折叠不露正文，展开后不重复摘要', async ({ page }) => {
  await setup(page)
  const reasoning = '先核对活动资料，再确认筹备条件，最后整理需要用户核对的事项。'.repeat(6)
  await page.route(url=>url.pathname==='/api/agent/conversations/2/messages', route=>route.fulfill({json:{code:200,data:{records:[
    {id:301,role:'USER',content:'请核对活动筹备资料。'},
    {id:302,role:'ASSISTANT',streamId:'reasoning-regression',content:'已核对，请补充活动时间。',thinking:[{round:0,text:reasoning,durationMs:1300}],toolCalls:[],references:[],citations:[]}
  ],total:2}}}))
  await task(page,'查询归档要求').click()
  await page.locator('.lingsi-process-head').click()
  const body = page.locator('.lingsi-tl-think-body')
  await expect(body).not.toBeVisible()
  await page.getByRole('button',{name:'展开推理',exact:true}).click()
  await expect(body).toBeVisible()
  await expect(body).toHaveText(reasoning)
  await expect(page.locator('.lingsi-tl-think-head .lingsi-tl-label:visible')).toHaveText('推理内容')
  await page.setViewportSize({width:390,height:844})
  await expect(body).toBeVisible()
  const box = await body.boundingBox()
  expect(box!.x+box!.width).toBeLessThanOrEqual(390)
  await page.screenshot({path:resolve(output,'reasoning-expanded-fixed.png'),animations:'disabled'})
  await page.getByRole('button',{name:'收起推理',exact:true}).click()
  await expect(body).not.toBeVisible()
})

test('非连续角标与历史策划 JSON 片段可读并可打开', async ({ page }) => {
  await setup(page)
  await page.route(url => url.pathname === '/api/agent/conversations/2/messages', route => route.fulfill({json:{code:200,data:{records:[
    {id:200,role:'USER',content:'预算依据是什么？',references:[]},
    {id:201,role:'ASSISTANT',streamId:'citation-audit',content:'经费参考历史预算[3]，请核对原文。',toolCalls:[],references:[],citations:[
      {index:3,sourceType:'activity_plan',sourceId:5,sourceTitle:'培训班策划书',path:'经费预算',snippet:'[{"item":"培训资料","quantity":200,"unitPrice":1.5,"totalPrice":300},{"item":"结业证…',link:'/activities/1?tab=plan'}
    ]}
  ],total:2}}}))
  await task(page,'查询归档要求').click()
  const cite = page.locator('.lingsi-reply .lingsi-cite')
  await expect(cite).toHaveText('3')
  const snippet = page.locator('.lingsi-source-snippet')
  await expect(snippet).toContainText('项目：培训资料；数量：200；单价：1.5；合计：300')
  await expect(snippet).not.toContainText('"item"')
  await cite.click()
  await expect(page.locator('.lingsi-preview-document')).toContainText('项目：培训资料')
  await expect(page.locator('.lingsi-preview-document')).toContainText('片段已截断')
  await page.screenshot({path:resolve(output,'citation-audit-fixed.png'),animations:'disabled'})
})

test('公共精修样式与复制反馈在明暗主题生效', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await setup(page)
  await task(page, '准备培训班策划初稿').click()
  const composer = page.locator('.lingsi-composer-box')
  await page.locator('.lingsi-composer-ta').focus()
  const light = await composer.evaluate(el => ({ shadow: getComputedStyle(el).boxShadow, border: getComputedStyle(el).borderTopColor }))
  expect(light.shadow).toContain('inset')
  expect(light.shadow.split(/,(?![^()]*\))/)).toHaveLength(2)
  expect(light.border).toBe('rgba(0, 0, 0, 0)')
  expect(await page.locator('.lingsi-main-title h1').evaluate(el => getComputedStyle(el).fontWeight)).toBe('600')
  await page.getByRole('button', { name: '切换深色' }).click()
  expect(await page.locator('.lingsi-main-title h1').evaluate(el => getComputedStyle(el).fontWeight)).toBe('560')
  expect(await page.locator('body').evaluate(el => getComputedStyle(el).fontVariantNumeric)).toBe('tabular-nums')
  await page.evaluate(() => Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: () => Promise.resolve() } }))
  const copy = page.locator('.lingsi-msg-actions .pams-copy')
  await copy.click()
  await expect(copy).toHaveAttribute('data-copied', 'true')
  await expect(copy).toHaveAttribute('data-copied', 'false', { timeout: 4000 })
  const detail = page.getByRole('button', { name: /查看明细/ })
  await detail.click()
  const collapse = page.locator('.lingsi-plan .pams-collapse')
  await expect(collapse).toHaveAttribute('data-open', 'true')
  await expect(collapse).not.toHaveAttribute('inert')
  await page.getByRole('button', { name: /收起明细/ }).click()
  await expect(collapse).toHaveAttribute('inert', '')
})

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
  await expect(page.locator('.lingsi-preview-scroll').getByRole('button',{name:/^(确认执行|确认写入|保存业务草稿)$/})).toHaveCount(0)
  await page.getByRole('tab',{name:'内容预览',exact:true}).click()
  await page.screenshot({path:resolve(output,'plan-preview-light.png'),animations:'disabled'})
  await pickMode(page,'只读模式')
  await expect(page.getByRole('button',{name:/^(确认执行|确认写入|保存业务草稿)$/,exact:true})).toBeDisabled()
  await expect(page.getByRole('checkbox',{name:'自动协作'})).toBeDisabled()
  await pickMode(page,'资料问答')
  await expect(page.getByRole('button',{name:/^(确认执行|确认写入|保存业务草稿)$/,exact:true})).toBeDisabled()
  await pickMode(page,'标准模式')
  await page.getByRole('button',{name:/^(确认执行|确认写入|保存业务草稿)$/,exact:true}).click()
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


test('S3 审查问题显示原文位置，修订后才出现可确认的新清单',async({page})=>{
  const {agentModule,errors}=await setup(page)
  let run={id:80,streamId:'review-stream',conversationId:1,status:'REVIEW_REQUIRED',stateVersion:10,
    nodes:[{id:1,workItemId:801,agent:'writing',task:'日期应为2026-11-20',status:'REVIEW_REQUIRED',dependsOn:[],revision:1,attempt:1}],
    reviews:{'801':{status:'BLOCKED',issues:[{id:1,revision:1,attempt:1,severity:'BLOCKING',code:'CONTENT',location:'正文第 1 行，字符 5',quote:'2025-11-20',reason:'日期与任务不一致，请改为2026年。',sourceRef:'task'}]}},
    actions:[{id:81,runId:80,workItemId:801,revision:1,toolName:'draftArticleFromActivity',status:'REVIEW_REQUIRED',summary:'培训班推文',conversationId:1,expired:false,reversible:false,plan:{...plan,planId:81,confirmToken:'',preview:'活动在2025-11-20举行。'}}]}
  let revisionBody:unknown
  await page.route('**/api/agent/team/runs/80**',async route=>{
    if(route.request().method()==='POST' && route.request().url().endsWith('/revise')) {
      revisionBody=route.request().postDataJSON()
      run={...run,status:'WAITING_CONFIRM',stateVersion:11,
        nodes:[{...run.nodes[0],revision:2,attempt:2,status:'WAITING_CONFIRM'}],
        reviews:{'801':{status:'CHECKED',issues:[]}},
        actions:[{...run.actions[0],id:82,revision:2,status:'PENDING_CONFIRM',plan:{...plan,planId:82,confirmToken:'new-revision-token',preview:'活动在2026-11-20举行。'}}]}
    }
    await route.fulfill({json:{code:200,message:'成功',data:run}})
  })
  await page.evaluate(async ({source,run})=>{
    const {useAgentStore}=await import(source)
    const state=useAgentStore.getState()
    useAgentStore.setState({currentConversationId:1,userTexts:['请准备培训班推文'],outcomes:[{messageId:800,streamId:run.streamId,reply:'正文存在需要修订的问题。',run,executionState:run.status,toolCalls:[],plan:null,form:null,references:[],artifacts:[],provider:'test',degraded:false,durationMs:1,thinking:[],thinkingNotes:[],timeline:[],streaming:false}]})
  },{source:agentModule(),run})
  await expect(page.getByRole('region',{name:'内容审查'})).toContainText('正文第 1 行')
  await expect(page.getByRole('region',{name:'内容审查'})).toContainText('日期与任务不一致')
  await expect(page.getByRole('button',{name:/^(确认执行|确认写入|保存业务草稿)$/,exact:true})).toHaveCount(0)
  await page.getByText('查看待修订正文',{exact:true}).click()
  await expect(page.locator('.lingsi-review-preview')).toContainText('活动在2025-11-20举行。')
  await page.screenshot({path:resolve(output,'s3-review-blocked-light.png'),animations:'disabled'})
  await page.setViewportSize({width:390,height:844})
  await page.getByRole('button',{name:'切换深色'}).click()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  await page.screenshot({path:resolve(output,'s3-review-blocked-dark-mobile.png'),animations:'disabled'})
  await page.getByRole('button',{name:'修改任务说明',exact:true}).click()
  await page.getByRole('textbox',{name:'任务说明'}).fill('请将日期修订为2026-11-20')
  await page.getByRole('button',{name:'保存修订并继续'}).click()
  await expect(page.getByRole('button',{name:/^(确认执行|确认写入|保存业务草稿)$/,exact:true})).toBeVisible()
  expect(revisionBody).toEqual({revision:1,instruction:'请将日期修订为2026-11-20'})
  await expect(page.getByRole('region',{name:'内容审查'})).toContainText('已完成自动审查')
  expect(errors).toEqual([])
})

test('S3 首帧前停止可点击，停止确认前保持等待状态',async({page})=>{
  const {agentModule,errors}=await setup(page)
  let release!:()=>void, requestId='', stopped=false
  const held=new Promise<void>(resolve=>release=resolve)
  await page.route('**/api/agent/chat',async route=>{
    requestId=route.request().postDataJSON().requestId
    await held
    await route.fulfill({json:{code:200,message:'成功',data:{requestId,streamId:'early-stop',conversationId:1,reply:'迟到的生成内容',toolCalls:[],references:[],plan:null,form:null}}})
  })
  await page.route('**/api/agent/requests/**',async route=>{
    const id=new URL(route.request().url()).pathname.split('/')[4]
    expect(id).toBe(requestId)
    const data=route.request().method()==='POST'?{status:'STOPPING',aborted:true}:{requestId,streamId:null,conversationId:null,status:stopped?'CANCELLED':'STOPPING',cancelled:true}
    await route.fulfill({json:{code:200,message:'成功',data}})
  })
  await page.locator('.lingsi-composer textarea').fill('测试首帧前停止')
  await page.getByRole('button',{name:'发送任务',exact:true}).click()
  await expect(page.getByRole('button',{name:'停止任务'})).toBeEnabled()
  await page.getByRole('button',{name:'停止任务'}).click()
  await expect(page.getByRole('button',{name:'停止任务'})).toBeDisabled()
  await expect(page.locator('.lingsi-notice')).toContainText('正在停止')
  stopped=true
  await page.evaluate(async source=>{const {useAgentStore}=await import(source);await useAgentStore.getState().reconcileConnection()},agentModule())
  await expect(page.locator('.lingsi-reply')).toContainText('本次生成已停止')
  release()
  await expect(page.locator('.lingsi-reply')).not.toContainText('迟到的生成内容')
  expect(requestId).toMatch(/^[a-zA-Z0-9-]{8,64}$/)
  expect(errors).toEqual([])
})


for(const phone of [false,true]) test(`S4 成果版本编辑、对比与确认恢复（${phone?'手机深色':'桌面浅色'}）`,async({page})=>{
  if(phone)await page.setViewportSize({width:390,height:844})
  const {agentModule,errors}=await setup(page)
  let current=1,status='PENDING_CONFIRM',rejectSave=false
  const versions:Record<number,{title:string,content:string}>={1:{title:'培训班报道',content:'活动日期为2025-11-20。'}}
  const descriptor=()=>({id:42,title:'培训班推文草稿',kind:'TEXT',currentVersion:current,taskId:80+current,runId:80,workItemId:801,status,toolName:'draftArticleFromActivity'})
  const currentPlan=()=>({...plan,planId:80+current,summary:'培训班推文草稿',confirmToken:`version-${current}-token`,preview:`# ${versions[current].title}\n\n${versions[current].content}`,skipped:[]})
  const action=()=>({id:80+current,artifactId:42,artifactVersion:current,runId:80,workItemId:801,revision:current,toolName:'draftArticleFromActivity',status,summary:'培训班推文草稿',conversationId:1,createdAt:'2026-10-25',confirmedAt:null,finishedAt:null,expired:false,reversible:true,plan:status==='PENDING_CONFIRM'?currentPlan():null,artifacts:status==='SUCCESS'?[{entityType:'article',entityId:9,name:versions[current].title,path:'/content/article?id=9'}]:[]})
  const run=()=>({id:80,streamId:'artifact-stream',conversationId:1,status:status==='PENDING_CONFIRM'?'WAITING_CONFIRM':'SUCCESS',stateVersion:10+current+(status==='SUCCESS'?10:0),
    nodes:[{id:1,workItemId:801,agent:'writing',task:'准备培训班推文草稿',status:status==='PENDING_CONFIRM'?'WAITING_CONFIRM':'SUCCESS',dependsOn:[],revision:current,attempt:current}],actions:[action()],artifacts:[descriptor()],reviews:{}})
  const detail=(v:number)=>({artifact:descriptor(),versions:Object.keys(versions).map(Number).sort((a,b)=>b-a).map(version=>({version,origin:version===1?'AGENT':'USER',createdAt:'2026-10-25',taskId:80+version})),version:v,origin:v===1?'AGENT':'USER',preview:`# ${versions[v].title}\n\n${versions[v].content}`,fields:versions[v],plan:{items:[{label:'正文',sub:versions[v].content}],skipped:[]},status:v===current?status:'CANCELLED',taskId:80+v,reviewStatus:'CHECKED',editable:v===current && status==='PENDING_CONFIRM',artifacts:status==='SUCCESS'?action().artifacts:[],sources:[{label:'任务要求',text:'请准备培训班报道。'}],sourceNotice:'本次任务与业务资料',issues:[]})
  await page.route('**/api/agent/artifacts**',async route=>{
    const path=new URL(route.request().url()).pathname
    if(path.endsWith('/artifacts'))return route.fulfill({json:{code:200,message:'成功',data:[descriptor()]}})
    if(route.request().method()==='POST') {
      const body=route.request().postDataJSON()
      if(rejectSave)return route.fulfill({json:{code:409,message:'已有新版本，请刷新核对',data:null}})
      expect(body.expectedVersion).toBe(current);expect(body.mode).toBe('standard')
      current++;versions[current]=body.fields
      return route.fulfill({json:{code:200,message:'成功',data:{detail:detail(current),task:action(),run:run()}}})
    }
    const v=Number(new URL(route.request().url()).searchParams.get('version') || current)
    return route.fulfill({json:{code:200,message:'成功',data:detail(v)}})
  })
  await page.route('**/api/agent/team/runs/80',route=>route.fulfill({json:{code:200,message:'成功',data:run()}}))
  await page.route('**/api/agent/tasks/*/confirm',async route=>{expect(route.request().postDataJSON().confirmToken).toBe(`version-${current}-token`);status='SUCCESS';await route.fulfill({json:{code:200,message:'成功',data:action()}})})
  await page.route('**/api/agent/conversations/1/messages**',route=>route.fulfill({json:{code:200,message:'成功',data:{records:[{id:1,role:'USER',content:'请准备培训班报道。',references:[]},{id:2,role:'ASSISTANT',content:'请核对成果后确认保存。',streamId:'artifact-stream',run:run(),references:[],toolCalls:[],artifacts:[],plan:null,form:null}],total:2}}}))
  if(phone){await page.getByRole('button',{name:'切换深色'}).click();await page.getByRole('button',{name:'打开任务列表',exact:true}).click()}
  await task(page,'准备培训班策划初稿').click()
  await expect(page.getByRole('combobox',{name:'成果版本'})).toBeVisible()
  await expect(page.locator('.lingsi-preview-document')).toContainText('2025-11-20')
  await page.getByRole('button',{name:'修改正文',exact:true}).click()
  await page.getByRole('textbox',{name:'正文',exact:true}).fill('活动日期为2026-11-20。用户已核对。')
  // A rejected save must preserve the editor content.
  rejectSave=true
  await page.getByRole('button',{name:'保存新版本并复核'}).click()
  await expect(page.getByRole('alert').filter({hasText:'保存未完成'})).toBeVisible()
  await expect(page.getByRole('textbox',{name:'正文',exact:true})).toHaveValue('活动日期为2026-11-20。用户已核对。')
  rejectSave=false
  await page.getByRole('button',{name:'保存新版本并复核'}).click()
  await expect(page.getByRole('combobox',{name:'成果版本'})).toHaveValue('2')
  await expect(page.locator('.lingsi-unsaved-prompt')).toHaveCount(0)
  await page.getByRole('tab',{name:'本次变更',exact:true}).click()
  await expect(page.locator('.lingsi-version-diff-removed')).toContainText('2025-11-20')
  await expect(page.locator('.lingsi-version-diff-added')).toContainText('2026-11-20')
  await page.screenshot({path:resolve(output,phone?'s4-workbench-dark-mobile.png':'s4-workbench-light-desktop.png'),animations:'disabled'})
  await page.getByRole('combobox',{name:'成果版本'}).selectOption('1')
  await page.getByRole('tab',{name:'内容预览',exact:true}).click()
  await expect(page.locator('.lingsi-preview-document')).toContainText('2025-11-20')
  await expect(page.getByRole('button',{name:'修改正文',exact:true})).toHaveCount(0)
  await page.getByRole('button',{name:'查看第 2 版更新'}).click()
  await expect(page.locator('.lingsi-preview-document')).toContainText('用户已核对')
  if(phone)await page.getByRole('button',{name:'关闭工作成果预览'}).click()
  await page.getByRole('button',{name:'保存业务草稿',exact:true}).click()
  await page.reload()
  if(phone)await page.getByRole('button',{name:'打开任务列表',exact:true}).click()
  await task(page,'准备培训班策划初稿').click()
  await expect(page.getByRole('combobox',{name:'成果版本'})).toHaveValue('2')
  await expect(page.locator('.lingsi-artifact-version-bar')).toContainText('已保存业务草稿')
  await expect(page.locator('.lingsi-preview-document')).toContainText('2026-11-20')
  await expect(page.getByRole('button',{name:'修改正文',exact:true})).toHaveCount(0)
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy()
  expect(errors).toEqual([])
})


test('S4 相同补充信息合并填写，部分校验失败保留输入并独立继续',async({page})=>{
  const {errors}=await setup(page)
  const actions=[101,102].map(id=>({id,runId:90,workItemId:id,revision:1,toolName:'generateActivitySchedule',status:'WAITING_INPUT',summary:`排班任务${id}`,createdAt:'2026-10-25',confirmedAt:null,finishedAt:null,expired:false,reversible:true,conversationId:1,
    form:{taskId:id,formKey:'department',title:'补充参与部门',fields:[{name:'department',label:'参与部门',type:'text' as const,required:true}],values:{department:''}},plan:null as typeof plan | null}))
  const submissions:number[]=[];let failSecond=true
  const run=()=>({id:90,streamId:'forms',conversationId:1,status:actions.some(a=>a.status==='WAITING_INPUT')?'WAITING_INPUT':'WAITING_CONFIRM',stateVersion:10+submissions.length,
    nodes:actions.map((a,i)=>({id:i+1,workItemId:a.id,agent:'scheduling',task:a.summary,status:a.status==='PENDING_CONFIRM'?'WAITING_CONFIRM':a.status,dependsOn:[],revision:1,attempt:1})),actions})
  await page.route('**/api/agent/conversations/1/messages**',route=>route.fulfill({json:{code:200,data:{records:[{id:1,role:'USER',content:'请准备两个排班。',references:[]},{id:2,role:'ASSISTANT',content:'请补充参与部门。',streamId:'forms',run:run(),references:[],toolCalls:[],artifacts:[],plan:null,form:null}],total:2}}}))
  await page.route('**/api/agent/team/runs/90',route=>route.fulfill({json:{code:200,data:run()}}))
  await page.route('**/api/agent/tasks/*/input',async route=>{
    const id=Number(new URL(route.request().url()).pathname.split('/')[4]);submissions.push(id)
    expect(route.request().postDataJSON().values).toEqual({department:'文秘部'})
    if(id===102 && failSecond){failSecond=false;return route.fulfill({json:{code:200,data:{status:'ERROR',error:'请核对排班日期'}}})}
    const action=actions.find(a=>a.id===id)!;action.status='PENDING_CONFIRM';action.plan={...plan,planId:id}
    await route.fulfill({json:{code:200,data:{status:'PLAN',plan:action.plan}}})
  })
  await task(page,'准备培训班策划初稿').click()
  await expect(page.getByRole('textbox',{name:'参与部门'})).toHaveCount(1)
  await page.getByRole('textbox',{name:'参与部门'}).fill('文秘部')
  await page.getByRole('button',{name:'补充并继续'}).click()
  await expect(page.locator('.lingsi-plan')).toHaveCount(1)
  await expect(page.getByRole('textbox',{name:'参与部门'})).toHaveValue('文秘部')
  await expect(page.getByRole('alert').filter({hasText:'排班任务102'}).first()).toContainText('请核对排班日期')
  await page.getByRole('button',{name:'补充并继续'}).click()
  await expect(page.locator('.lingsi-plan')).toHaveCount(2)
  await expect(page.getByRole('textbox',{name:'参与部门'})).toHaveCount(0)
  expect(submissions).toEqual([101,102,102]);expect(errors).toEqual([])
})
