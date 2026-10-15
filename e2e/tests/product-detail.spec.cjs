const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');
async function send(client,url,data,method='post',key){
  const token=await(await client.get('/auth/csrf')).json();
  return client[method](url,{headers:{[token.headerName]:token.token,...(key?{'Idempotency-Key':key}:{})},data});
}
async function login(page,admin=false){
  const account={email:`detail-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};
  expect((await send(page.request,'/auth/register',account)).status()).toBe(201);
  if(admin){
    const project=process.env.COMPOSE_PROJECT_NAME;if(!project?.startsWith('order-stock-browser-'))throw new Error('Disposable browser stack required');
    const args=[path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',account.email,'--role','ADMIN','--confirm-role-change'];
    if(process.env.E2E_ENV_FILE)args.push('--env-file',process.env.E2E_ENV_FILE);
    if(spawnSync(process.env.E2E_PYTHON||'python3',args,{encoding:'utf8',timeout:30000}).status!==0)throw new Error('Admin fixture failed');
  }
  const csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:account})).status()).toBe(200);
  return account;
}
async function confirm(page){await page.locator('#confirm-dialog').getByRole('button',{name:'확인하고 진행',exact:true}).click();}
test.afterEach(async({page})=>{
  await page.unrouteAll({behavior:'ignoreErrors'});
  if((await page.request.get('/auth/me')).status()!==200)return;
  for(const order of (await(await page.request.get('/orders')).json()).orders)if(order.status==='CREATED')expect((await send(page.request,`/orders/${order.orderId}/cancel`,{})).status()).toBe(200);
});
test('catalog links open public detail, reload retains it and guest purchase requests login',async({page})=>{
  await page.goto('/');await expect(page.locator('.product-card')).toHaveCount(2);
  const products=await(await page.request.get('/products')).json(),product=products[0];
  expect(await page.locator('#collection .product-card').first().boundingBox()).not.toBeNull();
  await page.getByRole('link',{name:product.name+' 상세 보기',exact:true}).click();await expect(page.locator('#product-title')).toHaveText(product.name);
  await expect(page.locator('#product-description-section')).toBeHidden();await expect(page.locator('#product-specification-section')).toBeHidden();
  await page.reload();await expect(page.locator('#product-title')).toHaveText(product.name);
  await page.getByLabel('담을 수량',{exact:true}).fill('2');await expect(page.locator('#product-subtotal')).toHaveText((product.price*2).toLocaleString('ko-KR')+'원');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.locator('#product-add').click();await expect(page.locator('#auth-dialog')).toBeVisible();
  const account={email:`detail-guest-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};
  expect((await send(page.request,'/auth/register',account)).status()).toBe(201);
  await page.locator('#auth-dialog').getByLabel('이메일',{exact:true}).fill(account.email);await page.locator('#auth-dialog').getByLabel('비밀번호',{exact:true}).fill(account.password);
  await page.locator('#auth-dialog').getByRole('button',{name:'로그인 →',exact:true}).click();
  await expect(page.locator('#product-action-status')).toContainText('2개를 장바구니에 담았어요');
  expect((await(await page.request.get('/cart')).json()).items[0].quantity).toBe(2);
  await page.getByRole('link',{name:'← 컬렉션으로 돌아가기',exact:true}).click();await expect(page.locator('.product-card')).toHaveCount(2);
  expect(await page.locator('.note-number').count()).toBe(0);
  await page.getByLabel('상품명 검색',{exact:true}).fill('없는상품-'+randomUUID());await page.getByRole('button',{name:'상품 찾기',exact:true}).click();
  await expect(page.locator('#products')).toContainText('조건에 맞는 상품이 없어요');await page.getByRole('button',{name:'전체 상품 보기',exact:true}).click();await expect(page.locator('.product-card')).toHaveCount(2);
});
test('deep-linked product supports quantity cart and confirmed direct purchase',async({page})=>{
  await login(page);const product=(await(await page.request.get('/products')).json())[0];
  await page.goto('/#product/'+product.id);await expect(page.locator('#product-add')).toBeEnabled();
  await page.getByLabel('담을 수량',{exact:true}).fill('2');await page.locator('#product-add').click();await expect(page.locator('#product-action-status')).toContainText('2개를 장바구니에 담았어요');
  expect((await(await page.request.get('/cart')).json()).items[0].quantity).toBe(2);
  await page.getByLabel('담을 수량',{exact:true}).fill('3');await page.locator('#product-buy').click();await expect(page.locator('#quantity')).toHaveValue('3');
  const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/orders/cart/checkout');
  await page.getByRole('button',{name:'주문 완료하기 →',exact:true}).click();await confirm(page);
  expect((await response).status()).toBe(201);await expect(page.locator('.order-card')).toHaveCount(1);
  const order=(await(await page.request.get('/orders')).json()).orders[0];expect(order.items[0].quantity).toBe(3);expect(order.totalAmount).toBe(product.price*3);
});
test('product fetch failure can retry and stopped products show an accessible error',async({page})=>{
  await login(page,true);const product=(await(await page.request.get('/products')).json())[0];
  await page.route('**/products/'+product.id,route=>route.abort('failed'));await page.goto('/#product/'+product.id);
  await expect(page.locator('#product-error')).toBeVisible();await expect(page.locator('#product-loading')).toBeHidden();
  await page.unrouteAll({behavior:'ignoreErrors'});await page.getByRole('button',{name:'상품 다시 불러오기',exact:true}).click();await expect(page.locator('#product-title')).toHaveText(product.name);
  async function selling(value){const current=(await(await page.request.get('/admin/products')).json()).find(p=>p.id===product.id);expect((await send(page.request,`/admin/products/${product.id}`,{name:current.name,price:current.price,selling:value,revision:current.revision},'put')).status()).toBe(200);}
  try{await selling(false);await page.reload();await expect(page.locator('#product-error')).toContainText('판매가 중지');await expect(page.locator('#product-content')).toBeHidden();}
  finally{await selling(true);}
});
test('admin description edits render as safe text and stale edits preserve newer data',async({page})=>{
  await login(page,true);const product=(await(await page.request.get('/products')).json())[0],url=`/admin/products/${product.id}/content`;
  const original=await(await page.request.get(url)).json();
  try{
    await page.goto('/catalog.html');await page.locator(`#catalog-products [data-id="${product.id}"]`).click();await expect(page.locator('#content-save')).toBeEnabled();
    const text='<img src=x onerror="alert(1)">\n등록한 설명 그대로';await page.getByLabel('상품 설명',{exact:true}).fill(text);await page.getByLabel('상품 규격',{exact:true}).fill('첫 줄\n둘째 줄');
    await page.getByRole('button',{name:'설명과 규격 저장 →',exact:true}).click();await page.locator('#catalog-confirm').getByRole('button',{name:'확인하고 저장',exact:true}).click();
    await expect(page.locator('#content-status')).toContainText('저장했어요');expect(await page.locator('#content-history img').count()).toBe(0);
    await page.goto('/#product/'+product.id);await expect(page.locator('#product-description')).toHaveText(text);expect(await page.locator('#product-description img').count()).toBe(0);
    await expect(page.locator('#product-specification')).toHaveText('첫 줄\n둘째 줄');
    await page.goto('/catalog.html');await page.locator(`#catalog-products [data-id="${product.id}"]`).click();await expect(page.locator('#content-save')).toBeEnabled();
    const current=await(await page.request.get(url)).json();expect((await send(page.request,url,{description:'다른 접속의 최신 설명',specification:current.specification,revision:current.revision},'put')).status()).toBe(200);
    await page.getByLabel('상품 설명',{exact:true}).fill('오래된 수정');await page.getByRole('button',{name:'설명과 규격 저장 →',exact:true}).click();
    await page.locator('#catalog-confirm').getByRole('button',{name:'확인하고 저장',exact:true}).click();await expect(page.locator('#content-status')).toContainText('다른 접속');
    expect((await(await page.request.get(url)).json()).description).toBe('다른 접속의 최신 설명');
  }finally{const current=await(await page.request.get(url)).json();expect((await send(page.request,url,{description:original.description,specification:original.specification,revision:current.revision},'put')).status()).toBe(200);}
});
test('late product response cannot replace a newer detail selection',async({page})=>{
  await page.goto('/');const products=await(await page.request.get('/products')).json();
  let release,started;const gate=new Promise(resolve=>release=resolve),received=new Promise(resolve=>started=resolve);
  await page.route('**/products/'+products[0].id,async route=>{const response=await route.fetch();started();await gate;await route.fulfill({response});});
  try{
    await page.getByRole('link',{name:products[0].name+' 상세 보기',exact:true}).click();await received;
    await page.getByRole('link',{name:'← 컬렉션으로 돌아가기',exact:true}).click();
    await page.getByRole('link',{name:products[1].name+' 상세 보기',exact:true}).click();await expect(page.locator('#product-title')).toHaveText(products[1].name);
    const completed=page.waitForResponse(r=>new URL(r.url()).pathname==='/products/'+products[0].id);release();await completed;
    await expect(page.locator('#product-title')).toHaveText(products[1].name);await expect(page.locator('#product-content')).toBeVisible();
  }finally{release();}
});
