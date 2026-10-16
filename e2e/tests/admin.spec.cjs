const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');
let createdCatalog=[];
test.beforeEach(()=>{createdCatalog=[];});

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
  for(const id of createdCatalog) {
    const product=(await(await page.request.get('/admin/products')).json()).find(p=>p.id===id);
    if(product) {const csrf=await(await page.request.get('/auth/csrf')).json(); expect((await page.request.put(`/admin/products/${id}`,{headers:{[csrf.headerName]:csrf.token},data:{name:product.name,price:product.price,selling:false,revision:product.revision}})).status()).toBe(200);}
  }
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

async function catalogCreate(page,name) {
  await page.getByLabel('새 상품명',{exact:true}).fill(name); await page.getByLabel('새 상품 가격',{exact:true}).fill('2500');
  await page.getByRole('button',{name:'상품 등록 →',exact:true}).click();
  const response=page.waitForResponse(r=>r.url().endsWith('/admin/products')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'확인하고 저장',exact:true}).click();
  const body=await(await response).json(); createdCatalog.push(body.productId);
  await expect(page.getByLabel('수정 상품명',{exact:true})).toHaveValue(name);
  return body.productId;
}

test('catalog registration, sale stop and full inventory movements',async({page})=>{
  await account(page,true); await page.goto('/admin.html');
  await page.getByRole('link',{name:'상품 관리 ↗',exact:true}).click();
  await expect(page.locator('#catalog-workspace')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  const name=`E2E 상품 ${randomUUID()}`; const id=await catalogCreate(page,name);
  expect((await adjust(page,id,3,'신상품 입고')).status()).toBe(201);
  await page.getByLabel('수정 가격',{exact:true}).fill('3500'); await page.getByLabel('판매 상태',{exact:true}).selectOption('false');
  await page.getByRole('button',{name:'상품 변경 저장 →',exact:true}).click(); await page.getByRole('button',{name:'확인하고 저장',exact:true}).click();
  await expect(page.locator('#catalog-status')).toContainText('저장됐어요');
  expect((await(await page.request.get('/products')).json()).some(p=>p.id===id)).toBe(false);
  const csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/orders',{headers:{[csrf.headerName]:csrf.token},data:{productId:id,quantity:1}})).status()).toBe(409);
  await page.getByRole('link',{name:'재고 관리 ↗',exact:true}).click();
  await expect(page.locator('#admin-workspace')).toBeVisible();await page.getByLabel('재고 상품명 검색',{exact:true}).fill(name);await page.getByRole('button',{name:'재고 찾기',exact:true}).click();
  await page.locator(`[data-product-id="${id}"]`).click();
  await expect(page.locator('#ledger-history')).toContainText('신상품 입고');
  await expect(page.locator('#ledger-balance')).toContainText('현재 3개 / 기록 합계 3개 · 일치');
  const ledgerPath=url=>url.pathname==='/admin/inventory-movements';
  await page.route(ledgerPath,route=>route.abort('failed'));
  await page.locator('#admin-products button').first().click();
  await expect(page.locator('#ledger-balance')).toContainText('새로고침하세요');
  await expect(page.locator('#ledger-history tr')).toHaveCount(0);
  await page.unroute(ledgerPath);
  await page.locator('#admin-refresh').click();
  await expect(page.locator('#ledger-balance')).toContainText('일치');
});

test('catalog lost response replays one registration after reload',async({page})=>{
  await account(page,true); await page.goto('/catalog.html'); await expect(page.locator('#catalog-workspace')).toBeVisible();
  let first=true; const keys=[];
  await page.route('**/admin/products',async route=>{
    if(route.request().method()!=='POST') return route.continue();
    keys.push(route.request().headers()['idempotency-key']);
    if(first) {first=false; const response=await route.fetch(); expect(response.status()).toBe(201); createdCatalog.push((await response.json()).productId); await route.abort('failed');}
    else await route.continue();
  });
  const name=`응답 유실 상품 ${randomUUID()}`;
  await page.getByLabel('새 상품명',{exact:true}).fill(name); await page.getByLabel('새 상품 가격',{exact:true}).fill('1000');
  await page.getByRole('button',{name:'상품 등록 →',exact:true}).click(); await page.getByRole('button',{name:'확인하고 저장',exact:true}).click();
  await expect(page.getByRole('button',{name:'같은 등록 요청 다시 확인 →',exact:true})).toBeEnabled();
  await page.reload(); await expect(page.getByRole('button',{name:'같은 등록 요청 다시 확인 →',exact:true})).toBeEnabled();
  await page.getByRole('button',{name:'같은 등록 요청 다시 확인 →',exact:true}).click();
  await expect(page.locator('#catalog-status')).toContainText('등록을 확인');
  expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
  expect((await(await page.request.get('/admin/products')).json()).filter(p=>p.name===name)).toHaveLength(1);
});

test('stale catalog editor reports conflict and preserves newer values',async({page})=>{
  await account(page,true); await page.goto('/catalog.html'); await expect(page.locator('#catalog-workspace')).toBeVisible();
  const id=await catalogCreate(page,`수정 충돌 ${randomUUID()}`);
  const csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.put(`/admin/products/${id}`,{headers:{[csrf.headerName]:csrf.token},data:{name:'먼저 저장한 이름',price:9000,selling:false,revision:0}})).status()).toBe(200);
  await page.getByLabel('수정 상품명',{exact:true}).fill('오래된 화면 변경');
  await page.getByRole('button',{name:'상품 변경 저장 →',exact:true}).click(); await page.getByRole('button',{name:'확인하고 저장',exact:true}).click();
  await expect(page.locator('#catalog-status')).toContainText('다른 변경이 반영');
  await page.getByRole('button',{name:'↻ 새로고침',exact:true}).click();
  await expect(page.getByLabel('수정 상품명',{exact:true})).toHaveValue('먼저 저장한 이름');
  await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(1);
  await expect(page.locator('#catalog-history')).not.toContainText('오래된 화면 변경');
});

