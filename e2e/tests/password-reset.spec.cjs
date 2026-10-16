const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {spawnSync}=require('node:child_process');
const path=require('node:path');
const mailURL=process.env.E2E_MAIL_URL||'http://127.0.0.1:18025';
const mailTarget=new URL(mailURL);
if(mailTarget.protocol!=='http:'||!['localhost','127.0.0.1'].includes(mailTarget.hostname)||!['18025','18026'].includes(mailTarget.port)||mailTarget.username||mailTarget.password||mailTarget.pathname!=='/'||mailTarget.search||mailTarget.hash)throw new Error('Use only the disposable local mail capture');
async function send(client,url,data,method='post',key){const csrf=await(await client.get('/auth/csrf')).json();return client[method](url,{headers:{[csrf.headerName]:csrf.token,...(key?{'Idempotency-Key':key}:{})},data});}
async function login(client,account){const csrf=await(await client.get('/auth/csrf')).json();return client.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:account});}
async function register(client){const account={email:`reset-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};expect((await send(client,'/auth/register',account)).status()).toBe(201);expect((await login(client,account)).status()).toBe(200);return account;}
async function messages(client,email){const result=await client.get(mailURL+'/api/v1/messages');expect(result.status()).toBe(200);return (await result.json()).messages.filter(message=>message.To.some(to=>to.Address===email));}
async function resetLink(client,email){let found=[];await expect.poll(async()=>{found=await messages(client,email);return found.length;}).toBe(1);const result=await(await client.get(mailURL+'/api/v1/message/'+found[0].ID)).json();const link=result.Text.match(/http:\/\/127\.0\.0\.1:18083\/reset\.html#token=[A-Za-z0-9_-]{43}/)?.[0];if(!link)throw new Error('Missing reset link in captured message');return link;}

test('captured email resets password once and revokes sessions on both devices',async({page,browser,baseURL})=>{
  const account=await register(page.request),other=await browser.newContext({baseURL});
  try{
    expect((await login(other.request,account)).status()).toBe(200);
    await page.goto('/reset.html');await expect(page.locator('#reset-request-submit')).toBeEnabled();await page.getByLabel('가입한 이메일',{exact:true}).fill(account.email);await page.locator('#reset-request-submit').click();await expect(page.locator('#reset-status')).toContainText('등록된 이메일이라면');await expect(page.locator('#reset-request-submit')).toBeDisabled();
    const link=await resetLink(page.request,account.email),token=new URL(link).hash.slice(7);// Keep the live token out of Playwright navigation step titles.
    await page.evaluate(url=>{location.href=url;},link);
    await expect(page.locator('#reset-confirm')).toBeVisible();expect(new URL(page.url()).hash).toBe('');expect((await page.evaluate(()=>JSON.stringify(history.state))).includes(token)).toBe(false);expect((await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}))).includes(token)).toBe(false);
    await page.getByLabel('새 비밀번호',{exact:true}).fill('New-test-password');await page.getByLabel('새 비밀번호 확인',{exact:true}).fill('Different-password');await page.locator('#reset-confirm-submit').click();await expect(page.locator('#reset-error')).toContainText('일치하지');
    await page.getByLabel('새 비밀번호 확인',{exact:true}).fill('New-test-password');await page.locator('#reset-confirm-submit').click();await expect(page.locator('#reset-status')).toContainText('기존 접속이 모두 종료');
    expect((await page.request.get('/auth/me')).status()).toBe(401);expect((await other.request.get('/auth/me')).status()).toBe(401);expect((await login(page.request,account)).status()).toBe(401);expect((await login(page.request,{...account,password:'New-test-password'})).status()).toBe(200);
    expect((await send(other.request,'/auth/password-reset/confirm',{token,newPassword:'Replay-password'})).status()).toBe(400);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  }finally{await other.close();}
});

test('known and unknown emails receive the same receipt; cooldown does not replace the issued link',async({page})=>{
  const account=await register(page.request);const first=await send(page.request,'/auth/password-reset/request',{email:account.email});expect(first.status()).toBe(202);const receipt=await first.json();const link=await resetLink(page.request,account.email);
  const unknown=`unknown-${randomUUID()}@example.test`;const absent=await send(page.request,'/auth/password-reset/request',{email:unknown});expect(absent.status()).toBe(202);expect(await absent.json()).toEqual(receipt);
  const second=await send(page.request,'/auth/password-reset/request',{email:account.email});expect(second.status()).toBe(202);expect(await second.json()).toEqual(receipt);
  expect((await send(page.request,'/auth/password-reset/confirm',{token:new URL(link).hash.slice(7),newPassword:'Confirmed-password'})).status()).toBe(204);
  expect(await messages(page.request,account.email)).toHaveLength(1);expect(await messages(page.request,unknown)).toHaveLength(0);
});

test('malformed and reloaded reset links ask to reopen email without persisting the secret',async({page})=>{
  await page.goto('/reset.html#token=broken');await expect(page.locator('#reset-error')).toContainText('메일의 재설정 링크를 다시');await expect(page.locator('#reset-confirm-submit')).toBeDisabled();expect(new URL(page.url()).hash).toBe('');
  await page.goto('/reset.html#token='+'x'.repeat(43));await expect(page.locator('#reset-confirm-submit')).toBeEnabled();await page.reload();await expect(page.locator('#reset-error')).toContainText('메일의 재설정 링크를 다시');await expect(page.locator('#reset-confirm-submit')).toBeDisabled();
});

test('admin sees empty content and long sold-out demo products stay inside the viewport',async({page})=>{
  const account=await register(page.request),project=process.env.COMPOSE_PROJECT_NAME;if(!project?.startsWith('order-stock-browser-'))throw new Error('Disposable stack required');
  const args=[path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',account.email,'--role','ADMIN','--confirm-role-change'];if(process.env.E2E_ENV_FILE)args.push('--env-file',process.env.E2E_ENV_FILE);if(spawnSync(process.env.E2E_PYTHON||'python3',args,{encoding:'utf8',timeout:30000}).status!==0)throw new Error('Admin fixture failed');expect((await login(page.request,account)).status()).toBe(200);
  // Put the new product beyond the first admin page, without changing public fixtures.
  for(let index=0;index<21;index++){
    const response=await send(page.request,'/admin/products',{name:'페이지 경계 '+randomUUID(),price:1000},'post',randomUUID());expect(response.status()).toBe(201);const id=(await response.json()).productId;
    expect((await send(page.request,'/admin/products/'+id,{name:'페이지 경계 '+index,price:1000,selling:false,revision:0},'put')).status()).toBe(200);
  }
  const name='긴이름'.repeat(30)+randomUUID().slice(0,10);
  await page.goto('/catalog.html');await expect(page.locator('#create-submit')).toBeEnabled();await page.getByLabel('새 상품명',{exact:true}).fill(name);await page.getByLabel('새 상품 가격',{exact:true}).fill('1000');
  const created=page.waitForResponse(response=>new URL(response.url()).pathname==='/admin/products'&&response.request().method()==='POST');await page.locator('#create-submit').click();await page.locator('#catalog-confirm').getByRole('button',{name:'확인하고 저장',exact:true}).click();const response=await created;expect(response.status()).toBe(201);const product={id:(await response.json()).productId};await expect(page.getByLabel('수정 상품명',{exact:true})).toHaveValue(name);
  async function stop(){const current=(await(await page.request.get('/admin/products')).json()).find(p=>p.id===product.id);expect((await send(page.request,`/admin/products/${product.id}`,{name,price:1000,selling:false,revision:current.revision},'put')).status()).toBe(200);}
  try{
    await page.goto('/catalog.html');await page.getByLabel('관리 상품명 검색',{exact:true}).fill(name);await page.locator('#catalog-product-search button[type=submit]').click();const row=page.locator(`#catalog-products [data-id="${product.id}"]`);await expect(row.locator('.content-readiness')).toContainText('이미지 없음 · 설명 없음 · 규격 없음');expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.goto('/#product/'+product.id);await expect(page.locator('#product-title')).toHaveText(name);await expect(page.locator('#product-add')).toBeDisabled();await expect(page.locator('#product-buy')).toBeDisabled();await expect(page.locator('#product-description-section')).toBeHidden();await expect(page.locator('#product-specification-section')).toBeHidden();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.goto('/admin.html');await page.getByLabel('재고 상품명 검색',{exact:true}).fill(name);await page.getByRole('button',{name:'재고 찾기',exact:true}).click();await expect(page.locator('#admin-products')).toContainText(name);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await stop();await page.goto('/#product/'+product.id);await expect(page.locator('#product-error')).toContainText('판매가 중지');await expect(page.locator('#product-content')).toBeHidden();
  }finally{await stop();}
});
