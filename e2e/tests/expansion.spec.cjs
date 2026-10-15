const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');
async function post(client,url,data,key,form=false,method='post'){
  const csrf=await(await client.get('/auth/csrf')).json();
  return client[method](url,{headers:{[csrf.headerName]:csrf.token,...(key?{'Idempotency-Key':key}:{})},[form?'form':'data']:data});
}
async function login(page,admin=false){
  const account={email:`expansion-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};
  expect((await post(page.request,'/auth/register',account)).status()).toBe(201);
  if(admin){
    const project=process.env.COMPOSE_PROJECT_NAME;
    if(!project?.startsWith('order-stock-browser-'))throw new Error('Disposable browser project required');
    const args=[path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',account.email,'--role','ADMIN','--confirm-role-change'];
    if(process.env.E2E_ENV_FILE)args.push('--env-file',process.env.E2E_ENV_FILE);
    const result=spawnSync(process.env.E2E_PYTHON||'python3',args,{encoding:'utf8',timeout:30000});if(result.status!==0)throw new Error('Disposable admin fixture failed');
  }
  expect((await post(page.request,'/auth/login',account,undefined,true)).status()).toBe(200);
  await page.goto('/');await page.reload();await expect(page.locator('.product-card')).toHaveCount(2);
  return account;
}
async function confirm(page){await page.locator('#confirm-dialog').getByRole('button',{name:'확인하고 진행',exact:true}).click();}
async function fillCart(page,products){
  for(const product of products)await page.locator(`[data-add-cart="${product.id}"]`).click();
  await page.getByRole('link',{name:/^장바구니/}).click();await expect(page.locator('.cart-row')).toHaveCount(products.length);
}
test.afterEach(async({page})=>{
  await page.unrouteAll({behavior:'ignoreErrors'});
  const me=await page.request.get('/auth/me');if(me.status()!==200)return;
  const response=await page.request.get('/orders');if(response.status()!==200)return;
  for(const order of (await response.json()).orders)if(order.status==='CREATED')expect((await post(page.request,`/orders/${order.orderId}/cancel`,{})).status()).toBe(200);
});
test('product search and price sorting use server results',async({page})=>{
  await page.goto('/');await expect(page.locator('.product-card')).toHaveCount(2);
  await page.getByLabel('상품명 검색',{exact:true}).fill('머그');await page.getByRole('button',{name:'상품 찾기',exact:true}).click();
  await expect(page.locator('.product-card')).toHaveCount(1);await expect(page.locator('.product-card')).toContainText('머그');
  await page.getByLabel('상품명 검색',{exact:true}).fill('');await page.getByLabel('정렬',{exact:true}).selectOption('PRICE_DESC');
  await page.getByRole('button',{name:'상품 찾기',exact:true}).click();await expect(page.locator('.product-card')).toHaveCount(2);
  const expected=(await(await page.request.get('/products/search?sort=PRICE_DESC')).json()).products;
  await expect(page.locator('.product-card h3').first()).toHaveText(expected[0].name);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.getByLabel('상품명 검색',{exact:true}).fill('없는상품-'+randomUUID());await page.getByRole('button',{name:'상품 찾기',exact:true}).click();
  await expect(page.locator('.product-card')).toHaveCount(0);await expect(page.locator('#products-more')).toBeHidden();
});
test('multi-product cart order and status-date filters work together',async({page})=>{
  await login(page);const products=await(await page.request.get('/products')).json();await fillCart(page,products);
  await page.locator(`[data-cart-quantity="${products[0].id}"]`).fill('2');await page.locator(`[data-cart-quantity="${products[0].id}"]`).blur();
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/orders/cart'&&r.request().method()==='POST');
  await page.locator('#cart-checkout').click();await confirm(page);const created=await response;expect(created.status()).toBe(201);
  const order=await created.json();expect(order.items).toHaveLength(2);expect(order.totalAmount).toBe(products[0].price*2+products[1].price);
  await expect(page.locator('.order-card')).toHaveCount(1);
  await page.getByLabel('주문 상태',{exact:true}).selectOption('CANCELLED');await page.getByRole('button',{name:'주문 찾기',exact:true}).click();await expect(page.locator('.order-card')).toHaveCount(0);
  await page.getByLabel('주문 상태',{exact:true}).selectOption('CREATED');await page.getByRole('button',{name:'주문 찾기',exact:true}).click();await expect(page.locator('.order-card')).toHaveCount(1);
  await page.getByLabel('주문 시작일',{exact:true}).fill('2090-01-01');await page.getByLabel('주문 종료일',{exact:true}).fill('2090-01-02');
  await page.getByRole('button',{name:'주문 찾기',exact:true}).click();await expect(page.locator('.order-card')).toHaveCount(0);
  expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(1);
});
test('lost cart response survives reload with the same key and frozen items',async({page})=>{
  await login(page);const products=await(await page.request.get('/products')).json();await fillCart(page,products);
  const keys=[];let drop=true;
  await page.route('**/orders/cart',async route=>{
    keys.push(route.request().headers()['idempotency-key']);
    if(drop){drop=false;const response=await route.fetch();expect(response.status()).toBe(201);await route.abort('failed');}
    else await route.continue();
  });
  await page.locator('#cart-checkout').click();await confirm(page);await expect(page.locator('#cart-error')).toContainText('응답을 받지 못했어요');
  await page.reload();await expect(page.locator('#cart-pending')).toBeVisible();await expect(page.locator('[data-cart-quantity]').first()).toBeDisabled();
  const replay=page.waitForResponse(r=>new URL(r.url()).pathname==='/orders/cart');await page.locator('#cart-checkout').click();
  expect((await replay).status()).toBe(200);await expect(page.locator('.order-card')).toHaveCount(1);
  expect(keys.length).toBe(2);expect(Boolean(keys[0])&&keys[0]===keys[1]).toBe(true);
  const fresh=await(await page.request.get('/products')).json();for(const product of products)expect(fresh.find(p=>p.id===product.id).stock).toBe(product.stock-1);
});
test('admin low-stock search and date filters preserve whole-ledger reconciliation',async({page})=>{
  await login(page,true);
  const name='품절검증-'+randomUUID();const created=await post(page.request,'/admin/products',{name,price:1000},randomUUID());expect(created.status()).toBe(201);
  const id=(await created.json()).productId;
  try{
    await page.goto('/admin.html');await expect(page.locator('#admin-workspace')).toBeVisible();
    await page.getByLabel('재고 상품명 검색',{exact:true}).fill(name);await page.getByLabel('재고 기준',{exact:true}).selectOption('0');
    await page.getByRole('button',{name:'재고 찾기',exact:true}).click();await expect(page.locator('#admin-products button')).toHaveCount(1);
    await page.getByLabel('이력 시작일',{exact:true}).fill('2090-01-01');await page.getByLabel('이력 종료일',{exact:true}).fill('2090-01-02');
    await page.getByRole('button',{name:'기간으로 이력 조회',exact:true}).click();await expect(page.locator('#ledger-history tr')).toHaveCount(0);await expect(page.locator('#ledger-balance')).toContainText('일치');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const current=(await(await page.request.get('/admin/products')).json()).find(p=>p.id===id);
    expect((await post(page.request,`/admin/products/${id}`,{name,price:1001,selling:false,revision:current.revision},undefined,false,'put')).status()).toBe(200);
    await page.goto('/catalog.html');await expect(page.locator('#catalog-workspace')).toBeVisible();
    await page.getByLabel('관리 상품명 검색',{exact:true}).fill(name);await page.getByLabel('목록 판매 상태',{exact:true}).selectOption('false');
    await page.getByRole('button',{name:'관리 상품 찾기',exact:true}).click();await expect(page.locator('#catalog-products button')).toHaveCount(1);
    await page.getByLabel('상품 변경 시작일',{exact:true}).fill('2090-01-01');await page.getByLabel('상품 변경 종료일',{exact:true}).fill('2090-01-02');
    await page.getByRole('button',{name:'변경 기간 조회',exact:true}).click();await expect(page.locator('#catalog-history')).toBeEmpty();
  }finally{
    const product=(await(await page.request.get('/admin/products')).json()).find(p=>p.id===id);
    if(product?.selling)await post(page.request,`/admin/products/${id}`,{name:product.name,price:product.price,selling:false,revision:product.revision},undefined,false,'put');
  }
});