test('catalog change snapshots, no-op save, safe text and cursor pagination',async({page})=>{
  await account(page,true); await page.goto('/catalog.html');
  await expect(page.locator('#catalog-workspace')).toBeVisible();
  const id=await catalogCreate(page,`이력 상품 ${randomUUID()}`);
  const name='<img src=x onerror=alert(1)> 변경 상품';
  await page.locator('#edit-name').fill(name); await page.locator('#edit-price').fill('3500');
  await page.locator('#edit-selling').selectOption('false');
  await page.locator('#edit-submit').click(); await page.getByRole('button',{name:'확인하고 저장',exact:true}).click();
  await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(1);
  await expect(page.locator('#catalog-history')).toContainText('2,500원 → 3,500원');
  await expect(page.locator('#catalog-history')).toContainText('판매 중 → 판매 중지');
  await expect(page.locator('#catalog-history')).toContainText(name);
  await expect(page.locator('#catalog-history img')).toHaveCount(0);
  const saved=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname===`/admin/products/${id}`);
  await page.locator('#edit-submit').click(); await page.getByRole('button',{name:'확인하고 저장',exact:true}).click();
  expect((await(await saved).json()).revision).toBe(1);
  await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(1);
  const csrf=await(await page.request.get('/auth/csrf')).json();
  for(let revision=1;revision<=20;revision++) {
    expect((await page.request.put(`/admin/products/${id}`,{headers:{[csrf.headerName]:csrf.token},
      data:{name:`이력 ${revision}`,price:3500+revision,selling:false,revision}})).status()).toBe(200);
  }
  await page.locator('#catalog-history-refresh').click();
  await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(20);
  await page.locator('#catalog-history-more').click();
  await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(21);
  const ids=await page.locator('#catalog-history .catalog-change').evaluateAll(rows=>rows.map(row=>row.dataset.changeId));
  expect(new Set(ids).size).toBe(21);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('catalog history ignores old product response and recovers after a fetch failure',async({page})=>{
  await account(page,true); await page.goto('/catalog.html'); await expect(page.locator('#catalog-workspace')).toBeVisible();
  const group=randomUUID();const first=await catalogCreate(page,`${group} 첫 상품`);
  const csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.put(`/admin/products/${first}`,{headers:{[csrf.headerName]:csrf.token},
    data:{name:group+' 이전 상품의 변경',price:3000,selling:true,revision:0}})).status()).toBe(200);
  const second=await catalogCreate(page,`${group} 다음 상품`);
  await page.getByLabel('관리 상품명 검색',{exact:true}).fill(group);await page.getByRole('button',{name:'관리 상품 찾기',exact:true}).click();await expect(page.locator('#catalog-products button')).toHaveCount(2);
  let release,received;
  const gate=new Promise(resolve=>{release=resolve;}); const captured=new Promise(resolve=>{received=resolve;});
  const firstPath=url=>url.pathname===`/admin/products/${first}/changes`;
  await page.route(firstPath,async route=>{const response=await route.fetch();received();await gate;await route.fulfill({response});});
  try {
    await page.locator(`#catalog-products [data-id="${first}"]`).click(); await captured;
    await page.locator(`#catalog-products [data-id="${second}"]`).click();
    await expect(page.locator('#catalog-history-status')).toContainText('기록된 변경이 없어요');
    const late=page.waitForResponse(r=>new URL(r.url()).pathname===`/admin/products/${first}/changes`);
    release(); await late;
    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(0);
  } finally {release();await page.unroute(firstPath);}
  const failing=url=>url.pathname===`/admin/products/${first}/changes`;
  await page.route(failing,route=>route.abort('failed'));
  await page.locator(`#catalog-products [data-id="${first}"]`).click();
  await expect(page.locator('#catalog-history-status')).toContainText('불러오지 못했어요');
  await expect(page.locator('#catalog-history .catalog-change')).toHaveCount(0);
  await page.unroute(failing); await page.locator('#catalog-history-refresh').click();
  await expect(page.locator('#catalog-history')).toContainText('이전 상품의 변경');
});
