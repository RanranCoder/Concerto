import { test, expect, type Page } from '@playwright/test'
import type { OrgAccount, OrgInfo, OrgRegisterPayload, OrgUnitNode } from '../src/api/org'

const permissions = ['org:manage','org:structure:manage','org:invite','user:view','user:manage','member:view','role:view']
test.setTimeout(30_000)
const makeNode = (id: number, name: string, parentId: number | null = null, children: OrgUnitNode[] = []): OrgUnitNode => ({
  id,name,parentId,children,unitType: parentId ? '小组' : '部门',leaderUserId:id===1?1:null,sortOrder:0,code:null,level:1,path:`/${id}`,
})
async function setup(page: Page, settings: { failLoad?: boolean; empty?: boolean; structureOnly?: boolean; noManage?: boolean } = {}) {
  const caps = settings.noManage ? [] : settings.structureOnly ? ['org:structure:manage'] : permissions
  let user = {id:1,username:'admin',realName:'林晓',roleCode:'DIRECTOR',roleLevel:4,deptId:1,deptName:'组织部',orgId:1,orgName:'测试学院党建办公室',permissions:caps,businessPermissions:[],managementCapabilities:caps,isOwner:true,authzVersion:1,roleId:1}
  let org: OrgInfo = {id:1,name:'测试学院党建办公室',shortName:'党建办公室',type:'PARTY',themeJson:'{"primaryColor":"#1668DC"}',enabledPacks:'["party"]',logoUrl:null,terminologyJson:null}
  let units = settings.empty ? [] : [makeNode(1,'组织部',null,[makeNode(2,'培训组',1,[makeNode(3,'服务分队',2)])]),makeNode(4,'宣传部')]
  let accounts: OrgAccount[] = [
    {id:1,username:'admin',realName:'林晓',deptId:1,roleName:'总负责人',status:1},
    {id:2,username:'staff',realName:'陈禾',deptId:3,roleName:'干事',status:1},
    {id:3,username:'disabled',realName:'停用成员',deptId:2,roleName:'干事',status:0},
  ]
  const calls: {method:string;path:string;body:Record<string,unknown>}[] = []
  const state = {failCreate:false,failDelete:false,failLoad:settings.failLoad ?? false,loadCount:0}
  function all(items=units):OrgUnitNode[]{return items.flatMap(n=>[n,...all(n.children)])}
  await page.addInitScript(user => localStorage.setItem('pams_token',JSON.stringify({state:{token:'org-ui-test',user},version:1})),user)
  await page.route('**/api/**',async route=>{
    const req=route.request(), path=new URL(req.url()).pathname, method=req.method()
    if(!path.startsWith('/api/'))return route.continue()
    const body=req.postData()?req.postDataJSON():{}
    if(method!=='GET')calls.push({method,path,body})
    const ok=(data:unknown)=>route.fulfill({json:{code:200,data}})
    if(path==='/api/auth/me')return ok(user)
    if(path==='/api/auth/login')return ok({token:'org-new-test',user})
    if(path==='/api/orgs/register'){
      const data=body as OrgRegisterPayload;let next=20
      const map=(items:OrgRegisterPayload['units'],parent:number|null=null):OrgUnitNode[]=>items.map(n=>{const id=next++;return {...makeNode(id,n.name,parent),unitType:n.unitType||'部门',leaderUserId:null,children:map(n.children||[],id)}})
      units=map(data.units);org={...org,id:2,name:data.org.name,shortName:data.org.shortName||null,type:data.org.type,themeJson:JSON.stringify({primaryColor:data.org.themeColor}),enabledPacks:JSON.stringify(data.enabledPacks)}
      user={...user,orgId:2,username:data.admin.username,orgName:org.name,deptId:units[0].id,deptName:units[0].name}
      accounts=[{id:1,username:user.username,realName:'管理员',deptId:units[0].id,roleName:'总负责人',status:1}]
      return ok({orgId:2,orgName:org.name,adminUsername:user.username})
    }
    if(path==='/api/orgs/current'&&method==='PUT'){
      org={...org,name:body.name,shortName:body.shortName,themeJson:JSON.stringify({primaryColor:body.themeColor})};return ok(null)
    }
    if(path==='/api/orgs/current')return ok(org)
    if(path==='/api/orgs/public')return ok([org])
    if(path==='/api/orgs/current/structure'){
      state.loadCount++
      if(state.failLoad)return route.fulfill({status:500,json:{code:500,message:'架构加载失败，请重试'}})
      return ok({units,accounts})
    }
    if(path==='/api/orgs/current/units'&&method==='POST'){
      if(state.failCreate)return route.fulfill({status:500,json:{code:500,message:'节点暂时无法保存'}})
      const id=100+calls.length,n={...makeNode(id,body.name,body.parentId),unitType:body.unitType,leaderUserId:body.leaderUserId,sortOrder:body.sortOrder}
      if(body.parentId)all().find(n=>n.id===body.parentId)!.children.push(n);else units.push(n)
      return ok(id)
    }
    if(path.match(/\/orgs\/current\/units\/\d+$/)){
      const id=Number(path.split('/').pop()),n=all().find(n=>n.id===id)!
      if(method==='PUT'){Object.assign(n,body);return ok(null)}
      if(method==='DELETE'){
        if(state.failDelete)return route.fulfill({status:400,json:{code:2013,message:'该节点有关联花名册或历史工作资料'}})
        const remove=(items:OrgUnitNode[]):OrgUnitNode[]=>items.filter(n=>n.id!==id).map(n=>({...n,children:remove(n.children)}));units=remove(units);return ok(null)
      }
    }
    if(path==='/api/orgs/current/invitations')return ok({id:1,orgId:org.id,code:'invite-test',roleCode:body.roleCode,orgUnitId:body.orgUnitId,expireAt:'2026-10-21T12:00:00'})
    if(path==='/api/roles/assignable')return ok([{id:2,code:'STAFF',name:'干事',status:'ACTIVE'}])
    if(path==='/api/depts')return ok(all().map(n=>({id:n.id,name:n.name})))
    if(path==='/api/users')return ok({records:[],total:0})
    if(path==='/api/users/summary')return ok({total:0,enabled:0,pendingActivation:0,disabled:0,ownerCount:0,administratorCount:0,roles:[]})
    if(method!=='GET')throw Error('Unexpected API mutation: '+path)
    return ok([])
  })
  return {calls,state}
}
const detail=(page:Page)=>page.locator('.org-detail')

