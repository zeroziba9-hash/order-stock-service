const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');

test('unpaid orders expire through the real scheduler and return stock', async ({ page }) => {
  await page.goto('/');
  const account = { email: `expiry-${randomUUID()}@example.test`, password: `Test-${randomUUID()}` };
  // Account setup uses the same real session API; order creation and inspection use the browser UI.
  let csrf = await (await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/register', {
    headers: { [csrf.headerName]: csrf.token }, data: account,
  })).status()).toBe(201);
  expect((await page.request.post('/auth/login', {
    headers: { [csrf.headerName]: csrf.token }, form: account,
  })).status()).toBe(200);
  await page.reload();
  await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
  const product = (await (await page.request.get('/products')).json())[0];
  await page.locator(`[data-buy="${product.id}"]`).click();
  const created = page.waitForResponse(r => new URL(r.url()).pathname === '/orders' && r.request().method() === 'POST');
  await page.getByRole('button', { name: '주문 완료하기 →', exact: true }).click();
  const order = await (await created).json();
  const card = page.locator('.order-card').filter({ hasText: `ORDER #${order.orderId}` });
  await card.getByRole('button', { name: '상세·이력 보기 ↗', exact: true }).click();
  const detail = page.locator('#detail-dialog');
  await expect(detail).toContainText('결제 기한');
  expect(Date.parse(order.expiresAt) - Date.parse(order.createdAt)).toBe(5000);
  await expect.poll(async () => (await (await page.request.get(`/orders/${order.orderId}`)).json()).status,
    { timeout: 15000 }).toBe('EXPIRED');
  await detail.getByRole('button', { name: '닫기', exact: true }).click();
  await page.reload();
  await page.getByRole('link', { name: '내 주문', exact: true }).click();
  await expect(card.locator('.status')).toHaveText('주문 만료');
  await expect(card.getByRole('button', { name: '주문 취소', exact: true })).toHaveCount(0);
  await card.getByRole('button', { name: '상세·이력 보기 ↗', exact: true }).click();
  await expect(detail.locator('.status')).toHaveText('주문 만료');
  await expect(detail.getByRole('button', { name: '모의 결제', exact: true })).toHaveCount(0);
  await expect(detail.getByRole('button', { name: '주문 취소', exact: true })).toHaveCount(0);
  const products = await (await page.request.get('/products')).json();
  expect(products.find(p => p.id === product.id).stock).toBe(product.stock);
  csrf = await (await page.request.get('/auth/csrf')).json();
  const late = await page.request.post(`/orders/${order.orderId}/payments`, {
    headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID() }, data: { result: 'SUCCESS' },
  });
  expect(late.status()).toBe(409);
  expect((await late.json()).code).toBe('ORDER_EXPIRED');
  expect(await (await page.request.get(`/orders/${order.orderId}/payments`)).json()).toEqual([]);
});
