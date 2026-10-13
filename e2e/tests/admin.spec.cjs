const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');

function role(email,value) {
  const project=process.env.COMPOSE_PROJECT_NAME;
  if(process.env.E2E_DISPOSABLE!=='true' || !project?.startsWith('order-stock-browser-')) throw new Error('Disposable browser project required');
  const args=[path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',email,'--role',value,'--confirm-role-change'];
  if(process.env.E2E_ENV_FILE) args.push('--env-file',process.env.E2E_ENV_FILE);
  const result=spawnSync(process.env.E2E_PYTHON || 'python3',args,{encoding:'utf8',timeout:30000});
  if(result.status!==0) throw new Error('Disposable account role fixture failed');
}
async function account(page,admin=false) {
  const account={email:`admin-e2e-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};
  const csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/register',{headers:{[csrf.headerName]:csrf.token},data:account})).status()).toBe(201);
  if(admin) role(account.email,'ADMIN');
  expect((await page.request.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:account})).status()).toBe(200);
  return account;
}
async function adjust(page,productId,delta,reason) {
  const csrf=await(await page.request.get('/auth/csrf')).json();
  return page.request.post(`/admin/products/${productId}/inventory-adjustments`,{headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':randomUUID()},data:{delta,reason}});
}
test.afterEach(async({page})=>{
  await page.unrouteAll({behavior:'ignoreErrors'});
  const me=await page.request.get('/auth/me'); if(me.status()!==200) return;
  const user=await me.json(); if(user.role!=='ADMIN') return;
  const response=await page.request.get('/admin/inventory-adjustments'); if(response.status()!==200) return;
  const totals=new Map();
  for(const row of (await response.json()).adjustments.filter(row=>row.adminId===user.userId)) totals.set(row.productId,(totals.get(row.productId)||0)+row.delta);
  for(const [id,delta] of totals) if(delta) expect((await adjust(page,id,-delta,'E2E fixture restoration')).status()).toBe(201);
});

test('ordinary accounts cannot access inventory operations',async({page})=>{
  await account(page); await page.goto('/');
  await expect(page.locator('#admin-nav')).toBeHidden();
  expect((await page.request.get('/admin.html')).status()).toBe(403);
  expect((await page.request.get('/admin/inventory-adjustments')).status()).toBe(403);
  expect((await adjust(page,1,1,'not allowed')).status()).toBe(403);
});
test('admin confirmation, adjustment audit and responsive layout',async({page})=>{
  await account(page,true); await page.goto('/');
  await page.getByRole('link',{name:'재고 관리',exact:true}).click();
  await expect(page.getByRole('heading',{name:'재고 조정',exact:true})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  const reason=`E2E-${randomUUID()} <img src=x onerror=alert(1)>`;
  await page.getByLabel('조정 수량',{exact:true}).fill('3'); await page.getByLabel('변경 사유',{exact:true}).fill(reason);
  await page.getByRole('button',{name:'조정 내용 확인 →',exact:true}).click();
  await page.getByRole('button',{name:'돌아가기',exact:true}).click();
  await expect(page.locator('#adjustment-history')).not.toContainText(reason);
  await page.getByRole('button',{name:'조정 내용 확인 →',exact:true}).click();
  await page.getByRole('button',{name:'확인하고 반영',exact:true}).click();
  await expect(page.locator('#admin-status')).toContainText('반영됐어요');
  await expect(page.locator('#adjustment-history')).toContainText(reason);
  await expect(page.locator('#adjustment-history img')).toHaveCount(0);
});
test('lost response survives reload and reuses the same adjustment key',async({page})=>{
  await account(page,true); await page.goto('/admin.html');
  await expect(page.locator('#admin-workspace')).toBeVisible();
  let first=true; const keys=[]; let committed;
  await page.route('**/admin/products/*/inventory-adjustments',async route=>{
    keys.push(route.request().headers()['idempotency-key']);
    if(first) {first=false; const response=await route.fetch(); expect(response.status()).toBe(201); committed=await response.json(); await route.abort('failed');}
    else await route.continue();
  });
  await page.getByLabel('조정 수량',{exact:true}).fill('2'); await page.getByLabel('변경 사유',{exact:true}).fill('응답 유실 검증');
  await page.getByRole('button',{name:'조정 내용 확인 →',exact:true}).click(); await page.getByRole('button',{name:'확인하고 반영',exact:true}).click();
  await expect(page.getByRole('button',{name:'같은 요청 다시 확인 →',exact:true})).toBeEnabled();
  await page.reload(); await expect(page.getByRole('button',{name:'같은 요청 다시 확인 →',exact:true})).toBeEnabled();
  await expect(page.getByLabel('조정 수량',{exact:true})).toHaveAttribute('readonly','');
  const replay=page.waitForResponse(response=>response.url().includes('/inventory-adjustments') && response.request().method()==='POST');
  await page.getByRole('button',{name:'같은 요청 다시 확인 →',exact:true}).click();
  const response=await replay; expect(response.status()).toBe(200); expect(response.headers()['idempotency-replayed']).toBe('true');
  expect((await response.json()).adjustmentId).toBe(committed.adjustmentId); expect(keys).toHaveLength(2); expect(keys[1]).toBe(keys[0]);
  await expect(page.locator('#admin-status')).toContainText('반영됐어요');
});
test('revoking a role blocks an already open admin session',async({page})=>{
  const user=await account(page,true); await page.goto('/admin.html'); await expect(page.locator('#admin-workspace')).toBeVisible();
  role(user.email,'USER');
  await page.getByLabel('조정 수량',{exact:true}).fill('1'); await page.getByLabel('변경 사유',{exact:true}).fill('권한 회수 검증');
  await page.getByRole('button',{name:'조정 내용 확인 →',exact:true}).click(); await page.getByRole('button',{name:'확인하고 반영',exact:true}).click();
  await expect(page.locator('#admin-status')).toContainText('관리자 권한을 확인');
  await expect(page.locator('#adjust-submit')).toBeDisabled(); expect((await page.request.get('/admin/inventory-adjustments')).status()).toBe(403);
});