test('preserves ancestry, complete counts and distinct create/edit forms; failed saves can be retried',async({page})=>{
  const {calls,state}=await setup(page)
  await page.goto('/org/structure?unit=1')
  await expect(detail(page).locator('h2').first()).toHaveText('组织部')
  await expect(detail(page).locator('.org-detail-summary')).toContainText('含下级账号3')
  await page.getByLabel('搜索架构节点').fill('服务分队')
  await expect(page.locator('.org-tree')).toContainText('培训组')
  await expect(page.locator('.org-tree')).not.toContainText('宣传部')
  await page.getByLabel('搜索架构节点').fill('')
  await detail(page).getByRole('button',{name:'添加下级',exact:true}).click()
  const dialog=page.getByRole('dialog')
  await expect(dialog.getByLabel('节点名称',{exact:true})).toHaveValue('')
  await dialog.getByLabel('节点名称',{exact:true}).fill('宣传部')
  await dialog.getByRole('button',{name:'添加节点',exact:true}).click()
  await expect(dialog).toContainText('当前组织已存在同名节点')
  expect(calls).toHaveLength(0)
  await dialog.getByLabel('节点名称',{exact:true}).fill('活动协调组')
  state.failCreate=true
  await dialog.getByRole('button',{name:'添加节点',exact:true}).click()
  await expect(dialog).toContainText('节点暂时无法保存')
  await expect(dialog.getByLabel('节点名称',{exact:true})).toHaveValue('活动协调组')
  state.failCreate=false
  const before=calls.filter(c=>c.method==='POST').length
  await dialog.getByRole('button',{name:/添加节点$/}).evaluate(button=>{(button as HTMLButtonElement).click();(button as HTMLButtonElement).click()})
  await expect(dialog).toHaveCount(0)
  expect(calls.filter(c=>c.method==='POST')).toHaveLength(before+1)
  await expect(detail(page).locator('h2').first()).toHaveText('活动协调组')
  expect(calls.filter(c=>c.method==='POST').at(-1)?.body).toMatchObject({name:'活动协调组',unitType:'小组',parentId:1,leaderUserId:null})
  await detail(page).getByRole('button',{name:'编辑节点',exact:true}).click()
  await expect(dialog.getByLabel('节点名称',{exact:true})).toHaveValue('活动协调组')
  await dialog.getByLabel('节点名称',{exact:true}).fill('协调分队')
  await dialog.getByRole('button',{name:'保存修改',exact:true}).click()
  await expect(detail(page).locator('h2').first()).toHaveText('协调分队')
})

