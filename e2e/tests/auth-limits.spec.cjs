const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');

async function write(page, path, body, form = false) {
  const csrf = await (await page.request.get('/auth/csrf')).json();
  return page.request.post(path, { headers: { [csrf.headerName]: csrf.token },
    ...(form ? { form: body } : { data: body }) });
}
async function openLogin(page, email, password) {
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await page.locator('#email').fill(email);
  await page.locator('#password').fill(password);
}
async function submit(page, status) {
  const response = page.waitForResponse(r => /\/auth\/(login|register)$/.test(new URL(r.url()).pathname)
    && r.request().method() === 'POST');
  await page.locator('#auth-submit').click();
  const result = await response;
  expect(result.status()).toBe(status);
  return result;
}
test.beforeEach(async ({ page }) => {
  await page.goto('/');
  // Let the previous case's real server-side fixed windows expire.
  await page.waitForTimeout(3100);
});

test('failed login cooldown survives dialog reopen and recovers without automatic submission', async ({ page }) => {
  const account = { email: `limit-${randomUUID()}@example.test`, password: `Test-${randomUUID()}` };
  expect((await write(page, '/auth/register', account)).status()).toBe(201);
  await openLogin(page, account.email, 'wrong-password');
  await submit(page, 401);
  await submit(page, 401);
  const limited = await submit(page, 429);
  expect((await limited.json()).code).toBe('AUTH_RATE_LIMITED');
  expect(Number(limited.headers()['retry-after'])).toBeGreaterThan(0);
  await expect(page.locator('#auth-submit')).toBeDisabled();
  await expect(page.locator('#auth-submit')).toHaveText(/초 후 다시 시도/);
  let requests = 0;
  page.on('request', r => { if (new URL(r.url()).pathname === '/auth/login' && r.method() === 'POST') requests++; });
  await page.locator('#auth-dialog [data-close]').click();
  await openLogin(page, account.email, account.password);
  await expect(page.locator('#auth-submit')).toBeDisabled();
  await page.locator('#auth-switch').click();
  await expect(page.locator('#auth-submit')).toHaveText('회원가입 →');
  await expect(page.locator('#auth-submit')).toBeEnabled();
  await page.locator('#auth-switch').click();
  await expect(page.locator('#auth-submit')).toBeEnabled({ timeout: 6000 });
  expect(requests).toBe(0);
  await submit(page, 200);
  await expect(page.locator('#auth-dialog')).not.toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃', exact: true })).toBeVisible();
});

test('registration quota rejects before creating an account then allows manual retry', async ({ page }) => {
  expect((await write(page, '/auth/register', {})).status()).toBe(400);
  expect((await write(page, '/auth/register', {})).status()).toBe(400);
  const account = { email: `registration-${randomUUID()}@example.test`, password: `Test-${randomUUID()}` };
  await openLogin(page, account.email, account.password);
  await page.locator('#auth-switch').click();
  await submit(page, 429);
  await expect(page.locator('#auth-submit')).toBeDisabled();
  await expect(page.locator('#auth-error')).toContainText('잠시 제한');
  await expect(page.locator('#auth-submit')).toBeEnabled({ timeout: 6000 });
  await submit(page, 201);
  await expect(page.locator('#auth-submit')).toHaveText('로그인 →');
  await submit(page, 200);
});

test('rotating email still hits client quota while public reads remain available', async ({ page }) => {
  for (let i = 0; i < 6; i++) {
    expect((await write(page, '/auth/login', { email: `${randomUUID()}@example.test`, password: 'wrong' }, true)).status()).toBe(401);
  }
  await openLogin(page, `${randomUUID()}@example.test`, 'wrong');
  await submit(page, 429);
  expect((await page.request.get('/products')).status()).toBe(200);
  expect((await page.request.get('/auth/csrf')).status()).toBe(200);
  await expect(page.locator('#auth-submit')).toBeEnabled({ timeout: 6000 });
  await submit(page, 401);
});
