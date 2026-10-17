const {test, expect} = require('@playwright/test');
const {AxeBuilder} = require('@axe-core/playwright');
const {randomUUID} = require('node:crypto');
const {spawnSync} = require('node:child_process');
const path = require('node:path');

async function audit(page) {
  const result = await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21aa']).analyze();
  expect(result.violations.map(item => ({id:item.id, nodes:item.nodes.map(node=>node.target)}))).toEqual([]);
}
async function account(page, admin = false) {
  const data = {email:`a11y-${randomUUID()}@example.test`, password:`Test-${randomUUID()}`};
  const csrf = await (await page.request.get('/auth/csrf')).json();
  expect((await page.request.post('/auth/register', {headers:{[csrf.headerName]:csrf.token},data})).status()).toBe(201);
  if (admin) {
    const project = process.env.COMPOSE_PROJECT_NAME;
    if (process.env.E2E_DISPOSABLE !== 'true' || !project?.startsWith('order-stock-browser-')) throw new Error('Disposable project required');
    const args = [path.resolve(__dirname,'../../scripts/set-admin-role.py'),'--project',project,'--email',data.email,'--role','ADMIN','--confirm-role-change'];
    if (process.env.E2E_ENV_FILE) args.push('--env-file',process.env.E2E_ENV_FILE);
    expect(spawnSync(process.env.E2E_PYTHON || 'python3',args,{timeout:30000}).status).toBe(0);
  }
  expect((await page.request.post('/auth/login',{headers:{[csrf.headerName]:csrf.token},form:data})).status()).toBe(200);
}

test('guest pages, authentication dialog and recovery have accessible names and contrast', async ({page}) => {
  await page.goto('/');
  await expect(page.locator('.product-card')).toHaveCount(2);
  await audit(page);
  await page.locator('#login-button').click();
  await expect(page.locator('#email')).toBeFocused();
  await audit(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('#login-button')).toBeFocused();
  // Account rendering can replace the button that opened the dialog.
  await page.locator('#login-button').click();
  await page.evaluate(()=>document.querySelector('#login-button').remove());
  await page.keyboard.press('Escape');
  await expect(page.locator('#shop-view h1')).toBeFocused();
  await page.goto('/reset.html');
  await expect(page.locator('#reset-request-submit')).toBeEnabled();
  await audit(page);
});

test('skip link, route headings, modal Tab containment and Escape work with keyboard', async ({page}) => {
  await page.goto('/');
  await expect(page.locator('.product-card')).toHaveCount(2);
  await page.keyboard.press('Tab');
  await expect(page.locator('.skip-link')).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main')).toBeFocused();
  await page.locator('#cart-nav').focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#cart-title')).toBeFocused();
  await expect(page).toHaveTitle('장바구니 — STOCKROOM');
  await page.locator('.skip-link').focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#main')).toBeFocused();
  await expect(page).toHaveURL(/#cart$/);
  await page.locator('#login-button').focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('#email')).toBeFocused();
  for (let i=0;i<12;i++) {
    await page.keyboard.press('Tab');
    expect(await page.evaluate(()=>document.querySelector('#auth-dialog').contains(document.activeElement))).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(page.locator('#login-button')).toBeFocused();
});

test('account, cart and admin screens pass accessibility checks without changing stock', async ({page}) => {
  await account(page,true);
  await page.goto('/#account');
  await expect(page.locator('.session-row')).toHaveCount(1);
  await audit(page);
  await page.locator('#cart-nav').click();
  await expect(page.locator('#cart-title')).toBeFocused();
  await audit(page);
  await page.goto('/admin.html');
  await expect(page.locator('#adjust-submit')).toBeEnabled();
  await audit(page);
  await page.goto('/catalog.html');
  await expect(page.locator('#catalog-products button').first()).toBeVisible();
  await expect(page.locator('#edit-submit')).toBeEnabled();
  await audit(page);
  await page.locator('#edit-price').fill(String(Number(await page.locator('#edit-price').inputValue()) + 1));
  await page.locator('#edit-submit').click();
  await expect(page.locator('#catalog-confirm')).toBeVisible();
  await expect(page.locator('#catalog-confirm')).toHaveAccessibleName('변경 내용을 확인하세요');
  await audit(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('#edit-submit')).toBeFocused();
});

test('product detail and nested checkout cancellation preserve keyboard focus', async ({page}) => {
  await account(page);
  await page.goto('/');
  await expect(page.locator('#logout-button')).toBeVisible();
  await expect(page.locator('.product-card')).toHaveCount(2);
  await page.locator('.product-visual-link').first().click();
  await expect(page.locator('#product-title')).toBeFocused();
  await audit(page);
  await page.locator('#product-buy').click();
  await expect(page.locator('#quantity')).toBeFocused();
  await audit(page);
  await page.locator('#purchase-submit').click();
  await expect(page.locator('#confirm-dialog')).toBeVisible();
  await audit(page);
  await page.keyboard.press('Escape');
  await expect(page.locator('#purchase-dialog')).toBeVisible();
  await expect(page.locator('#purchase-submit')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('#product-buy')).toBeFocused();
});

test('320 CSS pixels, doubled text and reduced motion retain usable content', async ({page}) => {
  await page.setViewportSize({width:320,height:720});
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.goto('/');
  await expect(page.locator('.product-card')).toHaveCount(2);
  for (const route of ['/#shop','/#cart','/#orders','/#account','/reset.html']) {
    await page.goto(route);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await audit(page);
  }
  // Most of this stylesheet uses px. Changing the root font size would not test text resizing.
  await page.setViewportSize({width:1280,height:900});
  await page.goto('/');
  await expect(page.locator('.product-card')).toHaveCount(2);
  await page.evaluate(() => {
    const elements = [...document.querySelectorAll('body *')].filter(element=>element.getClientRects().length);
    const sizes = elements.map(element=>getComputedStyle(element).fontSize);
    elements.forEach((element,index)=>element.style.fontSize = `${parseFloat(sizes[index])*2}px`);
  });
  expect(await page.evaluate(()=>document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.locator('#product-query')).toBeVisible();
  await expect(page.locator('#product-search button')).toBeVisible();
  await audit(page);
});
