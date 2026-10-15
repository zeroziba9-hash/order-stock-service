const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');

async function registerAndLogin(page) {
  const account = { email: `e2e-${randomUUID()}@example.test`, password: `Test-${randomUUID()}` };
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  const auth = page.locator('#auth-dialog');
  await auth.getByRole('button', { name: '회원가입', exact: true }).click();
  await auth.getByLabel('이메일', { exact: true }).fill(account.email);
  await auth.getByLabel('비밀번호', { exact: true }).fill(account.password);
  await auth.getByRole('button', { name: '회원가입 →', exact: true }).click();
  await expect(auth.getByRole('button', { name: '로그인 →', exact: true })).toBeEnabled();
  await auth.getByRole('button', { name: '로그인 →', exact: true }).click();
  await expect(auth).not.toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
  return account;
}

async function products(page) { return (await page.request.get('/products')).json(); }
async function stock(page, productId) { return (await products(page)).find(p => p.id === productId).stock; }
async function openPurchase(page, productId, quantity = 1) {
  await page.getByRole('link', { name: '컬렉션', exact: true }).click();
  await page.locator(`[data-buy="${productId}"]`).click();
  await expect(page.locator('#purchase-dialog')).toBeVisible();
  await page.getByLabel('수량', { exact: true }).fill(String(quantity));
}
async function submitPurchase(page) {
  const retry = await page.evaluate(() => Object.keys(sessionStorage).some(key => key.startsWith('stockroom-direct-pending:')));
  const response = page.waitForResponse(r => new URL(r.url()).pathname === '/orders/cart/checkout' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '주문 완료하기 →', exact: true }).click();
  if (!retry) await confirm(page);
  const result = await response;
  expect([200, 201]).toContain(result.status());
  const order = await result.json();
  await expect(page.locator('#purchase-dialog')).not.toBeVisible();
  await expect(orderCard(page, order.orderId)).toBeVisible();
  return order;
}
function orderCard(page, orderId) { return page.locator('.order-card').filter({ hasText: `ORDER #${orderId}` }); }
async function confirm(page) { await page.locator('#confirm-dialog').getByRole('button', { name: '확인하고 진행', exact: true }).click(); }

// Restore only this fresh account's test orders; never reset a shared product/table.
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'ignoreErrors' });
  const me = await page.request.get('/auth/me');
  if (me.status() !== 200) return;
  const response = await page.request.get('/orders');
  if (!response.ok()) return;
  for (const order of (await response.json()).orders) {
    const operation = order.status === 'CREATED' ? 'cancel' : order.status === 'PAID' ? 'refunds' : null;
    if (!operation) continue;
    const csrf = await (await page.request.get('/auth/csrf')).json();
    await page.request.post(`/orders/${order.orderId}/${operation}`, {
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() },
      data: operation === 'cancel' ? {} : { result: 'SUCCESS' },
    });
  }
});

test.beforeEach(async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.product-card')).toHaveCount(2);
});

test('public catalog, guest order gate and responsive layout', async ({ page }) => {
  await expect(page.getByRole('heading', { name: '한정판 머그컵', exact: true })).toBeVisible();
  expect((await page.request.get('/orders')).status()).toBe(401);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: '구매 가능한 상품', exact: true }).click();
  await expect(page.locator('.product-card')).toHaveCount(2);
  await page.getByRole('button', { name: '주문하기', exact: true }).first().click();
  await expect(page.locator('#auth-dialog')).toBeVisible();
});

test('registration, quantity pricing, cancellation confirmation and stock restoration', async ({ page }) => {
  await registerAndLogin(page);
  const product = (await products(page))[0];
  await openPurchase(page, product.id, 2);
  await expect(page.locator('#purchase-total')).toHaveText(`${(product.price * 2).toLocaleString('ko-KR')}원`);
  const order = await submitPurchase(page);
  expect(await stock(page, product.id)).toBe(product.stock - 2);
  await orderCard(page, order.orderId).getByRole('button', { name: '주문 취소', exact: true }).click();
  await page.locator('#confirm-dialog').getByRole('button', { name: '돌아가기', exact: true }).click();
  expect(await stock(page, product.id)).toBe(product.stock - 2);
  await expect(orderCard(page, order.orderId)).toContainText('결제 대기');
  await orderCard(page, order.orderId).getByRole('button', { name: '주문 취소', exact: true }).click();
  await confirm(page);
  await expect(orderCard(page, order.orderId)).toContainText('취소 완료');
  await expect.poll(() => stock(page, product.id)).toBe(product.stock);
});