test('leaders, disabled accounts, deletion guards and server-side history errors remain separate from roles',async({page})=>{
  const {calls,state}=await setup(page)
  await page.goto('/org/structure?unit=2')
  await detail(page).getByRole('button',{name:'直属账号',exact:false}).click()
  await expect(detail(page).getByRole('table')).toContainText('停用成员')
  await detail(page).getByRole('button',{name:'设置负责人',exact:true}).click()
  let dialog=page.getByRole('dialog')
  await dialog.getByRole('combobox').click()
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({hasText:'陈禾 / staff'}).click()
  await dialog.getByRole('button',{name:'保存负责人',exact:true}).click()
  await expect(detail(page).locator('.org-detail-summary')).toContainText('陈禾')
  expect(calls[0].body).toEqual({name:'培训组',unitType:'小组',sortOrder:0,leaderUserId:2})
  await detail(page).getByRole('button',{name:'删除节点',exact:true}).click()
  await expect(page.getByRole('dialog')).toContainText('暂时无法删除培训组')
  expect(calls.filter(c=>c.method==='DELETE')).toHaveLength(0)
  await page.getByRole('button',{name:'我知道了',exact:true}).click()
  await page.goto('/org/structure?unit=4')
  state.failDelete=true
  await detail(page).getByRole('button',{name:'删除节点',exact:true}).click()
  await page.getByRole('button',{name:'确认删除',exact:true}).click()
  await expect(page.getByRole('dialog').getByText('该节点有关联花名册或历史工作资料',{exact:true})).toBeVisible()
  await expect(detail(page).locator('h2').first()).toHaveText('宣传部')
  state.failDelete=false
  await page.getByRole('button',{name:'确认删除',exact:true}).click()
  await expect(detail(page).locator('h2').first()).toHaveText('党建办公室')
})

test('profile changes update login presentation and department links carry the selected department',async({page})=>{
  const {calls}=await setup(page)
  await page.goto('/org/structure')
  await detail(page).getByRole('button',{name:'编辑组织资料',exact:true}).click()
  let dialog=page.getByRole('dialog')
  await dialog.getByLabel('组织简称（选填）').fill('学院党建办')
  await dialog.getByLabel('组织主题色').fill('#087F8C')
  await dialog.getByRole('button',{name:'保存修改',exact:true}).click()
  await expect(page.locator('.org-profile')).toContainText('学院党建办')
  expect(calls.find(c=>c.path==='/api/orgs/current')?.body).not.toHaveProperty('enabledPacks')
  await page.getByRole('button',{name:'登录页预览',exact:true}).click()
  await expect(page.getByRole('dialog').locator('.login-org')).toContainText('学院党建办')
  await page.getByRole('button',{name:'完成预览',exact:true}).click()
  await page.goto('/org/structure?unit=2')
  await detail(page).getByRole('button',{name:'成员与账号入口',exact:true}).click()
  dialog=page.getByRole('dialog')
  await expect(dialog.getByRole('link',{name:'打开成员管理'})).toHaveAttribute('href','/members?deptId=2')
  await dialog.getByRole('link',{name:'打开用户管理'}).click()
  await expect(page).toHaveURL(/admin\/users\?deptId=2/)
  await expect(page.locator('.ant-select-selection-item').filter({hasText:'培训组'})).toBeVisible()
})

test('registration tree is reused after administrator login and passwords are never carried in navigation',async({page})=>{
  await setup(page)
  await page.goto('/register')
  await page.getByLabel('组织名称',{exact:true}).fill('星河志愿者协会')
  await page.getByRole('button',{name:'下一步：组织架构',exact:true}).click()
  await page.getByRole('textbox',{name:'节点 1 名称',exact:true}).fill('服务中心')
  await page.getByRole('button',{name:'为节点 1 添加子节点',exact:true}).click()
  await page.getByRole('textbox',{name:'节点 1.1 名称',exact:true}).fill('志愿培训组')
  await page.getByRole('button',{name:'为节点 1.1 添加子节点',exact:true}).click()
  await page.getByRole('textbox',{name:'节点 1.1.1 名称',exact:true}).fill('现场服务分队')
  await page.getByRole('button',{name:'下一步：管理员账号',exact:true}).click()
  await page.getByLabel('管理员用户名',{exact:true}).fill('xinghe-admin')
  await page.getByLabel('登录密码',{exact:true}).fill('test-password')
  await page.getByLabel('确认密码',{exact:true}).fill('test-password')
  await page.getByRole('button',{name:'下一步：确认并创建',exact:true}).click()
  await page.getByRole('button',{name:'创建组织',exact:true}).click()
  await page.getByRole('button',{name:'使用管理员账号登录',exact:true}).click()
  await expect(page.getByLabel('用户名',{exact:true})).toHaveValue('xinghe-admin')
  await expect(page.getByLabel('密码',{exact:true})).toHaveValue('')
  await page.getByLabel('密码',{exact:true}).fill('test-password')
  await page.getByRole('button',{name:'登录工作台',exact:true}).click()
  await expect(page).toHaveURL(/org\/structure/)
  await expect(page.locator('.org-created-notice')).toContainText('沿用创建时的设置')
  await expect(page.locator('.org-tree')).toContainText('现场服务分队')
  expect(await page.evaluate(()=>JSON.stringify(history.state))).not.toContain('test-password')
})

