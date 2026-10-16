const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=','base64');
async function send(client,url,data,method='post',key){const csrf=await(await client.get('/auth/csrf')).json();return client[method](url,{headers:{[csrf.headerName]:csrf.token,...(key?{'Idempotency-Key':key}:{})},data});}
async function setup(page,admin=false){
  const account={email:`media-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};expect((await send(page.request,'/auth/register',account)).status()).toBe(201);
  if(admin){const project=process.env.COMPOSE_PROJECT_NAME;if(!project?.startsWith('order-stock-browser-'))throw new Error('Disposable browser stack required');const args=[path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',account.email,'--role','ADMIN','--confirm-role-change'];if(process.env.E2E_ENV_FILE)args.push('--env-file',process.env.E2E_ENV_FILE);if(spawnSync(process.env.E2E_PYTHON||'python3',args,{encoding:'utf8',timeout:30000}).status!==0)throw new Error('Admin fixture failed');}
  await signIn(page.request,account);return {account,product:(await(await page.request.get('/products')).json())[0]};
}
async function signIn(client,account){const csrf=await(await client.get('/auth/csrf')).json();expect((await client.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:account})).status()).toBe(200);}
async function confirm(page){await page.locator('#confirm-dialog').getByRole('button',{name:'확인하고 진행',exact:true}).click();}
async function intent(client,product,kind='direct'){
  const quote=await(await send(client,'/orders/cart/quote',{items:[{productId:product.id,quantity:1}]})).json();const key=randomUUID();
  expect((await send(client,`/orders/cart/quote/${quote.quoteId}/intent`,{key,kind,cartRevision:kind==='cart'?0:null},'put')).status()).toBe(200);return {quoteId:quote.quoteId,key};
}
test.afterEach(async({page})=>{await page.unrouteAll({behavior:'ignoreErrors'});if((await page.request.get('/auth/me')).status()!==200)return;for(const order of (await(await page.request.get('/orders')).json()).orders)if(order.status==='CREATED')expect((await send(page.request,`/orders/${order.orderId}/cancel`,{})).status()).toBe(200);});

test('admin uploads previews and deletes product images; public detail uses the uploaded photo',async({page})=>{
  const {product}=await setup(page,true),url=`/admin/products/${product.id}/image`;
  try{
    await page.goto('/catalog.html');await page.locator(`#catalog-products [data-id="${product.id}"]`).click();await expect(page.locator('#image-upload')).toBeEnabled();
    await page.locator('#image-file').setInputFiles({name:'sample.png',mimeType:'image/png',buffer:png});await expect(page.locator('#image-preview')).toBeVisible();
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.getByRole('button',{name:'이미지 저장',exact:true}).click();await page.locator('#catalog-confirm').getByRole('button',{name:'확인하고 저장',exact:true}).click();await expect(page.locator('#image-status')).toContainText('저장했어요');
    await expect(page.locator('#image-current')).toBeVisible();await expect(page.locator('#image-history')).toContainText('이미지 등록');
    await page.goto('/#product/'+product.id);await expect(page.locator('#product-art img')).toBeVisible();await expect(page.locator('#product-image-caption')).toHaveText('등록된 상품 이미지예요.');
    expect(await page.locator('#product-art img').evaluate(image=>image.complete&&image.naturalWidth>0)).toBe(true);
    await page.goto('/catalog.html');await page.locator(`#catalog-products [data-id="${product.id}"]`).click();await expect(page.locator('#image-delete')).toBeEnabled();
    await page.getByRole('button',{name:'등록 이미지 삭제',exact:true}).click();await page.locator('#catalog-confirm').getByRole('button',{name:'확인하고 저장',exact:true}).click();await expect(page.locator('#image-status')).toContainText('삭제했어요');
    await page.goto('/#product/'+product.id);await expect(page.locator('#product-image-caption')).toHaveText('상품을 표현한 일러스트예요.');await expect(page.locator('#product-art img')).toHaveCount(0);
  }finally{const current=await(await page.request.get(url)).json();if(current.imageUrl)expect((await send(page.request,url+'?revision='+current.revision,undefined,'delete')).status()).toBe(200);}
});
test('stale image editor cannot overwrite replacement and failed photo falls back',async({page})=>{
  const {product}=await setup(page,true),url=`/admin/products/${product.id}/image`;
  try{
    await page.goto('/catalog.html');await page.locator(`#catalog-products [data-id="${product.id}"]`).click();await expect(page.locator('#image-upload')).toBeEnabled();
    const revision=(await(await page.request.get(url)).json()).revision,csrf=await(await page.request.get('/auth/csrf')).json();
    expect((await page.request.post(url,{headers:{[csrf.headerName]:csrf.token},multipart:{revision:String(revision),file:{name:'new.png',mimeType:'image/png',buffer:png}}})).status()).toBe(200);
    await page.locator('#image-file').setInputFiles({name:'stale.png',mimeType:'image/png',buffer:png});await page.getByRole('button',{name:'이미지 저장',exact:true}).click();await page.locator('#catalog-confirm').getByRole('button',{name:'확인하고 저장',exact:true}).click();await expect(page.locator('#image-status')).toContainText('다른 접속');
    expect((await(await page.request.get(url)).json()).revision).toBe(revision+1);
    await page.route('**/products/'+product.id+'/image?*',route=>route.abort());await page.goto('/#product/'+product.id);await expect(page.locator('#product-image-caption')).toContainText('기본 일러스트');await expect(page.locator('#product-art .generic-art')).toBeVisible();await expect(page.locator('#product-buy')).toBeEnabled();
  }finally{await page.unrouteAll({behavior:'ignoreErrors'});const current=await(await page.request.get(url)).json();if(current.imageUrl)expect((await send(page.request,url+'?revision='+current.revision,undefined,'delete')).status()).toBe(200);}
});
test('committed checkout is recovered after response loss and browser storage clearing',async({page})=>{
  const {product}=await setup(page);await page.goto('/#product/'+product.id);await expect(page.locator('#product-buy')).toBeEnabled();await page.locator('#product-buy').click();
  let lost=false;await page.route('**/orders/cart/checkout',async route=>{const response=await route.fetch();expect(response.status()).toBe(201);lost=true;await route.abort('failed');});
  await page.getByRole('button',{name:'주문 완료하기 →',exact:true}).click();await confirm(page);await expect(page.locator('#purchase-error')).toContainText('응답을 받지 못했어요');expect(lost).toBe(true);
  await page.unrouteAll({behavior:'ignoreErrors'});await page.evaluate(()=>sessionStorage.clear());await page.reload();await page.goto('/#orders');await expect(page.locator('#checkout-recovery')).toBeVisible();await expect(page.locator('#recovery-items')).toContainText('완료된 주문 #');
  await page.getByRole('button',{name:'주문 보기',exact:true}).click();await expect(page.locator('#detail-dialog')).toBeVisible();await page.locator('#detail-dialog [data-close]').click();
  await page.getByRole('button',{name:'확인 완료',exact:true}).click();await expect(page.locator('#checkout-recovery')).toBeHidden();
  const orders=(await(await page.request.get('/orders')).json()).orders;expect(orders).toHaveLength(1);expect((await(await page.request.get('/products')).json()).find(p=>p.id===product.id).stock).toBe(product.stock-1);
});
test('unsubmitted registered intent can resume in another session using the canonical request key',async({page,browser,baseURL})=>{
  const {account,product}=await setup(page);const pending=await intent(page.request,product);const other=await browser.newContext({baseURL});
  try{await signIn(other.request,account);const tab=await other.newPage();await tab.goto('/#orders');await expect(tab.locator('#checkout-recovery')).toBeVisible();await tab.getByRole('button',{name:'같은 요청으로 확인',exact:true}).click();await confirm(tab);await expect(tab.locator('.order-card')).toHaveCount(1);await expect(tab.locator('#checkout-recovery')).toBeHidden();expect((await send(page.request,'/orders/cart/checkout',{quoteId:pending.quoteId},'post',pending.key)).status()).toBe(200);expect(await tab.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
  finally{await other.close();}
});
test('closing an unresolved intent prevents later checkout and keeps the cart',async({page})=>{
  const {product}=await setup(page);expect((await send(page.request,'/cart',{revision:0,items:[{productId:product.id,quantity:2}]},'put')).status()).toBe(200);
  const pending=await intent(page.request,product);await page.goto('/#orders');await expect(page.locator('#checkout-recovery')).toBeVisible();await page.getByRole('button',{name:'이 요청 종료',exact:true}).click();await confirm(page);await expect(page.locator('#checkout-recovery')).toBeHidden();
  const result=await send(page.request,'/orders/cart/checkout',{quoteId:pending.quoteId},'post',pending.key);expect(result.status()).toBe(409);expect((await result.json()).code).toBe('CHECKOUT_CLOSED');expect((await(await page.request.get('/cart')).json()).items[0].quantity).toBe(2);expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(0);
});
test('filtered empty order list explains the filters and can reset them',async({page})=>{
  await setup(page);await page.goto('/#orders');await page.getByLabel('주문 상태',{exact:true}).selectOption('PAID');await page.locator('#order-search button[type=submit]').click();await expect(page.locator('#orders')).toContainText('조건에 맞는 주문이 없어요');await page.getByRole('button',{name:'전체 주문 보기',exact:true}).click();await expect(page.getByLabel('주문 상태',{exact:true})).toHaveValue('');await expect(page.locator('#orders')).toContainText('아직 담아 둔 취향');
});
test('recovery lookup failure is visible and can retry without losing the account',async({page})=>{
  await setup(page);await page.route('**/orders/cart/recovery',route=>route.abort('failed'));await page.goto('/#orders');await expect(page.locator('#checkout-recovery')).toBeVisible();await expect(page.locator('#recovery-status')).toContainText('서버에 연결할 수 없어요');
  await expect(page.locator('#logout-button')).toBeVisible();await page.unrouteAll({behavior:'ignoreErrors'});await page.getByRole('button',{name:'요청 새로고침',exact:true}).click();await expect(page.locator('#checkout-recovery')).toBeHidden();
});
test('malformed successful checkout response keeps its key and safely replays the committed order',async({page})=>{
  const {product}=await setup(page);await page.goto('/#product/'+product.id);await expect(page.locator('#product-buy')).toBeEnabled();await page.locator('#product-buy').click();
  let originalKey;await page.route('**/orders/cart/checkout',async route=>{originalKey=route.request().headers()['idempotency-key'];const response=await route.fetch();expect(response.status()).toBe(201);await route.fulfill({status:201,contentType:'application/json',body:'{'});});
  await page.getByRole('button',{name:'주문 완료하기 →',exact:true}).click();await confirm(page);await expect(page.locator('#purchase-error')).toContainText('서버 응답을 확인하지 못했어요');
  await page.unrouteAll({behavior:'ignoreErrors'});const retried=page.waitForRequest(request=>new URL(request.url()).pathname==='/orders/cart/checkout');await page.getByRole('button',{name:'주문 완료하기 →',exact:true}).click();expect((await retried).headers()['idempotency-key']).toBe(originalKey);await expect(page.locator('.order-card')).toHaveCount(1);expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(1);
});
test('recovered checkout retains a clear rejection message when its confirmed price changed',async({page})=>{
  const {product}=await setup(page,true);await intent(page.request,product);
  async function price(value){const current=(await(await page.request.get('/admin/products')).json()).find(item=>item.id===product.id);expect((await send(page.request,`/admin/products/${product.id}`,{name:current.name,price:value,selling:current.selling,revision:current.revision},'put')).status()).toBe(200);}
  try{await price(product.price+1);await page.goto('/#orders');await expect(page.locator('#checkout-recovery')).toBeVisible();await page.getByRole('button',{name:'같은 요청으로 확인',exact:true}).click();await confirm(page);await expect(page.locator('#recovery-status')).toContainText('상품 정보가 바뀌었어요');await expect(page.getByRole('button',{name:'같은 요청으로 확인',exact:true})).toBeEnabled();expect((await(await page.request.get('/orders')).json()).orders).toHaveLength(0);}
  finally{await price(product.price);}
});
