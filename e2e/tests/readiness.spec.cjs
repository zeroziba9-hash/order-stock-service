const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
async function account(page){
  const value={email:`readiness-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};
  let csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/register',{headers:{[csrf.headerName]:csrf.token},data:value})).status()).toBe(201);
  csrf=await(await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:value})).status()).toBe(200);return value;
}
test('delayed session lookup cannot turn an authenticated product action into a guest login',async({page})=>{
  await account(page);const product=(await(await page.request.get('/products')).json())[0];
  let release;const wait=new Promise(resolve=>{release=resolve;});
  await page.route('**/auth/me',async route=>{await wait;await route.continue();});
  try{
    await page.goto('/#product/'+product.id);await expect(page.locator('#product-content')).toBeVisible();
    await expect(page.locator('#product-add')).toBeDisabled();await expect(page.locator('#product-buy')).toBeDisabled();
    await page.getByLabel('담을 수량',{exact:true}).fill('2');release();
    await expect(page.locator('#product-add')).toBeEnabled();await page.locator('#product-add').click();
    await expect(page.locator('#product-action-status')).toContainText('2개를 장바구니에 담았어요');await expect(page.locator('#auth-dialog')).toBeHidden();
    expect((await(await page.request.get('/cart')).json()).items[0].quantity).toBe(2);
  }finally{release();await page.unrouteAll({behavior:'ignoreErrors'});}
});
test('delayed dialog focus restoration respects a newly edited password field',async({page})=>{
  const user=await account(page);await page.goto('/#account');await expect(page.locator('#profile-email')).toHaveText(user.email);
  await page.getByLabel('현재 비밀번호',{exact:true}).fill(user.password);
  await page.getByLabel('새 비밀번호',{exact:true}).fill('NextTestPassword123!');await page.getByLabel('새 비밀번호 확인',{exact:true}).fill('NextTestPassword123!');
  await page.getByRole('button',{name:'비밀번호 변경',exact:true}).click();await expect(page.locator('#confirm-dialog')).toBeVisible();
  await page.evaluate(()=>{
    window.__originalFrame=requestAnimationFrame;window.__closeFrames=[];
    window.requestAnimationFrame=callback=>{window.__closeFrames.push(callback);return 0;};
    document.querySelector('#confirm-dialog').close('no');
  });
  await expect.poll(()=>page.evaluate(()=>window.__closeFrames.length)).toBeGreaterThan(0);
  await expect(page.locator('#current-password')).toBeEnabled();await page.locator('#current-password').fill(user.password);
  await page.evaluate(()=>{window.requestAnimationFrame=window.__originalFrame;for(const callback of window.__closeFrames)callback(performance.now());delete window.__closeFrames;delete window.__originalFrame;});
  await expect(page.locator('#current-password')).toBeFocused();await expect(page.locator('#current-password')).toHaveValue(user.password);
});