test('loading failures are recoverable and an empty structure has a concrete creation entry',async({page})=>{
  const {state}=await setup(page,{failLoad:true,empty:true})
  await page.goto('/org/structure')
  await expect(page.getByRole('button',{name:'重新加载',exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'添加一级节点',exact:true})).toBeDisabled()
  state.failLoad=false
  await page.getByRole('button',{name:'重新加载',exact:true}).click()
  await expect(detail(page)).toContainText('还没有架构节点')
  await expect(detail(page).getByRole('button',{name:'添加一级节点',exact:true})).toBeEnabled()
})

test('structure-only delegation has node controls but no profile or invitation controls',async({page})=>{
  await setup(page,{structureOnly:true})
  await page.goto('/org/structure')
  await expect(detail(page)).toBeVisible()
  await expect(detail(page).getByRole('button',{name:'编辑组织资料'})).toHaveCount(0)
  await expect(page.getByRole('menuitem',{name:/组织架构/})).toBeVisible()
  await detail(page).getByRole('button',{name:'成员与账号入口'}).click()
  await expect(page.getByRole('dialog').getByRole('button',{name:'查看邀请码'})).toHaveCount(0)
})

test('invitation uses assignable roles and the selected node while explaining the missing redemption entry',async({page})=>{
  const {calls}=await setup(page)
  await page.goto('/org/structure?unit=2')
  await detail(page).getByRole('button',{name:'成员与账号入口'}).click()
  await page.getByRole('dialog').getByRole('button',{name:'查看邀请码'}).click()
  const dialog=page.getByRole('dialog',{name:/^邀请码/})
  await expect(dialog).toContainText('尚未开放兑换入组入口')
  await expect(dialog.getByRole('button',{name:'生成邀请码',exact:true})).toBeEnabled()
  await dialog.getByRole('button',{name:'生成邀请码',exact:true}).click()
  await expect(dialog).toContainText('invite-test')
  expect(calls.find(c=>c.path.endsWith('/invitations'))?.body).toEqual({roleCode:'STAFF',orgUnitId:2,ttlHours:72})
})

test('ordinary members cannot open organization management',async({page})=>{
  const {state}=await setup(page,{noManage:true})
  await page.goto('/org/structure')
  await expect(page).toHaveURL(/403/)
  expect(state.loadCount).toBe(0)
})

for(const mode of ['light','dark'])test(`desktop and mobile ${mode} layouts`,async({page})=>{
  await setup(page)
  await page.addInitScript(mode=>localStorage.setItem('pams_theme',JSON.stringify({state:{mode},version:0})),mode)
  await page.setViewportSize({width:1440,height:1000})
  await page.goto('/org/structure?unit=1')
  await expect(detail(page)).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('data-theme',mode)
  await page.screenshot({path:`../docs/design/ui-v6-org-implementation/desktop-${mode}.png`,fullPage:true,animations:'disabled'})
  await page.setViewportSize({width:390,height:844})
  await expect(page.getByLabel('搜索架构节点')).not.toBeVisible()
  await page.getByRole('button',{name:'切换节点'}).click()
  await page.getByLabel('搜索架构节点').fill('服务分队')
  await page.locator('.org-node').filter({hasText:'服务分队'}).click()
  await expect(detail(page).locator('h2').first()).toHaveText('服务分队')
  await expect(page.getByLabel('搜索架构节点')).not.toBeVisible()
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
  await page.screenshot({path:`../docs/design/ui-v6-org-implementation/mobile-${mode}.png`,fullPage:true,animations:'disabled'})
})
