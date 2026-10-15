const { test, expect } = require('@playwright/test');
const { randomUUID } = require('node:crypto');

async function csrfPost(client, path, options = {}) {
  const csrf = await (await client.get('/auth/csrf')).json();
  return client.post(path, { ...options, headers: { [csrf.headerName]: csrf.token } });
}
async function prepare(page) {
  const account = { email: `account-${randomUUID()}@example.test`, password: `Original-${randomUUID()}` };
  expect((await csrfPost(page.request, '/auth/register', { data: account })).status()).toBe(201);
  expect((await csrfPost(page.request, '/auth/login', { form: account })).status()).toBe(200);
  await page.goto('/#account');
  await page.reload();
  await expect(page.locator('#profile-email')).toHaveText(account.email);
  await expect(page.locator('.session-row')).toHaveCount(1);
  return account;
}
async function otherBrowser(browser, baseURL, account) {
  const context = await browser.newContext({ baseURL });
  expect((await csrfPost(context.request, '/auth/login', { form: account })).status()).toBe(200);
  return context;
}
async function fillPassword(page, current, next, confirmation = next) {
  await page.getByLabel('현재 비밀번호', { exact: true }).fill(current);
  await page.getByLabel('새 비밀번호', { exact: true }).fill(next);
  await page.getByLabel('새 비밀번호 확인', { exact: true }).fill(confirmation);
  await page.getByRole('button', { name: '비밀번호 변경', exact: true }).click();
}
async function confirm(page) {
  await page.locator('#confirm-dialog').getByRole('button', { name: '확인하고 진행', exact: true }).click();
}

test('account login gate, own session list and other-browser logout', async ({ page, browser, baseURL }) => {
  await page.goto('/#account');
  await expect(page.locator('#account-gate')).toBeVisible();
  await expect(page.locator('#account-content')).not.toBeVisible();
  const account = await prepare(page);
  const other = await otherBrowser(browser, baseURL, account);
  try {
    await page.locator('#refresh-sessions').click();
    await expect(page.locator('.session-row')).toHaveCount(2);
    await expect(page.locator('.session-row').first()).toContainText('현재 접속');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.getByRole('button', { name: '다른 기기 로그아웃', exact: true }).click();
    await page.locator('#confirm-dialog').getByRole('button', { name: '돌아가기' }).click();
    expect((await other.request.get('/auth/me')).status()).toBe(200);
    await expect(page.getByRole('button', { name: '다른 기기 로그아웃', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '다른 기기 로그아웃', exact: true }).click(); await confirm(page);
    await expect(page.locator('.session-row')).toHaveCount(1);
    await expect(page.getByRole('button', { name: '다른 기기 로그아웃', exact: true })).toBeDisabled();
    expect((await other.request.get('/auth/me')).status()).toBe(401);
    expect((await page.request.get('/auth/me')).status()).toBe(200);
  } finally { await other.close(); }
});

test('password confirmation, wrong current password and all-session revocation', async ({ page, browser, baseURL }) => {
  const account = await prepare(page); const next = `Changed-${randomUUID()}`;
  const other = await otherBrowser(browser, baseURL, account);
  try {
    await fillPassword(page, account.password, next, 'different-confirmation');
    await expect(page.locator('#change-password-error')).toContainText('일치하지');
    await expect(page.locator('#confirm-dialog')).not.toBeVisible();
    await fillPassword(page, account.password, next);
    await page.locator('#confirm-dialog').getByRole('button', { name: '돌아가기' }).click();
    await expect(page.locator('#current-password')).toHaveValue('');
    expect((await page.request.get('/auth/me')).status()).toBe(200);
    await fillPassword(page, 'WrongPassword!', next); await confirm(page);
    await expect(page.locator('#change-password-error')).toContainText('현재 비밀번호가 올바르지');
    await expect(page.locator('#current-password')).toHaveValue('');
    await expect(page.locator('#account-content')).toBeVisible();
    expect((await other.request.get('/auth/me')).status()).toBe(200);
    await fillPassword(page, account.password, next); await confirm(page);
    await expect(page.locator('#account-gate')).toBeVisible();
    await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeVisible();
    expect((await other.request.get('/auth/me')).status()).toBe(401);
    expect((await csrfPost(other.request, '/auth/login', { form: account })).status()).toBe(401);
    await page.getByRole('button', { name: '로그인', exact: true }).click();
    const auth = page.locator('#auth-dialog');
    await auth.getByLabel('이메일', { exact: true }).fill(account.email);
    await auth.getByLabel('비밀번호', { exact: true }).fill(next);
    await auth.getByRole('button', { name: '로그인 →', exact: true }).click();
    await expect(auth).not.toBeVisible(); await expect(page.locator('#account-content')).toBeVisible();
    await expect(page.locator('.session-row')).toHaveCount(1);
    await page.reload(); await expect(page.locator('#profile-email')).toHaveText(account.email);
  } finally { await other.close(); }
});

test('session refresh failure can recover and revoked current session clears account view', async ({ page, browser, baseURL }) => {
  const account = await prepare(page); const other = await otherBrowser(browser, baseURL, account);
  try {
    await page.route('**/auth/sessions', route => route.abort('failed'));
    await page.locator('#refresh-sessions').click();
    await expect(page.locator('#sessions-error')).toContainText('서버에 연결할 수 없어요');
    await expect(page.locator('#logout-others')).toBeDisabled();
    await page.unroute('**/auth/sessions'); await page.locator('#refresh-sessions').click();
    await expect(page.locator('.session-row')).toHaveCount(2);
    await page.route('**/auth/sessions/logout-others', route => route.abort('failed'));
    await page.locator('#logout-others').click(); await confirm(page);
    await expect(page.locator('#sessions-error')).toContainText('종료 결과를 확인하지 못했어요');
    await expect(page.locator('.session-row')).toHaveCount(2);
    expect((await other.request.get('/auth/me')).status()).toBe(200);
    await page.unroute('**/auth/sessions/logout-others');
    expect((await csrfPost(other.request, '/auth/sessions/logout-others')).status()).toBe(204);
    await page.locator('#refresh-sessions').click();
    await expect(page.locator('#account-gate')).toBeVisible();
    await expect(page.locator('#login-sessions')).toBeEmpty();
    await expect(page.getByRole('button', { name: '로그인', exact: true })).toBeVisible();
  } finally { await other.close(); }
});
