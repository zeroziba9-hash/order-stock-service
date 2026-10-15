const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');
async function send(client,url,data,method='post',key){
  const token=await(await client.get('/auth/csrf')).json();
  return client[method](url,{headers:{[token.headerName]:token.token,...(key?{'Idempotency-Key':key}:{})},data});
}
async function signIn(client,account){
  const token=await(await client.get('/auth/csrf')).json();
  expect((await client.post('/auth/login',{headers:{[token.headerName]:token.token},form:account})).status()).toBe(200);
}
async function setup(page,admin=false){
  const account={email:`checkout-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};
  expect((await send(page.request,'/auth/register',account)).status()).toBe(201);
  if(admin){
    const project=process.env.COMPOSE_PROJECT_NAME;if(!project?.startsWith('order-stock-browser-'))throw new Error('Disposable browser stack required');
    const args=[path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',account.email,'--role','ADMIN','--confirm-role-change'];
    if(process.env.E2E_ENV_FILE)args.push('--env-file',process.env.E2E_ENV_FILE);
    if(spawnSync(process.env.E2E_PYTHON||'python3',args,{encoding:'utf8',timeout:30000}).status!==0)throw new Error('Admin fixture failed');
  }
  await signIn(page.request,account);await page.goto('/');await expect(page.locator('[data-add-cart]').first()).toBeEnabled();
  const products=await(await page.request.get('/products')).json();
  for(const product of products)await page.locator(`[data-add-cart="${product.id}"]`).click();
  await page.getByRole('link',{name:/^장바구니/}).click();await expect(page.locator('.cart-row')).toHaveCount(2);
  return {account,products};
}
async function edit(page,product,price,selling=true){
  const current=(await(await page.request.get('/admin/products')).json()).find(p=>p.id===product.id);
  expect((await send(page.request,`/admin/products/${product.id}`,{name:product.name,price,selling,revision:current.revision},'put')).status()).toBe(200);
}
async function confirm(page){await page.locator('#confirm-dialog').getByRole('button',{name:'확인하고 진행',exact:true}).click();}
test.afterEach(async({page})=>{
  await page.unrouteAll({behavior:'ignoreErrors'});
  if((await page.request.get('/auth/me')).status()!==200)return;
  for(const order of (await(await page.request.get('/orders')).json()).orders)if(order.status==='CREATED')expect((await send(page.request,`/orders/${order.orderId}/cancel`,{})).status()).toBe(200);
});

test('saved cart is shared across independent sessions and browser storage loss',async({page,browser,baseURL})=>{
  const {account}=await setup(page);const second=await browser.newContext({baseURL});
  try{
    await signIn(second.request,account);const other=await second.newPage();await other.goto('/#cart');await expect(other.locator('.cart-row')).toHaveCount(2);
    await page.evaluate(()=>sessionStorage.clear());await page.reload();await expect(page.locator('.cart-row')).toHaveCount(2);
    expect(await other.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }finally{await second.close();}
});
test('stale cart edit reloads instead of overwriting a newer session',async({page})=>{
  const {products}=await setup(page);const saved=await(await page.request.get('/cart')).json();
  expect((await send(page.request,'/cart',{revision:saved.revision,items:[{productId:products[1].id,quantity:4}]},'put')).status()).toBe(200);
  await page.locator('[data-cart-remove]').first().click();await expect(page.locator('#cart-error')).toContainText('다른 창이나 기기');
  await expect(page.locator('.cart-row')).toHaveCount(1);await expect(page.locator('[data-cart-quantity]')).toHaveValue('4');
  expect((await(await page.request.get('/cart')).json()).items[0].quantity).toBe(4);
});
test('latest price is shown before confirmation and cancelling creates no order',async({page})=>{
  const {products}=await setup(page,true);const product=products[0];
  try{
    await edit(page,product,product.price+123);await page.locator('#cart-checkout').click();
    await expect(page.locator('#confirm-message')).toContainText('상품 정보가 바뀌었어요');
    await expect(page.locator('#confirm-message')).toContainText((product.price+123).toLocaleString('ko-KR')+'원');
    await page.locator('#confirm-dialog').getByRole('button',{name:'돌아가기',exact:true}).click();
    expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(0);
  }finally{await edit(page,product,product.price);}
});
test('price change while confirmation is open rejects checkout then allows reconfirmation',async({page})=>{
  const {products}=await setup(page,true);const product=products[1];
  try{
    await page.locator('#cart-checkout').click();await expect(page.locator('#confirm-dialog')).toBeVisible();await edit(page,product,product.price+77);
    await confirm(page);await expect(page.locator('#cart-error')).toContainText('상품 정보가 바뀌었어요');
    expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(0);
    const current=await(await page.request.get('/products')).json();for(const p of products)expect(current.find(x=>x.id===p.id).stock).toBe(p.stock);
    await page.locator('#cart-checkout').click();await expect(page.locator('#confirm-message')).toContainText((product.price+77).toLocaleString('ko-KR')+'원');await confirm(page);
    await expect(page.locator('.order-card')).toHaveCount(1);
    expect((await(await page.request.get('/orders')).json()).orders[0].totalAmount).toBe(products[0].price+product.price+77);
  }finally{await edit(page,product,product.price);}
});
test('stopped product blocks checkout and identifies the affected item',async({page})=>{
  const {products}=await setup(page,true);const product=products[0];
  try{
    await edit(page,product,product.price,false);await page.locator('#cart-checkout').click();
    await expect(page.locator('#cart-error')).toContainText(product.name+': 판매 중지');await expect(page.locator('#confirm-dialog')).toBeHidden();
    expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(0);
  }finally{await edit(page,product,product.price);}
});
test('successful checkout preserves cart changes made on another device',async({page})=>{
  const {products}=await setup(page);await page.locator('#cart-checkout').click();await expect(page.locator('#confirm-dialog')).toBeVisible();
  const saved=await(await page.request.get('/cart')).json();
  expect((await send(page.request,'/cart',{revision:saved.revision,items:[{productId:products[0].id,quantity:5}]},'put')).status()).toBe(200);
  await confirm(page);await expect(page.locator('.order-card')).toHaveCount(1);
  expect((await(await page.request.get('/cart')).json()).items[0].quantity).toBe(5);
  await page.getByRole('link',{name:/^장바구니/}).click();await expect(page.locator('[data-cart-quantity]')).toHaveValue('5');
});
test('legacy pending cart replays its original endpoint and request key after upgrade',async({page})=>{
  const {products}=await setup(page);const body={items:products.map(p=>({productId:p.id,quantity:1}))},key=randomUUID();
  const response=await send(page.request,'/orders/cart',body,'post',key);expect(response.status()).toBe(201);const original=await response.json();
  const owner=(await(await page.request.get('/auth/me')).json()).userId;
  await page.evaluate(({owner,body,key})=>sessionStorage.setItem(`stockroom-cart-pending:${owner}`,JSON.stringify({body,key})),{owner,body,key});
  await page.reload();await expect(page.locator('#cart-pending')).toBeVisible();
  const replay=page.waitForResponse(r=>new URL(r.url()).pathname==='/orders/cart');await page.locator('#cart-checkout').click();
  expect((await replay).status()).toBe(200);await expect(page.locator('.order-card')).toHaveCount(1);
  expect((await(await page.request.get('/orders')).json()).orders[0].orderId).toBe(original.orderId);
});
