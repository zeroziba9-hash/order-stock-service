const {test,expect}=require('@playwright/test');
const {randomUUID}=require('node:crypto');
const {AxeBuilder}=require('@axe-core/playwright');
async function send(client,url,data,key){const csrf=await(await client.get('/auth/csrf')).json();return client.post(url,{headers:{[csrf.headerName]:csrf.token,...(key?{'Idempotency-Key':key}:{})},data});}
async function fixture(id,body){const result=await fetch(`http://127.0.0.1:18028/__fixtures/${id}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});expect(result.status).toBe(200);}
async function setup(page){
  const account={email:`toss-${randomUUID()}@example.test`,password:`Test-${randomUUID()}`};expect((await send(page.request,'/auth/register',account)).status()).toBe(201);
  const csrf=await(await page.request.get('/auth/csrf')).json();expect((await page.request.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:account})).status()).toBe(200);
  const product=(await(await page.request.get('/products')).json()).find(p=>p.stock>0);
  const response=await send(page.request,'/orders',{productId:product.id,quantity:1},randomUUID());expect(response.status()).toBe(201);
  const order=await response.json();await page.goto('/#orders');await page.locator(`[data-detail="${order.orderId}"]`).click();await expect(page.locator('[data-toss-pay]')).toBeVisible();return {order,product};
}
async function checkout(page,order){const result=await send(page.request,`/orders/${order.orderId}/toss/checkout`,{},randomUUID());expect(result.status()).toBe(200);return (await result.json()).payment.checkoutId;}
async function callback(page,id,amount){await page.goto(`/payment-result.html?checkout=${id}&orderId=${id}&paymentKey=pk_${id}&amount=${amount}`);}
test.afterEach(async({page})=>{
  await page.unrouteAll({behavior:'ignoreErrors'});
  if((await page.request.get('/auth/me')).status()!==200)return;
  for(const order of (await(await page.request.get('/orders')).json()).orders){
    let gateway=await(await page.request.get(`/orders/${order.orderId}/toss`)).json();
    if(gateway.checkoutId&&gateway.pending){await fixture(gateway.checkoutId,{mode:'ok'});await expect.poll(async()=> (await(await page.request.get(`/orders/${order.orderId}/toss`)).json()).pending,{timeout:15000}).toBe(false);gateway=await(await page.request.get(`/orders/${order.orderId}/toss`)).json();}
    if(gateway.status==='PAID')expect((await send(page.request,`/payments/toss/${gateway.checkoutId}/refund`,{})).status()).toBe(200);
    if((await(await page.request.get(`/orders/${order.orderId}`)).json()).status==='CREATED')expect((await send(page.request,`/orders/${order.orderId}/cancel`,{})).status()).toBe(200);
  }
});
test('checkout SDK contract approves, strips identifiers and refunds exactly once',async({page})=>{
  const {order,product}=await setup(page);
  await page.route('https://js.tosspayments.com/v2/standard',route=>route.fulfill({contentType:'application/javascript',body:`window.TossPayments=(key)=>({payment:({customerKey})=>({requestPayment:async request=>{if(!key.startsWith('test_ck_')||!customerKey||request.method!=='CARD'||request.amount.currency!=='KRW'||request.windowTarget!=='self')throw Error('SDK contract');const target=new URL(request.successUrl);target.searchParams.set('orderId',request.orderId);target.searchParams.set('paymentKey','pk_'+request.orderId);target.searchParams.set('amount',request.amount.value);location.assign(target)}})})`}));
  await page.locator('[data-toss-pay]').click();await expect(page.locator('#payment-title')).toHaveText('카드 테스트 결제 완료');await expect(page).toHaveURL(/\/payment-result.html$/);
  const gateway=await(await page.request.get(`/orders/${order.orderId}/toss`)).json();
  expect(await page.evaluate(()=>JSON.stringify({local:{...localStorage},session:{...sessionStorage},history:history.state}))).not.toContain('pk_srp_');
  await page.reload();await expect(page.locator('#payment-title')).toHaveText('카드 테스트 결제 완료');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  expect((await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze()).violations.map(v=>({id:v.id,nodes:v.nodes.map(n=>n.target)}))).toEqual([]);
  await page.goto('/#orders');await page.locator(`[data-detail="${order.orderId}"]`).click();await page.locator('[data-toss-refund]').click();await page.locator('#confirm-dialog').getByRole('button',{name:'확인하고 진행',exact:true}).click();
  await expect(page.locator('.gateway-status')).toContainText('전액 환불 완료');expect((await send(page.request,`/payments/toss/${gateway.checkoutId}/refund`,{})).status()).toBe(200);
  expect((await(await page.request.get('/products')).json()).find(p=>p.id===product.id).stock).toBe(product.stock);
});
test('lost approval response survives reload and recovers without another approval',async({page})=>{
  const {order}=await setup(page);const id=await checkout(page,order);await fixture(id,{mode:'hidden'});await callback(page,id,order.totalAmount);
  await expect(page.locator('#payment-title')).toHaveText('결제 결과 확인 중');await page.reload();await expect(page.locator('#payment-title')).toHaveText('결제 결과 확인 중');
  expect((await send(page.request,`/orders/${order.orderId}/cancel`,{})).status()).toBe(409);
  await fixture(id,{mode:'ok'});await expect.poll(async()=> (await(await page.request.get(`/orders/${order.orderId}/toss`)).json()).status,{timeout:15000}).toBe('PAID');
  await page.locator('#payment-retry').click();await expect(page.locator('#payment-title')).toHaveText('카드 테스트 결제 완료');
  expect((await(await fetch(`http://127.0.0.1:18028/__fixtures/${id}`)).json()).confirms).toBe(1);
});
test('declined authentication remains unpaid and allows a fresh attempt',async({page})=>{
  const {order}=await setup(page);const id=await checkout(page,order);await fixture(id,{mode:'decline'});await callback(page,id,order.totalAmount);
  await expect(page.locator('#payment-title')).toHaveText('카드 테스트 결제 미승인');expect((await(await page.request.get(`/orders/${order.orderId}`)).json()).status).toBe('CREATED');
  await page.goto('/#orders');await page.locator(`[data-detail="${order.orderId}"]`).click();await expect(page.locator('[data-toss-pay]')).toBeVisible();
});
test('callback amount tampering never sends an approval',async({page})=>{
  const {order}=await setup(page);const id=await checkout(page,order);await callback(page,id,1);await expect(page.locator('#payment-error')).toContainText('금액이 일치하지');
  expect((await(await page.request.get(`/orders/${order.orderId}/toss`)).json()).status).toBe('READY');expect((await(await fetch(`http://127.0.0.1:18028/__fixtures/${id}`)).json()).confirms).toBeNull();
});
test('SDK load failure offers a retry without changing order or stock',async({page})=>{
  const {order}=await setup(page);await page.route('https://js.tosspayments.com/v2/standard',route=>route.abort());await page.locator('[data-toss-pay]').click();
  await expect(page.locator('#detail-error')).toContainText('결제창을 불러오지');await expect(page.locator('[data-toss-pay]')).toBeEnabled();expect((await(await page.request.get(`/orders/${order.orderId}`)).json()).status).toBe('CREATED');
});
test('cancelled authentication displays a restart path without approving',async({page})=>{
  const {order}=await setup(page);const id=await checkout(page,order);await page.goto(`/payment-result.html?checkout=${id}&failed=1&code=USER_CANCEL&message=untrusted`);
  await expect(page.locator('#payment-status')).toContainText('내 주문에서 다시 시작');await expect(page).toHaveURL(/\/payment-result.html$/);expect(await page.locator('body').innerText()).not.toContain('untrusted');
});