test('mock payment, refund and immutable history are visible', async ({ page }) => {
  await registerAndLogin(page);
  const product = (await products(page))[0];
  await openPurchase(page, product.id);
  const order = await submitPurchase(page);
  await orderCard(page, order.orderId).getByRole('button', { name: '상세·이력 보기 ↗', exact: true }).click();
  const detail = page.locator('#detail-dialog');
  await detail.getByRole('button', { name: '모의 결제', exact: true }).click();
  await confirm(page);
  await expect(detail.locator('.status')).toHaveText('결제 완료');
  await expect(detail.locator('.history-row')).toHaveCount(1);
  await detail.getByRole('button', { name: '모의 전액 환불', exact: true }).click();
  await confirm(page);
  await expect(detail.locator('.status')).toHaveText('환불 완료');
  await expect(detail.locator('.history-row')).toHaveCount(2);
  await expect(detail.locator('.history-row').first()).toContainText('완료');
  await expect(detail.locator('.history-row').last()).toContainText('완료');
  await expect.poll(() => stock(page, product.id)).toBe(product.stock);
});

test('other accounts cannot read an order or its histories', async ({ page, browser, baseURL }) => {
  await registerAndLogin(page);
  const product = (await products(page))[0];
  await openPurchase(page, product.id);
  const order = await submitPurchase(page);
  const otherContext = await browser.newContext({ baseURL });
  try {
    const other = await otherContext.newPage();
    await other.goto('/');
    await registerAndLogin(other);
    await other.getByRole('link', { name: /^내 주문/ }).click();
    await expect(other.locator('#orders')).toContainText('아직 담아 둔 취향이 없네요.');
    expect(await (await other.request.get('/orders')).json()).toEqual({ orders: [], nextBeforeId: null });
    for (const suffix of ['', '/payments', '/refunds']) {
      expect((await other.request.get(`/orders/${order.orderId}${suffix}`)).status()).toBe(404);
    }
  } finally { await otherContext.close(); }
});

test('lost order response retries the same key and creates one order', async ({ page }) => {
  await registerAndLogin(page);
  const product = (await products(page))[0];
  await openPurchase(page, product.id);
  const keys = [];
  let dropped = false;
  await page.route('**/orders/cart/checkout', async route => {
    if (route.request().method() !== 'POST') return route.continue();
    keys.push(route.request().headers()['idempotency-key']);
    if (dropped) return route.continue();
    dropped = true;
    const response = await route.fetch(); // Commit on the real server, then lose its response.
    expect(response.status()).toBe(201);
    await route.abort('failed');
  });
  await page.getByRole('button', { name: '주문 완료하기 →', exact: true }).click();
  await confirm(page);
  await expect(page.locator('#purchase-error')).toContainText('응답을 받지 못했어요.');
  await expect(page.getByRole('button', { name: '주문 완료하기 →', exact: true })).toBeEnabled();
  const order = await submitPurchase(page);
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBeTruthy();
  expect(keys[1]).toBe(keys[0]);
  expect((await (await page.request.get('/orders')).json()).orders.map(o => o.orderId)).toEqual([order.orderId]);
  expect(await stock(page, product.id)).toBe(product.stock - 1);
});

test('expired session returns to the login gate', async ({ page }) => {
  await registerAndLogin(page);
  await page.getByRole('link', { name: /^내 주문/ }).click();
  const csrf = await (await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/logout', { headers: { [csrf.headerName]: csrf.token } })).status()).toBe(204);
  await page.locator('#refresh-orders').click();
  await expect(page.locator('#toast')).toHaveText('로그인이 만료됐어요. 다시 로그인해 주세요.');
  await expect(page.locator('#orders').getByRole('button', { name: '로그인하기 →', exact: true })).toBeVisible();
});

test('catalog network failure shows a retry and recovers', async ({ page }) => {
  await page.route('**/products/search?**', route => route.abort('failed'));
  await page.reload();
  await expect(page.locator('#products')).toContainText('상품을 불러오지 못했어요.');
  await page.unroute('**/products/search?**');
  await page.getByRole('button', { name: '재고 새로고침', exact: true }).click();
  await expect(page.locator('.product-card')).toHaveCount(2);
});
